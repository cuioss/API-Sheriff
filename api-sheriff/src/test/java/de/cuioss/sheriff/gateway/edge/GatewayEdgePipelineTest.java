/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
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
        upstreamServer = vertx.createHttpServer().requestHandler(request ->
                request.body().onComplete(body -> {
                    String payload = body.succeeded() && body.result() != null ? body.result().toString() : "";
                    request.response()
                            .putHeader("X-Upstream-Echo", "hit")
                            .end(request.method().name() + " " + request.uri() + " body=" + payload);
                }))
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        int upstreamPort = upstreamServer.actualPort();

        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

        RouteTable routeTable = new RouteTable(List.of(
                route("secure", "/secure", "bearer", upstreamPort, HttpMethod.GET),
                route("echo", "/echo", "none", upstreamPort, HttpMethod.GET, HttpMethod.POST)));

        GatewayConfig gatewayConfig = GatewayConfig.builder()
                .version(1)
                .securityHeaders(Optional.of(corsHeaders()))
                .build();

        GatewayEdgeRoute edge = new GatewayEdgeRoute(routeTable, gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()));

        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        frontServer = vertx.createHttpServer().requestHandler(router)
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        frontPort = frontServer.actualPort();

        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        frontServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        upstreamServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        virtualThreadExecutor.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
    @DisplayName("rejects a request breaching the baseline parameter-count cap 400")
    void rejectsExcessiveParameters() throws Exception {
        // Arrange — one parameter beyond the default cap trips the stage-1 baseline filter
        int cap = SecurityConfiguration.defaults().maxParameterCount();
        StringJoiner query = new StringJoiner("&", "/echo/orders?", "");
        for (int i = 0; i <= cap; i++) {
            query.add("p" + i + "=1");
        }

        // Act
        Response response = send(io.vertx.core.http.HttpMethod.GET, query.toString(), Map.of(), null);

        // Assert
        assertEquals(400, response.status());
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

    private Response send(io.vertx.core.http.HttpMethod method, String uri, Map<String, String> requestHeaders,
            String body) throws Exception {
        RequestOptions options = new RequestOptions()
                .setHost("localhost").setPort(frontPort).setMethod(method).setURI(uri);
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
        return future.get(15, TimeUnit.SECONDS);
    }

    private static SecurityHeadersConfig corsHeaders() {
        SecurityHeadersConfig.Cors cors = SecurityHeadersConfig.Cors.builder()
                .enabled(Optional.of(Boolean.TRUE))
                .allowedOrigins(List.of(ORIGIN))
                .allowedMethods(List.of("GET", "POST"))
                .allowedHeaders(List.of("authorization"))
                .build();
        return SecurityHeadersConfig.builder().cors(Optional.of(cors)).build();
    }

    private static ResolvedRoute route(String id, String pathPrefix, String require, int upstreamPort,
            HttpMethod... methods) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(methods))
                .upstream(Optional.of(new ResolvedUpstream("http", "localhost", upstreamPort, "")))
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
