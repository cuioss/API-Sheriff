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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.auth.AuthBranch;
import de.cuioss.sheriff.gateway.bff.client.ClientSigningKey;
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ClaimAllowlistFilter;
import de.cuioss.sheriff.gateway.bff.reserved.ClientJwksEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
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
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
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
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Covers the D16 edge wiring of the server-mode BFF runtime: the {@link ReservedPathRegistry} now
 * registers the {@code user_info} and {@code login} folds, the {@link GatewayEdgeRoute} assembles the
 * session-aware authentication stage when an active runtime is wired, and {@link BffRuntime#dispatch}
 * routes each reserved path to its handler rather than the pre-wiring {@code NO_ROUTE_MATCHED} 404.
 * The live per-request serving over a Vert.x server (and the engine round-trips) is exercised by the
 * Keycloak integration tests; these deterministic module tests stay container- and IdP-free.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — server-mode BFF runtime edge wiring (D16)")
class GatewayEdgeRouteBffWiringTest {

    private static final String OIDC_HOST = "gw.example.com";
    private static final String ORIGIN = "https://gw.example.com";
    /** The fixture's configured post-login fallback — the resolved {@code oidc.login.default_return_url} when unset. */
    private static final String ROOT_RETURN_TARGET = "/";
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final String LOGOUT_RETURN_PATH = "/auth/logout/return";
    private static final String BACKCHANNEL_PATH = "/auth/backchannel";
    private static final String USER_INFO_PATH = "/auth/userinfo";
    private static final String LOGIN_PATH = "/auth/login";

    @Nested
    @DisplayName("ReservedPathRegistry registers the user_info and login folds (D11/D12)")
    class RegistryFolds {

        private final ReservedPathRegistry registry = ReservedPathRegistry.from(fullOidc());

        @Test
        @DisplayName("Should register the user_info fold path as USER_INFO")
        void shouldRegisterUserInfo() {
            assertEquals(Optional.of(ReservedEndpoint.USER_INFO), registry.match(OIDC_HOST, USER_INFO_PATH));
        }

        @Test
        @DisplayName("Should register the login fold path as LOGIN")
        void shouldRegisterLogin() {
            assertEquals(Optional.of(ReservedEndpoint.LOGIN), registry.match(OIDC_HOST, LOGIN_PATH));
        }

        @Test
        @DisplayName("Should keep the original four reserved paths alongside the two new folds")
        void shouldKeepOriginalReserved() {
            assertEquals(Optional.of(ReservedEndpoint.CALLBACK), registry.match(OIDC_HOST, CALLBACK_PATH));
            assertEquals(Optional.of(ReservedEndpoint.LOGOUT), registry.match(OIDC_HOST, LOGOUT_PATH));
            assertEquals(Optional.of(ReservedEndpoint.LOGOUT_RETURN), registry.match(OIDC_HOST, LOGOUT_RETURN_PATH));
            assertEquals(Optional.of(ReservedEndpoint.BACKCHANNEL_LOGOUT), registry.match(OIDC_HOST, BACKCHANNEL_PATH));
        }
    }

    @Nested
    @DisplayName("GatewayEdgeRoute assembles the session-aware stage when the runtime is active")
    class EdgeAssembly {

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private TokenValidator tokenValidator;

        @BeforeEach
        void setUp() {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            tokenValidator = TokenValidator.builder()
                    .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        }

        @AfterEach
        void tearDown() {
            virtualThreadExecutor.close();
            vertx.close();
        }

        @Test
        @DisplayName("Should boot a require:session route through the session-aware AuthenticationStage")
        void shouldBootSessionRouteWithActiveRuntime() throws Exception {
            RouteTable sessionTable = new RouteTable(List.of(sessionRoute()));

            // Not throwing at construction distinguishes nothing: the bearer assembly builds silently
            // too, and so would a constructor that ignored the runtime outright — both fail only once
            // a request arrives. The observable that IS specific to the session-aware assembly is the
            // wired SessionAuthenticationStage's own login challenge on an unauthenticated navigation.
            HttpClientResponse challenged = serveUnauthenticatedNavigation(sessionTable,
                    activeRuntime(serverBinding(new InMemorySessionStore(16))));
            HttpClientResponse unwired = serveUnauthenticatedNavigation(sessionTable, BffRuntime.inert());

            assertEquals(302, challenged.statusCode(),
                    "the wired session stage answers an unauthenticated HTML navigation with a redirect");
            assertEquals("/login", challenged.getHeader("Location"),
                    "and the target is that stage's own login challenge, not a bearer 401");
            assertEquals(500, unwired.statusCode(),
                    "control: without an active runtime the same route reaches an AuthenticationStage "
                            + "carrying no session stage, so the assertion above is attributable to the wiring");
        }

        /**
         * Drives one unauthenticated HTML navigation at the {@code require: session} route through a
         * freshly-assembled edge and returns the edge's own answer. {@code Accept: text/html} is what
         * selects the session stage's redirect branch over its API-shaped 401.
         */
        private HttpClientResponse serveUnauthenticatedNavigation(RouteTable table, BffRuntime runtime)
                throws Exception {
            GatewayEdgeRoute edge = newEdge(table, runtime);
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            HttpClient client = vertx.createHttpClient();
            try {
                RequestOptions options = new RequestOptions()
                        .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                        .setHost(OIDC_HOST).setPort(front.actualPort())
                        .setMethod(io.vertx.core.http.HttpMethod.GET).setURI("/s/page");
                return Awaits.connect(
                        client.request(options).compose(request ->
                                request.putHeader("Accept", "text/html").send()),
                        "the edge response to GET /s/page");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @Test
        @DisplayName("Should still register a single catch-all route with an active runtime")
        void shouldRegisterCatchAll() {
            GatewayEdgeRoute edge = newEdge(new RouteTable(List.of()), activeRuntime(serverBinding(new InMemorySessionStore(16))));
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            assertEquals(1, router.getRoutes().size());
        }

        private GatewayEdgeRoute newEdge(RouteTable table, BffRuntime runtime) {
            return new GatewayEdgeRoute(table, GatewayConfig.builder().version(1).build(),
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, new EdgeHardeningOptions(),
                    new SheriffMetrics(new SimpleMeterRegistry()), runtime, EgressTrustProfiles.unconsulted(),
                    PortalEndpoint.inert());
        }
    }

    /**
     * The ADR-0019 reserved-path relaxation is now <strong>structural</strong>. It used to be a
     * {@code reservedPathMatcher} predicate handed to {@code BasicChecksStage}; with the
     * url-parameter pipeline relocated into the post-route {@code ThoroughChecksStage}, a reserved
     * path simply terminates in {@code handleReservedPath} before route selection and therefore never
     * reaches that stage at all. The proxy route below is deliberately rigged to reject everything it
     * sees ({@code allowed_paths} matching nothing), so any response other than the reserved
     * handler's own proves the request fell through to route selection.
     */
    @Nested
    @DisplayName("reserved paths bypass ThoroughChecksStage structurally (ADR-0019, amended)")
    class StructuralReservedPathBypass {

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer front;
        private HttpClient client;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            TokenValidator tokenValidator = TokenValidator.builder()
                    .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
            GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(fullOidc()).build();
            GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(List.of(rejectEverythingRoute())),
                    gatewayConfig, new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()),
                    activeRuntime(serverBinding(new InMemorySessionStore(16))), EgressTrustProfiles.unconsulted(),
                    PortalEndpoint.inert());
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(front.close(), "the edge front server to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("a reserved path is served by its handler and never reaches the route's allowed_paths gate")
        void reservedPathNeverReachesThoroughChecks() throws Exception {
            // Act — a reserved user-info request under the same /auth prefix the proxy route claims,
            // carrying an arbitrary query parameter the url-parameter pipeline would reject. The
            // parameter NAME is incidental here — it is not a login parameter and carries no contract.
            int status = statusOf(USER_INFO_PATH + "?return_to=%3Chome");

            // Assert — 401 is the user-info handler's own no-session answer. A 400 would mean the
            // request reached route selection and then the route's allowed_paths gate.
            assertEquals(401, status,
                    "the reserved path terminates before route selection, so ThoroughChecksStage never runs");
        }

        @Test
        @DisplayName("a non-reserved path under the same prefix IS routed and hits the allowed_paths gate")
        void nonReservedPathStillReachesThoroughChecks() throws Exception {
            // Act — the control case that makes the assertion above meaningful
            int status = statusOf("/auth/not-reserved");

            // Assert
            assertEquals(400, status, "an ordinary path is routed and rejected by the route's allowed_paths");
        }

        private int statusOf(String uri) throws Exception {
            // Connect to the local front server but present the OIDC host in the authority: the
            // reserved-path registry is keyed on (host, canonicalPath).
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                    .setHost(OIDC_HOST).setPort(front.actualPort())
                    .setMethod(io.vertx.core.http.HttpMethod.GET).setURI(uri);
            return Awaits.connect(client.request(options).compose(HttpClientRequest::send),
                    "the edge response to GET " + uri).statusCode();
        }

        private static ResolvedRoute rejectEverythingRoute() {
            return ResolvedRoute.builder()
                    .id("auth-proxy")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/auth").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET))
                    .effectiveSecurityFilter(SecurityFilterConfig.builder()
                            .allowedPaths(List.of("/auth/never-matches")).build())
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, 1, ""))
                    .build();
        }
    }

    /**
     * Pins the <strong>wire name</strong> of the login return-URL query parameter to {@code returnUrl}.
     * <p>
     * The name is a browser-facing contract shared by the demo SPA, the Playwright helper and
     * {@link LoginInitiationEndpoint}'s own documented surface, but the only place it is actually read
     * is the edge's reserved dispatch — so a rename there silently breaks the whole login flow with no
     * compile error anywhere: every internal identifier on the path is already {@code returnUrl}, and a
     * value the edge fails to extract simply degrades to the configured default return URL
     * ({@link LoginFlow#defaultReturnUrl()}, {@code /} in this fixture). That
     * degradation is invisible to a type checker and to every test that does not drive a real request
     * through the edge, which is why these two run over a live Vert.x server rather than calling
     * {@link BffRuntime#dispatch} directly.
     * <p>
     * The pair is deliberately a matched positive/negative control: the accepted spelling must reach
     * the login fold, and the retired {@code return_to} spelling must NOT. Asserting only the positive
     * case would still pass if the edge accepted both.
     */
    @Nested
    @DisplayName("login initiation reads the returnUrl wire parameter — and only that spelling")
    class LoginReturnUrlWireName {

        private static final String RETURN_TARGET = "/dashboard";
        private static final String ENCODED_RETURN_TARGET = "%2Fdashboard";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer front;
        private HttpClient client;
        private String sessionCookie;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            TokenValidator tokenValidator = TokenValidator.builder()
                    .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

            // A live session makes login initiation take the already-authenticated short-circuit, whose
            // redirect Location IS the return URL the edge extracted — the cleanest observable for the
            // wire name, and one that never reaches the IdP engine.
            SessionStore store = new InMemorySessionStore(16);
            String sessionId = SessionRecord.newSessionId();
            store.create(SessionRecord.builder().sessionId(sessionId).accessToken("a").idToken("i").sub("sub")
                    .expiresAt(Instant.now().plus(Duration.ofHours(1))).build(), Instant.now());
            sessionCookie = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId;

            GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(fullOidc()).build();
            GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(List.of()), gatewayConfig,
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()),
                    activeRuntime(serverBinding(store)), EgressTrustProfiles.unconsulted(), PortalEndpoint.inert());
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(front.close(), "the edge front server to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("returnUrl reaches the login fold and becomes the short-circuit redirect target")
        void shouldReadReturnUrlParameter() throws Exception {
            // Arrange — see setUp: a live session and a same-origin relative return target

            // Act
            HttpClientResponse response = login("?returnUrl=" + ENCODED_RETURN_TARGET);

            // Assert
            assertEquals(302, response.statusCode(), "the live-session login short-circuit is a 302");
            assertEquals(RETURN_TARGET, response.getHeader("Location"),
                    "returnUrl is the login wire parameter, so its value must reach the login fold");
        }

        @Test
        @DisplayName("the retired return_to spelling is ignored and degrades to the default return URL")
        void shouldIgnoreRetiredReturnToSpelling() throws Exception {
            // Arrange — see setUp: identical request except for the parameter spelling

            // Act
            HttpClientResponse response = login("?return_to=" + ENCODED_RETURN_TARGET);

            // Assert — the regression pin. If the wire name ever reverts to return_to, this request
            // would be honoured and the Location would be RETURN_TARGET instead of the default.
            assertEquals(302, response.statusCode(), "the live-session login short-circuit is a 302");
            assertEquals(ROOT_RETURN_TARGET, response.getHeader("Location"),
                    "return_to is not the login wire parameter, so it must not reach the login fold");
        }

        private HttpClientResponse login(String query) throws Exception {
            // Connect to the local front server but present the OIDC host in the authority: the
            // reserved-path registry is keyed on (host, canonicalPath).
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                    .setHost(OIDC_HOST).setPort(front.actualPort())
                    .setMethod(io.vertx.core.http.HttpMethod.GET).setURI(LOGIN_PATH + query);
            return Awaits.connect(client.request(options)
                            .compose(request -> request.putHeader("Cookie", sessionCookie).send()),
                    "the login response for " + query);
        }
    }

    @Nested
    @DisplayName("BffRuntime.dispatch routes each reserved path to its handler (not NO_ROUTE_MATCHED)")
    class ReservedDispatch {

        private final SessionStore store = new InMemorySessionStore(16);
        private final BffRuntime runtime = activeRuntime(serverBinding(store));
        private final Instant now = Instant.parse("2026-07-25T10:00:00Z");

        @Test
        @DisplayName("USER_INFO with no session cookie yields 401 from the user-info handler")
        void shouldDispatchUserInfo() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.USER_INFO,
                    request(null, null), now);
            assertEquals(401, response.status());
        }

        @Test
        @DisplayName("CALLBACK with a stateless query yields 400 from the callback handler")
        void shouldDispatchCallback() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.CALLBACK,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"), now);
            assertEquals(400, response.status());
        }

        @Test
        @DisplayName("LOGOUT without a live session redirects (302) to final_redirect")
        void shouldDispatchLogout() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.LOGOUT,
                    request(null, null), now);
            assertEquals(302, response.status());
        }

        @Test
        @DisplayName("LOGOUT_RETURN without a logout-state cookie yields 400")
        void shouldDispatchLogoutReturn() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.LOGOUT_RETURN,
                    request(null, null), now);
            assertEquals(400, response.status());
        }

        @Test
        @DisplayName("BACKCHANNEL_LOGOUT with no form body yields 400 and is uncacheable")
        void shouldDispatchBackchannel() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.BACKCHANNEL_LOGOUT,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "POST"), now);
            assertEquals(400, response.status());
            assertEquals("no-store", response.headers().get("Cache-Control"));
        }

        @Test
        @DisplayName("LOGIN with a live session short-circuits (302) to the validated return URL")
        void shouldDispatchLogin() {
            String sessionId = SessionRecord.newSessionId();
            store.create(SessionRecord.builder().sessionId(sessionId).accessToken("a").idToken("i").sub("sub")
                    .expiresAt(now.plus(Duration.ofHours(1))).build(), now);
            String cookie = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId;
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.LOGIN,
                    new BffRuntime.ReservedHttpRequest("", cookie, null, "/home", null, null, "GET"), now);
            BffRuntime.ReservedHttpResponse crossOrigin = runtime.dispatch(ReservedEndpoint.LOGIN,
                    new BffRuntime.ReservedHttpRequest("", cookie, null, "https://evil.example.com/home",
                            null, null, "GET"), now);

            assertEquals(302, response.status());
            // "The validated return URL" is a value, not a presence: asserting only that SOME Location
            // is set would pass for a short-circuit to any target at all, including the attacker's.
            assertEquals(Optional.of("/home"), response.locationOptional(),
                    "the short-circuit goes to the requested same-origin return URL");
            // "/" is this fixture's configured default return URL (no oidc.login.default_return_url).
            assertEquals(Optional.of("/"), crossOrigin.locationOptional(),
                    "control: a cross-origin return URL is refused and replaced by the default, which is "
                            + "what makes the assertion above one about validation rather than about "
                            + "there being any Location header at all");
        }

        @Test
        @DisplayName("CLIENT_JWKS reaches the client JWKS handler — 404 from this fixture's withheld form, no header, no JSON body")
        void shouldDispatchClientJwks() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.CLIENT_JWKS,
                    request(null, null), now);

            assertAll("the withheld form through the dispatch",
                    () -> assertEquals(404, response.status()),
                    () -> assertEquals(Map.of(), response.headers(),
                            "the dispatch adds no header: the edge answers this outcome as an unrouted path"),
                    () -> assertEquals(Optional.empty(), response.jsonBodyOptional(),
                            "an absent document stays absent rather than being serialized as the JSON literal null"));
        }

        private BffRuntime.ReservedHttpRequest request(String cookie, String claims) {
            return new BffRuntime.ReservedHttpRequest("", cookie, claims, null, null, null, "GET");
        }
    }

    /**
     * The callback leg one layer out from {@link CallbackEndpoint}: through
     * {@link BffRuntime#dispatch}, whose {@code callbackParameters} selects WHICH raw string the
     * endpoint parses.
     * <p>
     * That selection is the reason this coverage exists separately from
     * {@code CallbackEndpointTest}. The endpoint is source-neutral — it parses whatever string it is
     * handed — so an endpoint-level duplicate-parameter test proves the parse rejects duplicates, but
     * NOT that the runtime hands it the genuinely raw, uncollapsed query. Under
     * {@code response_mode=query} the {@code code}/{@code state} arrive in the query string, so the
     * rawQuery selection path is now the live one and its BFF-13 duplicate-parameter defence (the
     * Keycloak CVE-2026-9689 class) is asserted HERE, at the seam that could silently collapse it.
     */
    @Nested
    @DisplayName("Query-mode callback dispatch (GET /auth/callback, raw-query code/state)")
    class QueryCallbackDispatch {

        private static final String RETURN_URL = "/dashboard";
        private static final String RAW_ACCESS_TOKEN = "raw-access-token";
        private static final String RAW_ID_TOKEN = "raw-id-token";
        private static final String SUBJECT = "user-sub-1";
        private final Instant now = Instant.parse("2026-07-25T10:00:00Z");

        private PendingAuthorizationStore.InMemory pendingStore;
        private BindingCookieCodec bindingCodec;
        private SessionBinding sessionBinding;
        private BffRuntime runtime;
        private String state;
        private String bindingCookieHeader;

        @BeforeEach
        void setUp() {
            pendingStore = new PendingAuthorizationStore.InMemory(16);
            bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
            sessionBinding = serverBinding(new InMemorySessionStore(16));

            FlowContext flow = FlowContext.create(ORIGIN + CALLBACK_PATH);
            state = flow.state();
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, RETURN_URL, List.of("openid"),
                    now);
            pendingStore.store(pending);
            bindingCookieHeader = bindingCodec.toSetCookieHeader(pending.id()).split(";", 2)[0];

            runtime = callbackRuntime();
        }

        /** A {@code response_mode=query} callback: GET, {@code code}/{@code state} in the raw query, no body. */
        private BffRuntime.ReservedHttpRequest queryCallback(String rawQuery) {
            return new BffRuntime.ReservedHttpRequest(rawQuery, bindingCookieHeader, null, null, null, null, "GET");
        }

        @Test
        @DisplayName("GET query with code+state resolves the pending record and creates the session (302 + session cookie)")
        void shouldCompleteQueryModeLogin() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.CALLBACK,
                    queryCallback("code=auth-code&state=" + state), now);

            assertEquals(302, response.status(), "the query-mode code exchange completes the login");
            assertEquals(Optional.of(RETURN_URL), response.locationOptional());
            assertTrue(response.setCookieHeaders().stream()
                            .anyMatch(cookie -> cookie.startsWith(SessionCookieCodec.DEFAULT_COOKIE_NAME + "=")),
                    "the session cookie is set from the query-mode callback");
        }

        @Test
        @DisplayName("A duplicate code in the RAW QUERY is rejected 400 (BFF-13 defence survives the mode switch)")
        void shouldRejectDuplicateCodeInRawQuery() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.CALLBACK,
                    queryCallback("code=first&code=second&state=" + state), now);

            assertEquals(400, response.status(),
                    "a duplicated code reaches parse() uncollapsed and is rejected — a first-value-wins "
                            + "projection anywhere between the edge and the endpoint would have let it through");
            assertTrue(pendingStore.consume(bindingCodec.readRecordId(bindingCookieHeader).orElseThrow(), now)
                    .isPresent(), "the record is untouched — parse fails before binding resolution");
        }

        @Test
        @DisplayName("A duplicate state in the RAW QUERY is rejected 400 (BFF-13 defence survives the mode switch)")
        void shouldRejectDuplicateStateInRawQuery() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.CALLBACK,
                    queryCallback("code=auth-code&state=" + state + "&state=attacker-supplied"), now);

            assertEquals(400, response.status(),
                    "a duplicated state is rejected too — state is the parameter the binding check compares, "
                            + "so a collapsed map choosing either occurrence would be exploitable");
            assertTrue(pendingStore.consume(bindingCodec.readRecordId(bindingCookieHeader).orElseThrow(), now)
                    .isPresent(), "the record is untouched — parse fails before binding resolution");
        }

        /**
         * The fail-closed counterpart: the edge no longer buffers a body for the callback, so a stray
         * POST to that path arrives with no body at all. It must be an honest {@code 400}, never a
         * {@code 500} from a null body reaching the parse.
         */
        @Test
        @DisplayName("A stray POST to the callback arrives bodyless and is rejected 400, never 500")
        void shouldRejectStrayPostBodyless() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.CALLBACK,
                    new BffRuntime.ReservedHttpRequest("", bindingCookieHeader, null, null, null, null, "POST"), now);

            assertEquals(400, response.status(),
                    "an absent form body normalizes to the empty string and fails for a missing state");
        }

        private BffRuntime callbackRuntime() {
            CallbackEndpoint callback = new CallbackEndpoint((context, params) -> {
                Map<String, ClaimValue> accessClaims = new HashMap<>();
                accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
                AccessTokenContent access = new AccessTokenContent(accessClaims, RAW_ACCESS_TOKEN);
                Map<String, ClaimValue> idClaims = new HashMap<>();
                idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
                IdTokenContent id = new IdTokenContent(idClaims, RAW_ID_TOKEN);
                // This fixture asserts edge dispatch and the raw-query hand-off, never the refresh
                // path, so the exchange grants no refresh token — the shape an authorization server
                // that issues none produces, and the one that keeps this runtime's session inert.
                return new AuthorizationCodeFlow.AuthenticationResult(access, id, null);
            }, pendingStore, bindingCodec, sessionBinding, Duration.ofHours(1));

            SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionBinding,
                    (session, cookieHeader, instant) -> SessionAuthenticationStage.RefreshResult.mediate(
                            new SessionBinding.BoundSession(session, List.of())),
                    (returnUrl, scopes, instant) -> new SessionAuthenticationStage.LoginChallenge("/login", List.of()),
                    SessionAuthenticationStage.OnFailure.REAUTHENTICATE,
                    Clock.systemUTC());
            StepUpCoordinator stepUp = new StepUpCoordinator(
                    (session, challenge, instant) -> Optional.empty(),
                    challenge -> {
                        throw new AssertionError("engine step-up must not be reached");
                    },
                    pendingStore, bindingCodec, ORIGIN, ROOT_RETURN_TARGET, List.of());
            LoginFlow loginFlow = new LoginFlow(scopes -> {
                throw new AssertionError("engine authorize must not be reached");
            }, pendingStore, bindingCodec, ORIGIN, ROOT_RETURN_TARGET);
            BackchannelLogoutEndpoint backchannel = new BackchannelLogoutEndpoint(new BackchannelLogoutReceiver(
                            rawToken -> {
                                throw new AssertionError("engine verify must not be reached");
                            },
                            new LogoutTokenValidator(ORIGIN, "client", Duration.ofMinutes(2)), sessionBinding),
                    sessionBinding);
            UserInfoEndpoint userInfo = new UserInfoEndpoint(sessionBinding,
                    new ClaimAllowlistFilter(List.of("sub"), List.of("sub")),
                    session -> Map.of("sub", session.sub()));
            LoginInitiationEndpoint login = new LoginInitiationEndpoint(loginFlow, sessionBinding, ORIGIN,
                    engineFreeReturnTargetScopes());

            return new BffRuntime(sessionStage, new CsrfDefence(Set.of(ORIGIN)), stepUp, callback,
                    () -> logoutEndpoint(sessionBinding), backchannel, userInfo, login, ClientJwksEndpoint.withheld());
        }
    }

    /**
     * The client JWKS endpoint at the edge, driven over a live Vert.x server against a stub upstream.
     * <p>
     * Three properties are only observable here, one layer out from {@link ClientJwksEndpoint} and
     * {@link ReservedPathRegistry}: the path is answered on a host that is <em>not</em> the OIDC host,
     * it is answered to a request carrying no credential at all, and it is answered <em>ahead of</em> the
     * route table — the proxy route below claims the whole {@code /auth} prefix on every host, so a
     * request the carve-out missed would reach the stub upstream and be counted there.
     * <p>
     * Each form is a matched pair with the control request beside it: the same edge forwards an
     * ordinary {@code /auth} path to the upstream, so an upstream count of zero for the JWKS path is
     * attributable to the carve-out rather than to a route that forwards nothing.
     */
    @Nested
    @DisplayName("client JWKS endpoint: answered on every host, ahead of the route table, without a credential")
    class ClientJwksAtTheEdge {

        /** The path the endpoint is reserved at when {@code jwks_path} is omitted — {@link #fullOidc()} omits it. */
        private static final String DEFAULT_JWKS_PATH = "/auth/jwks";
        private static final String ROUTED_CONTROL_PATH = "/auth/not-reserved";
        /** A path outside the {@code /auth} route that nothing reserves: the gateway does not know it. */
        private static final String UNKNOWN_PATH = "/not-a-route";
        /** A host the identity provider might dial that is not the host of {@code oidc.redirect_uri}. */
        private static final String FOREIGN_HOST = "gateway.internal";

        /** Counts the requests that actually reached the stub upstream. */
        private final AtomicInteger upstreamHits = new AtomicInteger();

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpServer publishingFront;
        private HttpServer withheldFront;
        private HttpClient client;
        private ClientSigningKey signingKey;

        /** What the edge answered: the status, the response headers and the body as text. */
        private record EdgeAnswer(int status, MultiMap headers, String body) {
        }

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                    request.body().onComplete(_ -> {
                        upstreamHits.incrementAndGet();
                        request.response().end("upstream");
                    })).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
            signingKey = ClientSigningKey.resolve(null, ClientSigningKey.Purpose.CLIENT_AUTHENTICATION);
            publishingFront = startEdge(new ClientJwksEndpoint(signingKey.publicJwk()));
            withheldFront = startEdge(ClientJwksEndpoint.withheld());
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(publishingFront.close(), "the publishing edge front server to close");
            Awaits.teardown(withheldFront.close(), "the withheld edge front server to close");
            Awaits.teardown(upstream.close(), "the stub upstream server to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("GET on a foreign host without a credential is answered 200 with the key set, and never proxied")
        void shouldPublishOnAForeignHostWithoutACredential() throws Exception {
            EdgeAnswer answer = get(publishingFront, FOREIGN_HOST, DEFAULT_JWKS_PATH);

            assertAll("the published client key set",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals("no-store", answer.headers().get("Cache-Control")),
                    () -> assertEquals("application/json", answer.headers().get("Content-Type")),
                    () -> assertEquals(Map.of("keys", List.of(signingKey.publicJwk())),
                            new JsonObject(answer.body()).getMap(),
                            "the body is the key set holding exactly the one public key the runtime was built with"),
                    () -> assertEquals(0, upstreamHits.get(),
                            "the /auth proxy route never sees the reserved path"));
        }

        @Test
        @DisplayName("GET on the OIDC host is answered the same key set")
        void shouldPublishOnTheOidcHost() throws Exception {
            EdgeAnswer onOidcHost = get(publishingFront, OIDC_HOST, DEFAULT_JWKS_PATH);
            EdgeAnswer onForeignHost = get(publishingFront, FOREIGN_HOST, DEFAULT_JWKS_PATH);

            assertAll("one key set whatever host it is fetched on",
                    () -> assertEquals(200, onOidcHost.status()),
                    () -> assertEquals(onForeignHost.body(), onOidcHost.body()),
                    () -> assertEquals(0, upstreamHits.get(), "neither request is proxied"));
        }

        @Test
        @DisplayName("control: an ordinary path under the same prefix on the foreign host IS proxied")
        void shouldProxyAnOrdinaryPathUnderTheSamePrefix() throws Exception {
            EdgeAnswer answer = get(publishingFront, FOREIGN_HOST, ROUTED_CONTROL_PATH);

            assertAll("the /auth proxy route serves the foreign host",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals("upstream", answer.body()),
                    () -> assertEquals(1, upstreamHits.get(),
                            "so the zero upstream count for the JWKS path is the carve-out's doing"));
        }

        @Test
        @DisplayName("a method other than GET is answered 405 with Allow: GET by the endpoint, and never proxied")
        void shouldRefuseAnotherMethodAtTheEndpoint() throws Exception {
            EdgeAnswer answer = send(publishingFront, io.vertx.core.http.HttpMethod.POST, FOREIGN_HOST,
                    DEFAULT_JWKS_PATH);

            assertAll("POST on the JWKS path — a method the /auth proxy route itself allows",
                    () -> assertEquals(405, answer.status()),
                    () -> assertEquals("GET", answer.headers().get("Allow")),
                    () -> assertEquals("", answer.body()),
                    () -> assertEquals(0, upstreamHits.get(), "the reserved path is not handed to the route"));
        }

        /**
         * The withheld form must not be told apart from a path the gateway does not know: an anonymous
         * caller who could tell would read the client-authentication mode off the JWKS path. So the
         * answer is compared with the answer the same edge gives, to the same method on the same host,
         * for {@link #UNKNOWN_PATH} — a real unrouted request, not a restated literal. The comparison
         * covers every header line in both directions, so a header the unknown path does not carry
         * ({@code Cache-Control}, {@code Allow}) fails it as surely as a differing media type.
         * <p>
         * The three leading assertions are the control: they pin the reference to the route table's own
         * {@code 404} problem document, so two answers that agreed on some other shape would not pass.
         * The upstream count is what keeps the equality from being bought by releasing the path — the
         * {@code /auth} proxy route covers the JWKS path and allows both methods, and still sees
         * nothing.
         */
        @ParameterizedTest(name = "{1} on host {0}")
        @CsvSource({OIDC_HOST + ",GET", OIDC_HOST + ",POST", FOREIGN_HOST + ",GET", FOREIGN_HOST + ",POST"})
        @DisplayName("the withheld form is answered exactly as a path the gateway does not know, and the path still does not proxy")
        void shouldAnswerTheWithheldFormAsAnUnknownPath(String host, String methodName) throws Exception {
            io.vertx.core.http.HttpMethod method = io.vertx.core.http.HttpMethod.valueOf(methodName);

            EdgeAnswer unknown = send(withheldFront, method, host, UNKNOWN_PATH);
            EdgeAnswer withheld = send(withheldFront, method, host, DEFAULT_JWKS_PATH);

            Map<String, Object> unknownProblem = new JsonObject(unknown.body()).getMap();
            assertAll("the JWKS path in its withheld form",
                    () -> assertEquals(404, unknown.status(), "control: the reference is the unrouted 404"),
                    () -> assertEquals("application/problem+json", unknown.headers().get("Content-Type"),
                            "control: in the route table's problem media type"),
                    () -> assertEquals(Set.of("type", "title", "status"), unknownProblem.keySet(),
                            "control: carrying the route table's problem document"),
                    () -> assertEquals(unknown.status(), withheld.status(), "the same status"),
                    () -> assertEquals(headerLines(unknown), headerLines(withheld),
                            "the same header lines, media type included, and none the unknown path does not carry"),
                    () -> assertEquals(unknownProblem, new JsonObject(withheld.body()).getMap(),
                            "the same problem document, member for member"),
                    () -> assertEquals(unknown.body(), withheld.body(), "and the same bytes"),
                    () -> assertEquals(0, upstreamHits.get(),
                            "the path stays reserved: the route that covers it is never reached"));
        }

        @Test
        @DisplayName("control: the withheld edge proxies an ordinary path under the same prefix")
        void shouldProxyAnOrdinaryPathOnTheWithheldEdge() throws Exception {
            EdgeAnswer answer = get(withheldFront, FOREIGN_HOST, ROUTED_CONTROL_PATH);

            assertAll("the /auth proxy route is live on the withheld edge too",
                    () -> assertEquals(200, answer.status()),
                    () -> assertEquals(1, upstreamHits.get(),
                            "so the 404 above cannot be a route that reaches no upstream"));
        }

        private HttpServer startEdge(ClientJwksEndpoint jwksEndpoint) throws Exception {
            TokenValidator tokenValidator = TokenValidator.builder()
                    .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
            GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(List.of(authPrefixRoute(upstream.actualPort()))),
                    GatewayConfig.builder().version(1).oidc(fullOidc()).build(),
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()),
                    activeRuntime(serverBinding(new InMemorySessionStore(16)), jwksEndpoint),
                    EgressTrustProfiles.unconsulted(), PortalEndpoint.inert());
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            return Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
        }

        private EdgeAnswer get(HttpServer front, String host, String uri) throws Exception {
            return send(front, io.vertx.core.http.HttpMethod.GET, host, uri);
        }

        /** Sends a request carrying no {@code Authorization} and no {@code Cookie} — no credential of any kind. */
        private EdgeAnswer send(HttpServer front, io.vertx.core.http.HttpMethod method, String host, String uri)
                throws Exception {
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                    .setHost(host).setPort(front.actualPort())
                    .setMethod(method).setURI(uri);
            return Awaits.connect(client.request(options).compose(HttpClientRequest::send)
                            .compose(response -> response.body().map(body ->
                                    new EdgeAnswer(response.statusCode(), response.headers(), body.toString()))),
                    "the edge response to " + method + " " + uri + " on host " + host);
        }

        /**
         * Every response header line as lower-cased name to values, so two answers compare as whole
         * sets: a header present on one side only is a difference, whichever side it is on.
         */
        private static Map<String, List<String>> headerLines(EdgeAnswer answer) {
            Map<String, List<String>> lines = new TreeMap<>();
            for (String name : answer.headers().names()) {
                lines.put(name.toLowerCase(Locale.ROOT), answer.headers().getAll(name));
            }
            return lines;
        }

        /** A credential-free proxy route claiming the whole {@code /auth} prefix on every host. */
        private static ResolvedRoute authPrefixRoute(int upstreamPort) {
            return ResolvedRoute.builder()
                    .id("auth-proxy")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/auth").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                    .build();
        }
    }

    /**
     * What a request reaches when the client JWKS path is one the boot refuses as non-canonical.
     * <p>
     * The configuration validator refuses such a path, so no booted gateway carries one. The edge is
     * handed the {@code oidc} block directly here, which stages the state the refusal prevents: a
     * publishing key-set endpoint reserved at the non-canonical path, next to a proxy route that claims
     * the {@code /keys} prefix. Each request is answered in one of three ways, told apart by the answer
     * itself — the key set, the stub upstream, or the {@code 400} of the pre-route floor.
     * <p>
     * Two findings are pinned, under the {@code strict} and the {@code lenient} baseline alike:
     * <ul>
     *   <li><strong>A path carrying a percent-encoded character is reached by no spelling.</strong> The
     *       request spelled as configured decodes to the canonical spelling, which is not reserved and
     *       is proxied; a spelling that would decode to the configured string is refused as double
     *       encoding.</li>
     *   <li><strong>A path carrying a matrix parameter is not reached by the request spelled as
     *       configured</strong>, which the canonical-path guard refuses. It <em>is</em> reached by the
     *       spelling that percent-encodes the {@code ;}: the guard reads the raw path, and the registry
     *       matches the decoded one.</li>
     * </ul>
     * The paths and the request spellings are literals on purpose: each is the one spelling the claim
     * is about.
     */
    @Nested
    @DisplayName("a client JWKS path the boot refuses as non-canonical: which request spellings reach the key set")
    class NonCanonicalClientJwksPath {

        private static final String CANONICAL_PATH = "/keys/client";
        private static final String PERCENT_ENCODED_PATH = "/keys/%63lient";
        private static final String MATRIX_PARAMETER_PATH = "/keys/client;v=1";
        /** The host an identity provider dials: the client JWKS path is matched on every host. */
        private static final String FOREIGN_HOST = "gateway.internal";
        private static final String UPSTREAM_BODY = "upstream";

        /** How the edge answered one request. */
        enum Answered {

            /** The key-set endpoint answered with the published key set. */
            KEY_SET,

            /** The request was routed to the {@code /keys} proxy route and reached the stub upstream. */
            PROXIED,

            /** The pre-route floor refused the request with {@code 400}. */
            REFUSED
        }

        private final List<HttpServer> fronts = new ArrayList<>();

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpClient client;
        private ClientSigningKey signingKey;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                    request.body().onComplete(_ -> request.response().end(UPSTREAM_BODY)))
                    .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
            signingKey = ClientSigningKey.resolve(null, ClientSigningKey.Purpose.CLIENT_AUTHENTICATION);
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the HTTP client to close");
            for (HttpServer front : fronts) {
                Awaits.teardown(front.close(), "the edge front server to close");
            }
            Awaits.teardown(upstream.close(), "the stub upstream server to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @ParameterizedTest(name = "profile {0}")
        @CsvSource({"strict", "lenient"})
        @DisplayName("control: a canonical path is answered with the key set, and a sibling path is proxied")
        void shouldAnswerACanonicalPathWithTheKeySet(String profile) throws Exception {
            HttpServer front = startEdge(profile, CANONICAL_PATH);

            assertAll("the fixture tells the three answers apart",
                    () -> assertEquals(Answered.KEY_SET, answered(front, CANONICAL_PATH),
                            "the reserved path is answered by the key-set endpoint"),
                    () -> assertEquals(Answered.PROXIED, answered(front, "/keys/other"),
                            "an unreserved path under the same prefix reaches the upstream"));
        }

        @ParameterizedTest(name = "profile {0}: GET {1} is {2}")
        @CsvSource({
                "strict,/keys/%63lient,PROXIED",
                "strict,/keys/%2563lient,REFUSED",
                "strict,/keys/%25%36%33lient,REFUSED",
                "lenient,/keys/%63lient,PROXIED",
                "lenient,/keys/%2563lient,REFUSED",
                "lenient,/keys/%25%36%33lient,REFUSED"})
        @DisplayName("a path with a percent-encoded character: neither the configured spelling nor a double-encoded one reaches the key set")
        void shouldReachAPercentEncodedPathWithNoSpelling(String profile, String requestUri, Answered expected)
                throws Exception {
            HttpServer front = startEdge(profile, PERCENT_ENCODED_PATH);

            assertEquals(expected, answered(front, requestUri),
                    "the request path is decoded once and a second encoding layer is refused, so no "
                            + "canonical request path carries the '%' the configured path does");
        }

        @ParameterizedTest(name = "profile {0}: GET {1} is {2}")
        @CsvSource({
                "strict,/keys/client;v=1,REFUSED",
                "strict,/keys/client%3Bv=1,KEY_SET",
                "strict,/keys/client%3bv=1,KEY_SET",
                "lenient,/keys/client;v=1,REFUSED",
                "lenient,/keys/client%3Bv=1,KEY_SET",
                "lenient,/keys/client%3bv=1,KEY_SET"})
        @DisplayName("a path with a matrix parameter: the configured spelling is refused, the spelling with the ';' percent-encoded reaches the key set")
        void shouldReachAMatrixParameterPathOnlyWithTheSemicolonEncoded(String profile, String requestUri,
                Answered expected) throws Exception {
            HttpServer front = startEdge(profile, MATRIX_PARAMETER_PATH);

            assertEquals(expected, answered(front, requestUri),
                    "the canonical-path guard reads the raw path, the registry matches the decoded one");
        }

        /**
         * Starts an edge whose {@code oidc} block reserves {@code jwksPath} verbatim, under the given
         * {@code security_defaults.profile}, with a publishing key-set endpoint and the {@code /keys}
         * proxy route. The block is handed to the edge without passing the configuration validator.
         */
        private HttpServer startEdge(String profile, String jwksPath) throws Exception {
            TokenValidator tokenValidator = TokenValidator.builder()
                    .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
            OidcConfig oidc = OidcConfig.builder()
                    .redirectUri(ORIGIN + CALLBACK_PATH)
                    .clientAuthentication(OidcConfig.ClientAuthenticationSettings.builder().jwksPath(jwksPath).build())
                    .build();
            GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc)
                    .securityDefaults(new SecurityDefaultsConfig(profile, null, null, null)).build();
            GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(List.of(keysPrefixRoute(upstream.actualPort()))),
                    gatewayConfig, new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()),
                    activeRuntime(serverBinding(new InMemorySessionStore(16)),
                            new ClientJwksEndpoint(signingKey.publicJwk())),
                    EgressTrustProfiles.unconsulted(), PortalEndpoint.inert());
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            fronts.add(front);
            return front;
        }

        /** Sends a credential-free {@code GET} for {@code uri}, spelled exactly as given, and classifies the answer. */
        private Answered answered(HttpServer front, String uri) throws Exception {
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                    .setHost(FOREIGN_HOST).setPort(front.actualPort())
                    .setMethod(io.vertx.core.http.HttpMethod.GET).setURI(uri);
            return Awaits.connect(client.request(options).compose(HttpClientRequest::send)
                            .compose(response -> response.body().map(body ->
                                    classify(uri, response.statusCode(), body.toString()))),
                    "the edge response to GET " + uri);
        }

        private Answered classify(String uri, int status, String body) {
            if (status == 400) {
                return Answered.REFUSED;
            }
            if (status == 200 && UPSTREAM_BODY.equals(body)) {
                return Answered.PROXIED;
            }
            if (status == 200 && Map.of("keys", List.of(signingKey.publicJwk())).equals(new JsonObject(body).getMap())) {
                return Answered.KEY_SET;
            }
            throw new AssertionError("GET " + uri + " was answered " + status + ", which is none of the three "
                    + "answers this fixture produces");
        }

        /** A credential-free proxy route claiming the whole {@code /keys} prefix on every host. */
        private static ResolvedRoute keysPrefixRoute(int upstreamPort) {
            return ResolvedRoute.builder()
                    .id("keys-proxy")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/keys").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                    .build();
        }
    }

    /**
     * The edge half of {@code session_fallback}, driven over a live Vert.x server against a stub
     * upstream: the fixed CSRF defence and the {@code sheriff_auth_branch_total} meter both key off the
     * {@link AuthBranch} resolved once per request, never off the route's declared {@code require}
     * posture. A {@code session_fallback} route therefore runs the CSRF defence on its
     * {@code Authorization}-less session branch only — the bearer branch carries no ambient credential
     * and is not a CSRF surface — and meters every branch it selects, while a plain
     * {@code require: bearer} route beside it never produces a series.
     * <p>
     * The CSRF assertions are a matched pair: the same live-session unsafe request is rejected
     * {@code 403} under a foreign {@code Origin} and forwarded under the trusted one, so the rejection is
     * attributable to the CSRF defence rather than to anything else on the session branch.
     */
    @Nested
    @DisplayName("session_fallback: CSRF gates the session branch only, and the branch is metered")
    class SessionFallbackBranching {

        private static final String FALLBACK_ROUTE = "fallback";
        private static final String PLAIN_BEARER_ROUTE = "plain-bearer";
        private static final String FOREIGN_ORIGIN = "https://evil.example.com";
        private static final String ROUTE_TAG = "route";
        private static final String BRANCH_TAG = "branch";
        private static final int CSRF_REJECTED = 403;

        /** Counts the requests that actually reached the stub upstream. */
        private final AtomicInteger upstreamHits = new AtomicInteger();

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private SimpleMeterRegistry meterRegistry;
        private HttpServer upstream;
        private HttpServer front;
        private HttpClient client;
        /** A bearer token the edge's validator accepts — issued by the holder whose issuer config it trusts. */
        private String validBearerToken;
        private String sessionCookie;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            meterRegistry = new SimpleMeterRegistry();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request ->
                    request.body().onComplete(body -> {
                        upstreamHits.incrementAndGet();
                        request.response().end("upstream");
                    })).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");

            TestTokenHolder tokenHolder = TestTokenGenerators.accessTokens().next();
            validBearerToken = tokenHolder.getRawToken();
            TokenValidator tokenValidator = TokenValidator.builder()
                    .issuerConfig(tokenHolder.getIssuerConfig()).build();

            SessionStore store = new InMemorySessionStore(16);
            String sessionId = SessionRecord.newSessionId();
            store.create(SessionRecord.builder().sessionId(sessionId).accessToken("a").idToken("i").sub("sub")
                    .expiresAt(Instant.now().plus(Duration.ofHours(1))).build(), Instant.now());
            sessionCookie = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId;

            int upstreamPort = upstream.actualPort();
            RouteTable table = new RouteTable(List.of(
                    bearerRoute(FALLBACK_ROUTE, "/fallback", Boolean.TRUE, upstreamPort),
                    bearerRoute(PLAIN_BEARER_ROUTE, "/plain", null, upstreamPort)));
            GatewayEdgeRoute edge = new GatewayEdgeRoute(table,
                    GatewayConfig.builder().version(1).oidc(fullOidc()).build(),
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(), new SheriffMetrics(meterRegistry),
                    activeRuntime(serverBinding(store)), EgressTrustProfiles.unconsulted(), PortalEndpoint.inert());
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(front.close(), "the edge front server to close");
            Awaits.teardown(upstream.close(), "the stub upstream server to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("an unsafe request with a valid bearer and a foreign Origin takes the bearer branch and is not CSRF-rejected")
        void bearerBranchSkipsCsrfDefence() throws Exception {
            HttpClientResponse response = send(io.vertx.core.http.HttpMethod.POST, "/fallback/orders",
                    Map.of("Authorization", "Bearer " + validBearerToken, "Origin", FOREIGN_ORIGIN));

            assertAll(
                    () -> assertEquals(200, response.statusCode(),
                            "the bearer branch carries no ambient credential, so the CSRF defence does not run"),
                    () -> assertEquals(1, upstreamHits.get(), "the validated request is forwarded upstream"),
                    () -> assertEquals(1.0, branchCount(FALLBACK_ROUTE, AuthBranch.BEARER),
                            "the selected bearer branch is metered once"));
        }

        @Test
        @DisplayName("an unsafe request without Authorization, with a live session and a foreign Origin is CSRF-rejected")
        void sessionBranchEnforcesCsrfDefence() throws Exception {
            HttpClientResponse response = send(io.vertx.core.http.HttpMethod.POST, "/fallback/orders",
                    Map.of("Cookie", sessionCookie, "Origin", FOREIGN_ORIGIN));

            assertAll(
                    () -> assertEquals(CSRF_REJECTED, response.statusCode(),
                            "the session branch carries an ambient credential, so the CSRF defence runs"),
                    () -> assertEquals(0, upstreamHits.get(), "a CSRF rejection never reaches the upstream"),
                    () -> assertEquals(1.0, branchCount(FALLBACK_ROUTE, AuthBranch.SESSION),
                            "the branch is metered before the CSRF defence decides, so a rejection is counted too"));
        }

        @Test
        @DisplayName("control: the same live-session unsafe request under the trusted Origin is forwarded")
        void sessionBranchWithTrustedOriginPasses() throws Exception {
            HttpClientResponse response = send(io.vertx.core.http.HttpMethod.POST, "/fallback/orders",
                    Map.of("Cookie", sessionCookie, "Origin", ORIGIN));

            assertAll(
                    () -> assertEquals(200, response.statusCode(),
                            "the session branch serves the request, so the 403 above is the CSRF defence's own"),
                    () -> assertEquals(1, upstreamHits.get(), "the session-authenticated request is forwarded"));
        }

        @Test
        @DisplayName("a navigation without Authorization and without a session is redirected into the login")
        void sessionBranchChallengesUnauthenticatedNavigation() throws Exception {
            HttpClientResponse response = send(io.vertx.core.http.HttpMethod.GET, "/fallback/page",
                    Map.of("Accept", "text/html"));

            assertAll(
                    () -> assertEquals(302, response.statusCode(),
                            "an Authorization-less request takes the session branch, whose challenge is a redirect"),
                    () -> assertEquals("/login", response.getHeader("Location"),
                            "the target is the session stage's own login challenge, not a bearer 401"),
                    () -> assertEquals(0, upstreamHits.get(), "an unauthenticated request never reaches the upstream"));
        }

        @Test
        @DisplayName("sheriff_auth_branch_total carries both branches of the session_fallback route and no plain bearer series")
        void metersBothBranchesAndNothingForPlainBearerRoute() throws Exception {
            Map<String, String> bearer = Map.of("Authorization", "Bearer " + validBearerToken);

            int fallbackBearer = send(io.vertx.core.http.HttpMethod.GET, "/fallback/a", bearer).statusCode();
            int fallbackSession = send(io.vertx.core.http.HttpMethod.GET, "/fallback/b",
                    Map.of("Cookie", sessionCookie)).statusCode();
            int plainBearer = send(io.vertx.core.http.HttpMethod.GET, "/plain/c", bearer).statusCode();

            assertAll(
                    () -> assertEquals(List.of(200, 200, 200), List.of(fallbackBearer, fallbackSession, plainBearer),
                            "every request is served, so each one reached the metering point"),
                    () -> assertEquals(1.0, branchCount(FALLBACK_ROUTE, AuthBranch.BEARER),
                            "the bearer branch of the session_fallback route is metered"),
                    () -> assertEquals(1.0, branchCount(FALLBACK_ROUTE, AuthBranch.SESSION),
                            "the session branch of the session_fallback route is metered"),
                    () -> assertTrue(meterRegistry.find(SheriffMetrics.AUTH_BRANCH_TOTAL)
                                    .tag(ROUTE_TAG, PLAIN_BEARER_ROUTE).counters().isEmpty(),
                            "a route without session_fallback never produces an auth-branch series"));
        }

        /**
         * The count of the {@code sheriff_auth_branch_total} series for the route and branch, failing
         * the test when the series was never registered — an absent series is not a zero count.
         */
        private double branchCount(String route, AuthBranch branch) {
            Counter counter = meterRegistry.find(SheriffMetrics.AUTH_BRANCH_TOTAL)
                    .tags(ROUTE_TAG, route, BRANCH_TAG, branch.label()).counter();
            assertNotNull(counter, "no " + SheriffMetrics.AUTH_BRANCH_TOTAL + " series for route=" + route
                    + ", branch=" + branch.label());
            return counter.count();
        }

        private HttpClientResponse send(io.vertx.core.http.HttpMethod method, String uri, Map<String, String> headers)
                throws Exception {
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                    .setHost(OIDC_HOST).setPort(front.actualPort())
                    .setMethod(method).setURI(uri);
            return Awaits.connect(client.request(options).compose(request -> {
                headers.forEach(request::putHeader);
                return request.send();
            }), "the edge response to " + method + " " + uri);
        }

        private static ResolvedRoute bearerRoute(String id, String pathPrefix, @Nullable Boolean sessionFallback,
                int upstreamPort) {
            return ResolvedRoute.builder()
                    .id(id)
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                    .effectiveAuth(AuthConfig.builder().require(Require.BEARER).sessionFallback(sessionFallback).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                    .build();
        }
    }

    private static OidcConfig fullOidc() {
        OidcConfig.Logout logout = OidcConfig.Logout.builder()
                .path(LOGOUT_PATH)
                .postLogoutRedirectUri(ORIGIN + LOGOUT_RETURN_PATH)
                .backchannelPath(BACKCHANNEL_PATH)
                .build();
        return OidcConfig.builder()
                .redirectUri(ORIGIN + CALLBACK_PATH)
                .logout(logout)
                .userInfo(OidcConfig.UserInfo.builder().path(USER_INFO_PATH).build())
                .login(OidcConfig.Login.builder().path(LOGIN_PATH).build())
                .build();
    }

    /**
     * The server-mode seam over an in-memory store, standing in for whichever binding is in force.
     * Package-private so {@code GatewayEdgeRouteTest} can obtain an active runtime for the
     * cookie-mode carve-out assertions without duplicating this assembly.
     */
    static SessionBinding serverBinding(SessionStore store) {
        return new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1)));
    }

    /**
     * Assembles a fully-wired active runtime with engine-free test seams — every reserved-endpoint
     * handler is real, but the seams that would reach the confidential-client engine throw or no-op,
     * so the paths these tests drive (no-session, no-input, live-session short-circuit) never touch a
     * live IdP. The client JWKS handler is the withheld form: these fixtures hold no client key.
     * <p>
     * Package-private so {@code GatewayEdgeRouteTest} can assert the cookie-mode header carve-out —
     * which is gated on {@link BffRuntime#isActive()} — against a real active runtime rather than
     * duplicating this assembly.
     */
    static BffRuntime activeRuntime(SessionBinding binding) {
        return activeRuntime(binding, ClientJwksEndpoint.withheld());
    }

    /**
     * The same engine-free active runtime as {@link #activeRuntime(SessionBinding)}, with the client
     * JWKS handler the caller chooses — the publishing form over a real public key, or the withheld
     * form — for the tests that drive the client JWKS path through the edge.
     */
    private static BffRuntime activeRuntime(SessionBinding binding, ClientJwksEndpoint clientJwksEndpoint) {
        BindingCookieCodec bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(16);
        Duration ttl = Duration.ofHours(1);

        LoginFlow loginFlow = new LoginFlow(scopes -> {
            throw new AssertionError("engine authorize must not be reached");
        }, pendingStore, bindingCodec, ORIGIN, ROOT_RETURN_TARGET);

        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(binding,
                (session, cookieHeader, instant) -> SessionAuthenticationStage.RefreshResult.mediate(
                        new SessionBinding.BoundSession(session, List.of())),
                (returnUrl, scopes, instant) -> new SessionAuthenticationStage.LoginChallenge("/login", List.of()),
                SessionAuthenticationStage.OnFailure.REAUTHENTICATE,
                Clock.systemUTC());

        CsrfDefence csrf = new CsrfDefence(Set.of(ORIGIN));

        StepUpCoordinator stepUp = new StepUpCoordinator(
                (session, challenge, instant) -> Optional.empty(),
                challenge -> {
                    throw new AssertionError("engine step-up must not be reached");
                },
                pendingStore, bindingCodec, ORIGIN, ROOT_RETURN_TARGET, List.of());

        CallbackEndpoint callback = new CallbackEndpoint((context, params) -> {
            throw new AssertionError("engine exchange must not be reached");
        }, pendingStore, bindingCodec, binding, ttl);

        BackchannelLogoutEndpoint backchannel = new BackchannelLogoutEndpoint(new BackchannelLogoutReceiver(
                rawToken -> {
                    throw new AssertionError("engine verify must not be reached");
                },
                new LogoutTokenValidator(ORIGIN, "client", Duration.ofMinutes(2)), binding), binding);

        UserInfoEndpoint userInfo = new UserInfoEndpoint(binding,
                new ClaimAllowlistFilter(List.of("sub"), List.of("sub")),
                session -> Map.of("sub", session.sub()));

        LoginInitiationEndpoint login = new LoginInitiationEndpoint(loginFlow, binding, ORIGIN,
                engineFreeReturnTargetScopes());

        return new BffRuntime(sessionStage, csrf, stepUp, callback, () -> logoutEndpoint(binding), backchannel,
                userInfo, login, clientJwksEndpoint);
    }

    /**
     * The login-initiation scope resolver for the engine-free fixtures: an empty route table and empty
     * {@code oidc.scopes}, because no path these fixtures drive ever reaches the engine that would
     * consume a resolved scope set.
     */
    private static ReturnTargetScopes engineFreeReturnTargetScopes() {
        return new ReturnTargetScopes(new RouteTable(List.of()), ORIGIN, List.of());
    }

    private static LogoutEndpoint logoutEndpoint(SessionBinding binding) {
        EndSessionFlow endSessionFlow = new EndSessionFlow(
                new PostLogoutRedirectValidator(Set.of(ORIGIN + LOGOUT_RETURN_PATH)));
        RpInitiatedLogout rpInitiatedLogout = new RpInitiatedLogout(endSessionFlow, session -> {
        }, "https://idp.example.com/logout", ORIGIN + LOGOUT_RETURN_PATH, "/", Duration.ofMinutes(1));
        return new LogoutEndpoint(rpInitiatedLogout, binding);
    }

    private static ResolvedRoute sessionRoute() {
        return ResolvedRoute.builder()
                .id("s")
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/s").build())
                .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("https", "s.example", 443, ""))
                .build();
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied bean. {@link #get()} is
     * reached only by a request that validates a bearer token (the {@code session_fallback} bearer
     * branch and the plain {@code require: bearer} route); the remaining accessors are never used by
     * the edge and throw.
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
