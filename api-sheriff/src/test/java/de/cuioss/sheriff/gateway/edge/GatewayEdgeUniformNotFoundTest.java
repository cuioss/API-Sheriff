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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.edge.error.UniformNotFound;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.portal.PortalCatalog;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.portal.PortalRenderer;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The endpoint-existence oracle closed at the edge, driven over a live Vert.x server against a stub
 * upstream: every rejection raised after route selection and before the trust boundary is answered to the
 * untrusted caller <em>byte-identically</em> to an address no route serves — status, body,
 * {@code Content-Type}, the whole header set, and the negotiated HTML page under {@code Accept: text/html}.
 * No {@code WWW-Authenticate}, no {@code Allow}, no route {@code security_headers} block and no
 * {@code match.headers} {@code Vary} survive, and a {@code protocol: grpc} route answers an HTTP
 * {@code 404} rather than a trailers-only status. The true event is still metered in
 * {@code sheriff_errors_total} (the in-process event counter is asserted beside the renderer, in
 * {@code ErrorResponseWriterTest}: the edge creates its own and exposes none).
 * <p>
 * Answers past the trust boundary, and the session branch's own negotiation, stay honest. An edge built
 * with {@code security_defaults.uniform_not_found: false} returns every honest code, so deleting the
 * switch turns this test red.
 * <p>
 * Every uniform {@code 404}, genuine or resolved, problem document or HTML page, leaves the edge no
 * earlier than {@link UniformNotFound#RESPONSE_FLOOR} after it was sent; an honest answer, and the
 * unrouted {@code 404} of an edge with the switch off, is not held back. The floor is asserted as a
 * lower bound on the round trip; "not held back" as the fastest of several round trips coming in under
 * the floor, which a held answer never can.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — a pre-trust rejection is answered exactly as an unrouted address")
@Tag("isolated-fork")
class GatewayEdgeUniformNotFoundTest {

    private static final String OIDC_HOST = "gw.example.com";
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String GLOBAL_POLICY = "default-src 'none'";
    private static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String TEXT_HTML = "text/html";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String UNROUTED = "/nowhere/at-all";
    private static final String TENANT_HEADER = "X-Tenant";
    private static final String NEVER_GRANTED_SCOPE = "sheriff-uniform-never-granted";
    private static final int BODY_CAP = 16;
    private static final String OVER_CAP_BODY = "x".repeat(BODY_CAP * 4);
    /** Round trips an answer that must not be held back gets to come in under the floor once. */
    private static final int TIMING_ATTEMPTS = 5;

    /** Counts the requests that actually reached the stub upstream. */
    private final AtomicInteger upstreamHits = new AtomicInteger();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer uniformFront;
    private HttpServer honestFront;
    private HttpClient client;
    private SimpleMeterRegistry uniformRegistry;
    private String validBearerToken;

    /**
     * One pre-trust rejection: the request that raises it, the route and the true event it is metered
     * under, and the status an honest edge answers it with.
     */
    record Rejection(String name, io.vertx.core.http.HttpMethod method, String uri, Map<String, String> headers,
            @Nullable String body, String routeId, EventType trueEvent, int honestStatus) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Rejection> preTrustRejections() {
        return Stream.of(
                new Rejection("missing bearer", io.vertx.core.http.HttpMethod.GET, "/secure/data", Map.of(), null,
                        "secure", EventType.TOKEN_MISSING, 401),
                new Rejection("garbage bearer", io.vertx.core.http.HttpMethod.GET, "/secure/data",
                        Map.of("Authorization", "Bearer not-a-real-token"), null, "secure", EventType.TOKEN_INVALID, 401),
                new Rejection("off-allowlist path", io.vertx.core.http.HttpMethod.GET, "/fenced/elsewhere", Map.of(),
                        null, "fenced", EventType.PATH_NOT_ALLOWED, 400),
                new Rejection("wrong method", io.vertx.core.http.HttpMethod.DELETE, "/echo/orders", Map.of(), null,
                        "echo", EventType.METHOD_NOT_ALLOWED, 405),
                new Rejection("route-scoped 400", io.vertx.core.http.HttpMethod.GET, "/echo/orders?q=%00", Map.of(),
                        null, "echo", EventType.SECURITY_FILTER_VIOLATION, 400),
                new Rejection("route-scoped 413", io.vertx.core.http.HttpMethod.POST, "/echo/orders", Map.of(),
                        OVER_CAP_BODY, "echo", EventType.CONTENT_TOO_LARGE, 413),
                new Rejection("cross-origin unsafe request", io.vertx.core.http.HttpMethod.POST, "/s/submit",
                        Map.of("Origin", "https://evil.example"), "payload", "session", EventType.CSRF_REJECTED, 403),
                new Rejection("gRPC route", io.vertx.core.http.HttpMethod.POST, "/grpc/Svc/Call",
                        Map.of(CONTENT_TYPE, "application/grpc"), null, "grpc", EventType.TOKEN_MISSING, 200),
                new Rejection("session-fallback bearer branch", io.vertx.core.http.HttpMethod.GET, "/fallback/data",
                        Map.of("Authorization", "Bearer not-a-real-token"), null, "fallback",
                        EventType.TOKEN_INVALID, 401),
                new Rejection("match.headers route", io.vertx.core.http.HttpMethod.GET, "/tenant/data",
                        Map.of(TENANT_HEADER, "acme"), null, "tenant", EventType.TOKEN_MISSING, 401));
    }

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamHits.set(0);
        upstreamServer = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                request.body().onComplete(ignored -> {
                    upstreamHits.incrementAndGet();
                    request.response().end("upstream");
                })).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        TestTokenHolder tokenHolder = TestTokenGenerators.accessTokens().next();
        validBearerToken = tokenHolder.getRawToken();
        TokenValidator tokenValidator = TokenValidator.builder().issuerConfig(tokenHolder.getIssuerConfig()).build();
        uniformRegistry = new SimpleMeterRegistry();
        uniformFront = startFront(tokenValidator, true, uniformRegistry);
        honestFront = startFront(tokenValidator, false, new SimpleMeterRegistry());
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(uniformFront.close(), "the uniform edge front server to close");
        Awaits.teardown(honestFront.close(), "the honest edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustRejections")
    @DisplayName("the problem answer is byte-identical to the unrouted one: status, body, Content-Type and headers")
    void problemAnswerIsTheUnroutedOne(Rejection rejection) throws Exception {
        Map<String, String> json = withAccept(rejection.headers(), "application/json");

        Response rejected = send(uniformFront, rejection.method(), rejection.uri(), json, rejection.body());
        Response unrouted = send(uniformFront, rejection.method(), UNROUTED, json, rejection.body());

        assertAll(rejection.name(),
                () -> assertEquals(404, rejected.status()),
                () -> assertEquals(PROBLEM_JSON, rejected.headers().get(CONTENT_TYPE)),
                () -> assertEquals(unrouted.status(), rejected.status()),
                () -> assertEquals(unrouted.body(), rejected.body(), "the NO_ROUTE_MATCHED problem body"),
                () -> assertEquals(unrouted.headers(), rejected.headers(), "exactly the unrouted header set"),
                () -> assertNothingDisclosesTheRoute(rejected),
                () -> assertEquals(0, upstreamHits.get(), "a refused request never reaches the upstream"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustRejections")
    @DisplayName("under Accept: text/html the answer is the unrouted HTML error page, byte for byte")
    void htmlAnswerIsTheUnroutedPage(Rejection rejection) throws Exception {
        Map<String, String> html = withAccept(rejection.headers(), TEXT_HTML);

        Response rejected = send(uniformFront, rejection.method(), rejection.uri(), html, rejection.body());
        Response unrouted = send(uniformFront, rejection.method(), UNROUTED, html, rejection.body());

        assertAll(rejection.name(),
                () -> assertEquals(404, rejected.status()),
                () -> assertTrue(rejected.headers().getOrDefault(CONTENT_TYPE, "").startsWith(TEXT_HTML),
                        String.valueOf(rejected.headers())),
                () -> assertTrue(rejected.body().startsWith("<!DOCTYPE html>"), rejected.body()),
                () -> assertEquals(unrouted.body(), rejected.body(), "the unrouted page"),
                () -> assertEquals(unrouted.headers(), rejected.headers(), "exactly the unrouted header set"),
                () -> assertNothingDisclosesTheRoute(rejected));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustRejections")
    @DisplayName("the true event is metered under the route in sheriff_errors_total, never NO_ROUTE_MATCHED")
    void theTrueEventIsMetered(Rejection rejection) throws Exception {
        assertEquals(404, send(uniformFront, rejection.method(), rejection.uri(), rejection.headers(),
                rejection.body()).status(), "precondition: the caller is shown the unrouted 404");

        Counter trueEvent = uniformRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                .tags("route", rejection.routeId(), "event", rejection.trueEvent().name()).counter();
        assertAll(rejection.name(),
                () -> assertNotNull(trueEvent, "the true event " + rejection.trueEvent() + " is metered"),
                () -> assertEquals(1.0, trueEvent == null ? 0.0 : trueEvent.count()),
                () -> assertNull(uniformRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                                .tags("route", rejection.routeId(), "event", EventType.NO_ROUTE_MATCHED.name()).counter(),
                        "the rejection is rendered TO the unrouted shape, never reported AS it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustRejections")
    @DisplayName("switch off: the honest code returns for every pre-trust rejection")
    void switchOffReturnsTheHonestCode(Rejection rejection) throws Exception {
        Response response = send(honestFront, rejection.method(), rejection.uri(),
                withAccept(rejection.headers(), "application/json"), rejection.body());

        assertAll(rejection.name(),
                () -> assertEquals(rejection.honestStatus(), response.status()),
                () -> assertEquals(0, upstreamHits.get(), "a refused request never reaches the upstream"));
    }

    @Test
    @DisplayName("switch off: the gRPC rejection is trailers-only and the bearer rejection carries its challenge")
    void switchOffRestoresTheDisclosingShapes() throws Exception {
        Response grpc = send(honestFront, io.vertx.core.http.HttpMethod.POST, "/grpc/Svc/Call",
                Map.of(CONTENT_TYPE, "application/grpc"), null);
        Response bearer = send(honestFront, io.vertx.core.http.HttpMethod.GET, "/secure/data", Map.of(), null);
        Response verb = send(honestFront, io.vertx.core.http.HttpMethod.DELETE, "/echo/orders", Map.of(), null);
        Response tenant = send(honestFront, io.vertx.core.http.HttpMethod.GET, "/tenant/data",
                Map.of(TENANT_HEADER, "acme"), null);

        assertAll("the honest edge discloses what the uniform edge hides",
                () -> assertEquals("16", grpc.headers().get("grpc-status"), "trailers-only UNAUTHENTICATED"),
                () -> assertEquals("Bearer", bearer.headers().get("WWW-Authenticate")),
                () -> assertEquals("DENY", bearer.headers().get(FRAME_OPTIONS), "the route block"),
                () -> assertEquals("GET, POST", verb.headers().get("Allow")),
                () -> assertTrue(tenant.headers().getOrDefault("Vary", "").toLowerCase(Locale.ROOT)
                        .contains(TENANT_HEADER.toLowerCase(Locale.ROOT)), "the match.headers Vary: " + tenant.headers()));
    }

    static Stream<Rejection> floorBoundAnswers() {
        Rejection unrouted = new Rejection("unrouted address", io.vertx.core.http.HttpMethod.GET, UNROUTED, Map.of(),
                null, "", EventType.NO_ROUTE_MATCHED, 404);
        Rejection unroutedPage = new Rejection("off-allowlist path, HTML page", io.vertx.core.http.HttpMethod.GET,
                "/fenced/elsewhere", Map.of("Accept", TEXT_HTML), null, "fenced", EventType.PATH_NOT_ALLOWED, 400);
        Set<String> timed = Set.of("missing bearer", "garbage bearer", "off-allowlist path");
        return Stream.concat(Stream.of(unrouted, unroutedPage),
                preTrustRejections().filter(rejection -> timed.contains(rejection.name())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("floorBoundAnswers")
    @DisplayName("no uniform 404, genuine or resolved, leaves the edge before the response-timing floor")
    void uniformAnswerWaitsForTheFloor(Rejection rejection) throws Exception {
        long sentAt = System.nanoTime();
        Response response = send(uniformFront, rejection.method(), rejection.uri(), rejection.headers(), null);
        long tookNanos = System.nanoTime() - sentAt;

        assertAll(rejection.name(),
                () -> assertEquals(404, response.status()),
                () -> assertTrue(tookNanos >= UniformNotFound.RESPONSE_FLOOR.toNanos(),
                        () -> "answered after " + tookNanos + " ns, before the floor of " + UniformNotFound.RESPONSE_FLOOR));
    }

    @Test
    @DisplayName("an honest answer is not held back: a SCOPE_MISSING 403 arrives before the floor could release it")
    void honestAnswerIsNotDelayed() throws Exception {
        long fastest = fastestAnswer(uniformFront, "/scoped/data",
                Map.of("Authorization", "Bearer " + validBearerToken, "Accept", "application/json"), 403);

        assertTrue(fastest < UniformNotFound.RESPONSE_FLOOR.toNanos(),
                () -> "the fastest of " + TIMING_ATTEMPTS + " scope refusals took " + fastest + " ns");
    }

    @Test
    @DisplayName("switch off: the unrouted 404 is not held back either")
    void switchOffAnswersTheUnroutedAddressAtOnce() throws Exception {
        long fastest = fastestAnswer(honestFront, UNROUTED, Map.of("Accept", "application/json"), 404);

        assertTrue(fastest < UniformNotFound.RESPONSE_FLOOR.toNanos(),
                () -> "the fastest of " + TIMING_ATTEMPTS + " unrouted answers took " + fastest + " ns");
    }

    @Test
    @DisplayName("past the trust boundary: a validated token without the needed scope is answered 403")
    void validatedTokenWithoutScopeIsAnswered403() throws Exception {
        Response response = send(uniformFront, io.vertx.core.http.HttpMethod.GET, "/scoped/data",
                Map.of("Authorization", "Bearer " + validBearerToken, "Accept", "application/json"), null);

        assertAll("the caller is trusted, so the scope refusal is honest",
                () -> assertEquals(403, response.status()),
                () -> assertTrue(response.headers().getOrDefault("WWW-Authenticate", "")
                        .contains("insufficient_scope"), String.valueOf(response.headers())));
    }

    @Test
    @DisplayName("the session branch keeps its negotiation: an XHR without a session is answered 401 problem+json")
    void sessionRouteXhrIsAnswered401() throws Exception {
        Response response = send(uniformFront, io.vertx.core.http.HttpMethod.GET, "/s/data",
                Map.of("Accept", "application/json"), null);

        assertAll("the verify-first clause of the session branch",
                () -> assertEquals(401, response.status()),
                () -> assertEquals(PROBLEM_JSON, response.headers().get(CONTENT_TYPE)));
    }

    @Test
    @DisplayName("the session branch keeps its negotiation: a navigation without a session is redirected 302")
    void sessionRouteNavigationIsRedirected() throws Exception {
        Response response = send(uniformFront, io.vertx.core.http.HttpMethod.GET, "/s/page",
                Map.of("Accept", TEXT_HTML), null);

        assertAll("the login redirect of the session branch",
                () -> assertEquals(302, response.status()),
                () -> assertNotNull(response.headers().get("Location")),
                () -> assertEquals(0, upstreamHits.get()));
    }

    @Test
    @DisplayName("an anonymous route past stage 4 is served, so the switch hides nothing a success already shows")
    void anonymousRouteIsServed() throws Exception {
        Response response = send(uniformFront, io.vertx.core.http.HttpMethod.GET, "/echo/orders", Map.of(), null);

        assertAll("control: the fixture's routes are live",
                () -> assertEquals(200, response.status()),
                () -> assertEquals(1, upstreamHits.get()));
    }

    /**
     * The shortest of {@value #TIMING_ATTEMPTS} round trips to {@code uri}: a held answer can never come
     * in under the floor, so one fast round trip proves the answer is not held, while a slow machine
     * needs only one of the attempts to be quick.
     */
    private long fastestAnswer(HttpServer front, String uri, Map<String, String> headers, int expectedStatus)
            throws Exception {
        long fastest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < TIMING_ATTEMPTS; attempt++) {
            long sentAt = System.nanoTime();
            Response response = send(front, io.vertx.core.http.HttpMethod.GET, uri, headers, null);
            fastest = Math.min(fastest, System.nanoTime() - sentAt);
            assertEquals(expectedStatus, response.status(), uri);
        }
        return fastest;
    }

    private static void assertNothingDisclosesTheRoute(Response response) {
        assertAll("no header discloses the route",
                () -> assertNull(response.headers().get("WWW-Authenticate"), "no challenge"),
                () -> assertNull(response.headers().get("Allow"), "no Allow"),
                () -> assertNull(response.headers().get(FRAME_OPTIONS), "no route security_headers block"),
                () -> assertNull(response.headers().get("Vary"), "no match.headers Vary"),
                () -> assertNull(response.headers().get("grpc-status"), "no trailers-only status"));
    }

    private HttpServer startFront(TokenValidator tokenValidator, boolean uniformNotFound,
            SimpleMeterRegistry registry) throws Exception {
        OidcConfig oidc = OidcConfig.builder().redirectUri("https://" + OIDC_HOST + CALLBACK_PATH).build();
        GatewayConfig gatewayConfig = ConfigFixtures.gateway()
                .securityHeaders(SecurityHeadersConfig.builder().contentSecurityPolicy(GLOBAL_POLICY).build())
                .securityDefaults(SecurityDefaultsConfig.builder().uniformNotFound(uniformNotFound).build())
                .oidc(oidc)
                .build();
        PortalConfig portal = PortalConfig.builder().path("/portal").title("Portal").errorPages(true).build();
        BffRuntime runtime = GatewayEdgeRouteBffWiringTest.activeRuntime(
                GatewayEdgeRouteBffWiringTest.serverBinding(GatewayEdgeRouteBffWiringTest.newStore()));
        PortalEndpoint portalEndpoint = PortalEndpoint.of(portal, new PortalCatalog(List.of()),
                PortalRenderer.builtIn(), runtime::sessionIdentity, oidc, true, "/");
        return EdgeFixtures.edge(vertx, virtualThreadExecutor).routes(routes()).gatewayConfig(gatewayConfig)
                .tokenValidator(tokenValidator).runtime(runtime).portal(portalEndpoint)
                .metrics(new SheriffMetrics(registry)).serve();
    }

    private RouteTable routes() {
        int port = upstreamServer.actualPort();
        return new RouteTable(List.of(
                route("secure", "/secure", port).effectiveAuth(auth(Require.BEARER)).build(),
                route("echo", "/echo", port)
                        .effectiveAllowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST))
                        .effectiveSecurityFilter(SecurityFilterConfig.builder().maxBodyBytes(BODY_CAP).build())
                        .build(),
                route("fenced", "/fenced", port)
                        .effectiveSecurityFilter(SecurityFilterConfig.builder()
                                .allowedPaths(List.of("/fenced/allowed")).build())
                        .build(),
                route("scoped", "/scoped", port).effectiveAuth(auth(Require.BEARER))
                        .neededScopes(Set.of(NEVER_GRANTED_SCOPE)).build(),
                route("session", "/s", port).effectiveAuth(auth(Require.SESSION))
                        .effectiveAllowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST)).build(),
                route("fallback", "/fallback", port)
                        .effectiveAuth(AuthConfig.builder().require(Require.BEARER).sessionFallback(Boolean.TRUE).build())
                        .build(),
                route("grpc", "/grpc", port).protocol(Protocol.GRPC).effectiveAuth(auth(Require.BEARER))
                        .effectiveAllowedMethods(Set.of(HttpMethod.POST)).build(),
                route("tenant", "/tenant", port).effectiveAuth(auth(Require.BEARER))
                        .match(MatchConfig.builder().pathPrefix("/tenant")
                                .headers(List.of(HeaderMatcher.builder().name(TENANT_HEADER).present(true).build()))
                                .build())
                        .build()));
    }

    private static AuthConfig auth(Require require) {
        return AuthConfig.builder().require(require).build();
    }

    /** A route carrying a block of its own, so a route-scoped header on a response names the route. */
    private static Route.RouteBuilder route(String id, String pathPrefix, int upstreamPort) {
        return ConfigFixtures.route(id, pathPrefix)
                .securityHeaders(SecurityHeadersConfig.builder().frameDeny(true).contentTypeNosniff(true).build())
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""));
    }

    private static Map<String, String> withAccept(Map<String, String> headers, String accept) {
        Map<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(headers);
        merged.put("Accept", accept);
        return merged;
    }

    private Response send(HttpServer front, io.vertx.core.http.HttpMethod method, String uri,
            Map<String, String> requestHeaders, @Nullable String body) throws Exception {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS).setPort(front.actualPort())
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

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }
}
