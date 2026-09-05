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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
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
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
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
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        void shouldBootSessionRouteWithActiveRuntime() {
            RouteTable sessionTable = new RouteTable(List.of(sessionRoute()));
            assertDoesNotThrow(() -> newEdge(sessionTable, activeRuntime(serverBinding(new InMemorySessionStore(16)))),
                    "A require:session route assembles through the wired SessionAuthenticationStage");
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
                    new SheriffMetrics(new SimpleMeterRegistry()), runtime);
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
                    activeRuntime(serverBinding(new InMemorySessionStore(16))));
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
            int status = statusOf(USER_INFO_PATH + "?return_to=%2Fhome");

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
     * value the edge fails to extract simply degrades to {@link LoginFlow#DEFAULT_RETURN_URL}. That
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
                    activeRuntime(serverBinding(store)));
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
            assertEquals(LoginFlow.DEFAULT_RETURN_URL, response.getHeader("Location"),
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
            assertEquals(302, response.status());
            assertTrue(response.locationOptional().isPresent());
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
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, RETURN_URL, now);
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
                return new AuthorizationCodeFlow.AuthenticationResult(access, id);
            }, pendingStore, bindingCodec, sessionBinding, Duration.ofHours(1));

            SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionBinding,
                    (session, cookieHeader, instant) ->
                            Optional.of(new SessionBinding.BoundSession(session, List.of())),
                    (token, scopes) -> true,
                    (returnUrl, instant) -> new SessionAuthenticationStage.LoginChallenge("/login", List.of()),
                    Clock.systemUTC());
            StepUpCoordinator stepUp = new StepUpCoordinator(
                    (session, challenge, instant) -> Optional.empty(),
                    challenge -> {
                        throw new AssertionError("engine step-up must not be reached");
                    },
                    pendingStore, bindingCodec, ORIGIN);
            LoginFlow loginFlow = new LoginFlow(() -> {
                throw new AssertionError("engine authorize must not be reached");
            }, pendingStore, bindingCodec, ORIGIN);
            BackchannelLogoutEndpoint backchannel = new BackchannelLogoutEndpoint(new BackchannelLogoutReceiver(
                            rawToken -> {
                                throw new AssertionError("engine verify must not be reached");
                            },
                            new LogoutTokenValidator(ORIGIN, "client", Duration.ofMinutes(2)), sessionBinding),
                    sessionBinding);
            UserInfoEndpoint userInfo = new UserInfoEndpoint(sessionBinding,
                    new ClaimAllowlistFilter(List.of("sub"), List.of("sub")),
                    session -> Map.of("sub", session.sub()));
            LoginInitiationEndpoint login = new LoginInitiationEndpoint(loginFlow, sessionBinding, ORIGIN);

            return new BffRuntime(sessionStage, new CsrfDefence(Set.of(ORIGIN)), stepUp, callback,
                    () -> logoutEndpoint(sessionBinding), backchannel, userInfo, login);
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
     * live IdP.
     * <p>
     * Package-private so {@code GatewayEdgeRouteTest} can assert the cookie-mode header carve-out —
     * which is gated on {@link BffRuntime#isActive()} — against a real active runtime rather than
     * duplicating this assembly.
     */
    static BffRuntime activeRuntime(SessionBinding binding) {
        BindingCookieCodec bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(16);
        Duration ttl = Duration.ofHours(1);

        LoginFlow loginFlow = new LoginFlow(() -> {
            throw new AssertionError("engine authorize must not be reached");
        }, pendingStore, bindingCodec, ORIGIN);

        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(binding,
                (session, cookieHeader, instant) ->
                        Optional.of(new SessionBinding.BoundSession(session, List.of())),
                (token, scopes) -> true,
                (returnUrl, instant) -> new SessionAuthenticationStage.LoginChallenge("/login", List.of()),
                Clock.systemUTC());

        CsrfDefence csrf = new CsrfDefence(Set.of(ORIGIN));

        StepUpCoordinator stepUp = new StepUpCoordinator(
                (session, challenge, instant) -> Optional.empty(),
                challenge -> {
                    throw new AssertionError("engine step-up must not be reached");
                },
                pendingStore, bindingCodec, ORIGIN);

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

        LoginInitiationEndpoint login = new LoginInitiationEndpoint(loginFlow, binding, ORIGIN);

        return new BffRuntime(sessionStage, csrf, stepUp, callback, () -> logoutEndpoint(binding), backchannel,
                userInfo, login);
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
     * Minimal {@link Instance} test double resolving to a single supplied bean. The assembly tests
     * exercise only construction; no require:session route validates a bearer token, so
     * {@link #get()} is never called and the remaining accessors throw.
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
