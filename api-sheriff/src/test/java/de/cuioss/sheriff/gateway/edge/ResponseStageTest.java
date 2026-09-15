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

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.routing.LocationRewriter;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ResponseStage — stage 7 streamed response header policy")
class ResponseStageTest {

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
        @DisplayName("relays validators untouched when the route enables not_modified")
        void relaysValidatorsWhenEnabled(String header) {
            assertTrue(ResponseStage.isForwardableResponseHeader(header, true),
                    header + " must relay on a not_modified-enabled route (304 pass-through)");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ETag", "Last-Modified"})
        @DisplayName("strips validators when the route disables not_modified")
        void stripsValidatorsWhenDisabled(String header) {
            assertFalse(ResponseStage.isForwardableResponseHeader(header, false),
                    header + " must be stripped on a not_modified-disabled route");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ETag", "Last-Modified"})
        @DisplayName("the validator flips with the toggle — this half is one direction of a two-directional contract")
        void validatorFlipsWithTheToggle(String header) {
            // The response half of the not_modified contract. Its counterpart is the request half in
            // forward.ForwardPolicyStage, whose conditional tier (the five RFC 9110 §13 validators) is
            // gated by the SAME flag; ForwardPolicyStageTest asserts the two together, since only that
            // package can see both. The control kept here is the one that survives locally: this header
            // must genuinely FLIP with the toggle rather than being unconditionally relayed or
            // unconditionally stripped. Asserting both states of one header in one test is what makes a
            // change that pins it in either direction fail here rather than silently orphaning the pair.
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
                new LocationRewriter(new ResolvedUpstream("https", "backend", 8443, "/svc/v1"), "/api");

        @ParameterizedTest
        @ValueSource(strings = {"Location", "location", "LOCATION"})
        @DisplayName("maps an upstream Location through the route rewriter when the route opts in")
        void rewritesLocationWhenEnabled(String header) {
            assertEquals("/api/items?id=7",
                    ResponseStage.relayedHeaderValue(header, "https://backend:8443/svc/v1/items?id=7", rewriter),
                    header + " must be mapped onto the route's match key on an opted-in route");
        }

        @Test
        @DisplayName("relays an upstream Location unchanged when the route does not opt in")
        void relaysLocationUnchangedWhenDisabled() {
            String location = "https://backend:8443/svc/v1/items?id=7";

            assertEquals(location, ResponseStage.relayedHeaderValue("Location", location, null),
                    "a route without rewrite_location carries no rewriter and relays Location verbatim");
        }

        @Test
        @DisplayName("the rewrite flips with the toggle for the same header value")
        void rewriteFlipsWithTheToggle() {
            String location = "/svc/v1/login";

            assertEquals("/api/login", ResponseStage.relayedHeaderValue("Location", location, rewriter));
            assertEquals(location, ResponseStage.relayedHeaderValue("Location", location, null));
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
            ResponseStage responseStage = new ResponseStage();
            front = Awaits.connect(vertx.createHttpServer().requestHandler(clientReq -> client
                    .request(io.vertx.core.http.HttpMethod.GET, upstreamPort, LoopbackHost.ADDRESS, clientReq.path())
                    .compose(HttpClientRequest::send)
                    .onSuccess(upResp -> responseStage
                            .relay(upResp, clientReq.response(), false, null, Map.of(FRAME_OPTIONS, "DENY"),
                                    Map.of(CSP, GATEWAY_POLICY))
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
                    .request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, path)
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
