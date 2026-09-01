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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end serving contract of the public data-plane edge, driven over a live Vert.x HTTP server
 * against a local stub upstream — no Docker, no Quarkus. A real request crosses the whole fixed
 * pipeline (stages 0-7): response-header prep + CORS preflight, the baseline security filter, route
 * selection and the verb gate, the per-route thorough checks, bearer authentication, streamed
 * upstream dispatch, and the streamed response relay. These complement the boot-time and lifecycle
 * assertions in {@link GatewayEdgeRouteTest}.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — end-to-end request pipeline over a live Vert.x server")
class GatewayEdgePipelineTest {

    private static final String ORIGIN = "https://app.example";
    /** The {@code max_body_bytes} the {@code profile: minimal} route declares, retained under that mode. */
    private static final int MINIMAL_ROUTE_BODY_CAP = 1024;

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private HttpClient client;
    private int frontPort;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Stub upstream: echoes the received method, URI, and body so the forward path is observable.
        upstreamServer = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                request.body().onComplete(body -> {
                    String payload = body.succeeded() && body.result() != null ? body.result().toString() : "";
                    request.response()
                            .putHeader("X-Upstream-Echo", "hit")
                            .end(request.method().name() + " " + request.uri() + " body=" + payload);
                }))
                .listen(0), "the stub upstream server to start listening");
        int upstreamPort = upstreamServer.actualPort();

        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

        RouteTable routeTable = new RouteTable(List.of(
                route("secure", "/secure", Require.BEARER, upstreamPort, HttpMethod.GET),
                route("echo", "/echo", Require.NONE, upstreamPort, HttpMethod.GET, HttpMethod.POST),
                minimalModeRoute(upstreamPort)));

        GatewayConfig gatewayConfig = GatewayConfig.builder()
                .version(1)
                .securityHeaders(corsHeaders())
                .build();

        GatewayEdgeRoute edge = new GatewayEdgeRoute(routeTable, gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert());

        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        frontServer = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0),
                "the edge front server to start listening");
        frontPort = frontServer.actualPort();

        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(frontServer.close(), "the edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("forwards a matched GET to the upstream and relays the streamed response body")
    void forwardsHappyPathGet() throws Exception {
        // Act
        Response response = send(io.vertx.core.http.HttpMethod.GET, "/echo/orders", Map.of(), null);

        // Assert — the prefix is stripped and the remainder is forwarded; the relay preserves the body
        assertEquals(200, response.status());
        assertEquals("hit", response.headers().get("X-Upstream-Echo"),
                "the upstream response headers relay back to the client");
        assertTrue(response.body().contains("GET /orders"),
                "the forwarded request reaches the upstream with the prefix-stripped path: " + response.body());
    }

    @Test
    @DisplayName("streams a POST request body through to the upstream")
    void forwardsPostBody() throws Exception {
        // Act
        Response response = send(io.vertx.core.http.HttpMethod.POST, "/echo/submit", Map.of(), "hello-upstream");

        // Assert
        assertEquals(200, response.status());
        assertTrue(response.body().contains("POST /submit"), response.body());
        assertTrue(response.body().contains("body=hello-upstream"),
                "the streamed request body reaches the upstream: " + response.body());
    }

    @Test
    @DisplayName("rejects an unmatched path 404 (deny by default)")
    void rejectsUnmatchedPath() throws Exception {
        // Act
        Response response = send(io.vertx.core.http.HttpMethod.GET, "/nowhere", Map.of(), null);

        // Assert
        assertEquals(404, response.status());
    }

    @Test
    @DisplayName("rejects a verb outside the route's allowed_methods 405 with an Allow header")
    void rejectsDisallowedVerb() throws Exception {
        // Act — the echo route allows GET and POST, never DELETE
        Response response = send(io.vertx.core.http.HttpMethod.DELETE, "/echo/orders", Map.of(), null);

        // Assert
        assertEquals(405, response.status());
        assertNotNull(response.headers().get("Allow"), "a 405 names the permitted verbs in the Allow header");
    }

    @Test
    @DisplayName("rejects a verb outside the gateway's method model 405 before any stage runs")
    void rejectsUnmodelledVerb() throws Exception {
        // Act — TRACE is a valid HTTP verb the gateway deliberately does not model, so it is refused at
        // the pipeline entry, before route selection ever runs
        Response response = send(io.vertx.core.http.HttpMethod.TRACE, "/echo/orders", Map.of(), null);

        // Assert
        assertEquals(405, response.status());
        assertNull(response.headers().get("X-Upstream-Echo"),
                "an unmodelled verb must never reach the upstream");
    }

    @Test
    @DisplayName("enforces the resolved security_defaults baseline cap exactly, not the bare cui-http default")
    void rejectsExcessiveParameters() throws Exception {
        // Arrange — this fixture's GatewayConfig declares no security_defaults block, so the
        // gateway-wide baseline resolves to SecurityProfile.DEFAULT_PROFILE's preset. Bracketing the
        // cap (at the limit passes, one over fails) pins the baseline actually in force, so a
        // regression back to the looser SecurityConfiguration.defaults() would fail this test rather
        // than pass it silently.
        int cap = SecurityProfile.DEFAULT_PROFILE.preset().maxParameterCount();

        // Act
        Response atCap = send(io.vertx.core.http.HttpMethod.GET, queryWithParameters(cap), Map.of(), null);
        Response overCap = send(io.vertx.core.http.HttpMethod.GET, queryWithParameters(cap + 1), Map.of(), null);

        // Assert
        assertEquals(200, atCap.status(), "a request exactly at the resolved cap is served");
        assertEquals(400, overCap.status(), "one parameter beyond the resolved cap trips the pre-route floor");
    }

    private static String queryWithParameters(int count) {
        StringJoiner query = new StringJoiner("&", "/echo/orders?", "");
        for (int i = 0; i < count; i++) {
            query.add("p" + i + "=1");
        }
        return query.toString();
    }

    @Test
    @DisplayName("rejects a bearer route without a token 401 carrying WWW-Authenticate")
    void rejectsMissingBearerToken() throws Exception {
        // Act
        Response response = send(io.vertx.core.http.HttpMethod.GET, "/secure/data", Map.of(), null);

        // Assert
        assertEquals(401, response.status());
        assertEquals("Bearer", response.headers().get("WWW-Authenticate"),
                "a missing bearer token challenges with WWW-Authenticate: Bearer");
    }

    @Test
    @DisplayName("rejects a bearer route with an invalid token 401")
    void rejectsInvalidBearerToken() throws Exception {
        // Act — a syntactically-bogus token is rejected offline by the validator
        Response response = send(io.vertx.core.http.HttpMethod.GET, "/secure/data",
                Map.of("Authorization", "Bearer not-a-real-token"), null);

        // Assert
        assertEquals(401, response.status());
    }

    @Test
    @DisplayName("answers an allow-listed CORS preflight 204 at stage 0 without reaching the upstream")
    void answersCorsPreflight() throws Exception {
        // Act — an OPTIONS preflight from an allow-listed origin short-circuits before route selection
        Response response = send(io.vertx.core.http.HttpMethod.OPTIONS, "/echo/orders",
                Map.of("Origin", ORIGIN, "Access-Control-Request-Method", "GET"), null);

        // Assert
        assertEquals(204, response.status());
        assertEquals(ORIGIN, response.headers().get("Access-Control-Allow-Origin"),
                "the preflight reflects the allow-listed origin");
        assertNotNull(response.headers().get("Access-Control-Allow-Methods"),
                "the preflight advertises the allowed methods");
    }

    @Test
    @DisplayName("profile: minimal disables only the url-parameter validation — the strict route still rejects it")
    void minimalRouteAcceptsParameterValueTheStrictRouteRejects() throws Exception {
        // Arrange — a '/' inside a parameter value is refused by the url-parameter pipeline

        // Act
        Response onStrictRoute = send(io.vertx.core.http.HttpMethod.GET, "/echo/orders?return_to=%2Fhome",
                Map.of(), null);
        Response onMinimalRoute = send(io.vertx.core.http.HttpMethod.GET, "/open/allowed?return_to=%2Fhome",
                Map.of(), null);

        // Assert
        assertEquals(400, onStrictRoute.status(), "the strict-baseline route rejects the parameter value");
        assertEquals(200, onMinimalRoute.status(),
                "the minimal-mode route accepts it — that is what 'minimal' turns off");
    }

    @Test
    @DisplayName("profile: minimal is still governed by the whole pre-route floor")
    void minimalRouteIsStillGovernedByThePreRouteFloor() throws Exception {
        // Arrange — the floor runs before route selection and no profile can switch it off
        int paramCap = SecurityProfile.DEFAULT_PROFILE.preset().maxParameterCount();
        int headerCap = SecurityProfile.DEFAULT_PROFILE.preset().maxHeaderValueLength();
        StringJoiner overCapQuery = new StringJoiner("&", "/open/allowed?", "");
        for (int i = 0; i <= paramCap; i++) {
            overCapQuery.add("p" + i + "=1");
        }

        // Act
        Response overCapParameters = send(io.vertx.core.http.HttpMethod.GET, overCapQuery.toString(), Map.of(), null);
        Response encodedSeparator = send(io.vertx.core.http.HttpMethod.GET, "/open/allowed%2Fx", Map.of(), null);
        Response oversizedHeader = send(io.vertx.core.http.HttpMethod.GET, "/open/allowed",
                Map.of("X-Custom", "a".repeat(headerCap + 1)), null);

        // Assert
        assertEquals(400, overCapParameters.status(), "the parameter-count cap still applies under 'minimal'");
        assertEquals(400, encodedSeparator.status(), "the canonical-path guard still applies under 'minimal'");
        assertEquals(400, oversizedHeader.status(), "header-value validation still applies under 'minimal'");
    }

    @Test
    @DisplayName("profile: minimal still enforces max_body_bytes and allowed_paths")
    void minimalRouteStillEnforcesBodyCapAndAllowedPaths() throws Exception {
        // Arrange — the two per-route enforcements the operator deliberately retained under 'minimal'

        // Act
        Response overCapBody = send(io.vertx.core.http.HttpMethod.POST, "/open/allowed", Map.of(),
                "a".repeat(MINIMAL_ROUTE_BODY_CAP + 1));
        Response outsideAllowlist = send(io.vertx.core.http.HttpMethod.GET, "/open/elsewhere", Map.of(), null);
        Response insideAllowlist = send(io.vertx.core.http.HttpMethod.GET, "/open/allowed", Map.of(), null);

        // Assert — the body cap is the gateway's own 413, the allowlist miss a 400; 'minimal' changes neither
        assertEquals(413, overCapBody.status(), "'minimal' is a validation statement, never a limits statement");
        assertEquals(400, outsideAllowlist.status(), "the path allowlist survives 'minimal'");
        assertEquals(200, insideAllowlist.status(),
                "a benign in-allowlist request on the minimal route is served");
    }

    private Response send(io.vertx.core.http.HttpMethod method, String uri, Map<String, String> requestHeaders,
            String body) throws Exception {
        RequestOptions options = new RequestOptions()
                .setHost("127.0.0.1").setPort(frontPort).setMethod(method).setURI(uri);
        CompletableFuture<Response> future = client.request(options)
                .compose(request -> {
                    requestHeaders.forEach(request::putHeader);
                    return body == null ? request.send() : request.send(Buffer.buffer(body));
                })
                .compose(response -> {
                    int status = response.statusCode();
                    Map<String, String> headers = new HashMap<>();
                    response.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                    return response.body().map(buffer -> new Response(status, headers,
                            buffer == null ? "" : buffer.toString()));
                })
                .toCompletionStage().toCompletableFuture();
        return Awaits.connect(future, "the edge response to " + method + " " + uri);
    }

    private static SecurityHeadersConfig corsHeaders() {
        SecurityHeadersConfig.Cors cors = SecurityHeadersConfig.Cors.builder()
                .enabled(Boolean.TRUE)
                .allowedOrigins(List.of(ORIGIN))
                .allowedMethods(List.of("GET", "POST"))
                .allowedHeaders(List.of("authorization"))
                .build();
        return SecurityHeadersConfig.builder().cors(cors).build();
    }

    /**
     * A {@code profile: minimal} route carrying both retained per-route enforcements — a
     * {@code max_body_bytes} cap and an {@code allowed_paths} allowlist — so the partial-disable
     * semantics are executable rather than merely documented.
     */
    private static ResolvedRoute minimalModeRoute(int upstreamPort) {
        SecurityFilterConfig filter = SecurityFilterConfig.builder()
                .profile("minimal")
                .allowedPaths(List.of("/open/allowed"))
                .maxBodyBytes(MINIMAL_ROUTE_BODY_CAP)
                .build();
        return ResolvedRoute.builder()
                .id("open")
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/open").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .effectiveSecurityFilter(filter)
                .upstream(new ResolvedUpstream("http", "127.0.0.1", upstreamPort, ""))
                .build();
    }

    private static ResolvedRoute route(String id, String pathPrefix, Require require, int upstreamPort,
            HttpMethod... methods) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(methods))
                .upstream(new ResolvedUpstream("http", "127.0.0.1", upstreamPort, ""))
                .build();
    }

    /** A record capturing the terminal response for assertions after the streamed body completes. */
    private record Response(int status, Map<String, String> headers, String body) {
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied validator; only
     * {@link #get()} and {@link #iterator()} are exercised by the pipeline, the remaining CDI
     * accessors are unused and throw.
     */
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
