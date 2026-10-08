/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two rules about the cookies the gateway sets, driven over a live Vert.x server against a stub
 * upstream:
 * <ul>
 *   <li>a response the gateway adds a cookie to is {@code Cache-Control: no-store}, whatever the
 *       upstream declared, and a response it adds none to keeps the upstream's cache policy;</li>
 *   <li>an upstream response cannot set a cookie the gateway owns, while every other
 *       {@code Set-Cookie} line of the upstream is relayed.</li>
 * </ul>
 * The stub upstream answers every request with a cacheable policy and with whatever
 * {@code Set-Cookie} lines the case configures, so each rule is observed next to its control.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — a gateway cookie makes the response uncacheable, and no upstream can set one")
class GatewayEdgeGatewayCookieTest {

    private static final String OIDC_HOST = "gw.example.com";
    private static final String ORIGIN = "https://gw.example.com";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final String BACKCHANNEL_PATH = "/auth/backchannel";
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String SET_COOKIE = "Set-Cookie";
    private static final String COOKIE = "Cookie";
    private static final String NO_STORE = "no-store";
    /** What the stub upstream declares on every response. */
    private static final String UPSTREAM_CACHE_POLICY = "public, max-age=600";
    private static final String UPSTREAM_BODY = "upstream";
    private static final String SESSION_COOKIE_NAME = SessionCookieCodec.DEFAULT_COOKIE_NAME;
    private static final String ACTIVITY_COOKIE_NAME = SESSION_COOKIE_NAME + "-activity";
    private static final String BINDING_COOKIE_NAME = "__Host-sheriff-binding";
    private static final String LOGOUT_STATE_COOKIE_NAME = "__Host-sheriff-logout";
    /** The attributes of the activity cookie the gateway itself sets. */
    private static final String GATEWAY_ACTIVITY_COOKIE_SUFFIX = "; Path=/; Secure; HttpOnly; SameSite=Lax";
    private static final String APP_COOKIE = "app=1; Path=/app";
    private static final String THEME_COOKIE = "theme=dark";
    private static final String SESSION_ROUTE = "/app";
    private static final String OPEN_ROUTE = "/open";
    private static final String GRPC_ROUTE = "/orders.OrderService";
    private static final Duration SESSION_TTL = Duration.ofHours(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    /** A login this long ago makes the activity cookie due; the gateway issues one at most every 60 s. */
    private static final long ACTIVITY_COOKIE_DUE = 120;
    private static final long INSIDE_THE_ACTIVITY_INTERVAL = 5;

    /** The {@code Set-Cookie} lines the stub upstream adds to its next responses. */
    private final List<String> upstreamSetCookies = new CopyOnWriteArrayList<>();
    /** The {@code Cookie} header fields the stub upstream received, one list per request. */
    private final List<List<String>> upstreamCookieFields = new CopyOnWriteArrayList<>();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstream;
    private @Nullable HttpServer front;
    private HttpClient client;
    private HttpClient http2Client;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                request.body().onComplete(ignored -> {
                    upstreamCookieFields.add(List.copyOf(request.headers().getAll(COOKIE)));
                    request.response().putHeader(CACHE_CONTROL, UPSTREAM_CACHE_POLICY);
                    upstreamSetCookies.forEach(line -> request.response().headers().add(SET_COOKIE, line));
                    request.response().end(UPSTREAM_BODY);
                })).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        client = vertx.createHttpClient();
        http2Client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(http2Client.close(), "the HTTP/2 client to close");
        if (front != null) {
            Awaits.teardown(front.close(), "the edge front server to close");
        }
        Awaits.teardown(upstream.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Nested
    @DisplayName("cookie mode — the activity cookie is the gateway cookie on a proxied response")
    class CookieMode {

        private CookieSessionBinding binding;

        @BeforeEach
        void startEdge() throws Exception {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 0x11);
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            byte[] activityKey = new byte[32];
            Arrays.fill(activityKey, (byte) 0x44);
            binding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(SESSION_COOKIE_NAME, SESSION_TTL,
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"),
                            (byte) 1),
                    salt,
                    new SessionActivityCookieCodec(SESSION_COOKIE_NAME, new SecretKeySpec(activityKey, "AES"),
                            (byte) 2),
                    IDLE_TIMEOUT);
            OidcConfig oidc = OidcConfig.builder()
                    .redirectUri(ORIGIN + "/auth/callback")
                    .session(OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_COOKIE).build())
                    .build();
            startFront(GatewayEdgeRouteBffWiringTest.activeRuntime(binding),
                    GatewayConfig.builder().version(1).oidc(oidc).build(), allRoutes());
        }

        /** Binds a session whose login lies {@code secondsAgo} in the past and returns its request cookie. */
        private String sessionBoundSecondsAgo(long secondsAgo) {
            Instant login = Instant.now().minusSeconds(secondsAgo);
            return requestCookie(binding.bind(SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                    .accessToken(Generators.letterStrings(16, 32).next())
                    .idToken(Generators.letterStrings(16, 32).next())
                    .sub(Generators.letterStrings(8, 16).next())
                    .expiresAt(login.plus(SESSION_TTL)).build(), login).setCookieHeaders().getFirst());
        }

        @Test
        @DisplayName("a proxied response the activity cookie is added to is no-store instead of the upstream's cacheable policy")
        void proxiedResponseWithTheActivityCookieIsNotStored() throws Exception {
            Answer answer = get(SESSION_ROUTE + "/orders", sessionBoundSecondsAgo(ACTIVITY_COOKIE_DUE));

            assertAll("the response that carries the gateway's cookie",
                    () -> assertEquals(200, answer.status(), "the session request is proxied"),
                    () -> assertEquals(1, gatewayActivityCookies(answer).size(),
                            "precondition: the gateway added its activity cookie: " + answer.setCookies()),
                    () -> assertEquals(List.of(NO_STORE), answer.cacheControl(),
                            "exactly one Cache-Control line, and it is the gateway's"));
        }

        @Test
        @DisplayName("control: a proxied response the gateway adds no cookie to keeps the upstream's cache policy")
        void proxiedResponseWithoutAGatewayCookieKeepsTheUpstreamPolicy() throws Exception {
            Answer answer = get(SESSION_ROUTE + "/orders", sessionBoundSecondsAgo(INSIDE_THE_ACTIVITY_INTERVAL));

            assertAll("the same route inside the activity interval",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(), answer.setCookies(), "precondition: the gateway added no cookie"),
                    () -> assertEquals(List.of(UPSTREAM_CACHE_POLICY), answer.cacheControl(),
                            "the upstream's policy is relayed unchanged"));
        }

        @Test
        @DisplayName("a gRPC response the activity cookie is added to is no-store, and one without it keeps the upstream's policy")
        void grpcResponseFollowsTheSameRule() throws Exception {
            Answer withCookie = grpc(sessionBoundSecondsAgo(ACTIVITY_COOKIE_DUE));
            Answer withoutCookie = grpc(sessionBoundSecondsAgo(INSIDE_THE_ACTIVITY_INTERVAL));

            assertAll("the gRPC relay",
                    () -> assertEquals(200, withCookie.status(), "the session call is proxied"),
                    () -> assertEquals(1, gatewayActivityCookies(withCookie).size(),
                            "precondition: the gateway added its activity cookie: " + withCookie.setCookies()),
                    () -> assertEquals(List.of(NO_STORE), withCookie.cacheControl()),
                    () -> assertEquals(200, withoutCookie.status()),
                    () -> assertEquals(List.of(), withoutCookie.setCookies(),
                            "precondition of the control: no gateway cookie"),
                    () -> assertEquals(List.of(UPSTREAM_CACHE_POLICY), withoutCookie.cacheControl(),
                            "control: the upstream's policy is relayed unchanged"));
        }

        @Test
        @DisplayName("an upstream of a session route sets neither the session cookie nor the activity cookie; the gateway's own activity cookie arrives")
        void upstreamOfSessionRouteCannotSetGatewayCookies() throws Exception {
            upstreamSetsGatewayCookiesAndItsOwn();

            Answer answer = get(SESSION_ROUTE + "/orders", sessionBoundSecondsAgo(ACTIVITY_COOKIE_DUE));

            assertAll("the upstream's lines naming a gateway cookie are gone, the rest is relayed",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(APP_COOKIE, THEME_COOKIE), cookiesNotSetByTheGateway(answer),
                            "the upstream's own cookies arrive unchanged and in order: " + answer.setCookies()),
                    () -> assertEquals(1, gatewayActivityCookies(answer).size(),
                            "the gateway's own activity cookie is on the same response: " + answer.setCookies()));
        }

        @Test
        @DisplayName("an upstream of a gRPC session route cannot set a gateway cookie either")
        void upstreamOfGrpcRouteCannotSetGatewayCookies() throws Exception {
            upstreamSetsGatewayCookiesAndItsOwn();

            Answer answer = grpc(sessionBoundSecondsAgo(ACTIVITY_COOKIE_DUE));

            assertAll("the trailing-header relay filters its leading headers the same way",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(APP_COOKIE, THEME_COOKIE), cookiesNotSetByTheGateway(answer),
                            answer.setCookies().toString()),
                    () -> assertEquals(1, gatewayActivityCookies(answer).size(), answer.setCookies().toString()));
        }

        @Test
        @DisplayName("an upstream of a route without a session on the same host cannot set a gateway cookie")
        void upstreamOfOpenRouteCannotSetGatewayCookies() throws Exception {
            upstreamSetsGatewayCookiesAndItsOwn();

            Answer answer = get(OPEN_ROUTE + "/page", null);

            assertAll("a route that asks for no session is filtered like any other",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(APP_COOKIE, THEME_COOKIE), answer.setCookies()),
                    () -> assertEquals(List.of(UPSTREAM_CACHE_POLICY), answer.cacheControl(),
                            "and with no gateway cookie on it the response keeps the upstream's cache policy"));
        }

        private void upstreamSetsGatewayCookiesAndItsOwn() {
            upstreamSetCookies.addAll(List.of(
                    APP_COOKIE,
                    SESSION_COOKIE_NAME + "=from-upstream; Path=/",
                    ACTIVITY_COOKIE_NAME + "=from-upstream; Path=/",
                    BINDING_COOKIE_NAME + "=from-upstream; Path=/",
                    LOGOUT_STATE_COOKIE_NAME + "=from-upstream; Path=/",
                    THEME_COOKIE));
        }

        private Answer grpc(String sessionCookie) throws Exception {
            return send(http2Client, io.vertx.core.http.HttpMethod.POST, GRPC_ROUTE + "/List",
                    Map.of(COOKIE, sessionCookie, "Origin", ORIGIN, "Content-Type", "application/grpc",
                            "TE", "trailers"),
                    Buffer.buffer(new byte[5]));
        }
    }

    @Nested
    @DisplayName("server mode — the session cookie, the login-binding cookie and the logout-state cookie are the gateway's")
    class ServerMode {

        private InMemorySessionStore store;

        @BeforeEach
        void startEdge() throws Exception {
            store = GatewayEdgeRouteBffWiringTest.newStore();
            OidcConfig oidc = OidcConfig.builder()
                    .redirectUri(ORIGIN + "/auth/callback")
                    .logout(OidcConfig.Logout.builder().path(LOGOUT_PATH)
                            .postLogoutRedirectUri(ORIGIN + "/auth/logout/return")
                            .backchannelPath(BACKCHANNEL_PATH).build())
                    .build();
            startFront(GatewayEdgeRouteBffWiringTest.activeRuntime(GatewayEdgeRouteBffWiringTest.serverBinding(store)),
                    GatewayConfig.builder().version(1).oidc(oidc).build(), allRoutes());
        }

        private String login(String idToken) {
            return storedSessionCookie(store, idToken, Instant.now());
        }

        private String login() {
            return login(Generators.letterStrings(16, 32).next());
        }

        @Test
        @DisplayName("an upstream of a session route cannot set the session, login-binding or logout-state cookie")
        void upstreamOfSessionRouteCannotSetGatewayCookies() throws Exception {
            upstreamSetsGatewayCookiesAndItsOwn();

            Answer answer = get(SESSION_ROUTE + "/orders", login());

            assertAll("only the upstream's own cookies arrive",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(APP_COOKIE, THEME_COOKIE), answer.setCookies()));
        }

        @Test
        @DisplayName("an upstream of a route without a session on the same host cannot set them either")
        void upstreamOfOpenRouteCannotSetGatewayCookies() throws Exception {
            upstreamSetsGatewayCookiesAndItsOwn();

            Answer answer = get(OPEN_ROUTE + "/page", null);

            assertAll("only the upstream's own cookies arrive",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(APP_COOKIE, THEME_COOKIE), answer.setCookies()));
        }

        @Test
        @DisplayName("a proxied session response carries no gateway cookie and keeps the upstream's cache policy")
        void proxiedSessionResponseKeepsTheUpstreamPolicy() throws Exception {
            Answer answer = get(SESSION_ROUTE + "/orders", login());

            assertAll("an access in server mode writes no cookie, so nothing changes the cache policy",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(List.of(), answer.setCookies()),
                    () -> assertEquals(List.of(UPSTREAM_CACHE_POLICY), answer.cacheControl()));
        }

        @Test
        @DisplayName("the logout redirect that carries the ID token is no-store and Referrer-Policy: no-referrer")
        void logoutRedirectCarryingTheIdTokenIsNotStoredAndSendsNoReferrer() throws Exception {
            String idToken = Generators.letterStrings(24, 32).next();

            Answer answer = reserved(io.vertx.core.http.HttpMethod.GET, LOGOUT_PATH, login(idToken));

            assertAll("the 302 to the identity provider",
                    () -> assertEquals(302, answer.status()),
                    () -> assertTrue(String.valueOf(answer.location()).startsWith("https://idp.example.com/logout?"),
                            "precondition: the redirect goes to the provider's end-session endpoint"),
                    () -> assertTrue(String.valueOf(answer.location()).contains("id_token_hint=" + idToken),
                            "precondition: its query carries the ID token"),
                    () -> assertEquals(List.of(NO_STORE), answer.cacheControl()),
                    () -> assertEquals(List.of("no-referrer"), answer.referrerPolicy()));
        }

        @Test
        @DisplayName("a logout without a session is answered locally: no-store, and no Referrer-Policy of the logout's own")
        void localLogoutIsNotStoredAndSetsNoReferrerPolicy() throws Exception {
            Answer answer = reserved(io.vertx.core.http.HttpMethod.GET, LOGOUT_PATH, null);

            assertAll("the 302 to the gateway's own final redirect",
                    () -> assertEquals(302, answer.status()),
                    () -> assertEquals("/", answer.location(), "precondition: the answer stays on the gateway"),
                    () -> assertFalse(answer.setCookies().isEmpty(), "precondition: it clears the gateway's cookies"),
                    () -> assertEquals(List.of(NO_STORE), answer.cacheControl()),
                    () -> assertEquals(List.of(), answer.referrerPolicy(),
                            "the no-referrer policy belongs to the redirect that carries the ID token only"));
        }

        @Test
        @DisplayName("a refused back-channel logout is answered 400 with exactly one Cache-Control: no-store")
        void refusedBackchannelLogoutIsNotStored() throws Exception {
            Answer answer = reserved(io.vertx.core.http.HttpMethod.POST, BACKCHANNEL_PATH, null);

            assertAll("the 400 of a delivery carrying no logout token",
                    () -> assertEquals(400, answer.status()),
                    () -> assertEquals(List.of(NO_STORE), answer.cacheControl()));
        }

        private void upstreamSetsGatewayCookiesAndItsOwn() {
            upstreamSetCookies.addAll(List.of(
                    APP_COOKIE,
                    SESSION_COOKIE_NAME + "=from-upstream; Path=/",
                    BINDING_COOKIE_NAME + "=from-upstream; Path=/",
                    LOGOUT_STATE_COOKIE_NAME + "=from-upstream; Path=/",
                    THEME_COOKIE));
        }
    }

    @Nested
    @DisplayName("a gateway without a session runtime owns no cookie and relays every upstream Set-Cookie line")
    class BearerOnly {

        @Test
        @DisplayName("control: cookies named like the session runtime's are relayed, each on its own line and in order")
        void relaysEveryUpstreamCookie() throws Exception {
            startFront(BffRuntime.inert(), GatewayConfig.builder().version(1).build(),
                    List.of(httpRoute("open", OPEN_ROUTE, Require.NONE)));
            List<String> lines = List.of(
                    APP_COOKIE,
                    SESSION_COOKIE_NAME + "=from-upstream; Path=/",
                    BINDING_COOKIE_NAME + "=from-upstream; Path=/",
                    LOGOUT_STATE_COOKIE_NAME + "=from-upstream; Path=/",
                    THEME_COOKIE);
            upstreamSetCookies.addAll(lines);

            Answer answer = get(OPEN_ROUTE + "/page", null);

            assertAll("nothing is owned, so nothing is dropped",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(lines, answer.setCookies()),
                    () -> assertEquals(List.of(UPSTREAM_CACHE_POLICY), answer.cacheControl()));
        }
    }

    /**
     * HTTP/2 lets a client send its cookies as several {@code Cookie} header fields instead of one
     * (RFC 9113 section 8.2.3). The session cookie must be found whichever field it travels in.
     */
    @Nested
    @DisplayName("HTTP/2 — the session cookie is found when the cookies arrive split over two Cookie header fields")
    class SplitCookieHeaderFields {

        private static final String OTHER_COOKIE = "theme=dark";

        private InMemorySessionStore store;

        @BeforeEach
        void startEdge() throws Exception {
            store = GatewayEdgeRouteBffWiringTest.newStore();
            OidcConfig oidc = OidcConfig.builder().redirectUri(ORIGIN + "/auth/callback").build();
            startFront(GatewayEdgeRouteBffWiringTest.activeRuntime(GatewayEdgeRouteBffWiringTest.serverBinding(store)),
                    GatewayConfig.builder().version(1).oidc(oidc).build(), allRoutes());
        }

        private String login() {
            return storedSessionCookie(store, Generators.letterStrings(16, 32).next(), Instant.now());
        }

        /**
         * Sends one HTTP/2 GET over a raw connection, each of {@code cookieFields} as a {@code cookie}
         * header field of its own, and returns once the response headers have arrived.
         * <p>
         * The frames are written by hand because an HTTP client library is free to join the fields
         * before they leave: the header block below is HPACK with every field a literal, so the two
         * {@code cookie} fields are on the wire as two.
         */
        private void exchange(int port, String path, List<String> cookieFields) throws Exception {
            Buffer block = Buffer.buffer();
            block.appendByte((byte) 0x82);
            block.appendByte((byte) 0x86);
            block.appendByte((byte) 0x04);
            appendString(block, path);
            block.appendByte((byte) 0x01);
            appendString(block, OIDC_HOST + ":" + port);
            appendLiteralField(block, "accept", "application/json");
            cookieFields.forEach(field -> appendLiteralField(block, "cookie", field));

            Buffer wire = Buffer.buffer("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
            wire.appendBytes(new byte[]{0, 0, 0, 4, 0, 0, 0, 0, 0});
            wire.appendMedium(block.length()).appendByte((byte) 1).appendByte((byte) 5).appendInt(1);
            wire.appendBuffer(block);

            CompletableFuture<Void> responseHeaders = new CompletableFuture<>();
            NetClient netClient = vertx.createNetClient();
            try {
                NetSocket socket = Awaits.connect(netClient.connect(port, LoopbackHost.ADDRESS),
                        "the raw HTTP/2 connection");
                Buffer received = Buffer.buffer();
                socket.handler(chunk -> {
                    received.appendBuffer(chunk);
                    int offset = 0;
                    while (received.length() - offset >= 9) {
                        int length = received.getUnsignedMedium(offset);
                        if (received.length() - offset < 9 + length) {
                            return;
                        }
                        boolean headersOfTheRequestStream = received.getByte(offset + 3) == 1
                                && received.getInt(offset + 5) == 1;
                        if (headersOfTheRequestStream) {
                            responseHeaders.complete(null);
                            return;
                        }
                        offset += 9 + length;
                    }
                });
                Awaits.connect(socket.write(wire), "the raw HTTP/2 request to be written");
                Awaits.connect(responseHeaders, "the response headers of the raw HTTP/2 request");
            } finally {
                Awaits.teardown(netClient.close(), "the raw HTTP/2 client to close");
            }
        }

        /** An HPACK literal header field without indexing, name and value as plain octets. */
        private static void appendLiteralField(Buffer block, String name, String value) {
            block.appendByte((byte) 0x00);
            appendString(block, name);
            appendString(block, value);
        }

        /** An HPACK string literal without Huffman coding; one length octet, so shorter than 127 octets. */
        private static void appendString(Buffer block, String text) {
            byte[] octets = text.getBytes(StandardCharsets.US_ASCII);
            if (octets.length >= 127) {
                throw new IllegalArgumentException("string too long for a one-octet HPACK length: " + octets.length);
            }
            block.appendByte((byte) octets.length).appendBytes(octets);
        }

        @Test
        @DisplayName("control: the session cookie in the first of two fields admits the request")
        void sessionCookieInTheFirstField() throws Exception {
            exchange(requireFront().actualPort(), SESSION_ROUTE + "/orders", List.of(login(), OTHER_COOKIE));

            assertEquals(1, upstreamCookieFields.size(), "the request was let through to the upstream");
        }

        @Test
        @DisplayName("the session cookie in the second of two fields admits the request")
        void sessionCookieInTheSecondField() throws Exception {
            exchange(requireFront().actualPort(), SESSION_ROUTE + "/orders", List.of(OTHER_COOKIE, login()));

            assertEquals(1, upstreamCookieFields.size(), "the request was let through to the upstream");
        }

        @Test
        @DisplayName("control: two fields of which neither is a session cookie do not admit the request")
        void noSessionCookieInEitherField() throws Exception {
            exchange(requireFront().actualPort(), SESSION_ROUTE + "/orders", List.of(OTHER_COOKIE, "lang=en"));

            assertEquals(0, upstreamCookieFields.size(), "the request was answered without reaching the upstream");
        }

        /**
         * What the cases above rest on. The HTTP server joins the {@code cookie} fields of an HTTP/2
         * request into one value before the request reaches application code, so the gateway reads one
         * {@code Cookie} value however many fields the client sent. The server here is a Vert.x HTTP
         * server created the way the test edge's is.
         */
        @Test
        @DisplayName("the HTTP/2 server hands two Cookie fields to application code as one joined value")
        void serverJoinsTheTwoFields() throws Exception {
            String sessionCookie = login();

            exchange(upstream.actualPort(), "/direct", List.of(OTHER_COOKIE, sessionCookie));

            assertEquals(List.of(List.of(OTHER_COOKIE + "; " + sessionCookie)), List.copyOf(upstreamCookieFields));
        }
    }

    /**
     * {@link BffRuntime#dispatch} is the one place a reserved-path answer is marked uncacheable, so the
     * rule is read off its result for every reserved endpoint at once.
     */
    @Nested
    @DisplayName("reserved-path answers — one that sets a cookie is no-store, one that sets none keeps its own headers")
    class ReservedAnswers {

        private final Instant now = Instant.parse("2026-07-25T10:00:00Z");
        private final InMemorySessionStore store = GatewayEdgeRouteBffWiringTest.newStore();
        private final BffRuntime runtime = GatewayEdgeRouteBffWiringTest.activeRuntime(
                GatewayEdgeRouteBffWiringTest.serverBinding(store));

        private String login(String idToken) {
            return storedSessionCookie(store, idToken, now);
        }

        private BffRuntime.ReservedHttpResponse dispatch(ReservedEndpoint kind, @Nullable String cookie,
                String method) {
            return runtime.dispatch(kind, new BffRuntime.ReservedHttpRequest("", cookie, null, "/home", null, null,
                    method), now);
        }

        @ParameterizedTest(name = "{0} without a session")
        @ValueSource(strings = {"CALLBACK", "LOGOUT", "LOGOUT_RETURN", "BACKCHANNEL_LOGOUT", "USER_INFO", "STEP_UP",
                "CLIENT_JWKS"})
        @DisplayName("an answer that sets a cookie carries Cache-Control: no-store")
        void answerSettingACookieIsNotStored(String kind) {
            BffRuntime.ReservedHttpResponse response = dispatch(ReservedEndpoint.valueOf(kind), null,
                    "BACKCHANNEL_LOGOUT".equals(kind) ? "POST" : "GET");

            assertTrue(response.setCookieHeaders().isEmpty() || NO_STORE.equals(response.headers().get(CACHE_CONTROL)),
                    () -> kind + " sets " + response.setCookieHeaders().size() + " cookie(s) with headers "
                            + response.headers());
        }

        @ParameterizedTest(name = "{0} with a live session")
        @ValueSource(strings = {"LOGOUT", "LOGIN", "USER_INFO", "STEP_UP"})
        @DisplayName("the same holds for the answers given to a live session")
        void answerToALiveSessionSettingACookieIsNotStored(String kind) {
            BffRuntime.ReservedHttpResponse response = dispatch(ReservedEndpoint.valueOf(kind),
                    login(Generators.letterStrings(24, 32).next()), "GET");

            assertTrue(response.setCookieHeaders().isEmpty() || NO_STORE.equals(response.headers().get(CACHE_CONTROL)),
                    () -> kind + " sets " + response.setCookieHeaders().size() + " cookie(s) with headers "
                            + response.headers());
        }

        @Test
        @DisplayName("the rule is exercised: both logout answers set cookies, and both are no-store")
        void logoutAnswersSetCookiesAndAreNotStored() {
            BffRuntime.ReservedHttpResponse local = dispatch(ReservedEndpoint.LOGOUT, null, "GET");
            BffRuntime.ReservedHttpResponse toProvider = dispatch(ReservedEndpoint.LOGOUT,
                    login(Generators.letterStrings(24, 32).next()), "GET");

            assertAll("the answers the rule above is about",
                    () -> assertFalse(local.setCookieHeaders().isEmpty(), "the local logout clears cookies"),
                    () -> assertEquals(Map.of(CACHE_CONTROL, NO_STORE), local.headers(),
                            "the local logout carries no-store and no Referrer-Policy"),
                    () -> assertFalse(toProvider.setCookieHeaders().isEmpty(),
                            "the logout through the provider sets the logout-state cookie and clears the rest"),
                    () -> assertEquals(Map.of(CACHE_CONTROL, NO_STORE, REFERRER_POLICY, "no-referrer"),
                            toProvider.headers(),
                            "the redirect carrying the ID token carries both headers"));
        }

        @Test
        @DisplayName("control: an answer that sets no cookie keeps exactly the headers its handler gave it")
        void answerWithoutACookieKeepsItsHeaders() {
            BffRuntime.ReservedHttpResponse jwks = dispatch(ReservedEndpoint.CLIENT_JWKS, null, "GET");

            assertAll("no cookie, no added header",
                    () -> assertTrue(jwks.setCookieHeaders().isEmpty(), "precondition: the answer sets no cookie"),
                    () -> assertEquals(Map.of(), jwks.headers(),
                            "the withheld key-set answer has no header before and none after"));
        }

        @Test
        @DisplayName("the string form of a reserved answer prints no location, no token and no cookie value")
        void stringFormIsRedacted() {
            String idToken = Generators.letterStrings(24, 32).next();
            String sessionCookie = login(idToken);
            String handle = sessionCookie.substring(sessionCookie.indexOf('=') + 1);

            BffRuntime.ReservedHttpResponse response = dispatch(ReservedEndpoint.LOGOUT, sessionCookie, "GET");
            String printed = response.toString();

            assertAll("what the string form holds",
                    () -> assertTrue(response.locationOptional().orElseThrow().contains(idToken),
                            "precondition: the location carries the ID token"),
                    () -> assertFalse(printed.contains(idToken), "no ID token"),
                    () -> assertFalse(printed.contains("idp.example.com"), "no location"),
                    () -> assertFalse(printed.contains(handle), "no session handle"),
                    () -> assertEquals(List.of(), cookieValues(response).stream().filter(printed::contains).toList(),
                            "no cookie value"),
                    () -> assertFalse(cookieValues(response).isEmpty(),
                            "precondition: the answer sets a cookie with a value, the logout-state cookie"),
                    () -> assertTrue(printed.contains("status=302"), printed),
                    () -> assertTrue(printed.contains("location=present"), printed),
                    () -> assertTrue(printed.contains("setCookieHeaders=" + response.setCookieHeaders().size()),
                            printed));
        }
    }

    /** The non-empty cookie values among the {@code Set-Cookie} lines of {@code response}. */
    private static List<String> cookieValues(BffRuntime.ReservedHttpResponse response) {
        return response.setCookieHeaders().stream()
                .map(line -> line.substring(line.indexOf('=') + 1, line.indexOf(';')))
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** Stores a session holding {@code idToken}, created at {@code now}, and returns its request cookie. */
    private static String storedSessionCookie(InMemorySessionStore store, String idToken, Instant now) {
        String handle = "handle-" + SessionRecord.newSessionId();
        store.create(SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                .accessToken(Generators.letterStrings(16, 32).next())
                .idToken(idToken)
                .sub(Generators.letterStrings(8, 16).next())
                .expiresAt(now.plus(SESSION_TTL)).build(), handle, now);
        return SESSION_COOKIE_NAME + "=" + handle;
    }

    private List<ResolvedRoute> allRoutes() {
        return List.of(
                httpRoute("session", SESSION_ROUTE, Require.SESSION),
                httpRoute("open", OPEN_ROUTE, Require.NONE),
                ResolvedRoute.builder()
                        .id("session-grpc")
                        .protocol(Protocol.GRPC)
                        .match(MatchConfig.builder().pathPrefix(GRPC_ROUTE).build())
                        .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                        .effectiveAllowedMethods(List.of(HttpMethod.POST))
                        .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                        .build());
    }

    private ResolvedRoute httpRoute(String id, String pathPrefix, Require require) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                .build();
    }

    private void startFront(BffRuntime runtime, GatewayConfig gatewayConfig, List<ResolvedRoute> routes)
            throws Exception {
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(routes), gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, new EdgeHardeningOptions(),
                new SheriffMetrics(new SimpleMeterRegistry()), runtime, EgressTrustProfiles.unconsulted(),
                PortalEndpoint.inert(), GatewayEdgeRouteBffWiringTest.gatewayJson());
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        front = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
    }

    private HttpServer requireFront() {
        HttpServer started = front;
        if (started == null) {
            throw new IllegalStateException("the edge front server is not started");
        }
        return started;
    }

    /** The {@code name=value} pair a browser sends back for {@code setCookie}. */
    private static String requestCookie(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /** The activity cookies on {@code answer} that carry the attributes the gateway itself writes. */
    private static List<String> gatewayActivityCookies(Answer answer) {
        return answer.setCookies().stream()
                .filter(line -> line.startsWith(ACTIVITY_COOKIE_NAME + "=")
                        && line.endsWith(GATEWAY_ACTIVITY_COOKIE_SUFFIX))
                .toList();
    }

    private static List<String> cookiesNotSetByTheGateway(Answer answer) {
        List<String> gateway = gatewayActivityCookies(answer);
        return answer.setCookies().stream().filter(line -> !gateway.contains(line)).toList();
    }

    private Answer get(String uri, @Nullable String cookie) throws Exception {
        return send(client, io.vertx.core.http.HttpMethod.GET, uri,
                cookie == null ? Map.of("Accept", "application/json")
                        : Map.of(COOKIE, cookie, "Accept", "application/json"),
                null);
    }

    private Answer reserved(io.vertx.core.http.HttpMethod method, String uri, @Nullable String cookie)
            throws Exception {
        return send(client, method, uri, cookie == null ? Map.of() : Map.of(COOKIE, cookie), null);
    }

    private Answer send(HttpClient via, io.vertx.core.http.HttpMethod method, String uri,
            Map<String, String> headers, @Nullable Buffer body) throws Exception {
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(requireFront().actualPort(), LoopbackHost.ADDRESS))
                .setHost(OIDC_HOST).setPort(requireFront().actualPort())
                .setMethod(method).setURI(uri);
        return Awaits.connect(via.request(options)
                        .compose(request -> {
                            headers.forEach(request::putHeader);
                            return body == null ? request.send() : request.send(body);
                        })
                        .compose(response -> response.body().map(buffer -> new Answer(response.statusCode(),
                                List.copyOf(response.headers().getAll(CACHE_CONTROL)),
                                List.copyOf(response.headers().getAll(SET_COOKIE)),
                                List.copyOf(response.headers().getAll(REFERRER_POLICY)),
                                response.getHeader("Location"), buffer.toString()))),
                "the edge response to " + method + " " + uri);
    }

    /** What the client saw of one edge response. */
    private record Answer(int status, List<String> cacheControl, List<String> setCookies,
    List<String> referrerPolicy, @Nullable String location, String body) {
    }

    /** Minimal {@link Instance} double resolving to one bean; the routes here never read it. */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        SingletonInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op: the test double owns no lifecycle
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}
