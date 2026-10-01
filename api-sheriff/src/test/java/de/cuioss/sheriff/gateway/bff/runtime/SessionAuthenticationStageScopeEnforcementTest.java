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

import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.CLOCK;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.bindingWith;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.identityRefresh;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.navigationHeaders;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.rebind;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.redirectLogin;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.session;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.sessionRequest;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.unreachableScopeRefresh;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.unreachableWidening;
import static de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStageTest.xhrHeaders;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;


import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.LoginChallenge;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.OnFailure;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage.RefreshResult;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.QueryParameter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The scope enforcement of {@link SessionAuthenticationStage}: a session route compares the session's
 * active scope set {@code A} against the route's needed scopes on every request, obtains a missing
 * scope by one refresh when it lies inside the granted set {@code S} and by a widening otherwise, and
 * never relays a session short of a needed scope.
 * <p>
 * The scope names, the step-up path and the two problem member names are literals on purpose: each is
 * the exact value the response contract carries, so a generated value would assert nothing about it.
 * The session resolution, near-expiry refresh and unauthenticated negotiation are covered by
 * {@link SessionAuthenticationStageTest}, whose fixtures this class shares.
 */
@DisplayName("SessionAuthenticationStage — needed scopes are enforced on every session request before relay")
class SessionAuthenticationStageScopeEnforcementTest {

    private static final String OPENID = "openid";
    private static final String ORDERS_READ = "orders:read";
    private static final String ORDERS_WRITE = "orders:write";
    private static final Set<String> NEEDED = Set.of(OPENID, ORDERS_READ);

    private static final String SESSION_TOKEN = "mediated-access-token";
    private static final String NEAR_EXPIRY_TOKEN = "near-expiry-refreshed-access-token";
    private static final String SCOPED_TOKEN = "scope-refreshed-access-token";

    private static final String WIDENING_LOCATION = "https://idp.example/authorize?prompt=none";
    private static final String WIDENING_COOKIE =
            "__Host-sheriff-binding=widening-value; Path=/; Secure; HttpOnly; SameSite=Lax";
    private static final String REBIND_COOKIE =
            "__Host-sheriff-session=re-sealed-value; Path=/; Secure; HttpOnly; SameSite=Lax";

    private static final String STEP_UP_PATH = "/auth/step-up";
    private static final String NAVIGATION_ACCEPT = "text/html,application/xhtml+xml";
    private static final String API_ACCEPT = "application/json";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    @Nested
    @DisplayName("Session carries every needed scope")
    class Satisfied {

        @ParameterizedTest(name = "Accept: {0}")
        @ValueSource(strings = {NAVIGATION_ACCEPT, API_ACCEPT})
        @DisplayName("relays with no scope refresh and no widening")
        void relaysWithoutRefreshOrWidening(String accept) {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, NEEDED, NEEDED));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, headers(accept), null);

            stage.process(request);

            assertAll("a covered session flows on untouched",
                    () -> assertEquals(Optional.of(SESSION_TOKEN), request.mediatedBearer(),
                            "the session's own token is relayed"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "the request is not short-circuited"),
                    () -> assertTrue(request.responseSetCookies().isEmpty(), "nothing is re-bound"));
        }

        @Test
        @DisplayName("relays when the active set holds more than the route needs")
        void relaysWhenActiveSetExceedsNeeded() {
            Set<String> active = Set.of(OPENID, ORDERS_READ, ORDERS_WRITE);
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, active, active));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            stage.process(request);

            assertEquals(Optional.of(SESSION_TOKEN), request.mediatedBearer(),
                    "a superset of the needed scopes satisfies the route");
        }

        @Test
        @DisplayName("token_relay: false lets the covered session through but records no bearer")
        void relayOffRecordsNoBearer() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, NEEDED, NEEDED));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), false);

            stage.process(request);

            assertAll("the session is accepted, the token is withheld",
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded with relay off"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "the request still flows on"));
        }
    }

    @Nested
    @DisplayName("Missing scope inside the granted set S")
    class InsideGrantedSet {

        @ParameterizedTest(name = "Accept: {0}")
        @ValueSource(strings = {NAVIGATION_ACCEPT, API_ACCEPT})
        @DisplayName("refreshes exactly once and relays the refreshed session")
        void refreshesExactlyOnceThenRelays(String accept) {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.obtaining(SCOPED_TOKEN, List.of());
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, headers(accept), null);

            stage.process(request);

            assertAll("one refresh restores the scope for navigation and API calls alike",
                    () -> assertEquals(List.of(NEEDED), scopeRefresh.requested,
                            "exactly one scope refresh is issued"),
                    () -> assertEquals(Optional.of(SCOPED_TOKEN), request.mediatedBearer(),
                            "the refreshed token is relayed, not the under-scoped one"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "the request is not redirected"));
        }

        @Test
        @DisplayName("requests the active set united with the missing scopes, never the needed set alone")
        void requestsActiveUnionMissing() {
            Set<String> active = Set.of(OPENID, ORDERS_WRITE);
            Set<String> granted = Set.of(OPENID, ORDERS_WRITE, ORDERS_READ);
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, active, granted));
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.obtaining(SCOPED_TOKEN, List.of());
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            stage.process(request);

            assertEquals(List.of(granted), scopeRefresh.requested,
                    "the grant keeps every active scope, so the refresh cannot narrow the session");
        }

        @Test
        @DisplayName("emits the cookies the scope refresh re-bound the session with")
        void emitsScopeRefreshRebindCookies() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.obtaining(SCOPED_TOKEN, List.of(REBIND_COOKIE));
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            stage.process(request);

            assertAll(
                    () -> assertEquals(List.of(REBIND_COOKIE), request.responseSetCookies(),
                            "the re-bind reaches the browser on this response"),
                    () -> assertEquals(Optional.of(SCOPED_TOKEN), request.mediatedBearer(),
                            "the refreshed token is relayed"));
        }

        @Test
        @DisplayName("token_relay: false still refreshes the scope but records no bearer")
        void relayOffStillRefreshes() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.obtaining(SCOPED_TOKEN, List.of());
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), false);

            stage.process(request);

            assertAll("the comparison runs whatever token_relay says",
                    () -> assertEquals(1, scopeRefresh.requested.size(), "the scope is still obtained"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded with relay off"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "the request flows on"));
        }

        @Test
        @DisplayName("a refresh that does not obtain the scope redirects a navigation into a widening")
        void refusedRefreshWidensNavigation() {
            SessionRecord live = session(SESSION_TOKEN, Set.of(OPENID), NEEDED);
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.keeping();
            RecordingWidening widening = new RecordingWidening();
            SessionAuthenticationStage stage = stage(bindingWith(live), scopeRefresh, widening);
            PipelineRequest request = sessionRequest(NEEDED, navigationHeaders(), null);

            stage.process(request);

            assertAll("a kept but still under-scoped session is widened, never relayed",
                    () -> assertEquals(1, scopeRefresh.requested.size(), "the refresh is attempted exactly once"),
                    () -> assertEquals(Optional.of(302), request.shortCircuitStatus(), "the navigation is redirected"),
                    () -> assertEquals(WIDENING_LOCATION, request.responseHeaders().get("Location"),
                            "the redirect targets the widening"),
                    () -> assertEquals(List.of(live), widening.liveSessions, "the kept session is the one widened"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded on a redirect"));
        }

        @Test
        @DisplayName("a refresh that does not obtain the scope refuses an API call 403")
        void refusedRefreshRefusesApiCall() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.keeping();
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(), "the API call is answered 403"),
                    () -> assertEquals(List.of(ORDERS_READ),
                            thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                            "the scope the refresh did not obtain is named"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded on a refusal"));
        }

        @Test
        @DisplayName("a narrower grant emits its re-bind cookie, then widens the rotated session, whose S lost the scope")
        void narrowerGrantEmitsRebindThenWidens() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            // What the coordinator hands back after a processed narrower grant: rotated token material, and
            // the scope the grant did not return gone from the granted set as well as from the active one.
            RecordingScopeRefresh scopeRefresh = new RecordingScopeRefresh((kept, requestedScopes) ->
                    RefreshResult.mediate(new SessionBinding.BoundSession(
                            session(SCOPED_TOKEN, Set.of(OPENID), Set.of(OPENID)), List.of(REBIND_COOKIE))));
            RecordingWidening widening = new RecordingWidening();
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, widening);
            PipelineRequest request = sessionRequest(NEEDED, navigationHeaders(), null);

            stage.process(request);

            assertAll("the rotated session's cookie is not lost on the widening redirect",
                    () -> assertEquals(1, scopeRefresh.requested.size(), "the refresh is attempted exactly once"),
                    () -> assertEquals(List.of(REBIND_COOKIE, WIDENING_COOKIE), request.responseSetCookies(),
                            "the re-bind cookie precedes the widening binding cookie"),
                    () -> assertEquals(SCOPED_TOKEN, widening.liveSessions.getFirst().accessToken(),
                            "the widening starts from the rotated session"),
                    () -> assertEquals(Set.of(OPENID), widening.liveSessions.getFirst().grantedScopes(),
                            "the widening starts from the truthful granted set, so its callback seeks the scope "
                                    + "beyond S and refuses a grant that lacks it again"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded"));
        }

        @Test
        @DisplayName("a scope refresh that ended the session clears the cookie and challenges 401")
        void endedSessionClearsCookieAndChallenges() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh =
                    new RecordingScopeRefresh((kept, requestedScopes) -> RefreshResult.sessionEnded());
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll("an ended session is answered exactly as on the near-expiry leg",
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "the request is unauthenticated"),
                    () -> assertEquals(List.of(binding.clearingSetCookieHeader()), request.responseSetCookies(),
                            "the destroyed session's cookie is cleared"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded"));
        }

        @Test
        @DisplayName("a scope refresh that failed the request challenges 401 without clearing the cookie")
        void requestFailedChallengesWithoutClearing() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh =
                    new RecordingScopeRefresh((kept, requestedScopes) -> RefreshResult.requestFailed());
            SessionAuthenticationStage stage = stage(binding, scopeRefresh, unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll("the session is still live, so its cookie stays",
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(), "the request is unauthenticated"),
                    () -> assertTrue(request.responseSetCookies().isEmpty(), "no clearing cookie is emitted"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded"));
        }

        @Test
        @DisplayName("on_failure: reject answers a navigation 401 when the scope refresh ended the session")
        void rejectPolicyGovernsScopeRefreshFailure() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh =
                    new RecordingScopeRefresh((kept, requestedScopes) -> RefreshResult.sessionEnded());
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), scopeRefresh, unreachableWidening(),
                    OnFailure.REJECT, STEP_UP_PATH);
            PipelineRequest request = sessionRequest(NEEDED, navigationHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(EventType.TOKEN_MISSING, thrown.getEventType(),
                            "reject answers 401 instead of re-driving the login"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "reject never redirects"));
        }
    }

    @Nested
    @DisplayName("Missing scope outside the granted set S")
    class OutsideGrantedSet {

        @Test
        @DisplayName("redirects a navigation 302 into a widening of the live session")
        void redirectsNavigationIntoWidening() {
            SessionRecord live = session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID));
            RecordingWidening widening = new RecordingWidening();
            SessionAuthenticationStage stage = stage(bindingWith(live), unreachableScopeRefresh(), widening);
            PipelineRequest request = sessionRequest(NEEDED, navigationHeaders(), null);

            stage.process(request);

            assertAll("a scope no refresh can obtain is widened without a refresh attempt",
                    () -> assertEquals(Optional.of(302), request.shortCircuitStatus(), "the navigation is redirected"),
                    () -> assertEquals(WIDENING_LOCATION, request.responseHeaders().get("Location"),
                            "the redirect targets the widening"),
                    () -> assertEquals(List.of(WIDENING_COOKIE), request.responseSetCookies(),
                            "the widening's binding cookie is emitted"),
                    () -> assertEquals(List.of(live), widening.liveSessions, "the live session is the one widened"),
                    () -> assertEquals(List.of(NEEDED), widening.neededScopes,
                            "the widening is asked for the route's needed scopes"),
                    () -> assertEquals(List.of("/app/orders"), widening.returnUrls,
                            "the browser returns to the requested path"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded on a redirect"));
        }

        @Test
        @DisplayName("hands the widening the requested path plus its raw query as the return URL")
        void wideningReturnUrlCarriesRawQuery() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID)));
            RecordingWidening widening = new RecordingWidening();
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), widening);
            PipelineRequest request = sessionRequest(NEEDED, navigationHeaders(), null,
                    List.of(new QueryParameter("tab", "a"), new QueryParameter("b", null)));

            stage.process(request);

            assertEquals(List.of("/app/orders?tab=a&b"), widening.returnUrls,
                    "the query is carried verbatim, bare names kept bare");
        }

        @Test
        @DisplayName("refuses an API call 403 naming the missing scopes and the step-up URL")
        void refusesApiCallWithProblemMembers() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID)));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            Map<String, Object> members = thrown.getProblemExtensions();
            assertAll("the 403 tells the client what is missing and where to obtain it",
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(), "the API call is answered 403"),
                    () -> assertEquals(List.of("missing_scopes", "step_up_url"), List.copyOf(members.keySet()),
                            "exactly the two documented members, in rendering order"),
                    () -> assertEquals(List.of(ORDERS_READ), members.get("missing_scopes"),
                            "only the scope the session lacks is named"),
                    () -> assertEquals("/auth/step-up?returnUrl=%2Fapp%2Forders", members.get("step_up_url"),
                            "the step-up URL is relative and returns to the refused path"),
                    () -> assertNull(request.responseHeaders().get(WWW_AUTHENTICATE),
                            "a session answer carries no bearer challenge"),
                    () -> assertTrue(request.responseSetCookies().isEmpty(), "the live session's cookie is untouched"),
                    () -> assertTrue(request.shortCircuitStatus().isEmpty(), "an API call is never redirected"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded on a refusal"));
        }

        @Test
        @DisplayName("lists several missing scopes sorted")
        void listsSeveralMissingScopesSorted() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID)));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(Set.of(ORDERS_WRITE, OPENID, ORDERS_READ), xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(List.of(ORDERS_READ, ORDERS_WRITE),
                    thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                    "the missing scopes are sorted and the covered one is left out");
        }

        @Test
        @DisplayName("percent-encodes the refused path and query into the step-up URL")
        void stepUpUrlPercentEncodesReturnUrl() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID)));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null,
                    List.of(new QueryParameter("tab", "a"), new QueryParameter("b", null)));

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals("/auth/step-up?returnUrl=%2Fapp%2Forders%3Ftab%3Da%26b",
                    thrown.getProblemExtensions().get(SessionAuthenticationStage.STEP_UP_URL_MEMBER),
                    "the whole return target travels as one query parameter value");
        }

        @Test
        @DisplayName("omits the step-up URL when oidc.step_up.path is not configured")
        void omitsStepUpUrlWhenPathUnset() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID)));
            SessionAuthenticationStage stage = stage(binding, identityRefresh(), unreachableScopeRefresh(),
                    unreachableWidening(), OnFailure.REAUTHENTICATE, null);
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(), "the refusal is unchanged"),
                    () -> assertEquals(Map.of("missing_scopes", List.of(ORDERS_READ)), thrown.getProblemExtensions(),
                            "only the missing scopes are named when no step-up path exists"));
        }

        @Test
        @DisplayName("token_relay: false still refuses an API call 403")
        void relayOffStillRefuses() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), Set.of(OPENID)));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), false);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(),
                    "withholding the token does not exempt the session from the scope check");
        }

        @Test
        @DisplayName("issues no refresh when only part of the missing set was granted before")
        void partlyGrantedMissingSetIsNotRefreshed() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            SessionAuthenticationStage stage = stage(binding, unreachableScopeRefresh(), unreachableWidening());
            PipelineRequest request = sessionRequest(Set.of(OPENID, ORDERS_READ, ORDERS_WRITE), xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertEquals(List.of(ORDERS_READ, ORDERS_WRITE),
                    thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                    "a refresh cannot obtain the ungranted scope, so both stay missing");
        }
    }

    /**
     * The near-expiry leg re-bound the session with a new cookie, so the request's own {@code Cookie}
     * header names the binding that leg rotated away. A scope refresh started from it would present a
     * refresh token the identity provider has already retired; the stage skips it. The cookie-less
     * re-bind is the matched control: the same rotation without a new cookie is refreshed normally.
     */
    @Nested
    @DisplayName("Near-expiry leg re-bound the session")
    class NearExpiryRebind {

        @Test
        @DisplayName("with a new cookie: skips the scope refresh and widens a navigation")
        void rotatedCookieSkipsScopeRefreshAndWidens() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingWidening widening = new RecordingWidening();
            SessionAuthenticationStage stage = stage(binding, rebindingRefresh(List.of(REBIND_COOKIE)),
                    unreachableScopeRefresh(), widening, OnFailure.REAUTHENTICATE, STEP_UP_PATH);
            PipelineRequest request = sessionRequest(NEEDED, navigationHeaders(), null);

            stage.process(request);

            assertAll("the stale Cookie header never starts a second exchange",
                    () -> assertEquals(Optional.of(302), request.shortCircuitStatus(), "the navigation is redirected"),
                    () -> assertEquals(List.of(REBIND_COOKIE, WIDENING_COOKIE), request.responseSetCookies(),
                            "the near-expiry re-bind cookie still reaches the browser"),
                    () -> assertEquals(NEAR_EXPIRY_TOKEN, widening.liveSessions.getFirst().accessToken(),
                            "the widening starts from the re-bound session"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded"));
        }

        @Test
        @DisplayName("with a new cookie: skips the scope refresh and refuses an API call 403")
        void rotatedCookieSkipsScopeRefreshAndRefuses() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            SessionAuthenticationStage stage = stage(binding, rebindingRefresh(List.of(REBIND_COOKIE)),
                    unreachableScopeRefresh(), unreachableWidening(), OnFailure.REAUTHENTICATE, STEP_UP_PATH);
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

            assertAll(
                    () -> assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(), "the API call is answered 403"),
                    () -> assertEquals(List.of(ORDERS_READ),
                            thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                            "the scope is named although it was granted before"),
                    () -> assertEquals(List.of(REBIND_COOKIE), request.responseSetCookies(),
                            "the re-bind cookie rides on the refusal, so the next request is refreshed"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(), "no bearer is recorded"));
        }

        @Test
        @DisplayName("without a new cookie: the scope refresh still runs (matched control)")
        void cookielessRebindStillRefreshes() {
            SessionBinding binding = bindingWith(session(SESSION_TOKEN, Set.of(OPENID), NEEDED));
            RecordingScopeRefresh scopeRefresh = RecordingScopeRefresh.obtaining(SCOPED_TOKEN, List.of());
            SessionAuthenticationStage stage = stage(binding, rebindingRefresh(List.of()), scopeRefresh,
                    unreachableWidening(), OnFailure.REAUTHENTICATE, STEP_UP_PATH);
            PipelineRequest request = sessionRequest(NEEDED, xhrHeaders(), null);

            stage.process(request);

            assertAll("a re-bind invisible to the browser leaves the Cookie header current",
                    () -> assertEquals(1, scopeRefresh.requested.size(), "the scope refresh is issued"),
                    () -> assertEquals(Optional.of(SCOPED_TOKEN), request.mediatedBearer(),
                            "the scope-refreshed token is relayed"));
        }

        /** A near-expiry seam that rotates the token, keeps {@code A} short of the scope, and emits {@code cookies}. */
        private static SessionAuthenticationStage.TokenRefresh rebindingRefresh(List<String> cookies) {
            return (live, cookieHeader, now) -> RefreshResult.mediate(new SessionBinding.BoundSession(
                    rebind(live, NEAR_EXPIRY_TOKEN, live.activeScopes()), cookies));
        }
    }

    @Test
    @DisplayName("rejects an absent scope-refresh or widening seam, and accepts an absent step-up path")
    void validatesItsScopeSeams() {
        SessionBinding binding = bindingWith(session(SESSION_TOKEN, NEEDED, NEEDED));
        SessionAuthenticationStage.TokenRefresh refresh = identityRefresh();
        SessionAuthenticationStage.ScopeRefresh scopeRefresh = unreachableScopeRefresh();
        SessionAuthenticationStage.LoginInitiation login = redirectLogin();
        SessionAuthenticationStage.WideningInitiation widening = unreachableWidening();

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new SessionAuthenticationStage(binding, refresh,
                                null, login, widening, OnFailure.REAUTHENTICATE, STEP_UP_PATH, CLOCK),
                        "the scope-refresh seam is mandatory"),
                () -> assertThrows(NullPointerException.class, () -> new SessionAuthenticationStage(binding, refresh,
                                scopeRefresh, login, null, OnFailure.REAUTHENTICATE, STEP_UP_PATH, CLOCK),
                        "the widening seam is mandatory"),
                () -> assertDoesNotThrow(() -> new SessionAuthenticationStage(binding, refresh, scopeRefresh, login,
                        widening, OnFailure.REAUTHENTICATE, null, CLOCK), "an unset step-up path is a valid shape"));
    }

    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.ScopeRefresh scopeRefresh,
            SessionAuthenticationStage.WideningInitiation widening) {
        return stage(binding, identityRefresh(), scopeRefresh, widening, OnFailure.REAUTHENTICATE, STEP_UP_PATH);
    }

    private static SessionAuthenticationStage stage(SessionBinding binding,
            SessionAuthenticationStage.TokenRefresh tokenRefresh, SessionAuthenticationStage.ScopeRefresh scopeRefresh,
            SessionAuthenticationStage.WideningInitiation widening, OnFailure onFailure, @Nullable String stepUpPath) {
        return new SessionAuthenticationStage(binding, tokenRefresh, scopeRefresh, redirectLogin(), widening,
                onFailure, stepUpPath, CLOCK);
    }

    /** The session cookie of the shared fixtures with the given {@code Accept} value. */
    private static Map<String, List<String>> headers(String accept) {
        return Map.of("cookie", navigationHeaders().get("cookie"), "accept", List.of(accept));
    }

    /**
     * A scope-refresh seam that records the scope set of every call and answers through
     * {@code answer}, which receives the session the stage handed in.
     */
    private static final class RecordingScopeRefresh implements SessionAuthenticationStage.ScopeRefresh {

        private final BiFunction<SessionRecord, Set<String>, RefreshResult> answer;
        private final List<Set<String>> requested = new ArrayList<>();

        private RecordingScopeRefresh(BiFunction<SessionRecord, Set<String>, RefreshResult> answer) {
            this.answer = answer;
        }

        /** A refresh that obtains exactly the requested set under a rotated token. */
        static RecordingScopeRefresh obtaining(String accessToken, List<String> cookies) {
            return new RecordingScopeRefresh((kept, requestedScopes) -> RefreshResult.mediate(
                    new SessionBinding.BoundSession(rebind(kept, accessToken, requestedScopes), cookies)));
        }

        /** A refresh that keeps the session exactly as it was — the set was not obtained. */
        static RecordingScopeRefresh keeping() {
            return new RecordingScopeRefresh((kept, requestedScopes) ->
                    RefreshResult.mediate(new SessionBinding.BoundSession(kept, List.of())));
        }

        @Override
        public RefreshResult refreshForScopes(SessionRecord live, @Nullable String cookieHeader,
                Set<String> requestedScopes, Instant now) {
            Set<String> requestedCopy = Set.copyOf(requestedScopes);
            requested.add(requestedCopy);
            return answer.apply(live, requestedCopy);
        }
    }

    /** A widening seam that records every call and answers with a fixed challenge. */
    private static final class RecordingWidening implements SessionAuthenticationStage.WideningInitiation {

        private final List<SessionRecord> liveSessions = new ArrayList<>();
        private final List<String> returnUrls = new ArrayList<>();
        private final List<Set<String>> neededScopes = new ArrayList<>();

        @Override
        public LoginChallenge initiate(SessionRecord live, String returnUrl, Set<String> needed, Instant now) {
            liveSessions.add(live);
            returnUrls.add(returnUrl);
            neededScopes.add(Set.copyOf(needed));
            return new LoginChallenge(WIDENING_LOCATION, List.of(WIDENING_COOKIE));
        }
    }
}
