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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The request {@code Cookie} header an upstream receives is the one stage 5 rebuilds from the route's
 * {@code cookies} block, on every dispatch path — the HTTP proxy, gRPC and the WebSocket upgrade —
 * driven over a live Vert.x edge against a stub upstream that records the {@code Cookie} header fields
 * of every request and every upgrade it receives.
 * <p>
 * Every case sends the same browser {@code Cookie} header: three application cookies, one cookie no
 * list names, and two of the gateway's own cookies. Under {@code none} the upstream receives no
 * {@code Cookie} at all, under a list exactly the listed pairs, and under {@code all} every pair except
 * the gateway's own — in their inbound order, as one header.
 * <p>
 * The HTTP/2 case pins the transport behaviour the rebuild rests on: a client may send its cookies as
 * several {@code cookie} fields, and the HTTP server joins them into one value before stage 5 reads it,
 * so every allowed cookie is forwarded whichever field it travelled in.
 * <p>
 * The response direction follows the same block. The stub upstream answers every request with four
 * {@code Set-Cookie} lines — two listed cookies carrying a {@code Domain}, one unlisted cookie and one
 * gateway cookie — and a gRPC call additionally with three {@code Set-Cookie} trailers. On the HTTP
 * relay, the gRPC leading headers and the gRPC trailers alike the client receives only the lines the
 * policy admits, the gateway's never, and none of them with a {@code Domain} attribute.
 * <p>
 * A route that forwards cookies also carries the gateway-side same-origin check, attached here exactly
 * as route-table assembly attaches it: an unsafe request carrying a cookie the route would forward is
 * refused unless it comes from the edge's own origin or an additional origin.
 */
@DisplayName("GatewayEdgeRoute — the upstream Cookie header follows the route's cookies policy on every dispatch path")
@Tag("isolated-fork")
class GatewayEdgeCookiePolicyTest {

    private static final String COOKIE = "Cookie";
    private static final String UPSTREAM_BODY = "upstream";
    private static final String THEME = "theme=dark";
    private static final String APP = "app=1";
    private static final String OTHER = "other=x";
    private static final String GATEWAY_SESSION = GatewayCookies.RESERVED_PREFIX + "session=forged";
    private static final String GATEWAY_BINDING = GatewayCookies.RESERVED_PREFIX + "binding=b";
    /** What the browser sends on every case: application cookies interleaved with gateway cookies. */
    private static final String BROWSER_COOKIES = String.join("; ", THEME, GATEWAY_SESSION, APP, OTHER, GATEWAY_BINDING);
    /** The origin every WebSocket route admits on its handshake. */
    private static final String WS_ORIGIN = "https://gw.example.com";
    private static final String SET_COOKIE = "Set-Cookie";
    private static final String GRPC_CONTENT_TYPE = "application/grpc";
    /** The upstream's leading {@code Set-Cookie} lines, each listed cookie carrying a {@code Domain}. */
    private static final String THEME_SET = "theme=dark; Domain=example.com; Path=/";
    private static final String APP_SET = "app=1; domain = .example.com";
    private static final String OTHER_SET = "other=x";
    private static final String GATEWAY_SET = GatewayCookies.RESERVED_PREFIX + "session=forged; Path=/";
    /** The same lines as the client receives them: every {@code Domain} attribute removed. */
    private static final String THEME_RELAYED = "theme=dark; Path=/";
    private static final String APP_RELAYED = "app=1";
    /** The upstream's {@code Set-Cookie} trailers on a gRPC response. */
    private static final String THEME_TRAILER = "theme=trailer; Domain=example.com";
    private static final String OTHER_TRAILER = "other=trailer";
    private static final String GATEWAY_TRAILER = GatewayCookies.RESERVED_PREFIX + "binding=forged";
    private static final String THEME_TRAILER_RELAYED = "theme=trailer";
    private static final String GRPC_STATUS = "grpc-status";
    /** A foreign page's origin, as a browser stamps it on a cross-site request. */
    private static final String CROSS_SITE_ORIGIN = "https://evil.example.com";
    /** The {@code additional_origins} entry of the same-origin-check route. */
    private static final String PARTNER_ORIGIN = "https://partner.example.com";

    /**
     * The three {@code cookies.allow} modes, each with the {@code Cookie} header fields the upstream
     * must receive for {@link #BROWSER_COOKIES}, and the upstream {@code Set-Cookie} lines and trailers
     * the client must receive back.
     */
    enum Mode {
        NONE(CookiesConfig.NONE, List.of(), List.of(), List.of()),
        LIST(CookiesConfig.builder().allow(new CookieAllow.Named(List.of("theme", "app"))).build(),
                List.of(THEME + "; " + APP), List.of(THEME_RELAYED, APP_RELAYED), List.of(THEME_TRAILER_RELAYED)),
        ALL(CookiesConfig.builder().allow(CookieAllow.ALL).build(), List.of(THEME + "; " + APP + "; " + OTHER),
                List.of(THEME_RELAYED, APP_RELAYED, OTHER_SET), List.of(THEME_TRAILER_RELAYED, OTHER_TRAILER));

        private final CookiesConfig cookies;
        private final List<String> expectedFields;
        private final List<String> expectedSetCookies;
        private final List<String> expectedTrailerSetCookies;

        Mode(CookiesConfig cookies, List<String> expectedFields, List<String> expectedSetCookies,
                List<String> expectedTrailerSetCookies) {
            this.cookies = cookies;
            this.expectedFields = expectedFields;
            this.expectedSetCookies = expectedSetCookies;
            this.expectedTrailerSetCookies = expectedTrailerSetCookies;
        }

        String segment() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The gRPC service path of this mode's route. */
        String grpcService() {
            return "/policy." + segment() + ".Service";
        }
    }

    /** The {@code Cookie} header fields the stub upstream received, one list per request or upgrade. */
    private final List<List<String>> upstreamCookieFields = new CopyOnWriteArrayList<>();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstream;
    private @Nullable HttpServer front;
    private HttpClient client;
    private HttpClient http2Client;
    private WebSocketClient wsClient;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstream = Awaits.connect(vertx.createHttpServer()
                .webSocketHandler(ws -> upstreamCookieFields.add(List.copyOf(ws.headers().getAll(COOKIE))))
                .requestHandler(request -> request.body().onComplete(ignored -> {
                    upstreamCookieFields.add(List.copyOf(request.headers().getAll(COOKIE)));
                    HttpServerResponse response = request.response();
                    response.headers().add(SET_COOKIE, THEME_SET).add(SET_COOKIE, GATEWAY_SET)
                            .add(SET_COOKIE, APP_SET).add(SET_COOKIE, OTHER_SET);
                    if (GRPC_CONTENT_TYPE.equals(request.getHeader("Content-Type"))) {
                        response.trailers().add(SET_COOKIE, THEME_TRAILER).add(SET_COOKIE, GATEWAY_TRAILER)
                                .add(SET_COOKIE, OTHER_TRAILER).add("grpc-status", "0");
                    }
                    response.end(UPSTREAM_BODY);
                })).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        client = vertx.createHttpClient();
        http2Client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        wsClient = vertx.createWebSocketClient();
        startFront();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(wsClient.close(), "the WebSocket client to close");
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(http2Client.close(), "the HTTP/2 client to close");
        if (front != null) {
            Awaits.teardown(front.close(), "the edge front server to close");
        }
        Awaits.teardown(upstream.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @ParameterizedTest(name = "allow: {0}")
    @EnumSource(Mode.class)
    @DisplayName("the HTTP proxy forwards exactly the Cookie the route's policy admits")
    void httpProxyForwardsThePolicyCookie(Mode mode) throws Exception {
        int status = send(client, io.vertx.core.http.HttpMethod.GET, "/http-" + mode.segment() + "/page",
                Map.of(COOKIE, BROWSER_COOKIES), null);

        assertAll("the HTTP proxy under " + mode,
                () -> assertEquals(200, status, "precondition: the request is proxied"),
                () -> assertEquals(List.of(mode.expectedFields), List.copyOf(upstreamCookieFields)));
    }

    @ParameterizedTest(name = "allow: {0}")
    @EnumSource(Mode.class)
    @DisplayName("gRPC forwards exactly the Cookie the route's policy admits")
    void grpcForwardsThePolicyCookie(Mode mode) throws Exception {
        int status = grpcCall(mode).status();

        assertAll("the gRPC dispatch under " + mode,
                () -> assertEquals(200, status, "precondition: the call is proxied"),
                () -> assertEquals(List.of(mode.expectedFields), List.copyOf(upstreamCookieFields)));
    }

    @ParameterizedTest(name = "allow: {0}")
    @EnumSource(Mode.class)
    @DisplayName("the WebSocket upgrade forwards exactly the Cookie the route's policy admits")
    void webSocketUpgradeForwardsThePolicyCookie(Mode mode) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(LoopbackHost.ADDRESS).setPort(requireFront().actualPort())
                .setURI("/ws-" + mode.segment() + "/room")
                .addHeader("Origin", WS_ORIGIN)
                .addHeader(COOKIE, BROWSER_COOKIES);

        WebSocket socket = Awaits.connect(wsClient.connect(options), "the WebSocket upgrade through the edge");
        Awaits.until(() -> !upstreamCookieFields.isEmpty(), "the upstream to receive the relayed upgrade",
                Awaits.CONNECT_CEILING_SECONDS);
        Awaits.teardown(socket.close(), "the relayed WebSocket to close");

        assertEquals(List.of(mode.expectedFields), List.copyOf(upstreamCookieFields),
                "the upstream's upgrade request under " + mode);
    }

    @ParameterizedTest(name = "allow: {0}")
    @EnumSource(Mode.class)
    @DisplayName("the HTTP relay returns exactly the upstream Set-Cookie lines the route's policy admits, without Domain")
    void httpRelayReturnsThePolicySetCookies(Mode mode) throws Exception {
        Answer answer = exchangeAnswer(client, io.vertx.core.http.HttpMethod.GET, "/http-" + mode.segment() + "/page",
                Map.of(COOKIE, BROWSER_COOKIES), null);

        assertAll("the HTTP relay under " + mode,
                () -> assertEquals(200, answer.status(), "precondition: the request is proxied"),
                () -> assertEquals(mode.expectedSetCookies, answer.setCookies(),
                        "the gateway cookie is dropped and every other line follows the policy, in order"),
                () -> assertFalse(answer.setCookies().stream().anyMatch(GatewayEdgeCookiePolicyTest::carriesDomain),
                        "no relayed line carries a Domain attribute"));
    }

    @ParameterizedTest(name = "allow: {0}")
    @EnumSource(Mode.class)
    @DisplayName("the gRPC relay returns exactly the leading Set-Cookie lines the route's policy admits, without Domain")
    void grpcLeadingHeadersReturnThePolicySetCookies(Mode mode) throws Exception {
        Answer answer = grpcCall(mode);

        assertAll("the gRPC leading headers under " + mode,
                () -> assertEquals(200, answer.status(), "precondition: the call is proxied"),
                () -> assertEquals(mode.expectedSetCookies, answer.setCookies()),
                () -> assertFalse(answer.setCookies().stream().anyMatch(GatewayEdgeCookiePolicyTest::carriesDomain),
                        "no relayed line carries a Domain attribute"));
    }

    @ParameterizedTest(name = "allow: {0}")
    @EnumSource(Mode.class)
    @DisplayName("the gRPC relay returns exactly the Set-Cookie trailers the route's policy admits, without Domain")
    void grpcTrailersReturnThePolicySetCookies(Mode mode) throws Exception {
        Answer answer = grpcCall(mode);

        assertAll("the gRPC trailers under " + mode,
                () -> assertEquals("0", answer.grpcStatus(), "the grpc-status trailer crosses under every mode"),
                () -> assertEquals(mode.expectedTrailerSetCookies, answer.trailerSetCookies(),
                        "a trailer passes the same filter as a leading header"),
                () -> assertFalse(answer.trailerSetCookies().stream()
                                .anyMatch(GatewayEdgeCookiePolicyTest::carriesDomain),
                        "no relayed trailer carries a Domain attribute"));
    }

    /**
     * HTTP/2 lets a client send its cookies as several {@code cookie} header fields (RFC 9113 section
     * 8.2.3). The frames are written by hand, every field an HPACK literal, because an HTTP client
     * library is free to join the fields before they leave. The control that the Vert.x HTTP server
     * joins such fields into one value before application code reads it is
     * {@code GatewayEdgeGatewayCookieTest.SplitCookieHeaderFields.serverJoinsTheTwoFields}.
     */
    @Nested
    @DisplayName("HTTP/2 — cookies split over several Cookie fields are all judged by the policy")
    class SplitCookieFields {

        @ParameterizedTest(name = "allow: {0}")
        @EnumSource(Mode.class)
        @DisplayName("every allowed cookie is forwarded whichever field it arrived in")
        void splitFieldsForwardEveryAllowedCookie(Mode mode) throws Exception {
            exchange("/http-" + mode.segment() + "/page", List.of(THEME, GATEWAY_SESSION, APP + "; " + OTHER,
                    GATEWAY_BINDING));

            assertEquals(List.of(mode.expectedFields), List.copyOf(upstreamCookieFields),
                    "the upstream receives the policy's pairs as one header, however many fields carried"
                            + " them — the regression a transport that stopped joining the fields would cause");
        }

        /**
         * Sends one HTTP/2 GET to the edge over a raw connection, each of {@code cookieFields} as a
         * {@code cookie} header field of its own, and returns once the response headers have arrived.
         */
        private void exchange(String path, List<String> cookieFields) throws Exception {
            int port = requireFront().actualPort();
            Buffer block = Buffer.buffer();
            block.appendByte((byte) 0x82);
            block.appendByte((byte) 0x86);
            block.appendByte((byte) 0x04);
            appendString(block, path);
            block.appendByte((byte) 0x01);
            appendString(block, LoopbackHost.ADDRESS + ":" + port);
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
    }

    /**
     * The gateway-side same-origin check of a route that forwards browser cookies: an unsafe request
     * carrying a cookie the route would forward must come from the origin the edge was reached on or
     * from one of the route's {@code additional_origins}, or it is refused {@code 403} before it reaches
     * the upstream. A request carrying no forwardable cookie and a safe method are never checked.
     */
    @Nested
    @DisplayName("the same-origin check of a cookie-forwarding route")
    class SameOriginCheck {

        private static final String CHECKED_PATH = "/csrf-list/orders";
        private static final String UNCHECKED_PATH = "/csrf-off/orders";
        private static final String FORWARDED_PAIRS = THEME + "; " + APP;

        private int post(String path, Map<String, String> headers) throws Exception {
            return send(client, io.vertx.core.http.HttpMethod.POST, path, headers, null);
        }

        @Test
        @DisplayName("a cross-site POST carrying a forwarded cookie is refused 403 and never reaches the upstream")
        void crossSitePostWithForwardedCookieRefused() throws Exception {
            int status = post(CHECKED_PATH, Map.of("Origin", CROSS_SITE_ORIGIN, COOKIE, BROWSER_COOKIES));

            assertAll("the forged request is stopped at the edge",
                    () -> assertEquals(403, status),
                    () -> assertTrue(upstreamCookieFields.isEmpty(), "the upstream must record no request"));
        }

        @Test
        @DisplayName("the same cross-site POST with no Cookie is forwarded")
        void crossSitePostWithoutCookieForwarded() throws Exception {
            int status = post(CHECKED_PATH, Map.of("Origin", CROSS_SITE_ORIGIN));

            assertAll("a request carrying no ambient credential has nothing to forge",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of()), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("a cross-site POST carrying only a gateway cookie is forwarded, the cookie stripped")
        void crossSitePostWithOnlyGatewayCookieForwarded() throws Exception {
            int status = post(CHECKED_PATH, Map.of("Origin", CROSS_SITE_ORIGIN, COOKIE, GATEWAY_SESSION));

            assertAll("the gateway's own cookie never crosses, so it is not a forwarded credential",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of()), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("a cross-site POST carrying only a name the route's list does not admit is forwarded")
        void crossSitePostWithOnlyUnadmittedCookieForwarded() throws Exception {
            int status = post(CHECKED_PATH, Map.of("Origin", CROSS_SITE_ORIGIN, COOKIE, OTHER));

            assertAll("a cookie the route would not forward is no credential for the upstream",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of()), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("an Origin-less, Sec-Fetch-Site-less POST is forwarded without a cookie and refused with one")
        void originlessPost() throws Exception {
            int withoutCookie = post(CHECKED_PATH, Map.of());
            int withCookie = post(CHECKED_PATH, Map.of(COOKIE, BROWSER_COOKIES));

            assertAll("a non-browser client passes until it carries a forwarded cookie without a proof",
                    () -> assertEquals(200, withoutCookie, "no cookie, no check"),
                    () -> assertEquals(403, withCookie, "a forwarded cookie with no origin proof is refused"),
                    () -> assertEquals(List.of(List.of()), List.copyOf(upstreamCookieFields),
                            "only the cookieless request reached the upstream"));
        }

        @Test
        @DisplayName("a same-origin POST carrying a forwarded cookie passes and forwards the listed pairs")
        void sameOriginPostPasses() throws Exception {
            int status = post(CHECKED_PATH, Map.of("Origin", frontOrigin(), COOKIE, BROWSER_COOKIES));

            assertAll("the origin the edge was reached on is trusted",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of(FORWARDED_PAIRS)), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("a POST from an additional origin carrying a forwarded cookie passes")
        void additionalOriginPasses() throws Exception {
            int status = post(CHECKED_PATH, Map.of("Origin", PARTNER_ORIGIN, COOKIE, BROWSER_COOKIES));

            assertAll("the route's additional_origins entry is trusted beside the received origin",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of(FORWARDED_PAIRS)), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("same_origin_check: false admits a cross-site POST carrying a forwarded cookie")
        void checkOffAdmitsCrossSitePost() throws Exception {
            int status = post(UNCHECKED_PATH, Map.of("Origin", CROSS_SITE_ORIGIN, COOKIE, BROWSER_COOKIES));

            assertAll("with the check off the upstream defends itself",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of(FORWARDED_PAIRS)), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("a cross-site GET carrying a forwarded cookie is never checked")
        void safeMethodNeverChecked() throws Exception {
            int status = send(client, io.vertx.core.http.HttpMethod.GET, CHECKED_PATH,
                    Map.of("Origin", CROSS_SITE_ORIGIN, COOKIE, BROWSER_COOKIES), null);

            assertAll("a safe method carries no state-changing intent",
                    () -> assertEquals(200, status),
                    () -> assertEquals(List.of(List.of(FORWARDED_PAIRS)), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("on a gRPC route under all a cookieless Origin-less call passes")
        void grpcCookielessOriginlessCallPasses() throws Exception {
            Answer answer = grpcCallWith(Mode.ALL, Map.of());

            assertAll("a gRPC client carrying no cookie is not asked for an origin",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals("0", answer.grpcStatus(), "the call completed at the upstream"),
                    () -> assertEquals(List.of(List.of()), List.copyOf(upstreamCookieFields)));
        }

        @Test
        @DisplayName("on a gRPC route under all a cross-site call carrying a forwarded cookie is refused")
        void grpcCrossSiteCallWithForwardedCookieRefused() throws Exception {
            Answer answer = grpcCallWith(Mode.ALL, Map.of("Origin", CROSS_SITE_ORIGIN, COOKIE, BROWSER_COOKIES));

            assertAll("the refusal is a trailers-only PERMISSION_DENIED",
                    () -> assertEquals(200, answer.status(), "a gRPC rejection is carried in grpc-status"),
                    () -> assertEquals(Integer.toString(GrpcStatusMapper.PERMISSION_DENIED), answer.headerGrpcStatus()),
                    () -> assertTrue(upstreamCookieFields.isEmpty(), "the upstream must record no request"));
        }
    }

    /**
     * One HTTP, one gRPC and one WebSocket route per {@link Mode}, all on the stub upstream, plus two
     * HTTP routes for the same-origin check: one with it on and one additional origin, one with it off.
     */
    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            routes.add(route("http-" + mode.segment(), Protocol.HTTP, "/http-" + mode.segment(), Set.of(HttpMethod.GET),
                    mode.cookies));
            routes.add(route("grpc-" + mode.segment(), Protocol.GRPC, mode.grpcService(), Set.of(HttpMethod.POST),
                    mode.cookies));
            routes.add(route("ws-" + mode.segment(), Protocol.WEBSOCKET, "/ws-" + mode.segment(),
                    Set.of(HttpMethod.GET), mode.cookies));
        }
        CookieAllow listed = new CookieAllow.Named(List.of("theme", "app"));
        routes.add(route("csrf-list", Protocol.HTTP, "/csrf-list", Set.of(HttpMethod.GET, HttpMethod.POST),
                CookiesConfig.builder().allow(listed).additionalOrigins(List.of(PARTNER_ORIGIN)).build()));
        routes.add(route("csrf-off", Protocol.HTTP, "/csrf-off", Set.of(HttpMethod.POST),
                CookiesConfig.builder().allow(listed).sameOriginCheck(false).build()));
        return routes;
    }

    /**
     * The same-origin check route-table assembly attaches to a route: present for a non-WebSocket route
     * whose block forwards a cookie with the check on, trusting the block's additional origins.
     */
    private static @Nullable CsrfDefence cookieOriginDefence(Protocol protocol, CookiesConfig cookies) {
        if (protocol == Protocol.WEBSOCKET || !cookies.forwardsAny() || !cookies.effectiveSameOriginCheck()) {
            return null;
        }
        List<String> additionalOrigins = cookies.additionalOrigins();
        return CsrfDefence.forCookieForwardingRoute(additionalOrigins == null ? List.of() : additionalOrigins);
    }

    private Route route(String id, Protocol protocol, String pathPrefix, Set<HttpMethod> methods,
            CookiesConfig cookies) {
        Route.RouteBuilder builder = ConfigFixtures.route(id, pathPrefix)
                .protocol(protocol)
                .effectiveAllowedMethods(methods)
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                .effectiveCookies(cookies)
                .cookieOriginDefence(cookieOriginDefence(protocol, cookies));
        if (protocol == Protocol.WEBSOCKET) {
            // A positive-list naming nothing, so the client's own handshake headers are not copied onto
            // the upgrade the relay dials; the Cookie header is governed by the cookies block alone.
            builder.effectiveAllowedOrigins(Set.of(WS_ORIGIN))
                    .effectiveForward(ForwardConfig.builder().headersAllow(List.of()).build());
        }
        return builder.build();
    }

    private void startFront() throws Exception {
        front = EdgeFixtures.edge(vertx, virtualThreadExecutor).routes(new RouteTable(routes())).serve();
    }

    private HttpServer requireFront() {
        HttpServer started = front;
        if (started == null) {
            throw new IllegalStateException("the edge front server is not started");
        }
        return started;
    }

    /** The origin of the edge itself, which a same-origin unsafe request carries. */
    private String frontOrigin() {
        return "http://" + LoopbackHost.ADDRESS + ":" + requireFront().actualPort();
    }

    private int send(HttpClient via, io.vertx.core.http.HttpMethod method, String uri, Map<String, String> headers,
            @Nullable Buffer body) throws Exception {
        return exchangeAnswer(via, method, uri, headers, body).status();
    }

    private Answer exchangeAnswer(HttpClient via, io.vertx.core.http.HttpMethod method, String uri,
            Map<String, String> headers, @Nullable Buffer body) throws Exception {
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(requireFront().actualPort(), LoopbackHost.ADDRESS))
                .setHost(LoopbackHost.ADDRESS).setPort(requireFront().actualPort())
                .setMethod(method).setURI(uri);
        return Awaits.connect(via.request(options)
                        .compose(request -> {
                            headers.forEach(request::putHeader);
                            return body == null ? request.send() : request.send(body);
                        })
                        .compose(response -> response.body().map(ignored -> new Answer(response.statusCode(),
                                List.copyOf(response.headers().getAll(SET_COOKIE)),
                                List.copyOf(response.trailers().getAll(SET_COOKIE)),
                                response.trailers().get(GRPC_STATUS),
                                response.headers().get(GRPC_STATUS)))),
                "the edge response to " + method + " " + uri);
    }

    /** A same-origin gRPC call carrying the browser cookies, which the route's check admits. */
    private Answer grpcCall(Mode mode) throws Exception {
        return grpcCallWith(mode, Map.of(COOKIE, BROWSER_COOKIES, "Origin", frontOrigin()));
    }

    private Answer grpcCallWith(Mode mode, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("Content-Type", GRPC_CONTENT_TYPE);
        headers.put("TE", "trailers");
        return exchangeAnswer(http2Client, io.vertx.core.http.HttpMethod.POST, mode.grpcService() + "/Call",
                headers, Buffer.buffer(new byte[5]));
    }

    /** Whether a relayed {@code Set-Cookie} line still carries a {@code Domain} attribute. */
    private static boolean carriesDomain(String setCookieLine) {
        return setCookieLine.toLowerCase(Locale.ROOT).contains("domain");
    }

    /**
     * What the client saw of one edge response.
     *
     * @param status            the response status
     * @param setCookies        the {@code Set-Cookie} lines of the leading headers
     * @param trailerSetCookies the {@code Set-Cookie} trailers
     * @param grpcStatus        the {@code grpc-status} trailer, {@code null} when none was sent
     * @param headerGrpcStatus  the {@code grpc-status} leading header of a trailers-only answer,
     *                          {@code null} when none was sent
     */
    private record Answer(int status, List<String> setCookies, List<String> trailerSetCookies,
    @Nullable String grpcStatus, @Nullable String headerGrpcStatus) {
    }
}
