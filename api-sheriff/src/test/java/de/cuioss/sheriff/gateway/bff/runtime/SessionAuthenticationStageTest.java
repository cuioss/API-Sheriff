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
package de.cuioss.sheriff.gateway.bff.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.LoginChallenge;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SessionAuthenticationStage — stage 4 require:session runtime")
class SessionAuthenticationStageTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Instant SESSION_EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String SESSION_ID = "opaque-session-id";
    private static final String MEDIATED_TOKEN = "mediated-access-token";
    private static final String REFRESHED_TOKEN = "refreshed-access-token";
    private static final String REQUIRED_SCOPE = "orders:read";
    private static final String LOGIN_LOCATION = "https://idp.example/authorize?client_id=sheriff";
    private static final String BINDING_COOKIE = "__Host-sheriff-binding=binding-value; Path=/; Secure; HttpOnly; SameSite=Lax";
    private static final String RESEAL_COOKIE = "__Host-sheriff-session=re-sealed-value; Path=/; Secure; HttpOnly; SameSite=Lax";

    private static final SessionCookieCodec CODEC =
            new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1));

    @Nested
    @DisplayName("Live session")
    class LiveSession {

        @Test
        @DisplayName("injects the mediated access token as the upstream bearer for a live session")
        void injectsMediatedBearer() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(MEDIATED_TOKEN), request.mediatedBearer(),
                    "the live session's mediated token is recorded for automatic upstream injection");
            assertTrue(request.shortCircuitStatus().isEmpty(), "an authenticated request flows on, never short-circuits");
        }

        @Test
        @DisplayName("injects the refreshed token when the single-flight refresh seam rotates it near expiry")
        void injectsRefreshedTokenAfterRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage.TokenRefresh rotating =
                    (session, cookieHeader, now) -> Optional.of(
                            new SessionBinding.BoundSession(rebind(session, REFRESHED_TOKEN), List.of()));
            SessionAuthenticationStage stage = stage(binding, rotating, scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            stage.process(request);

            assertEquals(Optional.of(REFRESHED_TOKEN), request.mediatedBearer(),
                    "the token injected is the one the refresh seam returned, not the pre-refresh token");
        }

        @Test
        @DisplayName("writes the re-seal Set-Cookie to the response when the refresh re-binds the session")
        void emitsResealSetCookieOnRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage.TokenRefresh resealing =
                    (session, cookieHeader, now) -> Optional.of(new SessionBinding.BoundSession(
                            rebind(session, REFRESHED_TOKEN), List.of(RESEAL_COOKIE)));
            SessionAuthenticationStage stage = stage(binding, resealing, scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            stage.process(request);

            assertEquals(List.of(RESEAL_COOKIE), request.responseSetCookies(),
                    "a cookie-mode re-seal must reach the browser on the very response it was produced for");
            assertEquals(Optional.of(REFRESHED_TOKEN), request.mediatedBearer(),
                    "the re-seal is emitted before the mediated bearer is injected");
        }

        @Test
        @DisplayName("passes and injects the bearer when the mediated token grants every required scope")
        void passesWhenRequiredScopesSatisfied() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of(REQUIRED_SCOPE)), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(MEDIATED_TOKEN), request.mediatedBearer(),
                    "a scope-satisfying session still mediates its bearer");
        }
    }

    @Nested
    @DisplayName("Scope enforcement")
    class ScopeEnforcement {

        @Test
        @DisplayName("rejects 403 SCOPE_MISSING when the mediated token lacks a required scope")
        void rejectsMissingScopeWith403() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), scopesDenied(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of(REQUIRED_SCOPE)), navigationHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(), "a scope shortfall is a 403 SCOPE_MISSING");
            assertTrue(request.mediatedBearer().isEmpty(), "no bearer is mediated when a required scope is missing");
        }
    }

    @Nested
    @DisplayName("Unauthenticated request")
    class Unauthenticated {

        @Test
        @DisplayName("redirects a navigation request 302 into the auth-code flow")
        void redirectsNavigationIntoLogin() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(302), request.shortCircuitStatus(), "an unauthenticated navigation is short-circuited 302");
            assertEquals(LOGIN_LOCATION, request.responseHeaders().get("Location"),
                    "the redirect targets the login-initiation location");
            assertEquals(List.of(BINDING_COOKIE), request.responseSetCookies(),
                    "the browser-binding Set-Cookie is emitted with the redirect");
            assertTrue(request.mediatedBearer().isEmpty(), "an unauthenticated request mediates no bearer");
        }

        @Test
        @DisplayName("challenges an XHR request 401 TOKEN_MISSING rather than redirecting")
        void challengesXhrWith401() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "an unauthenticated non-navigation request is a 401 application/problem+json challenge");
            assertTrue(request.shortCircuitStatus().isEmpty(), "the XHR challenge does not short-circuit into a redirect");
        }

        @Test
        @DisplayName("treats an expired session as unauthenticated")
        void treatsExpiredSessionAsUnauthenticated() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN, NOW.minusSeconds(1)));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "an expired session no longer authenticates");
        }

        @Test
        @DisplayName("treats a request without a session cookie as unauthenticated")
        void treatsAbsentCookieAsUnauthenticated() {
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), identityRefresh(),
                    scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), Map.of("accept", List.of("application/json")));

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "a request carrying no session cookie is unauthenticated");
        }

        @Test
        @DisplayName("treats a failed refresh as unauthenticated rather than mediating the pre-refresh token")
        void treatsFailedRefreshAsUnauthenticated() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, failedRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "a refresh failure destroyed the session — the request gets the same 401 as a missing session");
            assertTrue(request.mediatedBearer().isEmpty(),
                    "the revoked session's pre-refresh token is never injected upstream");
        }

        @Test
        @DisplayName("clears the browser's session cookie when a refresh failure destroys the session")
        void clearsTheCookieOnFailedRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, failedRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), xhrHeaders());

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(List.of(binding.clearingSetCookieHeader()), request.responseSetCookies(),
                    "the stale cookie is cleared so the browser stops presenting a destroyed session");
        }

        @Test
        @DisplayName("emits BOTH the clearing cookie and the login-challenge cookie when a refresh fails on an HTML navigation")
        void retainsBothCookiesOnFailedRefreshNavigation() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, failedRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            stage.process(request);

            assertEquals(List.of(binding.clearingSetCookieHeader(), BINDING_COOKIE), request.responseSetCookies(),
                    "both Set-Cookie values must reach the browser: the clearing cookie drops the revoked "
                            + "session's cookie, the binding cookie carries the new login — they name different "
                            + "cookies, so neither may overwrite or truncate the other");
        }

        @Test
        @DisplayName("retains every Set-Cookie a binding returns, never only the first")
        void retainsEveryBindingSetCookie() {
            String secondCookie = "__Host-sheriff-extra=second-value; Path=/; Secure; HttpOnly; SameSite=Lax";
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage.TokenRefresh multiCookieRefresh =
                    (session, cookieHeader, now) -> Optional.of(new SessionBinding.BoundSession(
                            rebind(session, REFRESHED_TOKEN), List.of(RESEAL_COOKIE, secondCookie)));
            SessionAuthenticationStage stage = stage(binding, multiCookieRefresh, scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            stage.process(request);

            assertEquals(List.of(RESEAL_COOKIE, secondCookie), request.responseSetCookies(),
                    "Set-Cookie is multi-valued — a binding returning two cookies must not be truncated to one");
        }

        @Test
        @DisplayName("re-drives a navigation request through login when the refresh fails")
        void redrivesNavigationOnFailedRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, failedRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(302), request.shortCircuitStatus(),
                    "a navigation whose refresh failed runs the same negotiation as a missing session");
            assertEquals(LOGIN_LOCATION, request.responseHeaders().get("Location"));
            assertTrue(request.mediatedBearer().isEmpty(), "no bearer is mediated from the destroyed session");
        }
    }

    @Nested
    @DisplayName("Edge short-circuit contract (honored by GatewayEdgeRoute; edge-render IT-covered)")
    class EdgeShortCircuitContract {

        /**
         * Pins the stage-side half of the edge fix in {@code GatewayEdgeRoute.handle}: after the
         * authentication stage runs, an unauthenticated navigation must leave the request short-circuited
         * (302) with a {@code Location}, and — critically — mediate NO upstream bearer. The edge honors
         * that short-circuit and renders the 302 instead of proceeding to the forward stage, so nothing is
         * forwarded to the upstream. The 302 actually written by the edge (rather than a 200 fall-through to
         * the upstream) is asserted end-to-end by the native BffSessionLoginIT / BffSessionMediationIT ITs.
         */
        @Test
        @DisplayName("an unauthenticated navigation leaves a 302 short-circuit and no bearer, so the edge redirects instead of forwarding")
        void navigationLeavesShortCircuitAndNoBearer() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), navigationHeaders());

            stage.process(request);

            assertEquals(Optional.of(302), request.shortCircuitStatus(),
                    "the short-circuit the edge must honor is present after the auth stage runs");
            assertEquals(LOGIN_LOCATION, request.responseHeaders().get("Location"),
                    "the redirect target the edge renders is set on the request");
            assertTrue(request.mediatedBearer().isEmpty(),
                    "no bearer is mediated, so there is nothing for the edge to forward to the upstream");
        }

        /**
         * The non-navigation counterpart: an unauthenticated XHR raises the 401 problem path (no
         * short-circuit), so the edge takes its rejection/problem branch rather than the redirect.
         */
        @Test
        @DisplayName("an unauthenticated XHR raises 401 TOKEN_MISSING with no short-circuit, so the edge takes the problem path not the redirect")
        void xhrRaises401WithNoShortCircuit() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = sessionRequest(authConfig(List.of()), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "an unauthenticated non-navigation request is the 401 application/problem+json path");
            assertTrue(request.shortCircuitStatus().isEmpty(),
                    "no short-circuit is set, so the edge renders the problem response rather than a redirect");
        }
    }

    @Nested
    @DisplayName("Preconditions")
    class Preconditions {

        @Test
        @DisplayName("rejects a request whose route was not selected at stage 2")
        void rejectsUnselectedRoute() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), scopesGranted(), redirectLogin());
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.GET).requestPath("/app").queryParameters(Map.of()).headers(Map.of()).build();

            assertThrows(IllegalStateException.class, () -> stage.process(request));
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), scopesGranted(), redirectLogin());

            assertThrows(NullPointerException.class, () -> stage.process(null));
        }
    }

    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh refresh, SessionAuthenticationStage.GrantedScopes scopes,
            SessionAuthenticationStage.LoginInitiation login) {
        return new SessionAuthenticationStage(binding, refresh, scopes, login, CLOCK);
    }

    private static SessionAuthenticationStage.TokenRefresh identityRefresh() {
        return (session, cookieHeader, now) ->
                Optional.of(new SessionBinding.BoundSession(session, List.of()));
    }

    /** A refresh seam that failed and destroyed the session — the empty outcome the stage negotiates on. */
    private static SessionAuthenticationStage.TokenRefresh failedRefresh() {
        return (session, cookieHeader, now) -> Optional.empty();
    }

    private static SessionAuthenticationStage.GrantedScopes scopesGranted() {
        return (accessToken, requiredScopes) -> true;
    }

    private static SessionAuthenticationStage.GrantedScopes scopesDenied() {
        return (accessToken, requiredScopes) -> false;
    }

    private static SessionAuthenticationStage.LoginInitiation redirectLogin() {
        return (returnUrl, now) -> new LoginChallenge(LOGIN_LOCATION, List.of(BINDING_COOKIE));
    }

    private static SessionBinding emptyBinding() {
        return new ServerSessionBinding(new InMemorySessionStore(16), CODEC);
    }

    private static SessionBinding bindingWith(SessionRecord session) {
        InMemorySessionStore store = new InMemorySessionStore(16);
        store.create(session);
        return new ServerSessionBinding(store, CODEC);
    }

    private static SessionRecord session(String accessToken) {
        return session(accessToken, SESSION_EXPIRY);
    }

    private static SessionRecord session(String accessToken, Instant expiresAt) {
        return SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken(accessToken)
                .refreshToken(Optional.of("refresh-token"))
                .idToken("id-token")
                .sub("subject")
                .sid(Optional.of("idp-sid"))
                .expiresAt(expiresAt)
                .build();
    }

    private static SessionRecord rebind(SessionRecord session, String accessToken) {
        return SessionRecord.builder()
                .sessionId(session.sessionId())
                .accessToken(accessToken)
                .refreshToken(session.refreshToken())
                .idToken(session.idToken())
                .sub(session.sub())
                .sid(session.sid())
                .expiresAt(session.expiresAt())
                .acr(session.acr())
                .authTime(session.authTime())
                .build();
    }

    private static AuthConfig authConfig(List<String> requiredScopes) {
        return AuthConfig.builder().require("session").requiredScopes(requiredScopes).build();
    }

    private static Map<String, List<String>> navigationHeaders() {
        return Map.of("cookie", List.of(cookie()), "accept", List.of("text/html,application/xhtml+xml"));
    }

    private static Map<String, List<String>> xhrHeaders() {
        return Map.of("cookie", List.of(cookie()), "accept", List.of("application/json"));
    }

    private static String cookie() {
        return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + SESSION_ID;
    }

    private static PipelineRequest sessionRequest(AuthConfig auth, Map<String, List<String>> headers) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/app/orders")
                .queryParameters(Map.of())
                .headers(headers)
                .build();
        request.canonicalPath("/app/orders");
        request.selectedRoute(RouteRuntime.builder().id("orders").effectiveAuth(auth).build());
        return request;
    }
}
