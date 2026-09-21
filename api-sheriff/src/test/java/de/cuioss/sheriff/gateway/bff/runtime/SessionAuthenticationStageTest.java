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
package de.cuioss.sheriff.gateway.bff.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.LoginChallenge;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.OnFailure;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.RefreshResult;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.QueryParameter;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import org.jspecify.annotations.Nullable;
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
    private static final String NEEDED_SCOPE = "orders:read";
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
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

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
                    (session, cookieHeader, now) -> RefreshResult.mediate(
                            new SessionBinding.BoundSession(rebind(session, REFRESHED_TOKEN), List.of()));
            SessionAuthenticationStage stage = stage(binding, rotating, redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertEquals(Optional.of(REFRESHED_TOKEN), request.mediatedBearer(),
                    "the token injected is the one the refresh seam returned, not the pre-refresh token");
        }

        @Test
        @DisplayName("writes the re-seal Set-Cookie to the response when the refresh re-binds the session")
        void emitsResealSetCookieOnRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage.TokenRefresh resealing =
                    (session, cookieHeader, now) -> RefreshResult.mediate(new SessionBinding.BoundSession(
                            rebind(session, REFRESHED_TOKEN), List.of(RESEAL_COOKIE)));
            SessionAuthenticationStage stage = stage(binding, resealing, redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertEquals(List.of(RESEAL_COOKIE), request.responseSetCookies(),
                    "a cookie-mode re-seal must reach the browser on the very response it was produced for");
            assertEquals(Optional.of(REFRESHED_TOKEN), request.mediatedBearer(),
                    "the re-seal is emitted before the mediated bearer is injected");
        }

        @Test
        @DisplayName("mediates a live session on a route with non-empty needed scopes without any scope check")
        void mediatesWithoutScopeCheckOnScopedRoute() {
            // The mediated token is an opaque string granting nothing: were any scope check left on the
            // session path it could only fail, so a clean mediation proves none runs.
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(NEEDED_SCOPE), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(MEDIATED_TOKEN), request.mediatedBearer(),
                    "a session route requests its needed scopes at login and never enforces them per request");
            assertTrue(request.shortCircuitStatus().isEmpty());
        }
    }

    @Nested
    @DisplayName("token_relay")
    class TokenRelay {

        @Test
        @DisplayName("token_relay: false resolves the live session but mediates no bearer")
        void liveSessionMediatesNoBearerWhenRelayOff() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders(), false);

            assertDoesNotThrow(() -> stage.process(request));

            assertAll("the session is accepted, but no Authorization reaches the upstream",
                    () -> assertTrue(request.mediatedBearer().isEmpty()),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(),
                            "an authenticated request flows on, never short-circuits"));
        }

        @Test
        @DisplayName("token_relay: false still emits the refresh re-bind cookie")
        void relayOffStillEmitsRebindCookie() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage.TokenRefresh resealing =
                    (session, cookieHeader, now) -> RefreshResult.mediate(new SessionBinding.BoundSession(
                            rebind(session, REFRESHED_TOKEN), List.of(RESEAL_COOKIE)));
            SessionAuthenticationStage stage = stage(binding, resealing, redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders(), false);

            stage.process(request);

            assertAll(
                    () -> assertEquals(List.of(RESEAL_COOKIE), request.responseSetCookies(),
                            "the session is still refreshed and re-bound"),
                    () -> assertTrue(request.mediatedBearer().isEmpty()));
        }

        @Test
        @DisplayName("token_relay: false still redirects an unauthenticated navigation 302 into login")
        void relayOffStillRedirectsNavigation() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders(), false);

            stage.process(request);

            assertAll(
                    () -> assertEquals(Optional.of(302), request.shortCircuitStatus()),
                    () -> assertEquals(LOGIN_LOCATION, request.responseHeaders().get("Location")));
        }

        @Test
        @DisplayName("token_relay: false still challenges an unauthenticated XHR 401")
        void relayOffStillChallengesXhr() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders(), false);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType());
        }

        @Test
        @DisplayName("token_relay: false still clears the cookie of a session the refresh ended")
        void relayOffStillClearsEndedSessionCookie() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders(), false);

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(List.of(binding.clearingSetCookieHeader()), request.responseSetCookies());
        }

        @Test
        @DisplayName("token_relay absent or true mediates the session's bearer")
        void relayAbsentOrTrueMediates() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest absent = sessionRequest(Set.of(), navigationHeaders(), null);
            PipelineRequest explicit = sessionRequest(Set.of(), navigationHeaders(), true);

            stage.process(absent);
            stage.process(explicit);

            assertAll(
                    () -> assertEquals(Optional.of(MEDIATED_TOKEN), absent.mediatedBearer()),
                    () -> assertEquals(Optional.of(MEDIATED_TOKEN), explicit.mediatedBearer()));
        }
    }

    @Nested
    @DisplayName("Unauthenticated request")
    class Unauthenticated {

        @Test
        @DisplayName("redirects a navigation request 302 into the auth-code flow")
        void redirectsNavigationIntoLogin() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(302), request.shortCircuitStatus(), "an unauthenticated navigation is short-circuited 302");
            assertEquals(LOGIN_LOCATION, request.responseHeaders().get("Location"),
                    "the redirect targets the login-initiation location");
            assertEquals(List.of(BINDING_COOKIE), request.responseSetCookies(),
                    "the browser-binding Set-Cookie is emitted with the redirect");
            assertTrue(request.mediatedBearer().isEmpty(), "an unauthenticated request mediates no bearer");
        }

        @Test
        @DisplayName("requests exactly the selected route's needed scopes in the login challenge")
        void requestsRouteNeededScopesAtLogin() {
            Set<String> neededScopes = Set.of("openid", "profile", NEEDED_SCOPE);
            AtomicReference<Collection<String>> requested = new AtomicReference<>();
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), (returnUrl, scopes, now) -> {
                requested.set(scopes);
                return new LoginChallenge(LOGIN_LOCATION, List.of(BINDING_COOKIE));
            });
            PipelineRequest request = sessionRequest(neededScopes, navigationHeaders());

            stage.process(request);

            assertEquals(neededScopes, Set.copyOf(requested.get()),
                    "the login requests the route's boot-derived neededScopes, the set the bearer check also reads");
        }

        @Test
        @DisplayName("requests the needed scopes again when a failed refresh re-drives the login")
        void requestsRouteNeededScopesOnReauthentication() {
            Set<String> neededScopes = Set.of("openid", NEEDED_SCOPE);
            AtomicReference<Collection<String>> requested = new AtomicReference<>();
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), sessionEndedRefresh(),
                    (returnUrl, scopes, now) -> {
                        requested.set(scopes);
                        return new LoginChallenge(LOGIN_LOCATION, List.of(BINDING_COOKIE));
                    });
            PipelineRequest request = sessionRequest(neededScopes, navigationHeaders());

            stage.process(request);

            assertEquals(neededScopes, Set.copyOf(requested.get()));
        }

        @Test
        @DisplayName("challenges an XHR request 401 TOKEN_MISSING rather than redirecting")
        void challengesXhrWith401() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "an unauthenticated non-navigation request is a 401 application/problem+json challenge");
            assertTrue(request.shortCircuitStatus().isEmpty(), "the XHR challenge does not short-circuit into a redirect");
        }

        @Test
        @DisplayName("treats an expired session as unauthenticated")
        void treatsExpiredSessionAsUnauthenticated() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN, NOW.minusSeconds(1)));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "an expired session no longer authenticates");
        }

        @Test
        @DisplayName("treats a request without a session cookie as unauthenticated")
        void treatsAbsentCookieAsUnauthenticated() {
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), identityRefresh(),
                    redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), Map.of("accept", List.of("application/json")));

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "a request carrying no session cookie is unauthenticated");
        }

        @Test
        @DisplayName("treats a failed refresh as unauthenticated rather than mediating the pre-refresh token")
        void treatsFailedRefreshAsUnauthenticated() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

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
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(List.of(binding.clearingSetCookieHeader()), request.responseSetCookies(),
                    "the stale cookie is cleared so the browser stops presenting a destroyed session");
        }

        @Test
        @DisplayName("emits BOTH the clearing cookie and the login-challenge cookie when a refresh failure ends the session on an HTML navigation")
        void retainsBothCookiesWhenRefreshFailureEndsSessionOnNavigation() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

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
                    (session, cookieHeader, now) -> RefreshResult.mediate(new SessionBinding.BoundSession(
                            rebind(session, REFRESHED_TOKEN), List.of(RESEAL_COOKIE, secondCookie)));
            SessionAuthenticationStage stage = stage(binding, multiCookieRefresh, redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertEquals(List.of(RESEAL_COOKIE, secondCookie), request.responseSetCookies(),
                    "Set-Cookie is multi-valued — a binding returning two cookies must not be truncated to one");
        }

        @Test
        @DisplayName("re-drives a navigation request through login when the refresh fails")
        void redrivesNavigationOnFailedRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(302), request.shortCircuitStatus(),
                    "a navigation whose refresh failed runs the same negotiation as a missing session");
            assertEquals(LOGIN_LOCATION, request.responseHeaders().get("Location"));
            assertTrue(request.mediatedBearer().isEmpty(), "no bearer is mediated from the destroyed session");
        }
    }

    @Nested
    @DisplayName("Refresh-failure dispositions and on_failure")
    class RefreshFailureDispositions {

        @Test
        @DisplayName("session ended under reject: clears the cookie and answers 401 even for a navigation")
        void sessionEndedUnderRejectClearsCookieAndRejectsNavigation() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin(),
                    OnFailure.REJECT);
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "on_failure: reject answers 401 problem+json instead of redirecting into login");
            assertTrue(request.shortCircuitStatus().isEmpty(), "reject never short-circuits into a login redirect");
            assertEquals(List.of(binding.clearingSetCookieHeader()), request.responseSetCookies(),
                    "the destroyed session's cookie is cleared under reject too, and no login binding cookie is minted");
            assertTrue(request.mediatedBearer().isEmpty(), "no bearer is mediated from the destroyed session");
        }

        @Test
        @DisplayName("request failed under reauthenticate: an XHR gets 401 and the session cookie is NOT cleared")
        void requestFailedKeepsCookieOnXhr() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, requestFailedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "a request the refresh left without a token is challenged like a missing session");
            assertTrue(request.responseSetCookies().isEmpty(),
                    "the session is still live, so no clearing cookie may drop it from the browser");
            assertTrue(request.mediatedBearer().isEmpty(), "the expired access token is never mediated");
        }

        @Test
        @DisplayName("request failed under reauthenticate: a navigation is redirected with only the login cookie")
        void requestFailedRedirectsNavigationWithoutClearing() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, requestFailedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertEquals(Optional.of(302), request.shortCircuitStatus(),
                    "reauthenticate re-drives the login negotiation for a navigation");
            assertEquals(List.of(BINDING_COOKIE), request.responseSetCookies(),
                    "only the login binding cookie is emitted — the live session's cookie is not cleared");
        }

        @Test
        @DisplayName("request failed under reject: a navigation gets 401 and the session cookie is NOT cleared")
        void requestFailedUnderRejectRejectsNavigation() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, requestFailedRefresh(), redirectLogin(),
                    OnFailure.REJECT);
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "reject answers 401 for a navigation request");
            assertTrue(request.shortCircuitStatus().isEmpty(), "reject never redirects");
            assertTrue(request.responseSetCookies().isEmpty(),
                    "neither a clearing cookie nor a login binding cookie is emitted");
        }

        @Test
        @DisplayName("reject governs only a refresh failure: a missing session still redirects a navigation into login")
        void rejectDoesNotApplyToMissingSession() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin(),
                    OnFailure.REJECT);
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(302), request.shortCircuitStatus(),
                    "on_failure is the refresh-failure response; an unauthenticated navigation is still sent to login");
        }

        @Test
        @DisplayName("mediates normally under reject when the refresh succeeds")
        void rejectMediatesSuccessfulRefresh() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin(),
                    OnFailure.REJECT);
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            assertDoesNotThrow(() -> stage.process(request));

            assertEquals(Optional.of(MEDIATED_TOKEN), request.mediatedBearer(),
                    "the policy never touches a request whose session mediates");
        }

        @Test
        @DisplayName("rejects a null on-failure policy")
        void rejectsNullOnFailurePolicy() {
            SessionBinding binding = emptyBinding();
            SessionAuthenticationStage.TokenRefresh refresh = identityRefresh();
            SessionAuthenticationStage.LoginInitiation login = redirectLogin();

            assertThrows(NullPointerException.class,
                    () -> new SessionAuthenticationStage(binding, refresh, login, null, CLOCK));
        }

        @Test
        @DisplayName("rejects a mediate result without a bound session")
        void rejectsMediateWithoutBoundSession() {
            assertThrows(NullPointerException.class, () -> RefreshResult.mediate(null));
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
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

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
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "an unauthenticated non-navigation request is the 401 application/problem+json path");
            assertTrue(request.shortCircuitStatus().isEmpty(),
                    "no short-circuit is set, so the edge renders the problem response rather than a redirect");
        }
    }

    @Nested
    @DisplayName("Post-login return URL")
    class ReturnUrl {

        @Test
        @DisplayName("records the canonical path alone when the request carries no query")
        void pathOnly() {
            assertEquals("/x", recordedReturnUrl("/x", List.of()),
                    "no '?' is appended when there is no query");
        }

        @Test
        @DisplayName("records the path plus the raw query, bare names kept bare")
        void pathPlusQuery() {
            List<QueryParameter> query = List.of(new QueryParameter("tab", "a"), new QueryParameter("b", null));

            assertEquals("/x?tab=a&b", recordedReturnUrl("/x", query),
                    "the recorded return URL is exactly the navigated path plus its query");
        }

        @Test
        @DisplayName("keeps repeated and interleaved names in wire order")
        void repeatedAndInterleavedNamesKeepWireOrder() {
            List<QueryParameter> query = List.of(new QueryParameter("a", "1"), new QueryParameter("b", "2"),
                    new QueryParameter("a", "3"));

            assertEquals("/x?a=1&b=2&a=3", recordedReturnUrl("/x", query),
                    "pairs are never regrouped by name");
        }

        @Test
        @DisplayName("keeps a bare name bare and an empty value as name=")
        void bareNameStaysBare() {
            List<QueryParameter> query = List.of(new QueryParameter("flag", null), new QueryParameter("empty", ""));

            assertEquals("/x?flag&empty=", recordedReturnUrl("/x", query),
                    "a pair without '=' is never rendered as 'flag='");
        }

        @Test
        @DisplayName("keeps percent-encoded bytes verbatim, never decoding or re-encoding them")
        void percentEncodedBytesKeptVerbatim() {
            List<QueryParameter> query = List.of(new QueryParameter("q", "a%20b%2Fc+d"),
                    new QueryParameter("n%C3%A4me", "%26"));

            assertEquals("/x?q=a%20b%2Fc+d&n%C3%A4me=%26", recordedReturnUrl("/x", query),
                    "the rebuilt query is byte-identical to the inbound one");
        }

        private String recordedReturnUrl(String path, List<QueryParameter> query) {
            AtomicReference<String> recorded = new AtomicReference<>();
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), (returnUrl, scopes, now) -> {
                recorded.set(returnUrl);
                return new LoginChallenge(LOGIN_LOCATION, List.of(BINDING_COOKIE));
            });
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.GET)
                    .requestPath(path)
                    .queryParameters(query)
                    .headers(navigationHeaders())
                    .build();
            request.canonicalPath(path);
            request.selectedRoute(RouteRuntime.builder().id("orders")
                    .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                    .neededScopes(Set.of())
                    .build());

            stage.process(request);

            assertEquals(Optional.of(302), request.shortCircuitStatus(), "the navigation is redirected into login");
            return recorded.get();
        }
    }

    @Nested
    @DisplayName("Preconditions")
    class Preconditions {

        @Test
        @DisplayName("rejects a request whose route was not selected at stage 2")
        void rejectsUnselectedRoute() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.GET).requestPath("/app").queryParameters(List.of()).headers(Map.of()).build();

            assertThrows(IllegalStateException.class, () -> stage.process(request));
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());

            assertThrows(NullPointerException.class, () -> stage.process(null));
        }
    }

    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh refresh, SessionAuthenticationStage.LoginInitiation login) {
        return stage(binding, refresh, login, OnFailure.REAUTHENTICATE);
    }

    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh refresh, SessionAuthenticationStage.LoginInitiation login,
            OnFailure onFailure) {
        return new SessionAuthenticationStage(binding, refresh, login, onFailure, CLOCK);
    }

    private static SessionAuthenticationStage.TokenRefresh identityRefresh() {
        return (session, cookieHeader, now) ->
                RefreshResult.mediate(new SessionBinding.BoundSession(session, List.of()));
    }

    /** A refresh seam that destroyed the session — the stage clears the cookie, then negotiates. */
    private static SessionAuthenticationStage.TokenRefresh sessionEndedRefresh() {
        return (session, cookieHeader, now) -> RefreshResult.sessionEnded();
    }

    /** A refresh seam that kept the session but left this request without a token — no cookie clearing. */
    private static SessionAuthenticationStage.TokenRefresh requestFailedRefresh() {
        return (session, cookieHeader, now) -> RefreshResult.requestFailed();
    }

    private static SessionAuthenticationStage.LoginInitiation redirectLogin() {
        return (returnUrl, scopes, now) -> new LoginChallenge(LOGIN_LOCATION, List.of(BINDING_COOKIE));
    }

    private static SessionBinding emptyBinding() {
        return new ServerSessionBinding(new InMemorySessionStore(16), CODEC);
    }

    private static SessionBinding bindingWith(SessionRecord session) {
        InMemorySessionStore store = new InMemorySessionStore(16);
        store.create(session, NOW);
        return new ServerSessionBinding(store, CODEC);
    }

    private static SessionRecord session(String accessToken) {
        return session(accessToken, SESSION_EXPIRY);
    }

    private static SessionRecord session(String accessToken, Instant expiresAt) {
        return SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken(accessToken)
                .refreshToken("refresh-token")
                .idToken("id-token")
                .sub("subject")
                .sid("idp-sid")
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

    private static Map<String, List<String>> navigationHeaders() {
        return Map.of("cookie", List.of(cookie()), "accept", List.of("text/html,application/xhtml+xml"));
    }

    private static Map<String, List<String>> xhrHeaders() {
        return Map.of("cookie", List.of(cookie()), "accept", List.of("application/json"));
    }

    private static String cookie() {
        return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + SESSION_ID;
    }

    private static PipelineRequest sessionRequest(Set<String> neededScopes, Map<String, List<String>> headers) {
        return sessionRequest(neededScopes, headers, null);
    }

    private static PipelineRequest sessionRequest(Set<String> neededScopes, Map<String, List<String>> headers,
            @Nullable Boolean tokenRelay) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/app/orders")
                .queryParameters(List.of())
                .headers(headers)
                .build();
        request.canonicalPath("/app/orders");
        request.selectedRoute(RouteRuntime.builder().id("orders")
                .effectiveAuth(AuthConfig.builder().require(Require.SESSION).tokenRelay(tokenRelay).build())
                .neededScopes(neededScopes)
                .build());
        return request;
    }
}
