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
package de.cuioss.sheriff.gateway.edge.relay;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnd;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.routing.LocationRewriter;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ResponseStage — stage 7 streamed response header policy")
@Tag("isolated-fork")
class ResponseStageTest {

    /** The gateway cookies of a gateway without an {@code oidc} block: the reserved prefix alone. */
    private static final GatewayCookies RESERVED_ONLY = GatewayCookies.of(ConfigFixtures.gateway().build());

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("a gRPC stream-idle bound built with a non-positive timeout is refused")
    void nonPositiveStreamIdleTimeoutIsRefused(long timeoutMillis) {
        Vertx vertx = Vertx.vertx();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> ResponseStage.GrpcStreamIdleBound.withTimeout(vertx, timeoutMillis));
        } finally {
            vertx.close();
        }
    }

    @Nested
    @DisplayName("hop-by-hop stripping")
    class HopByHop {

        @ParameterizedTest
        @ValueSource(strings = {
                "Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
                "Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade",
                "Content-Length"})
        @DisplayName("strips hop-by-hop and framing headers regardless of not_modified")
        void stripsHopByHop(String header) {
            assertFalse(ResponseStage.isForwardableResponseHeader(header, true),
                    header + " is hop-by-hop and must never relay (not_modified enabled)");
            assertFalse(ResponseStage.isForwardableResponseHeader(header, false),
                    header + " is hop-by-hop and must never relay (not_modified disabled)");
        }

        @Test
        @DisplayName("matching is case-insensitive")
        void caseInsensitive() {
            assertFalse(ResponseStage.isForwardableResponseHeader("transfer-encoding", true));
            assertFalse(ResponseStage.isForwardableResponseHeader("CONTENT-LENGTH", true));
        }
    }

    @Nested
    @DisplayName("conditional-response headers gated by not_modified")
    class ConditionalHeaders {

        @ParameterizedTest
        @ValueSource(strings = {"ETag", "Last-Modified"})
        @DisplayName("the validator flips with the toggle — this half is one direction of a two-directional contract")
        void validatorFlipsWithTheToggle(String header) {
            // The response half of the not_modified contract. Its counterpart is the request half in
            // forward.ForwardPolicyStage, whose conditional tier (the two 304 validators If-None-Match
            // and If-Modified-Since) is gated by the SAME flag; ForwardPolicyStageTest asserts the two
            // together, since only that package can see both. The control kept here is the one that
            // survives locally: this header must genuinely FLIP with the toggle rather than being
            // unconditionally relayed or unconditionally stripped. Asserting both states of one header in
            // one test is what makes a change that pins it in either direction fail here rather than
            // silently orphaning the pair.
            assertTrue(ResponseStage.isForwardableResponseHeader(header, true),
                    header + " must relay while the route honours conditional requests");
            assertFalse(ResponseStage.isForwardableResponseHeader(header, false),
                    header + " must be stripped once it does not — a validator relayed on a route whose"
                            + " request half never forwarded the matching precondition is an answer to a"
                            + " question the gateway never asked");
        }
    }

    @Nested
    @DisplayName("ordinary headers")
    class OrdinaryHeaders {

        @ParameterizedTest
        @ValueSource(strings = {"Content-Type", "Cache-Control", "Set-Cookie", "Location"})
        @DisplayName("relays ordinary response headers regardless of not_modified")
        void relaysOrdinaryHeaders(String header) {
            assertTrue(ResponseStage.isForwardableResponseHeader(header, true));
            assertTrue(ResponseStage.isForwardableResponseHeader(header, false));
        }
    }

    @Nested
    @DisplayName("Location rewrite gated by upstream.rewrite_location")
    class LocationRewrite {

        private final LocationRewriter rewriter =
                new LocationRewriter(new ResolvedUpstream("https", "backend", 8443, "/svc/v1"), "/api", false);

        @ParameterizedTest
        @ValueSource(strings = {"Location", "location", "LOCATION"})
        @DisplayName("maps an upstream Location through the route rewriter when the route opts in")
        void rewritesLocationWhenEnabled(String header) {
            assertEquals("/api/items?id=7",
                    ResponseStage.relayedHeaderValue(header, "https://backend:8443/svc/v1/items?id=7", rewriter),
                    header + " must be mapped onto the route's match key on an opted-in route");
        }

        @ParameterizedTest
        @CsvSource({
                "/svc/v1/login, /api/login",
                "https://backend:8443/svc/v1/items?id=7, /api/items?id=7"})
        @DisplayName("the rewrite flips with the toggle for the same header value, path or absolute URL")
        void rewriteFlipsWithTheToggle(String location, String rewritten) {
            assertEquals(rewritten, ResponseStage.relayedHeaderValue("Location", location, rewriter),
                    "an opted-in route maps the upstream Location onto its match key");
            assertEquals(location, ResponseStage.relayedHeaderValue("Location", location, null),
                    "a route without rewrite_location carries no rewriter and relays Location verbatim");
        }

        @Test
        @DisplayName("relays a foreign-origin Location unchanged even when the route opts in")
        void relaysForeignLocationUnchangedWhenEnabled() {
            String location = "https://idp.example/authorize?client_id=gw";

            assertEquals(location, ResponseStage.relayedHeaderValue("Location", location, rewriter));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Content-Location", "Refresh", "Link", "Set-Cookie"})
        @DisplayName("never rewrites a header other than Location")
        void neverRewritesOtherHeaders(String header) {
            String value = "https://backend:8443/svc/v1/items";

            assertEquals(value, ResponseStage.relayedHeaderValue(header, value, rewriter),
                    header + " is not Location and must keep its upstream value");
        }
    }

    @Nested
    @DisplayName("gateway header precedence over a live relay: set overwrites, default defers to the origin")
    class HeaderPrecedence {

        private static final String FRAME_OPTIONS = "X-Frame-Options";
        private static final String CSP = "Content-Security-Policy";
        private static final String ORIGIN_POLICY = "default-src https://origin.example";
        private static final String GATEWAY_POLICY = "default-src 'self'";
        private static final String ORIGIN_SETS_HEADERS = "/origin-sets-headers";
        private static final String ORIGIN_SETS_NOTHING = "/origin-sets-nothing";

        private Vertx vertx;
        private HttpClient client;
        private HttpServer upstream;
        private HttpServer front;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();

            // Stub origin: on one path it sends its own X-Frame-Options and — lower-cased, since header
            // names are case-insensitive — its own Content-Security-Policy; on the other it sends neither.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(req -> {
                HttpServerResponse response = req.response();
                if (ORIGIN_SETS_HEADERS.equals(req.path())) {
                    response.putHeader(FRAME_OPTIONS, "SAMEORIGIN");
                    response.putHeader("content-security-policy", ORIGIN_POLICY);
                }
                response.end("origin-body");
            }).listen(0, LoopbackHost.ADDRESS), "the stub origin to start listening");
            int upstreamPort = upstream.actualPort();

            // Front server: relays exactly as the proxy dispatch path does, with X-Frame-Options in
            // set mode and Content-Security-Policy in default mode.
            ResponseStage responseStage = new ResponseStage(RESERVED_ONLY);
            front = Awaits.connect(vertx.createHttpServer().requestHandler(clientReq -> client
                    .request(HttpMethod.GET, upstreamPort, LoopbackHost.ADDRESS, clientReq.path())
                    .compose(HttpClientRequest::send)
                    .onSuccess(upResp -> responseStage
                            .relay(upResp, clientReq.response(), false, null, CookiesConfig.NONE, false,
                                    Map.of(FRAME_OPTIONS, "DENY"), Map.of(CSP, GATEWAY_POLICY), Supplier::get)
                            .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                    .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                    .listen(0, LoopbackHost.ADDRESS), "the relaying front server to start listening");
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(front.close(), "the relaying front server to close");
            Awaits.teardown(upstream.close(), "the stub origin to close");
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        private MultiMap relayedHeaders(String path) throws Exception {
            return Awaits.connect(client
                    .request(HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, path)
                    .compose(HttpClientRequest::send)
                    .compose(resp -> resp.body().map(body -> resp.headers())), "the relayed response to " + path);
        }

        @Test
        @DisplayName("a set-mode header overwrites the value the origin sent")
        void setModeOverwritesOriginValue() throws Exception {
            MultiMap headers = relayedHeaders(ORIGIN_SETS_HEADERS);

            assertEquals(List.of("DENY"), headers.getAll(FRAME_OPTIONS),
                    "exactly the gateway value reaches the client — the origin SAMEORIGIN is replaced, not appended");
        }

        @Test
        @DisplayName("a default-mode header keeps the value the origin sent, whatever case it used")
        void defaultModeKeepsOriginValue() throws Exception {
            MultiMap headers = relayedHeaders(ORIGIN_SETS_HEADERS);

            assertEquals(List.of(ORIGIN_POLICY), headers.getAll(CSP),
                    "the origin policy is relayed untouched and the gateway policy is not added alongside it");
        }

        @Test
        @DisplayName("a default-mode header is emitted when the origin sent none, and a set-mode header still applies")
        void defaultModeFillsAbsentOriginValue() throws Exception {
            MultiMap headers = relayedHeaders(ORIGIN_SETS_NOTHING);

            assertAll("with no origin value both modes emit the gateway value",
                    () -> assertEquals(List.of(GATEWAY_POLICY), headers.getAll(CSP)),
                    () -> assertEquals(List.of("DENY"), headers.getAll(FRAME_OPTIONS)));
        }
    }

    /**
     * The one shared upstream-header predicate both relays pass every leading header and trailer
     * through, judged directly.
     */
    @Nested
    @DisplayName("relayedUpstreamHeader — the one judgement every upstream header passes")
    class SharedPredicate {

        private final ResponseStage stage = new ResponseStage(RESERVED_ONLY);
        private final CookiesConfig all = new CookiesConfig(CookieAllow.ALL, null, null);

        @Test
        @DisplayName("a hop-by-hop header does not cross under any cookie policy")
        void hopByHopNeverCrosses() {
            assertAll("the forwardability rule comes first",
                    () -> assertNull(stage.relayedUpstreamHeader("Connection", "close", true, all, false)),
                    () -> assertNull(stage.relayedUpstreamHeader("Transfer-Encoding", "chunked", true, all, true),
                            "keeping the identity headers re-admits no hop-by-hop header"));
        }

        @Test
        @DisplayName("a validator crosses only on a not_modified-enabled route")
        void validatorFollowsNotModified() {
            assertAll("the not_modified gate is part of the shared judgement",
                    () -> assertEquals("\"v1\"",
                            stage.relayedUpstreamHeader("ETag", "\"v1\"", true, CookiesConfig.NONE, false)),
                    () -> assertNull(stage.relayedUpstreamHeader("ETag", "\"v1\"", false, CookiesConfig.NONE, false)));
        }

        @Test
        @DisplayName("an ordinary header crosses unchanged even under allow: none")
        void ordinaryHeaderCrossesUnchanged() {
            assertEquals("text/plain",
                    stage.relayedUpstreamHeader("Content-Type", "text/plain", false, CookiesConfig.NONE, false));
        }

        @Test
        @DisplayName("a Set-Cookie line crosses by the policy, with its Domain attribute removed")
        void setCookieFollowsThePolicy() {
            assertAll("the cookie policy is part of the shared judgement",
                    () -> assertNull(stage.relayedUpstreamHeader("Set-Cookie", "app=1", false, CookiesConfig.NONE,
                                    false),
                            "the default none relays no upstream cookie"),
                    () -> assertEquals("app=1; Path=/",
                            stage.relayedUpstreamHeader("Set-Cookie", "app=1; Domain=example.com; Path=/", false, all,
                                    false)),
                    () -> assertNull(stage.relayedUpstreamHeader("Set-Cookie", "__Host-sheriff-x=1", false, all, false),
                            "a gateway cookie is dropped under all"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Server", "server", "X-Powered-By", "X-POWERED-BY"})
        @DisplayName("an identity header is stripped by default and kept on the opt-out")
        void identityHeaderFollowsTheOptOut(String name) {
            assertAll("the identity strip is part of the shared judgement",
                    () -> assertNull(stage.relayedUpstreamHeader(name, "probe/1.0", true, all, false),
                            name + " must not cross on a route that keeps the default"),
                    () -> assertEquals("probe/1.0", stage.relayedUpstreamHeader(name, "probe/1.0", true, all, true),
                            name + " crosses unchanged on a keep_identity_headers route"));
        }

        @ParameterizedTest(name = "keepIdentityHeaders = {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("the strip composes with the ETag gating and the Set-Cookie policy, changing neither")
        void identityStripComposesWithTheOtherRules(boolean keepIdentityHeaders) {
            assertAll("the not_modified gate and the cookie policy are independent of the identity strip",
                    () -> assertEquals("\"v1\"",
                            stage.relayedUpstreamHeader("ETag", "\"v1\"", true, CookiesConfig.NONE, keepIdentityHeaders)),
                    () -> assertNull(
                            stage.relayedUpstreamHeader("ETag", "\"v1\"", false, CookiesConfig.NONE, keepIdentityHeaders)),
                    () -> assertNull(stage.relayedUpstreamHeader("Set-Cookie", "app=1", false, CookiesConfig.NONE,
                            keepIdentityHeaders)),
                    () -> assertEquals("app=1; Path=/", stage.relayedUpstreamHeader("Set-Cookie",
                            "app=1; Domain=example.com; Path=/", false, all, keepIdentityHeaders)),
                    () -> assertEquals("kept", stage.relayedUpstreamHeader("X-Custom", "kept", false,
                            CookiesConfig.NONE, keepIdentityHeaders)));
        }
    }

    /**
     * Both relays over a live origin that sends {@code Server} and {@code X-Powered-By} in its leading
     * headers <em>and</em> in its trailer section, beside an ordinary {@code X-Custom} control. The front
     * server relays that one origin with the identity strip on (the default) and off (the
     * {@code keep_identity_headers} opt-out), so every difference between the answers is the flag's
     * doing — and the opt-out answers prove the origin really sent the headers the default answers lack.
     */
    @Nested
    @DisplayName("upstream identity headers are stripped on every relay path unless the route opts out")
    class IdentityHeaders {

        private static final String SERVER = "Server";
        private static final String POWERED_BY = "X-Powered-By";
        private static final String CONTROL = "X-Custom";
        private static final String SERVER_VALUE = "nginx/1.27.0";
        private static final String POWERED_BY_VALUE = "Express";
        private static final String TRAILER_SERVER_VALUE = "nginx/1.27.0-trailer";
        private static final String TRAILER_POWERED_BY_VALUE = "Express-trailer";
        private static final String STRIP = "/strip";
        private static final String KEEP = "/keep";
        private static final String RELAY = "/relay";
        private static final String WITH_TRAILERS = "/with-trailers";

        private Vertx vertx;
        private HttpClient client;
        private HttpServer upstream;
        private HttpServer front;

        /** What the client saw of one relayed response: its leading headers and its trailers. */
        private record Relayed(MultiMap headers, MultiMap trailers) {
        }

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(req -> {
                HttpServerResponse response = req.response();
                response.putHeader(SERVER, SERVER_VALUE).putHeader(POWERED_BY, POWERED_BY_VALUE)
                        .putHeader(CONTROL, "kept");
                response.setChunked(true);
                response.trailers().add(SERVER, TRAILER_SERVER_VALUE).add(POWERED_BY, TRAILER_POWERED_BY_VALUE)
                        .add("grpc-status", "0");
                response.end("origin-body");
            }).listen(0, LoopbackHost.ADDRESS), "the stub origin to start listening");
            int upstreamPort = upstream.actualPort();

            ResponseStage stage = new ResponseStage(RESERVED_ONLY);
            front = Awaits.connect(vertx.createHttpServer().requestHandler(clientReq -> {
                String path = clientReq.path();
                boolean keep = path.startsWith(KEEP);
                boolean withTrailers = path.endsWith(WITH_TRAILERS);
                client.request(HttpMethod.GET, upstreamPort, LoopbackHost.ADDRESS, "/")
                        .compose(HttpClientRequest::send)
                        .onSuccess(upResp -> (withTrailers
                                ? stage.relayWithTrailers(upResp, clientReq.response(), false, CookiesConfig.NONE, keep,
                                Map.of(), Map.of(), Supplier::get, ResponseStage.GrpcStreamIdleBound.on(vertx))
                                : stage.relay(upResp, clientReq.response(), false, null, CookiesConfig.NONE, keep,
                                Map.of(), Map.of(), Supplier::get))
                                .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                        .onFailure(failure -> clientReq.response().setStatusCode(502).end());
            }).listen(0, LoopbackHost.ADDRESS), "the relaying front server to start listening");
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(front.close(), "the relaying front server to close");
            Awaits.teardown(upstream.close(), "the stub origin to close");
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        private Relayed relayed(String path) throws Exception {
            return Awaits.connect(client
                            .request(HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, path)
                            .compose(HttpClientRequest::send)
                            .compose(resp -> resp.body().map(body -> new Relayed(resp.headers(), resp.trailers()))),
                    "the relayed response to " + path);
        }

        @Test
        @DisplayName("HTTP relay: Server and X-Powered-By are stripped by default while X-Custom crosses")
        void httpRelayStripsByDefault() throws Exception {
            Relayed answer = relayed(STRIP + RELAY);

            assertAll("the default strips the identity headers of the HTTP relay",
                    () -> assertNull(answer.headers().get(SERVER), "Server must not reach the client"),
                    () -> assertNull(answer.headers().get(POWERED_BY), "X-Powered-By must not reach the client"),
                    () -> assertEquals("kept", answer.headers().get(CONTROL), "the control header crosses"));
        }

        @Test
        @DisplayName("gRPC relay: the leading Server and X-Powered-By are stripped by default")
        void grpcLeadingHeadersStripByDefault() throws Exception {
            Relayed answer = relayed(STRIP + WITH_TRAILERS);

            assertAll("the default strips the identity headers of the gRPC leading headers",
                    () -> assertNull(answer.headers().get(SERVER)),
                    () -> assertNull(answer.headers().get(POWERED_BY)),
                    () -> assertEquals("kept", answer.headers().get(CONTROL), "the control header crosses"));
        }

        @Test
        @DisplayName("gRPC relay: a Server and X-Powered-By trailer is stripped by default, grpc-status crosses")
        void grpcTrailersStripByDefault() throws Exception {
            Relayed answer = relayed(STRIP + WITH_TRAILERS);

            assertAll("the default strips the identity trailers",
                    () -> assertNull(answer.trailers().get(SERVER)),
                    () -> assertNull(answer.trailers().get(POWERED_BY)),
                    () -> assertEquals("0", answer.trailers().get("grpc-status"), "the call status still crosses"));
        }

        @Test
        @DisplayName("opt-out: both headers cross on the HTTP relay, the gRPC leading headers and the gRPC trailers")
        void optOutKeepsBothHeadersOnEveryPath() throws Exception {
            Relayed http = relayed(KEEP + RELAY);
            Relayed grpc = relayed(KEEP + WITH_TRAILERS);

            assertAll("keep_identity_headers: true relays the origin's identity headers unchanged",
                    () -> assertEquals(SERVER_VALUE, http.headers().get(SERVER)),
                    () -> assertEquals(POWERED_BY_VALUE, http.headers().get(POWERED_BY)),
                    () -> assertEquals(SERVER_VALUE, grpc.headers().get(SERVER)),
                    () -> assertEquals(POWERED_BY_VALUE, grpc.headers().get(POWERED_BY)),
                    () -> assertEquals(TRAILER_SERVER_VALUE, grpc.trailers().get(SERVER)),
                    () -> assertEquals(TRAILER_POWERED_BY_VALUE, grpc.trailers().get(POWERED_BY)));
        }
    }

    /**
     * Both relays over a live origin that sends four {@code Set-Cookie} leading lines — an application
     * cookie, the configured session cookie, an unlisted tracker and a theme cookie carrying a
     * {@code Domain} — and a trailer section holding three {@code Set-Cookie} trailers beside its
     * {@code grpc-status}. The front server relays that one origin under {@code none}, a list and
     * {@code all}, so every difference between the answers is the policy's doing; a stage whose
     * configuration does not name the session cookie is the control for the gateway-cookie drop.
     */
    @Nested
    @DisplayName("upstream Set-Cookie lines cross by the route's cookie policy on both relays, trailers included")
    class UpstreamSetCookie {

        private static final String SET_COOKIE = "Set-Cookie";
        /** A configured session cookie name, outside the reserved prefix every stage owns. */
        private static final String OWNED_NAME = "__Host-shop-session";
        private static final String APP_LINE = "app=1; Path=/app";
        private static final String OWNED_LINE = OWNED_NAME + "=from-upstream; Path=/";
        private static final String TRACKER_LINE = "tracker=1";
        private static final String THEME_LINE = "theme=dark; Domain=example.com";
        /** {@link #THEME_LINE} as it reaches the client: its {@code Domain} attribute removed. */
        private static final String THEME_RELAYED = "theme=dark";
        private static final String THEME_TRAILER = "theme=trailer; Domain=example.com";
        private static final String TRACKER_TRAILER = "tracker=trailer";
        private static final String OWNED_TRAILER = OWNED_NAME + "=from-trailer";
        private static final String NONE = "/none";
        private static final String LISTED = "/listed";
        private static final String ALL = "/all";
        private static final String OWNING_NOTHING = "/owning-nothing";
        private static final String RELAY = "/relay";
        private static final String WITH_TRAILERS = "/with-trailers";

        private static final CookiesConfig LIST_POLICY = new CookiesConfig(
                new CookieAllow.Named(List.of("app", "theme")), null, null);
        private static final CookiesConfig ALL_POLICY = new CookiesConfig(CookieAllow.ALL, null, null);

        private Vertx vertx;
        private HttpClient client;
        private HttpServer upstream;
        private HttpServer front;

        /** What the client saw of one relayed response: its leading headers and its trailers. */
        private record Relayed(MultiMap headers, MultiMap trailers) {
        }

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(req -> {
                HttpServerResponse response = req.response();
                response.headers().add(SET_COOKIE, APP_LINE).add(SET_COOKIE, OWNED_LINE)
                        .add(SET_COOKIE, TRACKER_LINE).add(SET_COOKIE, THEME_LINE);
                response.setChunked(true);
                response.trailers().add(SET_COOKIE, THEME_TRAILER).add(SET_COOKIE, TRACKER_TRAILER)
                        .add(SET_COOKIE, OWNED_TRAILER).add("grpc-status", "0");
                response.end("origin-body");
            }).listen(0, LoopbackHost.ADDRESS), "the stub origin to start listening");
            int upstreamPort = upstream.actualPort();

            ResponseStage owning = new ResponseStage(GatewayCookies.of(ConfigFixtures.gateway()
                    .oidc(OidcConfig.builder().redirectUri("https://gw.example.com/auth/callback")
                            .session(OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_SERVER)
                                    .cookieName(OWNED_NAME).build())
                            .build())
                    .build()));
            ResponseStage owningNothing = new ResponseStage(RESERVED_ONLY);
            front = Awaits.connect(vertx.createHttpServer().requestHandler(clientReq -> {
                String path = clientReq.path();
                ResponseStage stage = path.startsWith(OWNING_NOTHING) ? owningNothing : owning;
                CookiesConfig policy = policyFor(path);
                boolean withTrailers = path.endsWith(WITH_TRAILERS);
                client.request(HttpMethod.GET, upstreamPort, LoopbackHost.ADDRESS, "/")
                        .compose(HttpClientRequest::send)
                        .onSuccess(upResp -> (withTrailers
                                ? stage.relayWithTrailers(upResp, clientReq.response(), false, policy, false,
                                Map.of(), Map.of(), Supplier::get, ResponseStage.GrpcStreamIdleBound.on(vertx))
                                : stage.relay(upResp, clientReq.response(), false, null, policy, false, Map.of(),
                                Map.of(), Supplier::get))
                                .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                        .onFailure(failure -> clientReq.response().setStatusCode(502).end());
            }).listen(0, LoopbackHost.ADDRESS), "the relaying front server to start listening");
        }

        /** The policy a front-server path relays under; the owning-nothing control relays under {@code all}. */
        private static CookiesConfig policyFor(String path) {
            if (path.startsWith(NONE)) {
                return CookiesConfig.NONE;
            }
            if (path.startsWith(LISTED)) {
                return LIST_POLICY;
            }
            return ALL_POLICY;
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(front.close(), "the relaying front server to close");
            Awaits.teardown(upstream.close(), "the stub origin to close");
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        private Relayed relayed(String path) throws Exception {
            return Awaits.connect(client
                            .request(HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, path)
                            .compose(HttpClientRequest::send)
                            .compose(resp -> resp.body().map(body -> new Relayed(resp.headers(), resp.trailers()))),
                    "the relayed response to " + path);
        }

        @ParameterizedTest
        @ValueSource(strings = {RELAY, WITH_TRAILERS})
        @DisplayName("default none: no upstream Set-Cookie line reaches the client, not even an application cookie")
        void noneRelaysNoLine(String relay) throws Exception {
            Relayed answer = relayed(NONE + relay);

            assertEquals(List.of(), answer.headers().getAll(SET_COOKIE),
                    "under the default none an upstream cookie is not relayed — the stated breaking change");
        }

        @ParameterizedTest
        @ValueSource(strings = {RELAY, WITH_TRAILERS})
        @DisplayName("a list: only the listed lines arrive, in order, with Domain removed")
        void listRelaysListedLines(String relay) throws Exception {
            Relayed answer = relayed(LISTED + relay);

            assertEquals(List.of(APP_LINE, THEME_RELAYED), answer.headers().getAll(SET_COOKIE));
        }

        @ParameterizedTest
        @ValueSource(strings = {RELAY, WITH_TRAILERS})
        @DisplayName("all: every line but the gateway cookie arrives, in order, with Domain removed")
        void allRelaysEveryOtherLine(String relay) throws Exception {
            Relayed answer = relayed(ALL + relay);

            assertEquals(List.of(APP_LINE, TRACKER_LINE, THEME_RELAYED), answer.headers().getAll(SET_COOKIE));
        }

        @Test
        @DisplayName("relayWithTrailers: a Set-Cookie trailer outside the policy is not relayed, grpc-status is")
        void trailersPassTheSharedFilter() throws Exception {
            Relayed listed = relayed(LISTED + WITH_TRAILERS);
            Relayed none = relayed(NONE + WITH_TRAILERS);

            assertAll("the trailers pass the same judgement as the leading headers",
                    () -> assertEquals(List.of("0"), listed.trailers().getAll("grpc-status")),
                    () -> assertEquals(List.of("theme=trailer"), listed.trailers().getAll(SET_COOKIE),
                            "only the listed trailer crosses, with Domain removed; the unlisted and the gateway"
                                    + " trailer are dropped"),
                    () -> assertEquals(List.of("0"), none.trailers().getAll("grpc-status")),
                    () -> assertEquals(List.of(), none.trailers().getAll(SET_COOKIE),
                            "under none no Set-Cookie trailer crosses"));
        }

        @Test
        @DisplayName("relayWithTrailers under all: every Set-Cookie trailer but the gateway cookie is relayed")
        void allRelaysEveryOtherTrailer() throws Exception {
            Relayed answer = relayed(ALL + WITH_TRAILERS);

            assertEquals(List.of("theme=trailer", TRACKER_TRAILER), answer.trailers().getAll(SET_COOKIE));
        }

        @ParameterizedTest
        @ValueSource(strings = {RELAY, WITH_TRAILERS})
        @DisplayName("control: under all, a stage whose configuration does not name the session cookie relays it too")
        void stageOwningNothingRelaysTheSessionLine(String relay) throws Exception {
            Relayed answer = relayed(OWNING_NOTHING + relay);

            assertEquals(List.of(APP_LINE, OWNED_LINE, TRACKER_LINE, THEME_RELAYED),
                    answer.headers().getAll(SET_COOKIE));
        }
    }

    /**
     * Both relays over a live origin whose body fails mid-stream: it writes one chunk and then drops
     * the connection. A Vert.x pipe ends its destination when the source fails unless told not to, so
     * a relay that left that default in place would end the client response itself — past the end
     * rule, and before the caller's failure path could decide how the response ends. Neither relay may
     * do that, and neither may ask the end rule to end a body that never completed.
     */
    @Nested
    @DisplayName("an upstream body failing mid-stream leaves the client response to the caller on both relays")
    class MidStreamFailure {

        private static final String RELAY = "/relay";
        private static final String WITH_TRAILERS = "/with-trailers";

        private Vertx vertx;
        private HttpClient client;
        private HttpServer upstream;
        private HttpServer front;
        private Promise<Observation> observed;

        /**
         * What the front server saw once the relay future settled.
         *
         * @param relayFailed   whether the relay future failed
         * @param responseEnded whether the client response had been ended by then
         * @param endsRequested how often the relay handed an end to the end rule
         */
        private record Observation(boolean relayFailed, boolean responseEnded, int endsRequested) {
        }

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();
            observed = Promise.promise();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(req -> req.response()
                    .setChunked(true)
                    .write("partial-")
                    .onComplete(w -> req.connection().close()))
                    .listen(0, LoopbackHost.ADDRESS), "the stub origin to start listening");
            int upstreamPort = upstream.actualPort();

            ResponseStage stage = new ResponseStage(RESERVED_ONLY);
            front = Awaits.connect(vertx.createHttpServer().requestHandler(clientReq -> {
                HttpServerResponse response = clientReq.response();
                AtomicInteger endsRequested = new AtomicInteger();
                ResponseEnd end = ending -> {
                    endsRequested.incrementAndGet();
                    return ending.get();
                };
                client.request(HttpMethod.GET, upstreamPort, LoopbackHost.ADDRESS, "/")
                        .compose(HttpClientRequest::send)
                        .onSuccess(upResp -> relay(stage, clientReq.path(), upResp, response, end)
                                .onComplete(relayed -> {
                                    observed.tryComplete(new Observation(relayed.failed(), response.ended(),
                                            endsRequested.get()));
                                    if (!response.ended()) {
                                        // Release the requesting client rather than leave it waiting.
                                        response.reset();
                                    }
                                }))
                        .onFailure(observed::tryFail);
            }).listen(0, LoopbackHost.ADDRESS), "the relaying front server to start listening");
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(front.close(), "the relaying front server to close");
            Awaits.teardown(upstream.close(), "the stub origin to close");
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        private Future<Void> relay(ResponseStage stage, String path, HttpClientResponse upResp,
                HttpServerResponse response, ResponseEnd end) {
            return WITH_TRAILERS.equals(path)
                    ? stage.relayWithTrailers(upResp, response, false, CookiesConfig.NONE, false, Map.of(), Map.of(),
                    end, ResponseStage.GrpcStreamIdleBound.on(vertx))
                    : stage.relay(upResp, response, false, null, CookiesConfig.NONE, false, Map.of(), Map.of(), end);
        }

        @ParameterizedTest
        @ValueSource(strings = {RELAY, WITH_TRAILERS})
        @DisplayName("the relay fails, the client response stays open and no end is requested")
        void failedBodyLeavesTheResponseToTheCaller(String path) throws Exception {
            // The requesting client's own outcome is the caller's to decide and is not asserted here.
            client.request(HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, path)
                    .compose(HttpClientRequest::send);

            Observation seen = Awaits.connect(observed.future(), "the relay of " + path + " to settle");

            assertAll("a body that failed mid-stream is not ended by the relay",
                    () -> assertTrue(seen.relayFailed(), "the relay future reports the failed upstream body"),
                    () -> assertFalse(seen.responseEnded(),
                            "the client response is left open for the caller's failure path to end"),
                    () -> assertEquals(0, seen.endsRequested(),
                            "a truncated body is never handed to the end rule as a completed response"));
        }
    }

    @Nested
    @DisplayName("body-framing eligibility (Content-Length preservation vs chunked streaming)")
    class BodyFraming {

        @ParameterizedTest
        @ValueSource(ints = {200, 201, 301, 400, 404, 500, 502})
        @DisplayName("a body-bearing status streams a relayed body (chunked when length is unknown)")
        void statusMayCarryBody(int status) {
            assertTrue(ResponseStage.mayCarryBody(status),
                    status + " permits a message body and must frame the streamed relay");
        }

        @ParameterizedTest
        @ValueSource(ints = {100, 101, 199, 204, 304})
        @DisplayName("a bodyless status never frames a streamed body")
        void statusForbidsBody(int status) {
            assertFalse(ResponseStage.mayCarryBody(status),
                    status + " forbids a message body (1xx / 204 / 304) and must not be chunked");
        }
    }
}
