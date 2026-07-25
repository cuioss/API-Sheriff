/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
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

        private final ReservedPathRegistry registry = ReservedPathRegistry.from(Optional.of(fullOidc()));

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
            assertDoesNotThrow(() -> newEdge(sessionTable, activeRuntime(new InMemorySessionStore(16))),
                    "A require:session route assembles through the wired SessionAuthenticationStage");
        }

        @Test
        @DisplayName("Should still register a single catch-all route with an active runtime")
        void shouldRegisterCatchAll() {
            GatewayEdgeRoute edge = newEdge(new RouteTable(List.of()), activeRuntime(new InMemorySessionStore(16)));
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

    @Nested
    @DisplayName("BffRuntime.dispatch routes each reserved path to its handler (not NO_ROUTE_MATCHED)")
    class ReservedDispatch {

        private final SessionStore store = new InMemorySessionStore(16);
        private final SessionCookieCodec codec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                Duration.ofHours(1));
        private final BffRuntime runtime = activeRuntime(store, codec);
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
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null), now);
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
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null), now);
            assertEquals(400, response.status());
            assertEquals("no-store", response.headers().get("Cache-Control"));
        }

        @Test
        @DisplayName("LOGIN with a live session short-circuits (302) to the validated return URL")
        void shouldDispatchLogin() {
            String sessionId = SessionRecord.newSessionId();
            store.create(SessionRecord.builder().sessionId(sessionId).accessToken("a").idToken("i").sub("sub")
                    .expiresAt(now.plus(Duration.ofHours(1))).build());
            String cookie = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId;
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.LOGIN,
                    new BffRuntime.ReservedHttpRequest("", cookie, null, "/home", null, null), now);
            assertEquals(302, response.status());
            assertTrue(response.locationOptional().isPresent());
        }

        private BffRuntime.ReservedHttpRequest request(String cookie, String claims) {
            return new BffRuntime.ReservedHttpRequest("", cookie, claims, null, null, null);
        }
    }

    private static OidcConfig fullOidc() {
        OidcConfig.Logout logout = OidcConfig.Logout.builder()
                .path(Optional.of(LOGOUT_PATH))
                .postLogoutRedirectUri(Optional.of(ORIGIN + LOGOUT_RETURN_PATH))
                .backchannelPath(Optional.of(BACKCHANNEL_PATH))
                .build();
        return OidcConfig.builder()
                .redirectUri(Optional.of(ORIGIN + CALLBACK_PATH))
                .logout(Optional.of(logout))
                .userInfo(Optional.of(OidcConfig.UserInfo.builder().path(Optional.of(USER_INFO_PATH)).build()))
                .login(Optional.of(OidcConfig.Login.builder().path(Optional.of(LOGIN_PATH)).build()))
                .build();
    }

    private static BffRuntime activeRuntime(SessionStore store) {
        return activeRuntime(store, new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                Duration.ofHours(1)));
    }

    /**
     * Assembles a fully-wired active runtime with engine-free test seams — every reserved-endpoint
     * handler is real, but the seams that would reach the confidential-client engine throw or no-op,
     * so the paths these tests drive (no-session, no-input, live-session short-circuit) never touch a
     * live IdP.
     */
    private static BffRuntime activeRuntime(SessionStore store, SessionCookieCodec codec) {
        BindingCookieCodec bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(16);
        Duration ttl = Duration.ofHours(1);

        LoginFlow loginFlow = new LoginFlow(() -> {
            throw new AssertionError("engine authorize must not be reached");
        }, pendingStore, bindingCodec, ORIGIN);

        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(store, codec,
                (session, instant) -> session,
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
        }, pendingStore, bindingCodec, store, codec, ttl);

        BackchannelLogoutEndpoint backchannel = new BackchannelLogoutEndpoint(new BackchannelLogoutReceiver(
                rawToken -> {
                    throw new AssertionError("engine verify must not be reached");
                },
                new LogoutTokenValidator(ORIGIN, "client", Duration.ofMinutes(2)), store));

        UserInfoEndpoint userInfo = new UserInfoEndpoint(store, codec,
                new ClaimAllowlistFilter(List.of("sub"), List.of("sub")),
                session -> Map.of("sub", session.sub()));

        LoginInitiationEndpoint login = new LoginInitiationEndpoint(loginFlow, store, codec, ORIGIN);

        return new BffRuntime(sessionStage, csrf, stepUp, callback, () -> logoutEndpoint(store, codec), backchannel,
                userInfo, login);
    }

    private static LogoutEndpoint logoutEndpoint(SessionStore store, SessionCookieCodec codec) {
        EndSessionFlow endSessionFlow = new EndSessionFlow(
                new PostLogoutRedirectValidator(Set.of(ORIGIN + LOGOUT_RETURN_PATH)));
        RpInitiatedLogout rpInitiatedLogout = new RpInitiatedLogout(endSessionFlow, session -> {
        }, "https://idp.example.com/logout", ORIGIN + LOGOUT_RETURN_PATH, "/", Duration.ofMinutes(1));
        return new LogoutEndpoint(rpInitiatedLogout, store, codec);
    }

    private static ResolvedRoute sessionRoute() {
        return ResolvedRoute.builder()
                .id("s")
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/s").build())
                .effectiveAuth(AuthConfig.builder().require("session").build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(Optional.of(new ResolvedUpstream("https", "s.example", 443, "")))
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
