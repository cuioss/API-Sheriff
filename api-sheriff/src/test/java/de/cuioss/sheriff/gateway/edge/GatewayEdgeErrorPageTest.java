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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.pipeline.SecurityHeadersStage;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier;
import de.cuioss.sheriff.gateway.portal.PortalCatalog;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.portal.PortalRenderer;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.testsupport.UnreachablePort;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Negotiated HTML error pages at the edge, driven over a live Vert.x server against a local stub
 * upstream. Every {@code HTML_ELIGIBLE} gateway-originated error class is sent three times — with an
 * explicit {@code Accept: text/html}, with {@code Accept: application/json} and with {@code Accept: *}{@code /*}
 * — and once more to an identical edge whose {@code portal.error_pages} is off: only the first answers
 * the portal's HTML error page, and all four answer the <em>same</em> status. A response relayed from
 * the origin, a keep-shape rejection and a gRPC trailers-only rejection are never negotiated.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — negotiated HTML error pages for gateway-originated errors")
class GatewayEdgeErrorPageTest {

    private static final String OIDC_HOST = "gw.example.com";
    private static final String CALLBACK_PATH = "/auth/callback";
    /** A policy distinct from the portal's, so the served CSP names which writer answered. */
    private static final String GLOBAL_POLICY = "default-src 'none'";
    private static final String CSP = "Content-Security-Policy";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String TEXT_HTML = "text/html";
    private static final String NEVER_GRANTED_SCOPE = "sheriff-error-page-never-granted";
    private static final String CAPPED_BODY = "x".repeat(64);
    /** The breaker's request-volume threshold (see {@code GatewayEdgeRoute.guardFor}). */
    private static final int BREAKER_VOLUME = 20;

    @TempDir
    Path assetDirectory;

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer enabledFront;
    private HttpServer disabledFront;
    private HttpClient client;
    private String validBearerToken;

    /** How an error class answers when it is not negotiated to HTML. */
    private enum Shape {
        /** The RFC 9457 {@code application/problem+json} body. */
        PROBLEM,
        /** The exit's own bare shape (a failed callback, a directory-asset miss). */
        BARE
    }

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // The stub origin answers /status/<code> with that status and a fixed plain-text body, so a
        // relayed origin error is observable byte-for-byte.
        upstreamServer = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                request.body().onComplete(ignored -> {
                    String path = request.path();
                    int status = path.startsWith("/status/") ? Integer.parseInt(path.substring(8)) : 200;
                    request.response().setStatusCode(status).putHeader(CONTENT_TYPE, "text/plain")
                            .end("origin-" + status);
                })).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        TestTokenHolder tokenHolder = TestTokenGenerators.accessTokens().next();
        validBearerToken = tokenHolder.getRawToken();
        TokenValidator tokenValidator = TokenValidator.builder().issuerConfig(tokenHolder.getIssuerConfig()).build();
        // Below the ephemeral range: a released ephemeral probe port could be handed to an edge itself,
        // which then proxies /dead into itself and relays its own 400 instead of answering 502.
        int[] unreachable = UnreachablePort.pick(3);
        int deadPort = unreachable[0];
        // Each edge gets its own breaker target: the breaker name derives from the target, and a second
        // edge in the same JVM whose breaker carries an already-used name was observed never to open.
        enabledFront = startFront(routes(deadPort, unreachable[1]), tokenValidator, true);
        disabledFront = startFront(routes(deadPort, unreachable[2]), tokenValidator, false);
        client = vertx.createHttpClient();
    }

    private RouteTable routes(int deadPort, int breakerPort) {
        return new RouteTable(List.of(
                proxyRoute("echo", "/echo", upstreamServer.actualPort()).build(),
                proxyRoute("capped", "/capped", upstreamServer.actualPort())
                        .effectiveAllowedMethods(List.of(HttpMethod.POST))
                        .effectiveSecurityFilter(SecurityFilterConfig.builder().maxBodyBytes(16).build()).build(),
                proxyRoute("scoped", "/scoped", upstreamServer.actualPort())
                        .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                        .neededScopes(Set.of(NEVER_GRANTED_SCOPE)).build(),
                proxyRoute("dead", "/dead", deadPort).build(),
                proxyRoute("breaker", "/breaker", breakerPort).build(),
                proxyRoute("session", "/s", upstreamServer.actualPort())
                        .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                        .effectiveAllowedMethods(List.of(HttpMethod.POST)).build(),
                proxyRoute("grpc", "/grpc", upstreamServer.actualPort()).protocol(Protocol.GRPC)
                        .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                        .effectiveAllowedMethods(List.of(HttpMethod.POST)).build(),
                ResolvedRoute.builder().id("assets").protocol(Protocol.HTTP)
                        .match(MatchConfig.builder().pathPrefix("/assets").build())
                        .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                        .effectiveAllowedMethods(List.of(HttpMethod.GET))
                        .effectiveSecurityHeaders(globalHeaders())
                        .asset(ResolvedAsset.directory(assetDirectory.toString(), AccessLevel.PUBLIC, null, null))
                        .build()));
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(enabledFront.close(), "the enabled edge front server to close");
        Awaits.teardown(disabledFront.close(), "the disabled edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("an unrouted address negotiates 404")
    void unroutedAddress() throws Exception {
        assertNegotiates(404, Shape.PROBLEM, io.vertx.core.http.HttpMethod.GET, "/nowhere", Map.of(), null, null);
    }

    @Test
    @DisplayName("a bearer token lacking a needed scope negotiates 403")
    void missingScope() throws Exception {
        assertNegotiates(403, Shape.PROBLEM, io.vertx.core.http.HttpMethod.GET, "/scoped/data",
                Map.of("Authorization", "Bearer " + validBearerToken), null, null);
    }

    @Test
    @DisplayName("a cross-origin unsafe request on a session route negotiates the CSRF 403")
    void csrfRejection() throws Exception {
        assertNegotiates(403, Shape.PROBLEM, io.vertx.core.http.HttpMethod.POST, "/s/submit",
                Map.of("Origin", "https://evil.example"), "payload", null);
    }

    @Test
    @DisplayName("a body over the route cap negotiates 413")
    void bodyOverCap() throws Exception {
        assertNegotiates(413, Shape.PROBLEM, io.vertx.core.http.HttpMethod.POST, "/capped/upload", Map.of(),
                CAPPED_BODY, null);
    }

    @Test
    @DisplayName("an unreachable upstream negotiates 502")
    void unreachableUpstream() throws Exception {
        assertNegotiates(502, Shape.PROBLEM, io.vertx.core.http.HttpMethod.GET, "/dead/x", Map.of(), null, null);
    }

    @Test
    @DisplayName("an open circuit breaker negotiates 503")
    void openCircuit() throws Exception {
        tripBreaker(enabledFront);
        tripBreaker(disabledFront);

        assertNegotiates(503, Shape.PROBLEM, io.vertx.core.http.HttpMethod.GET, "/breaker/x", Map.of(), null, null);
    }

    /**
     * Drives failing dispatches through {@code front}'s breaker route until it answers {@code 503},
     * bounded so a breaker that never opens fails the test instead of spinning.
     */
    private void tripBreaker(HttpServer front) throws Exception {
        int status = 0;
        for (int attempt = 0; attempt < 3 * BREAKER_VOLUME && status != 503; attempt++) {
            status = send(front, io.vertx.core.http.HttpMethod.GET, "/breaker/x", Map.of(), null, null).status();
        }
        assertEquals(503, status, "the breaker opens after its request-volume threshold of failures");
    }

    @Test
    @DisplayName("a failed OIDC callback negotiates its error status, keeping its bare shape otherwise")
    void failedCallback() throws Exception {
        assertNegotiates(400, Shape.BARE, io.vertx.core.http.HttpMethod.GET, CALLBACK_PATH, Map.of(), null, OIDC_HOST);
    }

    @Test
    @DisplayName("a directory-asset miss negotiates 404, keeping its governed shape otherwise")
    void directoryAssetMiss() throws Exception {
        assertNegotiates(404, Shape.BARE, io.vertx.core.http.HttpMethod.GET, "/assets/missing.html", Map.of(), null,
                null);
    }

    @Test
    @DisplayName("a keep-shape rejection (the bearer 401) is never negotiated")
    void keepShapeRejectionStaysProblem() throws Exception {
        Response response = send(enabledFront, io.vertx.core.http.HttpMethod.GET, "/scoped/data",
                Map.of("Accept", TEXT_HTML), null, null);

        assertProblem(response, 401);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("an error relayed from the origin passes through byte-for-byte, whatever the Accept header")
    void relayedOriginErrorPassesThrough(int status) throws Exception {
        Response response = send(enabledFront, io.vertx.core.http.HttpMethod.GET, "/echo/status/" + status,
                Map.of("Accept", TEXT_HTML), null, null);

        assertAll("relayed origin " + status,
                () -> assertEquals(status, response.status()),
                () -> assertEquals("origin-" + status, response.body(), "the origin body is never replaced"),
                () -> assertEquals("text/plain", response.headers().get(CONTENT_TYPE)),
                () -> assertEquals(GLOBAL_POLICY, response.headers().get(CSP), "the portal CSP is never applied"));
    }

    @Test
    @DisplayName("a gRPC route rejection stays trailers-only even for an explicit text/html")
    void grpcRejectionStaysTrailersOnly() throws Exception {
        Response response = send(enabledFront, io.vertx.core.http.HttpMethod.POST, "/grpc/Svc/Call",
                Map.of("Accept", TEXT_HTML, CONTENT_TYPE, "application/grpc"), null, null);

        assertAll(
                () -> assertEquals(200, response.status(), "a gRPC rejection is an HTTP 200 trailers-only response"),
                () -> assertEquals("16", response.headers().get("grpc-status")),
                () -> assertFalse(response.body().contains("<!DOCTYPE html>"), response.body()));
    }

    /**
     * Sends the same request with an explicit {@code text/html}, with {@code application/json} and with
     * a wildcard, and with {@code text/html} to the edge whose error pages are off; asserts that only
     * the first answers HTML and all four answer {@code status}.
     */
    private void assertNegotiates(int status, Shape shape, io.vertx.core.http.HttpMethod method, String uri,
            Map<String, String> headers, @Nullable String body, @Nullable String host) throws Exception {
        Response html = send(enabledFront, method, uri, withAccept(headers, "text/html,application/xhtml+xml"), body,
                host);
        Response json = send(enabledFront, method, uri, withAccept(headers, "application/json"), body, host);
        Response wildcard = send(enabledFront, method, uri, withAccept(headers, "*/*"), body, host);
        Response disabled = send(disabledFront, method, uri, withAccept(headers, TEXT_HTML), body, host);

        assertHtmlPage(html, status);
        assertCurrentShape(json, status, shape);
        assertCurrentShape(wildcard, status, shape);
        assertCurrentShape(disabled, status, shape);
    }

    private static void assertHtmlPage(Response response, int status) {
        assertAll("HTML error page " + status,
                () -> assertEquals(status, response.status(), "the status is preserved"),
                () -> assertTrue(response.headers().getOrDefault(CONTENT_TYPE, "").startsWith(TEXT_HTML),
                        String.valueOf(response.headers())),
                () -> assertEquals(SecurityHeadersStage.PORTAL_CONTENT_SECURITY_POLICY, response.headers().get(CSP)),
                () -> assertEquals("nosniff", response.headers().get("X-Content-Type-Options")),
                () -> assertEquals("no-store", response.headers().get("Cache-Control")),
                () -> assertTrue(response.body().startsWith("<!DOCTYPE html>"), response.body()),
                () -> assertTrue(response.body().contains(status + " " + ErrorPageClassifier.titleFor(status)),
                        response.body()),
                () -> assertFalse(response.body().contains("\"type\""), "no problem detail reaches the page"));
    }

    private static void assertCurrentShape(Response response, int status, Shape shape) {
        if (shape == Shape.PROBLEM) {
            assertProblem(response, status);
            return;
        }
        assertAll("bare shape " + status,
                () -> assertEquals(status, response.status(), "the status is identical"),
                () -> assertFalse(response.body().contains("<!DOCTYPE html>"), response.body()),
                () -> assertNotEquals(SecurityHeadersStage.PORTAL_CONTENT_SECURITY_POLICY, response.headers().get(CSP)));
    }

    private static void assertProblem(Response response, int status) {
        assertAll("problem+json " + status,
                () -> assertEquals(status, response.status(), "the status is identical"),
                () -> assertEquals(PROBLEM_JSON, response.headers().get(CONTENT_TYPE)),
                () -> assertTrue(response.body().contains("\"status\":" + status), response.body()),
                () -> assertNotEquals(SecurityHeadersStage.PORTAL_CONTENT_SECURITY_POLICY, response.headers().get(CSP)));
    }

    private static Map<String, String> withAccept(Map<String, String> headers, String accept) {
        Map<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(headers);
        merged.put("Accept", accept);
        return merged;
    }

    private HttpServer startFront(RouteTable routes, TokenValidator tokenValidator, boolean errorPages)
            throws Exception {
        OidcConfig oidc = OidcConfig.builder().redirectUri("https://" + OIDC_HOST + CALLBACK_PATH).build();
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).securityHeaders(globalHeaders()).oidc(oidc)
                .build();
        PortalConfig portal = PortalConfig.builder().path("/portal").title("Portal").errorPages(errorPages).build();
        BffRuntime runtime = GatewayEdgeRouteBffWiringTest.activeRuntime(
                GatewayEdgeRouteBffWiringTest.serverBinding(new InMemorySessionStore(16)));
        PortalEndpoint portalEndpoint = PortalEndpoint.of(portal, new PortalCatalog(List.of()),
                PortalRenderer.builtIn(), runtime::sessionIdentity, oidc, true, "/");
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routes, gatewayConfig, new SingletonInstance<>(tokenValidator),
                vertx, virtualThreadExecutor, new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()),
                runtime, EgressTrustProfiles.unconsulted(), portalEndpoint);
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        return Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
    }

    private Response send(HttpServer front, io.vertx.core.http.HttpMethod method, String uri,
            Map<String, String> requestHeaders, @Nullable String body, @Nullable String host) throws Exception {
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                .setHost(host != null ? host : LoopbackHost.ADDRESS).setPort(front.actualPort())
                .setMethod(method).setURI(uri);
        CompletableFuture<Response> future = client.request(options)
                .compose(request -> {
                    requestHeaders.forEach(request::putHeader);
                    return body == null ? request.send() : request.send(Buffer.buffer(body));
                })
                .compose(response -> {
                    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    response.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                    return response.body().map(buffer -> new Response(response.statusCode(), headers,
                            buffer == null ? "" : buffer.toString()));
                })
                .toCompletionStage().toCompletableFuture();
        return Awaits.connect(future, "the edge response to " + method + " " + uri);
    }

    private static SecurityHeadersConfig globalHeaders() {
        return SecurityHeadersConfig.builder().contentSecurityPolicy(GLOBAL_POLICY).build();
    }

    private static ResolvedRoute.ResolvedRouteBuilder proxyRoute(String id, String pathPrefix, int upstreamPort) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .effectiveSecurityHeaders(globalHeaders())
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""));
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }

    /** Minimal {@link Instance} double resolving to one supplied validator; unused accessors throw. */
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
