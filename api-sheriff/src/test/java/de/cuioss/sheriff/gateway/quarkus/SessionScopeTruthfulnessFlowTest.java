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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.refresh.EndedRefreshTokens;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint.CallbackOutcome;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
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
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A session whose granted scope set {@code S} names a scope the identity provider no longer grants,
 * driven through the assembled session path: the real {@link SessionAuthenticationStage}, the real
 * {@link TokenRefreshCoordinator} behind the producer's own seam adapters
 * ({@link BffRuntimeProducer#nearExpiryRefresh} and {@link BffRuntimeProducer#scopeRefresh}), the real
 * {@link SessionWidening} and the real {@link CallbackEndpoint}, all over one server-mode binding.
 * <p>
 * Only the two engine seams are bound to stubs, and both answer with the same thing: the scope the
 * identity provider grants in that test. The refresh exchange counts its calls, and the widening
 * authorization seam records every request it is asked to build, so "how often was the identity
 * provider asked" is read off directly rather than inferred.
 * <p>
 * The invariant: such a session is never relayed, a navigation gets no redirect after the terminal
 * {@code 403}, and a scope the provider <em>does</em> still grant keeps being restored by a refresh
 * and relayed (the matched control).
 * <p>
 * The scope names are literals on purpose: they are the values the route's needed set, the session's
 * two scope sets and the grants are compared by, so a generated value would assert nothing about the
 * comparison.
 */
@DisplayName("Session route — a scope of S the identity provider no longer grants is refused, never looped or re-refreshed")
class SessionScopeTruthfulnessFlowTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration LEEWAY = Duration.ofSeconds(60);

    private static final String ORIGIN = "https://gw.example.com";
    private static final String CALLBACK_URI = ORIGIN + "/auth/callback";
    private static final String STEP_UP_PATH = "/auth/step-up";
    private static final String ROUTE_PATH = "/app/orders";
    private static final String WIDENING_URL = "https://idp.example.com/authorize?client_id=widen";

    private static final String SUBJECT = "user-sub-1";
    private static final String OPENID = "openid";
    private static final String ORDERS_READ = "orders:read";
    /** What the route needs, and what the session was granted before the provider stopped granting it. */
    private static final Set<String> NEEDED = Set.of(OPENID, ORDERS_READ);
    private static final String STILL_GRANTS_BOTH = "openid orders:read";

    private static final String LIVE_REFRESH_TOKEN = "live-refresh-token";
    private static final String ROTATED_ACCESS_TOKEN = "rotated-access-token";

    private static final String NAVIGATION_ACCEPT = "text/html,application/xhtml+xml";
    private static final String API_ACCEPT = "application/json";

    /** One request the widening authorization seam was asked to build. */
    private record WideningCall(Set<String> scopes, boolean silent, FlowContext context) {
    }

    /** The idle timeout equals the absolute lifetime, so it is not in play in these flows. */
    private final InMemorySessionStore store = new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE,
            sessionId -> {
            });
    private final SessionBinding binding = new ServerSessionBinding(store,
            new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
    private final PendingAuthorizationStore.InMemory pendingStore = new PendingAuthorizationStore.InMemory(8);
    private final BindingCookieCodec bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
    private final List<WideningCall> wideningCalls = new ArrayList<>();
    private final AtomicInteger refreshGrants = new AtomicInteger();
    private final SessionWidening sessionWidening = new SessionWidening((scopes, silent) -> {
        FlowContext context = FlowContext.create(CALLBACK_URI);
        wideningCalls.add(new WideningCall(Set.copyOf(scopes), silent, context));
        return new AuthorizationCodeFlow.AuthorizationRedirect(WIDENING_URL, context);
    }, pendingStore, bindingCodec, ORIGIN, "/");

    /** The request-cookie pair of the live session. */
    private String sessionCookie;

    @Test
    @DisplayName("an API call costs one refresh grant in total: the refused scope leaves S and later calls are refused locally")
    void apiCallsRefreshOnceThenAreRefusedLocally() {
        bindLive(LIVE_REFRESH_TOKEN);
        SessionAuthenticationStage stage = stage(OPENID);
        PipelineRequest firstCall = request(API_ACCEPT);
        PipelineRequest secondCall = request(API_ACCEPT);
        PipelineRequest thirdCall = request(API_ACCEPT);

        GatewayException first = assertThrows(GatewayException.class, () -> stage.process(firstCall));
        int grantsAfterFirstCall = refreshGrants.get();
        GatewayException second = assertThrows(GatewayException.class, () -> stage.process(secondCall));
        GatewayException third = assertThrows(GatewayException.class, () -> stage.process(thirdCall));

        assertAll("the refresh token is presented for the scope once, not once per request",
                () -> assertEquals(EventType.SCOPE_MISSING, first.getEventType(), "the first call is refused 403"),
                () -> assertEquals(1, grantsAfterFirstCall, "the first call asks the identity provider once"),
                () -> assertEquals(EventType.SCOPE_MISSING, second.getEventType(), "the second call is refused 403"),
                () -> assertEquals(EventType.SCOPE_MISSING, third.getEventType(), "the third call is refused 403"),
                () -> assertEquals(1, refreshGrants.get(), "no later call starts another refresh grant"),
                () -> assertEquals(Set.of(OPENID), storedSession().grantedScopes(),
                        "S no longer names the scope the processed grant did not return"),
                () -> assertTrue(wideningCalls.isEmpty(), "an API call is never widened"));
    }

    @Test
    @DisplayName("a navigation is widened once and its callback is a terminal 403, not a redirect back to the route")
    void navigationEndsInTerminalRefusal() {
        bindLive(LIVE_REFRESH_TOKEN);
        PipelineRequest navigation = request(NAVIGATION_ACCEPT);

        stage(OPENID).process(navigation);
        CallbackOutcome callback = followWidening(navigation, OPENID);

        assertAll("the scope lies beyond S by the time the widening callback checks the grant",
                () -> assertEquals(Optional.of(302), navigation.shortCircuitStatus(),
                        "the navigation is redirected into a widening"),
                () -> assertEquals(1, refreshGrants.get(), "after exactly one refresh grant"),
                () -> assertEquals(NEEDED, wideningCalls.getFirst().scopes(),
                        "the widening asks for the reduced S united with the route's needed scopes"),
                () -> assertTrue(wideningCalls.getFirst().silent(), "as a silent attempt"),
                () -> assertEquals(403, callback.status(), "a grant lacking the scope again is refused"),
                () -> assertNull(callback.location(), "the refusal redirects nowhere — the browser stops here"),
                () -> assertTrue(callback.setCookieHeaders().isEmpty(), "and touches no cookie"),
                () -> assertEquals(1, wideningCalls.size(), "no second widening is started"),
                () -> assertEquals(Set.of(OPENID), storedSession().grantedScopes(),
                        "the session is kept, with a granted set that is true"));
    }

    @Test
    @DisplayName("a session with no refresh token is merged once, then refused: the merge takes the scope out of S")
    void sessionWithoutRefreshTokenIsRefusedOnTheSecondRound() {
        bindLive(null);
        SessionAuthenticationStage stage = stage(OPENID);
        PipelineRequest firstNavigation = request(NAVIGATION_ACCEPT);

        stage.process(firstNavigation);
        CallbackOutcome firstRound = followWidening(firstNavigation, OPENID);
        // Built only now: the merged widening re-issued the session cookie, and the browser's next
        // navigation carries the value that widening returned.
        PipelineRequest secondNavigation = request(NAVIGATION_ACCEPT);
        stage.process(secondNavigation);
        CallbackOutcome secondRound = followWidening(secondNavigation, OPENID);

        assertAll("without a refresh the widening merge is what makes S truthful",
                () -> assertEquals(0, refreshGrants.get(), "there is no refresh token to present"),
                () -> assertEquals(302, firstRound.status(),
                        "S still named the scope, so nothing was sought beyond it and the grant is merged"),
                () -> assertEquals(ROUTE_PATH, firstRound.location(), "the browser is sent back to the route once"),
                () -> assertEquals(403, secondRound.status(),
                        "the merge took the scope out of S, so the next grant lacking it is refused"),
                () -> assertNull(secondRound.location(), "the second round redirects nowhere"),
                () -> assertEquals(2, wideningCalls.size(), "two widenings, and the second one is the last"),
                () -> assertEquals(Set.of(OPENID), storedSession().grantedScopes()));
    }

    @Test
    @DisplayName("control: a scope the identity provider still grants is restored by one refresh and keeps being relayed")
    void stillGrantedScopeIsRestoredAndRelayed() {
        bindLive(LIVE_REFRESH_TOKEN);
        SessionAuthenticationStage stage = stage(STILL_GRANTS_BOTH);
        PipelineRequest first = request(API_ACCEPT);
        PipelineRequest second = request(API_ACCEPT);

        stage.process(first);
        stage.process(second);

        assertAll("the same session and route are served when the provider returns the scope",
                () -> assertEquals(Optional.of(ROTATED_ACCESS_TOKEN), first.mediatedBearer(),
                        "the first call relays the token the refresh obtained"),
                () -> assertEquals(Optional.of(ROTATED_ACCESS_TOKEN), second.mediatedBearer(),
                        "the second call relays it again"),
                () -> assertEquals(1, refreshGrants.get(), "one refresh restored the scope; a satisfied session needs none"),
                () -> assertEquals(NEEDED, storedSession().grantedScopes(), "S is unchanged"),
                () -> assertEquals(NEEDED, storedSession().activeScopes(), "and A carries the scope again"),
                () -> assertTrue(wideningCalls.isEmpty(), "nothing is widened"));
    }

    /**
     * The session stage as the producer assembles it, over a coordinator whose refresh exchange is
     * answered with {@code refreshGrantScope}. The access token is ten minutes from expiry, outside the
     * leeway, so only the scope-driven leg can reach the engine.
     */
    private SessionAuthenticationStage stage(String refreshGrantScope) {
        TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY,
                sessionRecord -> NOW.plusSeconds(600),
                (refreshToken, scopes) -> {
                    refreshGrants.incrementAndGet();
                    return rotation(refreshGrantScope);
                },
                binding, refreshToken -> {
                }, Runnable::run, EndedRefreshTokens.inert());
        return new SessionAuthenticationStage(binding,
                BffRuntimeProducer.nearExpiryRefresh(coordinator),
                BffRuntimeProducer.scopeRefresh(coordinator),
                (returnUrl, scopes, now) -> {
                    throw new AssertionError("a live session must never be sent into a fresh login");
                },
                (live, returnUrl, neededScopes, now) -> {
                    LoginFlow.LoginRedirect redirect = sessionWidening.initiate(live, returnUrl, neededScopes,
                            PendingAuthorizationRecord.Widening.Attempt.SILENT, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
                SessionAuthenticationStage.OnFailure.REAUTHENTICATE, STEP_UP_PATH, CLOCK);
    }

    /**
     * Follows the widening redirect {@code navigation} was answered with, the way the browser does: the
     * identity provider answers with a code, and the callback's exchange yields {@code grantScope}.
     */
    private CallbackOutcome followWidening(PipelineRequest navigation, String grantScope) {
        String bindingCookie = cookiePair(navigation.responseSetCookies().getLast());
        String state = wideningCalls.getLast().context().state();
        CallbackEndpoint callback = new CallbackEndpoint((context, params) -> authorizationGrant(grantScope),
                pendingStore, bindingCodec, binding, SESSION_TTL, sessionWidening);
        CallbackOutcome outcome = callback.handle("code=widening-code&state=" + state,
                bindingCookie + "; " + sessionCookie, NOW);
        // A merged widening re-issues the session cookie value; the browser holds the returned one from
        // here on, and the previous value resolves nothing.
        outcome.setCookieHeaders().stream()
                .filter(header -> header.startsWith(SessionCookieCodec.DEFAULT_COOKIE_NAME + "="))
                .findFirst()
                .ifPresent(header -> sessionCookie = cookiePair(header));
        return outcome;
    }

    /** Binds the live session: {@code openid} active, both needed scopes granted. */
    private void bindLive(@Nullable String refreshToken) {
        SessionBinding.BoundSession bound = binding.bind(SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken("live-access-token")
                .refreshToken(refreshToken)
                .idToken("live-id-token")
                .sub(SUBJECT)
                .expiresAt(NOW.plus(SESSION_TTL))
                .activeScopes(Set.of(OPENID))
                .grantedScopes(NEEDED)
                .build(), NOW);
        sessionCookie = cookiePair(bound.setCookieHeaders().getFirst());
    }

    private SessionRecord storedSession() {
        return binding.resolve(sessionCookie, NOW).orElseThrow();
    }

    /** A request for the session route needing {@link #NEEDED}, carrying the live session's cookie. */
    private PipelineRequest request(String accept) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(ROUTE_PATH)
                .queryParameters(List.of())
                .headers(Map.of("cookie", List.of(sessionCookie), "accept", List.of(accept)))
                .build();
        request.canonicalPath(ROUTE_PATH);
        request.selectedRoute(RouteRuntime.builder().id("orders")
                .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                .neededScopes(NEEDED)
                .build());
        return request;
    }

    /**
     * The refresh response of an identity provider that grants {@code scope}. The scope delta is the
     * engine's own classification of the response; the coordinator decides on the granted scope alone,
     * so one fixed value serves every test here.
     */
    private static RotationResult rotation(String scope) {
        Map<String, ClaimValue> claims = new HashMap<>();
        claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        return new RotationResult(new AccessTokenContent(claims, ROTATED_ACCESS_TOKEN), "rotated-refresh-token",
                "rotated-id-token", 300L, true, scope, RotationResult.ScopeDelta.EQUAL);
    }

    /** The validated tokens of an authorization grant whose access token carries {@code scope}. */
    private static AuthorizationCodeFlow.AuthenticationResult authorizationGrant(String scope) {
        Map<String, ClaimValue> accessClaims = new HashMap<>();
        accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        accessClaims.put(ClaimName.SCOPE.getName(), ClaimValue.forPlainString(scope));
        Map<String, ClaimValue> idClaims = new HashMap<>();
        idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        return new AuthorizationCodeFlow.AuthenticationResult(
                new AccessTokenContent(accessClaims, "widened-access-token"),
                new IdTokenContent(idClaims, "widened-id-token"), "widened-refresh-token");
    }

    /** The {@code name=value} request-cookie pair of a {@code Set-Cookie} header value. */
    private static String cookiePair(String setCookieHeader) {
        return setCookieHeader.split(";", 2)[0];
    }
}
