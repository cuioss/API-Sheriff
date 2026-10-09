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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SessionAuthenticationStage — stage 4 require:session runtime")
class SessionAuthenticationStageTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Instant SESSION_EXPIRY = NOW.plusSeconds(3600);
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** The session's internal identity — never the cookie value. */
    private static final String SESSION_ID = "internal-session-id";
    /** The opaque value the session cookie carries, which the store resolves to the session. */
    private static final String COOKIE_HANDLE = "opaque-cookie-handle";
    /**
     * The idle timeout of the fixture store. It equals the fixture session's lifetime, so with the fixed
     * {@link #CLOCK} the idle deadline is not in play; the {@code IdleTimeout} cases set their own.
     */
    private static final Duration NO_IDLE_EFFECT = Duration.ofHours(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(600);
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
        @DisplayName("never relays a live session whose scopes fall short of the route's needed scopes")
        void neverRelaysSessionShortOfNeededScopes() {
            // The session carries no scope at all, so the needed scope is neither active nor granted:
            // no refresh can obtain it, and an API call is refused rather than relayed.
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(NEEDED_SCOPE), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll("a session route enforces its needed scopes on every request",
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(),
                            "a session short of a needed scope is answered 403"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(),
                            "the under-scoped session's token is never recorded for the upstream"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(),
                            "an API call is refused, never redirected"));
        }
    }

    @Nested
    @DisplayName("Admitting session — what a long-lived relay is tracked under")
    class AdmittingSessionRecorded {

        @Test
        @DisplayName("records the session's identity and absolute expiry on a request it lets through")
        void recordsIdentityAndExpiryOfTheSessionLetThrough() {
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), identityRefresh(),
                    redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertAll("the request names the session it was admitted with",
                    () -> assertEquals(Optional.of(SESSION_ID),
                            request.admittingSession().map(PipelineRequest.AdmittingSession::sessionId),
                            "the stable session identity, never the cookie value"),
                    () -> assertEquals(Optional.of(SESSION_EXPIRY),
                            request.admittingSession().map(PipelineRequest.AdmittingSession::expiresAt)),
                    () -> assertFalse(request.admittingSession().orElseThrow().toString().contains(SESSION_ID),
                            "the identity is not printed"));
        }

        @Test
        @DisplayName("records it with token_relay off too: the relay is opened with the session either way")
        void recordsItWithoutTokenRelay() {
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), identityRefresh(),
                    redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders(), false);

            stage.process(request);

            assertEquals(Optional.of(SESSION_ID),
                    request.admittingSession().map(PipelineRequest.AdmittingSession::sessionId));
        }

        @Test
        @DisplayName("records none on a navigation it redirects into login")
        void recordsNoneOnRedirect() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertEquals(Optional.of(302), request.shortCircuitStatus(), "precondition: the request was redirected");
            assertEquals(Optional.empty(), request.admittingSession());
        }

        @Test
        @DisplayName("records none on a request it challenges 401")
        void recordsNoneOnChallenge() {
            SessionAuthenticationStage stage = stage(emptyBinding(), identityRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(Optional.empty(), request.admittingSession());
        }

        @Test
        @DisplayName("records none on a live session it refuses for a missing scope")
        void recordsNoneOnScopeRefusal() {
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), identityRefresh(),
                    redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(NEEDED_SCOPE), xhrHeaders());

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(Optional.empty(), request.admittingSession());
        }

        @Test
        @DisplayName("records none when the refresh ended the session")
        void recordsNoneWhenTheRefreshEndedTheSession() {
            SessionAuthenticationStage stage = stage(bindingWith(session(MEDIATED_TOKEN)), sessionEndedRefresh(),
                    redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(Optional.empty(), request.admittingSession());
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

            assertEquals(binding.clearingSetCookieHeaders(), request.responseSetCookies());
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

    /**
     * The first two cases are also the stage-side half of the short-circuit contract
     * {@code GatewayEdgeRoute.handle} honors. After the authentication stage ran, an unauthenticated
     * navigation leaves the request short-circuited ({@code 302}) with a {@code Location} and mediates
     * no upstream bearer, so the edge renders the redirect instead of proceeding to the forward stage;
     * an unauthenticated XHR raises the {@code 401} problem path with no short-circuit, so the edge
     * takes its rejection branch. The {@code 302} the edge actually writes, rather than a {@code 200}
     * fall-through to the upstream, is asserted end-to-end by {@code BffSessionMediationIT}.
     */
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

            assertEquals(binding.clearingSetCookieHeaders(), request.responseSetCookies(),
                    "the stale cookie is cleared so the browser stops presenting a destroyed session");
        }

        @Test
        @DisplayName("emits BOTH the clearing cookie and the login-challenge cookie when a refresh failure ends the session on an HTML navigation")
        void retainsBothCookiesWhenRefreshFailureEndsSessionOnNavigation() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, sessionEndedRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage.process(request);

            assertEquals(List.of(binding.clearingSetCookieHeaders().getFirst(), BINDING_COOKIE),
                    request.responseSetCookies(),
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
            assertEquals(binding.clearingSetCookieHeaders(), request.responseSetCookies(),
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
            SessionAuthenticationStage.ScopeRefresh scopeRefresh = unreachableScopeRefresh();
            SessionAuthenticationStage.LoginInitiation login = redirectLogin();
            SessionAuthenticationStage.WideningInitiation widening = unreachableWidening();

            assertThrows(NullPointerException.class, () -> new SessionAuthenticationStage(binding, refresh,
                    scopeRefresh, login, widening, null, null, CLOCK));
        }

        @Test
        @DisplayName("rejects a mediate result without a bound session")
        void rejectsMediateWithoutBoundSession() {
            assertThrows(NullPointerException.class, () -> RefreshResult.mediate(null));
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

    /**
     * The idle timeout: the stage is the one place an access is counted — once per request it lets
     * through — and the binding's resolve enforces the deadline, so an idle session reaches the stage
     * as no session at all.
     */
    @Nested
    @DisplayName("Idle timeout")
    class IdleTimeout {

        @Test
        @DisplayName("extends the idle deadline for a request it lets through")
        void letThroughRequestExtendsTheIdleDeadline() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            Instant access = NOW.plusSeconds(500);
            stageAt(clockAt(access), binding, identityRefresh()).process(sessionRequest(Set.of(), xhrHeaders()));
            PipelineRequest afterTheOriginalDeadline = sessionRequest(Set.of(), xhrHeaders());

            stageAt(clockAt(NOW.plusSeconds(1000)), binding, identityRefresh()).process(afterTheOriginalDeadline);

            assertEquals(Optional.of(MEDIATED_TOKEN), afterTheOriginalDeadline.mediatedBearer(),
                    "the access at 500 s moved the deadline from 600 s to 1100 s, so the request at 1000 s is live");
        }

        @Test
        @DisplayName("ends the session one idle timeout after the last request it let through")
        void sessionEndsOneIdleTimeoutAfterTheLastAccess() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            Instant access = NOW.plusSeconds(500);
            stageAt(clockAt(access), binding, identityRefresh()).process(sessionRequest(Set.of(), xhrHeaders()));
            SessionAuthenticationStage atTheMovedDeadline =
                    stageAt(clockAt(access.plus(IDLE_TIMEOUT)), binding, identityRefresh());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> atTheMovedDeadline.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType());
        }

        @Test
        @DisplayName("does not extend the idle deadline for a request it refuses")
        void refusedRequestDoesNotExtendTheIdleDeadline() {
            // The session carries no scope, so a route needing one refuses the request 403.
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            SessionAuthenticationStage early = stageAt(clockAt(NOW.plusSeconds(500)), binding, identityRefresh());
            PipelineRequest refused = sessionRequest(Set.of(NEEDED_SCOPE), xhrHeaders());
            GatewayException refusal = assertThrows(GatewayException.class, () -> early.process(refused));
            assertEquals(EventType.SCOPE_MISSING, refusal.getEventType(), "precondition: the request was refused");
            SessionAuthenticationStage atTheDeadline = stageAt(clockAt(NOW.plus(IDLE_TIMEOUT)), binding,
                    identityRefresh());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> atTheDeadline.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "the refused request was no access: the session ended one idle timeout after its creation");
        }

        @Test
        @DisplayName("does not extend the idle deadline for a request it redirects into a login")
        void redirectedRequestDoesNotExtendTheIdleDeadline() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            PipelineRequest redirected = sessionRequest(Set.of(), navigationHeaders());
            stageAt(clockAt(NOW.plusSeconds(500)), binding, requestFailedRefresh()).process(redirected);
            assertEquals(Optional.of(302), redirected.shortCircuitStatus(), "precondition: the request was redirected");
            SessionAuthenticationStage atTheDeadline = stageAt(clockAt(NOW.plus(IDLE_TIMEOUT)), binding,
                    identityRefresh());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> atTheDeadline.process(request));

            assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                    "the redirected request was no access: the session ended one idle timeout after its creation");
        }

        @Test
        @DisplayName("treats an idle session as no session and sends no clearing cookie on a 401")
        void idleSessionIsNoSessionOnXhr() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            SessionAuthenticationStage stage = stageAt(clockAt(NOW.plus(IDLE_TIMEOUT)), binding, identityRefresh());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll("an idle-expired request gets the ordinary unauthenticated answer",
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType()),
                    () -> assertTrue(request.responseSetCookies().isEmpty(), "no clearing cookie is sent"),
                    () -> assertTrue(request.mediatedBearer().isEmpty()));
        }

        @Test
        @DisplayName("redirects a navigation of an idle session into login with the login cookie alone")
        void idleSessionIsNoSessionOnNavigation() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stageAt(clockAt(NOW.plus(IDLE_TIMEOUT)), binding, identityRefresh()).process(request);

            assertAll(
                    () -> assertEquals(Optional.of(302), request.shortCircuitStatus()),
                    () -> assertEquals(List.of(BINDING_COOKIE), request.responseSetCookies(),
                            "only the login binding cookie — no clearing cookie for the idle session"));
        }
    }

    /** Cookie mode: the access is remembered in the activity cookie, which the stage has to pass on. */
    @Nested
    @DisplayName("Activity cookie (cookie mode)")
    class ActivityCookie {

        private static final String ACTIVITY_COOKIE_PREFIX = SessionCookieCodec.DEFAULT_COOKIE_NAME + "-activity=";

        private SessionBinding cookieBinding;
        private String sessionCookie;

        @BeforeEach
        void bindCookieModeSession() {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 0x11);
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            byte[] activityKey = new byte[32];
            Arrays.fill(activityKey, (byte) 0x44);
            cookieBinding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1),
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"),
                            (byte) 1),
                    salt,
                    new SessionActivityCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                            new SecretKeySpec(activityKey, "AES"), (byte) 2),
                    IDLE_TIMEOUT);
            String setCookie = cookieBinding.bind(session(MEDIATED_TOKEN), NOW).setCookieHeaders().getFirst();
            sessionCookie = setCookie.substring(0, setCookie.indexOf(';'));
        }

        @Test
        @DisplayName("adds the activity cookie to the response cookies of a request it lets through")
        void activityCookieReachesTheResponseCookies() {
            PipelineRequest request = sessionRequest(Set.of(), headersWith(sessionCookie, "application/json"));

            stageAt(clockAt(NOW.plusSeconds(60)), cookieBinding, identityRefresh()).process(request);

            assertAll(
                    () -> assertEquals(Optional.of(MEDIATED_TOKEN), request.mediatedBearer()),
                    () -> assertEquals(1, request.responseSetCookies().size(),
                            "the access is remembered by exactly one cookie"),
                    () -> assertTrue(request.responseSetCookies().getFirst().startsWith(ACTIVITY_COOKIE_PREFIX),
                            "and that cookie is the activity cookie, never the session cookie"));
        }

        @Test
        @DisplayName("sets no cookie for an access younger than the activity interval")
        void noCookieBeforeTheInterval() {
            PipelineRequest request = sessionRequest(Set.of(), headersWith(sessionCookie, "application/json"));

            stageAt(clockAt(NOW.plusSeconds(59)), cookieBinding, identityRefresh()).process(request);

            assertTrue(request.responseSetCookies().isEmpty());
        }

        @Test
        @DisplayName("keeps the session alive through the activity cookie it set")
        void activityCookieExtendsTheSession() {
            PipelineRequest first = sessionRequest(Set.of(), headersWith(sessionCookie, "application/json"));
            stageAt(clockAt(NOW.plusSeconds(500)), cookieBinding, identityRefresh()).process(first);
            String activitySetCookie = first.responseSetCookies().getFirst();
            String cookies = sessionCookie + "; " + activitySetCookie.substring(0, activitySetCookie.indexOf(';'));
            PipelineRequest second = sessionRequest(Set.of(), headersWith(cookies, "application/json"));

            stageAt(clockAt(NOW.plusSeconds(1000)), cookieBinding, identityRefresh()).process(second);

            assertEquals(Optional.of(MEDIATED_TOKEN), second.mediatedBearer(),
                    "with the activity cookie the request past the login-measured deadline is live");
        }

        @Test
        @DisplayName("adds the activity cookie after the cookies of a refresh re-bind, replacing none of them")
        void activityCookieJoinsTheRebindCookies() {
            SessionAuthenticationStage.TokenRefresh resealing = (session, cookieHeader, now) -> RefreshResult
                    .mediate(new SessionBinding.BoundSession(session, List.of(RESEAL_COOKIE)));
            PipelineRequest request = sessionRequest(Set.of(), headersWith(sessionCookie, "application/json"));

            stageAt(clockAt(NOW.plusSeconds(60)), cookieBinding, resealing).process(request);

            assertAll(
                    () -> assertEquals(2, request.responseSetCookies().size()),
                    () -> assertEquals(RESEAL_COOKIE, request.responseSetCookies().getFirst()),
                    () -> assertTrue(request.responseSetCookies().get(1).startsWith(ACTIVITY_COOKIE_PREFIX)));
        }

        @Test
        @DisplayName("counts the access under token_relay: false as well")
        void relayOffStillCountsTheAccess() {
            PipelineRequest request = sessionRequest(Set.of(), headersWith(sessionCookie, "application/json"), false);

            stageAt(clockAt(NOW.plusSeconds(60)), cookieBinding, identityRefresh()).process(request);

            assertAll(
                    () -> assertTrue(request.mediatedBearer().isEmpty()),
                    () -> assertEquals(1, request.responseSetCookies().size()),
                    () -> assertTrue(request.responseSetCookies().getFirst().startsWith(ACTIVITY_COOKIE_PREFIX)));
        }

        @Test
        @DisplayName("sets no activity cookie for a request it refuses")
        void refusedRequestSetsNoActivityCookie() {
            SessionAuthenticationStage stage = stageAt(clockAt(NOW.plusSeconds(60)), cookieBinding, identityRefresh());
            PipelineRequest request = sessionRequest(Set.of(NEEDED_SCOPE),
                    headersWith(sessionCookie, "application/json"));

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertTrue(request.responseSetCookies().isEmpty(), "a refused request is not an access");
        }

        @Test
        @DisplayName("clears both cookies of the binding when the refresh ended the session")
        void endedSessionClearsEveryCookieOfTheBinding() {
            SessionAuthenticationStage stage = stageAt(clockAt(NOW), cookieBinding, sessionEndedRefresh());
            PipelineRequest request = sessionRequest(Set.of(), headersWith(sessionCookie, "application/json"));

            assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(2, request.responseSetCookies().size(),
                            "the session cookie and the activity cookie are both cleared"),
                    () -> assertEquals(cookieBinding.clearingSetCookieHeaders(), request.responseSetCookies()));
        }

        @Test
        @DisplayName("clears both cookies and adds the login cookie when an ended session is re-driven into login")
        void endedSessionOnNavigationClearsBothAndAddsTheLoginCookie() {
            PipelineRequest request = sessionRequest(Set.of(), headersWith(sessionCookie, "text/html,application/xhtml+xml"));

            stageAt(clockAt(NOW), cookieBinding, sessionEndedRefresh()).process(request);

            List<String> clearing = cookieBinding.clearingSetCookieHeaders();
            assertEquals(List.of(clearing.get(0), clearing.get(1), BINDING_COOKIE), request.responseSetCookies());
        }
    }

    /**
     * The fourth refresh disposition: the seam resolved no session from the request's cookie a second
     * time and destroyed nothing. The request is answered as unauthenticated and no cookie is cleared.
     */
    @Nested
    @DisplayName("No session for this request")
    class NoSessionForThisRequest {

        @Test
        @DisplayName("answers an XHR 401 without a clearing cookie")
        void answersXhrWithoutClearingCookie() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, noSessionRefresh(), redirectLogin());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType()),
                    () -> assertTrue(request.responseSetCookies().isEmpty(),
                            "a clearing cookie would delete a cookie value the browser may just have been given"),
                    () -> assertTrue(request.mediatedBearer().isEmpty()));
        }

        @Test
        @DisplayName("redirects a navigation into login with the login cookie alone")
        void redirectsNavigationWithoutClearingCookie() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            stage(binding, noSessionRefresh(), redirectLogin()).process(request);

            assertAll(
                    () -> assertEquals(Optional.of(302), request.shortCircuitStatus()),
                    () -> assertEquals(List.of(BINDING_COOKIE), request.responseSetCookies(),
                            "only the login binding cookie is emitted"));
        }

        @Test
        @DisplayName("answers 401 without any cookie under reject, even for a navigation")
        void rejectsWithoutAnyCookie() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage stage = stage(binding, noSessionRefresh(), redirectLogin(), OnFailure.REJECT);
            PipelineRequest request = sessionRequest(Set.of(), navigationHeaders());

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType()),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty()),
                    () -> assertTrue(request.responseSetCookies().isEmpty()));
        }

        @Test
        @DisplayName("leaves the session in place, so the next request with the live cookie succeeds")
        void leavesTheSessionInPlace() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN));
            SessionAuthenticationStage answeringNoSession = stage(binding, noSessionRefresh(), redirectLogin());
            PipelineRequest first = sessionRequest(Set.of(), xhrHeaders());
            assertThrows(GatewayException.class, () -> answeringNoSession.process(first));
            PipelineRequest next = sessionRequest(Set.of(), xhrHeaders());

            stage(binding, identityRefresh(), redirectLogin()).process(next);

            assertEquals(Optional.of(MEDIATED_TOKEN), next.mediatedBearer(),
                    "nothing was destroyed: the one unauthenticated answer is followed by a served request");
        }

        @Test
        @DisplayName("answers a request still carrying a re-issued-away cookie value 401 without a clearing cookie, and serves the new value")
        void previousCookieValueIsUnauthenticatedOnce() {
            ServerSessionBinding binding = new ServerSessionBinding(new InMemorySessionStore(16, NO_IDLE_EFFECT,
                    Integer.MAX_VALUE, sessionId -> {
                    }), CODEC);
            SessionRecord live = session(MEDIATED_TOKEN);
            String previousSetCookie = binding.bind(live, NOW).setCookieHeaders().getFirst();
            String reissuedSetCookie = binding.persistReissuingCookie(live, NOW).orElseThrow()
                    .setCookieHeaders().getFirst();
            String previousCookie = previousSetCookie.substring(0, previousSetCookie.indexOf(';'));
            String reissuedCookie = reissuedSetCookie.substring(0, reissuedSetCookie.indexOf(';'));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), redirectLogin());
            PipelineRequest inFlight = sessionRequest(Set.of(), headersWith(previousCookie, "application/json"));
            PipelineRequest next = sessionRequest(Set.of(), headersWith(reissuedCookie, "application/json"));

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(inFlight));
            stage.process(next);

            assertAll("a step-up costs a request in flight one unauthenticated answer and nothing else",
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType()),
                    () -> assertTrue(inFlight.responseSetCookies().isEmpty(),
                            "no clearing cookie: it would delete the cookie value the browser was just given"),
                    () -> assertEquals(Optional.of(MEDIATED_TOKEN), next.mediatedBearer(),
                            "the request carrying the re-issued value is served"));
        }

        @Test
        @DisplayName("does not count the request as an access")
        void doesNotCountAsAccess() {
            SessionBinding binding = bindingWith(session(MEDIATED_TOKEN), IDLE_TIMEOUT);
            SessionAuthenticationStage early = stageAt(clockAt(NOW.plusSeconds(500)), binding, noSessionRefresh());
            PipelineRequest unanswered = sessionRequest(Set.of(), xhrHeaders());
            assertThrows(GatewayException.class, () -> early.process(unanswered));
            SessionAuthenticationStage atTheDeadline = stageAt(clockAt(NOW.plus(IDLE_TIMEOUT)), binding,
                    identityRefresh());
            PipelineRequest request = sessionRequest(Set.of(), xhrHeaders());

            assertThrows(GatewayException.class, () -> atTheDeadline.process(request),
                    "the session was idle from its creation, so it ended at the idle deadline");
        }
    }

    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh refresh, SessionAuthenticationStage.LoginInitiation login) {
        return stage(binding, refresh, login, OnFailure.REAUTHENTICATE);
    }

    /**
     * A stage whose scope-refresh and widening seams fail the test when reached and that names no
     * step-up path: every test in this class drives a session that either covers the route's needed
     * scopes or lacks one no refresh could obtain, so neither seam is ever legitimately called here.
     * The seams' own behaviour is covered by {@code SessionAuthenticationStageScopeEnforcementTest}.
     */
    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh refresh, SessionAuthenticationStage.LoginInitiation login,
            OnFailure onFailure) {
        return new SessionAuthenticationStage(binding, refresh, unreachableScopeRefresh(), login,
                unreachableWidening(), onFailure, null, CLOCK);
    }

    static SessionAuthenticationStage.TokenRefresh identityRefresh() {
        return (session, cookieHeader, now) ->
                RefreshResult.mediate(new SessionBinding.BoundSession(session, List.of()));
    }

    /** A scope-refresh seam that fails the test when reached — the request must not need a scope refresh. */
    static SessionAuthenticationStage.ScopeRefresh unreachableScopeRefresh() {
        return (session, cookieHeader, requestedScopes, now) -> {
            throw new AssertionError("the scope-refresh seam must not be reached");
        };
    }

    /** A widening seam that fails the test when reached — the request must not be redirected into a widening. */
    static SessionAuthenticationStage.WideningInitiation unreachableWidening() {
        return (live, returnUrl, neededScopes, now) -> {
            throw new AssertionError("the widening seam must not be reached");
        };
    }

    /** A refresh seam that destroyed the session — the stage clears the cookie, then negotiates. */
    private static SessionAuthenticationStage.TokenRefresh sessionEndedRefresh() {
        return (session, cookieHeader, now) -> RefreshResult.sessionEnded();
    }

    /** A refresh seam that kept the session but left this request without a token — no cookie clearing. */
    private static SessionAuthenticationStage.TokenRefresh requestFailedRefresh() {
        return (session, cookieHeader, now) -> RefreshResult.requestFailed();
    }

    static SessionAuthenticationStage.LoginInitiation redirectLogin() {
        return (returnUrl, scopes, now) -> new LoginChallenge(LOGIN_LOCATION, List.of(BINDING_COOKIE));
    }

    private static SessionBinding emptyBinding() {
        return new ServerSessionBinding(new InMemorySessionStore(16, NO_IDLE_EFFECT,
                Integer.MAX_VALUE, sessionId -> {
                }), CODEC);
    }

    static SessionBinding bindingWith(SessionRecord session) {
        return bindingWith(session, NO_IDLE_EFFECT);
    }

    /** A server-mode binding holding {@code session} under {@link #COOKIE_HANDLE}, created at {@link #NOW}. */
    private static SessionBinding bindingWith(SessionRecord session, Duration idleTimeout) {
        InMemorySessionStore store = new InMemorySessionStore(16, idleTimeout, Integer.MAX_VALUE, sessionId -> {
        });
        store.create(session, COOKIE_HANDLE, NOW);
        return new ServerSessionBinding(store, CODEC);
    }

    private static SessionRecord session(String accessToken) {
        return session(accessToken, SESSION_EXPIRY);
    }

    private static SessionRecord session(String accessToken, Instant expiresAt) {
        return session(accessToken, expiresAt, Set.of(), Set.of());
    }

    /** A live session carrying the active scope set {@code A} and the granted scope set {@code S}. */
    static SessionRecord session(String accessToken, Set<String> activeScopes, Set<String> grantedScopes) {
        return session(accessToken, SESSION_EXPIRY, activeScopes, grantedScopes);
    }

    private static SessionRecord session(String accessToken, Instant expiresAt, Set<String> activeScopes,
            Set<String> grantedScopes) {
        return SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken(accessToken)
                .refreshToken("refresh-token")
                .idToken("id-token")
                .sub("subject")
                .sid("idp-sid")
                .expiresAt(expiresAt)
                .activeScopes(activeScopes)
                .grantedScopes(grantedScopes)
                .build();
    }

    private static SessionRecord rebind(SessionRecord session, String accessToken) {
        return rebind(session, accessToken, session.activeScopes());
    }

    /** The session as a refresh re-binds it: rotated token material, the given active scope set, {@code S} unchanged. */
    static SessionRecord rebind(SessionRecord session, String accessToken, Set<String> activeScopes) {
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
                .activeScopes(activeScopes)
                .grantedScopes(session.grantedScopes())
                .build();
    }

    static Map<String, List<String>> navigationHeaders() {
        return Map.of("cookie", List.of(cookie()), "accept", List.of("text/html,application/xhtml+xml"));
    }

    static Map<String, List<String>> xhrHeaders() {
        return Map.of("cookie", List.of(cookie()), "accept", List.of("application/json"));
    }

    private static String cookie() {
        return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + COOKIE_HANDLE;
    }

    /** A refresh seam whose second resolve found no session for the request and that destroyed nothing. */
    private static SessionAuthenticationStage.TokenRefresh noSessionRefresh() {
        return (session, cookieHeader, now) -> RefreshResult.noSession();
    }

    /** A stage over an explicit clock, for the cases that move time between two requests. */
    private static SessionAuthenticationStage stageAt(Clock clock, SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh refresh) {
        return new SessionAuthenticationStage(binding, refresh, unreachableScopeRefresh(), redirectLogin(),
                unreachableWidening(), OnFailure.REAUTHENTICATE, null, clock);
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static Map<String, List<String>> headersWith(String cookieHeader, String accept) {
        return Map.of("cookie", List.of(cookieHeader), "accept", List.of(accept));
    }

    private static PipelineRequest sessionRequest(Set<String> neededScopes, Map<String, List<String>> headers) {
        return sessionRequest(neededScopes, headers, null);
    }

    static PipelineRequest sessionRequest(Set<String> neededScopes, Map<String, List<String>> headers,
            @Nullable Boolean tokenRelay) {
        return sessionRequest(neededScopes, headers, tokenRelay, List.of());
    }

    static PipelineRequest sessionRequest(Set<String> neededScopes, Map<String, List<String>> headers,
            @Nullable Boolean tokenRelay, List<QueryParameter> query) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/app/orders")
                .queryParameters(query)
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
