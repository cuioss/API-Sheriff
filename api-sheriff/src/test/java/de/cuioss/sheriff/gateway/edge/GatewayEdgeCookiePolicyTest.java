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

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
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
     * library is free to join the fields before they leave.
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

        @Test
        @DisplayName("control: the HTTP/2 server hands the split fields to the edge as one joined value")
        void serverJoinsTheSplitFields() throws Exception {
            List<String> fields = List.of(THEME, APP);

            exchangeWith(upstream.actualPort(), "/direct", fields);

            assertEquals(List.of(List.of(THEME + "; " + APP)), List.copyOf(upstreamCookieFields),
                    "the Vert.x HTTP server joins the cookie fields with a semicolon and a space; stage 5"
                            + " reads that one value, which is why its first-value rule loses no cookie");
        }

        private void exchange(String path, List<String> cookieFields) throws Exception {
            exchangeWith(requireFront().actualPort(), path, cookieFields);
        }

        /**
         * Sends one HTTP/2 GET over a raw connection, each of {@code cookieFields} as a {@code cookie}
         * header field of its own, and returns once the response headers have arrived.
         */
        private void exchangeWith(int port, String path, List<String> cookieFields) throws Exception {
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

    /** One HTTP, one gRPC and one WebSocket route per {@link Mode}, all on the stub upstream. */
    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            routes.add(route("http-" + mode.segment(), Protocol.HTTP, "/http-" + mode.segment(), HttpMethod.GET,
                    mode.cookies));
            routes.add(route("grpc-" + mode.segment(), Protocol.GRPC, mode.grpcService(), HttpMethod.POST,
                    mode.cookies));
            routes.add(route("ws-" + mode.segment(), Protocol.WEBSOCKET, "/ws-" + mode.segment(), HttpMethod.GET,
                    mode.cookies));
        }
        return routes;
    }

    private Route route(String id, Protocol protocol, String pathPrefix, HttpMethod method, CookiesConfig cookies) {
        Route.RouteBuilder builder = Route.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(method))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                .effectiveCookies(cookies);
        if (protocol == Protocol.WEBSOCKET) {
            // A positive-list naming nothing, so the client's own handshake headers are not copied onto
            // the upgrade the relay dials; the Cookie header is governed by the cookies block alone.
            builder.effectiveAllowedOrigins(Set.of(WS_ORIGIN))
                    .effectiveForward(ForwardConfig.builder().headersAllow(List.of()).build());
        }
        return builder.build();
    }

    private void startFront() throws Exception {
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(routes()), GatewayConfig.builder().version(1).build(),
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, new EdgeHardeningOptions(),
                new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(), EgressTrustProfiles.unconsulted(),
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
                                response.trailers().get("grpc-status")))),
                "the edge response to " + method + " " + uri);
    }

    private Answer grpcCall(Mode mode) throws Exception {
        return exchangeAnswer(http2Client, io.vertx.core.http.HttpMethod.POST, mode.grpcService() + "/Call",
                Map.of(COOKIE, BROWSER_COOKIES, "Origin", frontOrigin(), "Content-Type", GRPC_CONTENT_TYPE,
                        "TE", "trailers"),
                Buffer.buffer(new byte[5]));
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
     */
    private record Answer(int status, List<String> setCookies, List<String> trailerSetCookies,
            @Nullable String grpcStatus) {
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
