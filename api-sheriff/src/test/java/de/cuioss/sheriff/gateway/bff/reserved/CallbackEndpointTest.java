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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint.CallbackOutcome;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint.CodeExchange;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CallbackEndpoint}: the OIDC auth-code callback orchestration — the BFF-13
 * duplicate-parameter rejection (via {@code parse(rawQuery)}), the browser-binding checks (D2b),
 * and the success path that drives the exchange seam, creates the session, and redirects.
 * <p>
 * <strong>Every case below is a {@code response_mode=query} callback.</strong> The gateway drives
 * the authorization request with {@code response_mode=query}, so the live callback is a top-level
 * GET whose {@code code}/{@code state} arrive in the query string, and the string each test hands
 * {@code handle(..)} IS that raw query. The endpoint itself stays source-neutral by design — it
 * parses whatever raw parameter string it is given — which is exactly why the assertion that the
 * <em>runtime</em> hands it the uncollapsed query lives one layer out, in
 * {@code GatewayEdgeRouteBffWiringTest}'s query-mode dispatch coverage rather than here.
 * <p>
 * The engine exchange is driven through the {@link CodeExchange} seam, so the success and
 * exchange-failure paths are exercised with a hand-built {@link AuthorizationCodeFlow.AuthenticationResult}
 * — no live token endpoint, no signed tokens, no test double framework.
 * <p>
 * The widening cases drive a real {@link SessionWidening} whose authorization seam records each
 * attempt it is asked for, so the single interactive re-drive and the absence of a second one are
 * observable, and they run the merge against both session bindings.
 */
@EnableTestLogger
class CallbackEndpointTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String RETURN_URL = "/dashboard";
    private static final String SUBJECT = "user-sub-1";
    private static final String RAW_ACCESS_TOKEN = "raw-access-token";
    private static final String RAW_ID_TOKEN = "raw-id-token";
    private static final String RAW_REFRESH_TOKEN = "raw-refresh-token";
    private static final String IDP_SID = "idp-sid-9";
    private static final List<String> REQUESTED_SCOPES = List.of("openid", "profile", "email", "orders:read");

    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String CALLBACK_URI = GATEWAY_ORIGIN + "/auth/callback";
    private static final String WIDENING_AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=widen";

    /** One call of the widening authorization seam: the set it asked for, whether silent, its context. */
    private record WideningCall(Set<String> scopes, boolean silent, FlowContext context) {
    }

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private InMemorySessionStore sessionStore;
    private SessionCookieCodec sessionCodec;
    private SessionBinding sessionBinding;
    private List<WideningCall> wideningCalls;
    private SessionWidening sessionWidening;
    private CallbackEndpoint endpoint;

    private String state;
    private String recordId;
    private String bindingCookieHeader;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        // The idle timeout equals the absolute lifetime, so it is not in play in these cases.
        sessionStore = new InMemorySessionStore(16, SESSION_TTL);
        sessionCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL);
        sessionBinding = new ServerSessionBinding(sessionStore, sessionCodec);
        wideningCalls = new ArrayList<>();
        sessionWidening = new SessionWidening((scopes, silent) -> {
            FlowContext context = FlowContext.create(CALLBACK_URI);
            wideningCalls.add(new WideningCall(Set.copyOf(scopes), silent, context));
            return new AuthorizationCodeFlow.AuthorizationRedirect(WIDENING_AUTHORIZATION_URL, context);
        }, pendingStore, bindingCodec, GATEWAY_ORIGIN, RETURN_URL);
        endpoint = endpoint(successfulExchange(), sessionBinding);

        FlowContext flow = FlowContext.create(CALLBACK_URI);
        state = flow.state();
        PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, RETURN_URL, REQUESTED_SCOPES, T0);
        pendingStore.store(pending);
        recordId = pending.id();
        bindingCookieHeader = cookiePair(bindingCodec.toSetCookieHeader(recordId));
    }

    /** The callback endpoint over {@code exchange} and {@code binding}, sharing the fixture's widening. */
    private CallbackEndpoint endpoint(CodeExchange exchange, SessionBinding binding) {
        return new CallbackEndpoint(exchange, pendingStore, bindingCodec, binding, SESSION_TTL, sessionWidening);
    }

    /** The {@code name=value} request-cookie pair of a {@code Set-Cookie} header value. */
    private static String cookiePair(String setCookieHeader) {
        return setCookieHeader.split(";", 2)[0];
    }

    private static CodeExchange successfulExchange() {
        return exchangeReturning(RAW_REFRESH_TOKEN);
    }

    /**
     * A successful exchange whose result carries {@code refreshToken} — the third component the
     * engine's {@code AuthenticationResult} returns. Parameterising it is what lets the session
     * assertions cover both the granted case and the {@code null} case an authorization server that
     * issues no refresh token produces, without duplicating the claim fixtures.
     */
    private static CodeExchange exchangeReturning(@Nullable String refreshToken) {
        return exchangeReturning(refreshToken, null);
    }

    /**
     * A successful exchange whose access token carries {@code scopeClaim} as its {@code scope} claim,
     * or no {@code scope} claim at all when {@code null}.
     */
    private static CodeExchange exchangeReturning(@Nullable String refreshToken, @Nullable ClaimValue scopeClaim) {
        Map<String, ClaimValue> accessClaims = new HashMap<>();
        accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        if (scopeClaim != null) {
            accessClaims.put(ClaimName.SCOPE.getName(), scopeClaim);
        }
        AccessTokenContent access = new AccessTokenContent(accessClaims, RAW_ACCESS_TOKEN);

        Map<String, ClaimValue> idClaims = new HashMap<>(Map.of(
                ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT),
                "sid", ClaimValue.forPlainString(IDP_SID),
                "acr", ClaimValue.forPlainString("urn:mace:incommon:iap:silver"),
                "auth_time", ClaimValue.forPlainString("1721730000")));
        IdTokenContent id = new IdTokenContent(idClaims, RAW_ID_TOKEN);

        AuthorizationCodeFlow.AuthenticationResult result =
                new AuthorizationCodeFlow.AuthenticationResult(access, id, refreshToken);
        return (context, params) -> result;
    }

    /**
     * An exchange whose validated ID token is far larger than the browser-safe cookie budget — the
     * realistic driver of the documented {@code bind()} failure in stateless cookie mode.
     * <p>
     * The oversized token is <em>random</em> material rendered base64url, not a repeated character.
     * The codec deflates the payload before sealing, so a run of one
     * character now seals comfortably <em>inside</em> the budget: a repeated-character fixture would
     * bind successfully and this test would silently stop exercising the failure it was written for.
     * Base64 carries six bits per byte, so deflate recovers only that quarter and the value stays
     * over the budget for the reason the test needs.
     */
    private static CodeExchange oversizedExchange() {
        Map<String, ClaimValue> accessClaims = new HashMap<>();
        accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        AccessTokenContent access = new AccessTokenContent(accessClaims, RAW_ACCESS_TOKEN);

        byte[] incompressible = new byte[12_288];
        new SecureRandom().nextBytes(incompressible);

        Map<String, ClaimValue> idClaims = new HashMap<>();
        idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        IdTokenContent id = new IdTokenContent(idClaims,
                Base64.getUrlEncoder().withoutPadding().encodeToString(incompressible));

        AuthorizationCodeFlow.AuthenticationResult result =
                new AuthorizationCodeFlow.AuthenticationResult(access, id, RAW_REFRESH_TOKEN);
        return (context, params) -> result;
    }

    /** The stateless cookie binding — {@code bind()} refuses a session past the cookie-size budget. */
    private static SessionBinding cookieBinding() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        byte[] activityKey = new byte[32];
        Arrays.fill(activityKey, (byte) 0x44);
        return new CookieSessionBinding(
                new SealedSessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL,
                        SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"), (byte) 1),
                salt,
                new SessionActivityCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                        new SecretKeySpec(activityKey, "AES"), (byte) 2),
                SESSION_TTL);
    }

    /**
     * A {@link SessionBinding} that behaves exactly like the one it wraps, except that it runs
     * {@code termination} once, immediately after a {@code resolve} returned a live session. The
     * callback resolves the live session and persists the merge later in the same call, so this is the
     * deterministic stand-in for a logout or a back-channel logout arriving between the two: the
     * session is terminated through the wrapped binding's own destroy methods, on the calling thread,
     * with no sleep and no second thread.
     */
    private static final class TerminatingAfterResolve implements SessionBinding {

        private final SessionBinding delegate;
        private final BiConsumer<SessionBinding, SessionRecord> termination;
        private int terminationsRun;

        TerminatingAfterResolve(SessionBinding delegate, BiConsumer<SessionBinding, SessionRecord> termination) {
            this.delegate = delegate;
            this.termination = termination;
        }

        /** How often the termination ran — once per resolve that returned a live session. */
        int terminationsRun() {
            return terminationsRun;
        }

        @Override
        public BoundSession bind(SessionRecord session, Instant now) {
            return delegate.bind(session, now);
        }

        @Override
        public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
            Optional<SessionRecord> resolved = delegate.resolve(cookieHeader, now);
            resolved.ifPresent(session -> {
                terminationsRun++;
                termination.accept(delegate, session);
            });
            return resolved;
        }

        @Override
        public Optional<BoundSession> persist(SessionRecord updated, Instant now) {
            return delegate.persist(updated, now);
        }

        @Override
        public Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now) {
            return delegate.persistReissuingCookie(updated, now);
        }

        @Override
        public List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
            return delegate.recordAccess(session, cookieHeader, now);
        }

        @Override
        public void destroy(SessionRecord session) {
            delegate.destroy(session);
        }

        @Override
        public int destroyBySid(String sid) {
            return delegate.destroyBySid(sid);
        }

        @Override
        public int destroyBySub(String sub) {
            return delegate.destroyBySub(sub);
        }

        @Override
        public IdpDestruction idpDestruction() {
            return delegate.idpDestruction();
        }

        @Override
        public List<String> clearingSetCookieHeaders() {
            return delegate.clearingSetCookieHeaders();
        }
    }

    /**
     * The BFF-13 duplicate-parameter defence — the Keycloak CVE-2026-9689 class — asserted on the
     * raw query, which under {@code response_mode=query} is the live callback shape.
     * <p>
     * The defence is structural: the endpoint parses the raw string with
     * {@code CallbackParameters.parse(String)} and never {@code CallbackParameters.of(Map)}, because
     * only the raw parse can still SEE a duplicated {@code code} or {@code state}. A collapsed map
     * has already silently chosen one occurrence by the time the endpoint runs.
     */
    @Nested
    @DisplayName("BFF-13 duplicate-parameter rejection on the raw query")
    class DuplicateParameterRejection {

        @Test
        @DisplayName("Should reject a duplicate-code callback 400 without consuming the pending record")
        void shouldRejectDuplicateCode() {
            CallbackOutcome outcome = endpoint.handle("code=first&code=second&state=" + state, bindingCookieHeader, T0);

            assertEquals(400, outcome.status(), "a duplicated code is rejected by parse(rawQuery)");
            assertFalse(outcome.isRedirect());
            assertTrue(pendingStore.consume(recordId, T0).isPresent(),
                    "the record is untouched — parse fails before binding resolution");
        }

        @Test
        @DisplayName("Should reject a duplicate-state callback 400 without consuming the pending record")
        void shouldRejectDuplicateState() {
            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state + "&state=other", bindingCookieHeader,
                    T0);

            assertEquals(400, outcome.status(),
                    "state is the parameter the binding check compares, so a duplicate must be refused "
                            + "outright rather than resolved to whichever occurrence a parser happened to pick");
            assertTrue(pendingStore.consume(recordId, T0).isPresent(),
                    "the record is untouched — parse fails before binding resolution");
        }
    }

    @Nested
    @DisplayName("Browser-binding checks (D2b)")
    class BrowserBinding {

        @Test
        @DisplayName("Should reject a callback that carries no binding cookie 403 (cross-browser replay)")
        void shouldRejectWithoutBindingCookie() {
            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state, null, T0);

            assertEquals(403, outcome.status());
            assertTrue(pendingStore.consume(recordId, T0).isPresent(), "the record was never resolved");
        }

        @Test
        @DisplayName("Should reject a binding cookie that resolves no live record 403")
        void shouldRejectUnknownRecord() {
            String foreignCookie = bindingCodec.toSetCookieHeader("nonexistent-id").split(";", 2)[0];

            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state, foreignCookie, T0);

            assertEquals(403, outcome.status());
        }

        @Test
        @DisplayName("Should reject a returned state that does not match the bound record 403")
        void shouldRejectStateMismatch() {
            CallbackOutcome outcome = endpoint.handle("code=abc&state=not-the-bound-state", bindingCookieHeader, T0);

            assertEquals(403, outcome.status());
        }

        @Test
        @DisplayName("Should reject an expired pending record 403")
        void shouldRejectExpiredRecord() {
            Instant afterTtl = T0.plus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);

            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state, bindingCookieHeader, afterTtl);

            assertEquals(403, outcome.status());
        }
    }

    @Nested
    @DisplayName("IdP error and exchange failure")
    class FailurePaths {

        @Test
        @DisplayName("Should answer a bound IdP error response for a plain login 400")
        void shouldRejectIdpError() {
            CallbackOutcome outcome = endpoint.handle("error=access_denied&error_description=nope&state=" + state,
                    bindingCookieHeader, T0);

            assertEquals(400, outcome.status());
            assertTrue(outcome.setCookieHeaders().isEmpty());
            assertTrue(wideningCalls.isEmpty(), "a login error never drives a widening re-drive");
        }

        @Test
        @DisplayName("Should reject an IdP error response without a binding cookie 403 — the binding check runs first")
        void shouldRejectIdpErrorWithoutBindingCookie() {
            CallbackOutcome outcome = endpoint.handle("error=access_denied&state=" + state, null, T0);

            assertEquals(403, outcome.status(), "an unbound error is a forgery candidate, not an IdP answer");
            assertTrue(pendingStore.consume(recordId, T0).isPresent(), "the record was never resolved");
        }

        @Test
        @DisplayName("Should reject an IdP error response whose state does not match the bound record 403")
        void shouldRejectIdpErrorWithStateMismatch() {
            CallbackOutcome outcome = endpoint.handle("error=access_denied&state=not-the-bound-state",
                    bindingCookieHeader, T0);

            assertEquals(403, outcome.status());
        }

        @Test
        @DisplayName("Should reject a missing state parameter 400")
        void shouldRejectMissingState() {
            CallbackOutcome outcome = endpoint.handle("code=abc", bindingCookieHeader, T0);

            assertEquals(400, outcome.status());
        }

        @Test
        @DisplayName("Should map an engine exchange failure to 400")
        void shouldMapExchangeFailure() {
            CodeExchange failing = (context, params) -> {
                throw new ClientProtocolException("token endpoint rejected the code");
            };
            CallbackEndpoint failingEndpoint = endpoint(failing, sessionBinding);

            CallbackOutcome outcome = failingEndpoint.handle("code=abc&state=" + state, bindingCookieHeader, T0);

            assertEquals(400, outcome.status());
        }

        @Test
        @DisplayName("Should map a binding failure to 500 rather than letting IllegalStateException escape handle()")
        void shouldMapBindFailureTo500() {
            CallbackEndpoint bindFailingEndpoint = endpoint(oversizedExchange(), cookieBinding());

            CallbackOutcome outcome = assertDoesNotThrow(
                    () -> bindFailingEndpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0),
                    "a session the binding cannot hold is a clean outcome, never an escaping IllegalStateException");

            assertEquals(500, outcome.status(),
                    "the exchange succeeded and the caller did nothing wrong — an unbindable session is a "
                            + "gateway-side 500, not a 4xx");
            assertFalse(outcome.isRedirect());
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a failed binding emits no session cookie");
        }
    }

    @Nested
    @DisplayName("Successful login")
    class SuccessfulLogin {

        @Test
        @DisplayName("Should create a session, set the cookies, and redirect to the return URL")
        void shouldCompleteLogin() {
            CallbackOutcome outcome = endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            assertTrue(outcome.isRedirect(), "a successful login is a 302 redirect");
            assertEquals(302, outcome.status());
            assertEquals(RETURN_URL, outcome.location());
            assertEquals(2, outcome.setCookieHeaders().size(), "one session cookie + one binding-clearing cookie");

            String sessionSetCookie = outcome.setCookieHeaders().getFirst();
            assertTrue(sessionSetCookie.startsWith(SessionCookieCodec.DEFAULT_COOKIE_NAME + "="), sessionSetCookie);
            String bindingClear = outcome.setCookieHeaders().get(1);
            assertTrue(bindingClear.contains(BindingCookieCodec.COOKIE_NAME + "="), bindingClear);
            assertTrue(bindingClear.contains("Max-Age=0"), "the single-use binding cookie is cleared");
        }

        @Test
        @DisplayName("Should store the mediated token material and identity claims under the session id")
        void shouldStoreSession() {
            CallbackOutcome outcome = endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            String cookieHandle = sessionCodec.readCookieHandle(outcome.setCookieHeaders().getFirst()).orElseThrow();
            Optional<SessionRecord> session = sessionStore.resolve(cookieHandle, T0);

            assertTrue(session.isPresent(), "the session was created under the opaque id from the cookie");
            SessionRecord sessionRecord = session.get();
            assertEquals(RAW_ACCESS_TOKEN, sessionRecord.accessToken());
            assertEquals(RAW_ID_TOKEN, sessionRecord.idToken());
            assertEquals(SUBJECT, sessionRecord.sub());
            assertEquals(IDP_SID, sessionRecord.sid());
            assertEquals(T0.plus(SESSION_TTL), sessionRecord.expiresAt(), "the session TTL is absolute from login");
        }

        @Test
        @DisplayName("Should mint a fresh session id per login, keep it out of the cookie, and give the cookie the full ttl")
        void shouldMintFreshSessionIdPerLogin() {
            CallbackOutcome first = endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);
            FlowContext secondFlow = FlowContext.create(CALLBACK_URI);
            PendingAuthorizationRecord secondPending =
                    PendingAuthorizationRecord.create(secondFlow, RETURN_URL, REQUESTED_SCOPES, T0);
            pendingStore.store(secondPending);
            CallbackOutcome second = endpoint.handle("code=auth-code&state=" + secondFlow.state(),
                    cookiePair(bindingCodec.toSetCookieHeader(secondPending.id())), T0);

            SessionRecord firstSession = storedSessionOf(first);
            SessionRecord secondSession = storedSessionOf(second);
            String firstSetCookie = first.setCookieHeaders().getFirst();

            assertAll("a login creates a session of its own",
                    () -> assertNotEquals(firstSession.sessionId(), secondSession.sessionId(),
                            "each login mints a fresh session id"),
                    () -> assertEquals(2, sessionStore.size(), "two logins are two sessions"),
                    () -> assertFalse(firstSetCookie.contains(firstSession.sessionId()),
                            "the session id never reaches the browser"),
                    () -> assertTrue(firstSetCookie.contains("; Max-Age=" + SESSION_TTL.toSeconds() + ";"),
                            "the login cookie carries the full ttl: " + firstSetCookie));
        }

        /**
         * The refresh token is the component whose omission made the near-expiry refresh silently
         * never fire: {@code TokenRefreshCoordinator.refresh} returns on its first guard when
         * {@code session.refreshToken()} is null, before any logging.
         * Asserting it here is what keeps the exchange's third component wired into the session.
         */
        @Test
        @DisplayName("Should seed the session with the refresh token the exchange returned")
        void shouldSeedRefreshTokenFromExchange() {
            CallbackOutcome outcome = endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            SessionRecord sessionRecord = storedSessionOf(outcome);

            assertEquals(RAW_REFRESH_TOKEN, sessionRecord.refreshToken(),
                    "without the exchange's refresh token on the session the coordinator short-circuits "
                            + "on its null guard and no refresh can ever run");
        }

        /**
         * The matched negative control: an authorization server that grants no refresh token is a
         * normal outcome, not an error, so the login must still complete and simply leave the
         * component absent — never substitute a placeholder that would drive the coordinator past its
         * null guard into a refresh it cannot perform.
         */
        @Test
        @DisplayName("Should complete the login with a null refresh token when the IdP granted none")
        void shouldCompleteLoginWithoutRefreshToken() {
            CallbackEndpoint noRefreshToken = endpoint(exchangeReturning(null), sessionBinding);

            CallbackOutcome outcome = noRefreshToken.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            assertTrue(outcome.isRedirect(), "a refresh token the IdP never issued is not a login failure");
            assertNull(storedSessionOf(outcome).refreshToken(),
                    "an absent grant stays absent on the session rather than becoming a placeholder");
        }

        private SessionRecord storedSessionOf(CallbackOutcome outcome) {
            String cookieHandle = sessionCodec.readCookieHandle(outcome.setCookieHeaders().getFirst()).orElseThrow();
            return sessionStore.resolve(cookieHandle, T0).orElseThrow();
        }

        @Test
        @DisplayName("Should consume the pending record exactly once (single-use)")
        void shouldConsumePendingRecordOnce() {
            endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            assertTrue(pendingStore.consume(recordId, T0).isEmpty(), "the pending record was consumed by the callback");
        }
    }

    @Nested
    @DisplayName("Active scope set A")
    class ActiveScopeSet {

        private SessionRecord loginWith(@Nullable ClaimValue scopeClaim) {
            CallbackEndpoint scoped = endpoint(exchangeReturning(RAW_REFRESH_TOKEN, scopeClaim), sessionBinding);
            CallbackOutcome outcome = scoped.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);
            assertTrue(outcome.isRedirect(), "the login completes");
            String cookieHandle = sessionCodec.readCookieHandle(outcome.setCookieHeaders().getFirst()).orElseThrow();
            return sessionStore.resolve(cookieHandle, T0).orElseThrow();
        }

        @Test
        @DisplayName("Should set A to the access token's granted scope, not the requested set")
        void shouldTakeGrantedScope() {
            SessionRecord session = loginWith(ClaimValue.forPlainString("openid profile orders:read"));

            assertEquals(Set.of("openid", "profile", "orders:read"), session.activeScopes(),
                    "the identity provider may narrow the request, so the granted scope is authoritative");
        }

        @Test
        @DisplayName("Should set A from a list-typed scope claim")
        void shouldTakeListTypedGrantedScope() {
            SessionRecord session = loginWith(ClaimValue.forList("openid orders:read",
                    List.of("openid", "orders:read")));

            assertEquals(Set.of("openid", "orders:read"), session.activeScopes());
        }

        @Test
        @DisplayName("Should fall back to the pending record's requested set when the token has no scope claim")
        void shouldFallBackToRequestedScopes() {
            SessionRecord session = loginWith(null);

            assertEquals(Set.copyOf(REQUESTED_SCOPES), session.activeScopes(),
                    "without a scope claim the requested set is the only honest source for A");
        }

        @Test
        @DisplayName("Should fall back to the requested set when the token's scope claim is blank")
        void shouldFallBackOnBlankScopeClaim() {
            SessionRecord session = loginWith(ClaimValue.forPlainString("   "));

            assertEquals(Set.copyOf(REQUESTED_SCOPES), session.activeScopes());
        }
    }

    @Nested
    @DisplayName("Granted scope set S at login")
    class GrantedScopeSetAtLogin {

        private SessionRecord loginWith(@Nullable ClaimValue scopeClaim) {
            CallbackEndpoint scoped = endpoint(exchangeReturning(RAW_REFRESH_TOKEN, scopeClaim), sessionBinding);
            CallbackOutcome outcome = scoped.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);
            assertTrue(outcome.isRedirect(), "the login completes");
            String cookieHandle = sessionCodec.readCookieHandle(outcome.setCookieHeaders().getFirst()).orElseThrow();
            return sessionStore.resolve(cookieHandle, T0).orElseThrow();
        }

        @Test
        @DisplayName("Should set S equal to A when A comes from the token's scope claim")
        void shouldSetGrantedToActiveFromScopeClaim() {
            SessionRecord session = loginWith(ClaimValue.forPlainString("openid profile orders:read"));

            assertEquals(Set.of("openid", "profile", "orders:read"), session.grantedScopes(),
                    "a fresh login has been granted exactly what the token carries");
            assertEquals(session.activeScopes(), session.grantedScopes(), "S = A at login");
        }

        @Test
        @DisplayName("Should set S equal to A when A falls back to the requested set")
        void shouldSetGrantedToActiveFromRequestedFallback() {
            SessionRecord session = loginWith(null);

            assertEquals(Set.copyOf(REQUESTED_SCOPES), session.grantedScopes());
            assertEquals(session.activeScopes(), session.grantedScopes(), "S = A at login");
        }

        @Test
        @DisplayName("Should set S equal to A in cookie mode too, surviving the sealed round trip")
        void shouldSetGrantedToActiveInCookieMode() {
            SessionBinding cookieBinding = cookieBinding();
            CallbackEndpoint cookieEndpoint = endpoint(
                    exchangeReturning(RAW_REFRESH_TOKEN, ClaimValue.forPlainString("openid orders:read")), cookieBinding);

            CallbackOutcome outcome = cookieEndpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);
            String sessionSetCookie = outcome.setCookieHeaders().getFirst();
            SessionRecord resolved = cookieBinding
                    .resolve(sessionSetCookie.substring(0, sessionSetCookie.indexOf(';')), T0).orElseThrow();

            assertEquals(Set.of("openid", "orders:read"), resolved.activeScopes());
            assertEquals(Set.of("openid", "orders:read"), resolved.grantedScopes(),
                    "the sealed cookie carries S alongside A, so a stateless gateway sees S = A after login");
        }
    }

    /**
     * A callback landing on a widening pending record: it answers the IdP's error or grant for a live
     * session widening its scopes, never minting a session of its own.
     */
    @Nested
    @DisplayName("Widening callback")
    class WideningCallback {

        private static final String WIDEN_RETURN_URL = "/orders/42";
        private static final Set<String> LIVE_SCOPES = Set.of("openid", "profile");
        private static final Set<String> WIDENING_REQUEST = Set.of("openid", "profile", "orders:read");
        private static final String LIVE_ACR = "urn:acr:live";
        private static final Instant LIVE_AUTH_TIME = T0.minusSeconds(60);
        private static final String WIDENED_ACCESS_TOKEN = "widened-access-token";
        private static final String WIDENED_ID_TOKEN = "widened-id-token";
        private static final String WIDENED_REFRESH_TOKEN = "widened-refresh-token";
        private static final String WIDENED_ACR = "urn:acr:widened";
        /** The {@code sid} of the identity-provider session that answers the widening — not the live one. */
        private static final String WIDENED_SID = "idp-sid-other";
        private static final String OTHER_SUBJECT = "user-sub-2";
        private static final Instant CALLBACK_AT = T0.plusSeconds(30);

        /** The live session as the binding holds it. */
        private SessionRecord live;
        /** The live session's {@code name=value} request-cookie pair. */
        private String sessionCookie;
        private String wideningState;
        private String wideningBindingCookie;

        /** Binds a live session for {@link #SUBJECT} through {@code binding} and remembers its cookie. */
        private void bindLive(SessionBinding binding) {
            bindLive(binding, LIVE_SCOPES);
        }

        /**
         * Binds a live session whose active scope set is {@link #LIVE_SCOPES} and whose granted scope set
         * is {@code grantedScopes} — a superset models a scope granted earlier that a refresh narrowed away.
         */
        private void bindLive(SessionBinding binding, Set<String> grantedScopes) {
            SessionRecord login = SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(RAW_ACCESS_TOKEN)
                    .refreshToken(RAW_REFRESH_TOKEN)
                    .idToken(RAW_ID_TOKEN)
                    .sub(SUBJECT)
                    .sid(IDP_SID)
                    .expiresAt(T0.plus(SESSION_TTL))
                    .acr(LIVE_ACR)
                    .authTime(LIVE_AUTH_TIME)
                    .activeScopes(LIVE_SCOPES)
                    .grantedScopes(grantedScopes)
                    .build();
            SessionBinding.BoundSession bound = binding.bind(login, T0);
            live = bound.session();
            sessionCookie = cookiePair(bound.setCookieHeaders().getFirst());
        }

        /** Stores a widening pending record for {@code sub} and remembers its binding cookie and state. */
        private void pendWidening(String sub, PendingAuthorizationRecord.Widening.Attempt attempt) {
            FlowContext flow = FlowContext.create(CALLBACK_URI);
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.createWidening(flow, WIDEN_RETURN_URL,
                    WIDENING_REQUEST, sub, attempt, T0);
            pendingStore.store(pending);
            wideningState = flow.state();
            wideningBindingCookie = cookiePair(bindingCodec.toSetCookieHeader(pending.id()));
        }

        private String requestCookies() {
            return wideningBindingCookie + "; " + sessionCookie;
        }

        /**
         * A successful widening grant for {@code subject} whose access token carries {@code scopeClaim}, or
         * no {@code scope} claim when {@code null}. The ID token names a different {@code sid} and
         * {@code auth_time} than the live session, so the merge's keep-vs-take split is observable.
         */
        private static CodeExchange grant(String subject, @Nullable ClaimValue scopeClaim) {
            return grant(subject, scopeClaim, WIDENED_SID);
        }

        /**
         * As {@link #grant(String, ClaimValue)}, with the ID token carrying {@code sid} — or no
         * {@code sid} claim at all when {@code null}.
         */
        private static CodeExchange grant(String subject, @Nullable ClaimValue scopeClaim, @Nullable String sid) {
            Map<String, ClaimValue> accessClaims = new HashMap<>();
            accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(subject));
            if (scopeClaim != null) {
                accessClaims.put(ClaimName.SCOPE.getName(), scopeClaim);
            }
            AccessTokenContent access = new AccessTokenContent(accessClaims, WIDENED_ACCESS_TOKEN);
            Map<String, ClaimValue> idClaims = new HashMap<>(Map.of(
                    ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(subject),
                    "acr", ClaimValue.forPlainString(WIDENED_ACR),
                    "auth_time", ClaimValue.forPlainString("1790000000")));
            if (sid != null) {
                idClaims.put("sid", ClaimValue.forPlainString(sid));
            }
            IdTokenContent id = new IdTokenContent(idClaims, WIDENED_ID_TOKEN);
            AuthorizationCodeFlow.AuthenticationResult result =
                    new AuthorizationCodeFlow.AuthenticationResult(access, id, WIDENED_REFRESH_TOKEN);
            return (context, params) -> result;
        }

        private static CodeExchange fullGrant() {
            return grant(SUBJECT, ClaimValue.forPlainString("openid profile orders:read"));
        }

        private CallbackOutcome error(String error) {
            return endpoint.handle("error=" + error + "&state=" + wideningState, requestCookies(), CALLBACK_AT);
        }

        private SessionRecord resolveServerSession() {
            return sessionBinding.resolve(sessionCookie, CALLBACK_AT).orElseThrow();
        }

        /**
         * Does what a browser does with a widening's answer: it replaces the session cookie it holds by
         * the one the answer set, when it set one. A server-mode widening re-issues the cookie value, so
         * every later request of the test has to carry the returned one.
         */
        private void followReissuedCookie(CallbackOutcome outcome) {
            outcome.setCookieHeaders().stream()
                    .filter(header -> header.startsWith(SessionCookieCodec.DEFAULT_COOKIE_NAME + "="))
                    .findFirst()
                    .ifPresent(header -> sessionCookie = cookiePair(header));
        }

        private static void assertRefused(CallbackOutcome outcome) {
            assertEquals(403, outcome.status());
            assertNull(outcome.location(), "a refusal never redirects the browser round the widening again");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a refusal touches no cookie, the session's least");
        }

        @Nested
        @DisplayName("IdP error on the silent attempt")
        class SilentError {

            @BeforeEach
            void setUpLiveSilentWidening() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);
            }

            @ParameterizedTest(name = "{0} re-drives exactly one interactive attempt")
            @ValueSource(strings = {"login_required", "interaction_required", "consent_required",
                    "account_selection_required"})
            @DisplayName("Should re-drive exactly one interactive attempt when the silent attempt needs interaction")
            void shouldRedriveOneInteractiveAttempt(String interactionNeeded) {
                CallbackOutcome outcome = error(interactionNeeded);

                assertEquals(302, outcome.status());
                assertEquals(WIDENING_AUTHORIZATION_URL, outcome.location(), "the browser goes back to the IdP");
                assertEquals(1, outcome.setCookieHeaders().size(), "only a new binding cookie, no session cookie");
                assertEquals(1, wideningCalls.size(), "exactly one re-drive");
                assertFalse(wideningCalls.getFirst().silent(), "the re-drive is the interactive attempt");
                assertEquals(WIDENING_REQUEST, wideningCalls.getFirst().scopes(), "the same scopes as the silent one");
                PendingAuthorizationRecord interactive = pendingStore.consume(
                        bindingCodec.readRecordId(cookiePair(outcome.setCookieHeaders().getFirst())).orElseThrow(),
                        CALLBACK_AT).orElseThrow();
                assertEquals(PendingAuthorizationRecord.Widening.Attempt.INTERACTIVE, interactive.widening().attempt());
                assertEquals(SUBJECT, interactive.widening().sub());
                assertEquals(WIDEN_RETURN_URL, interactive.returnUrl(), "the same return URL as the silent one");
                assertEquals(live, resolveServerSession(), "the re-drive leaves the live session unchanged");
            }

            @ParameterizedTest(name = "a second {0} is terminal")
            @ValueSource(strings = {"login_required", "interaction_required", "consent_required",
                    "account_selection_required"})
            @DisplayName("Should refuse the same error on the interactive attempt: 403, no further redirect, session unchanged")
            void shouldRefuseSameErrorOnInteractiveAttempt(String interactionNeeded) {
                CallbackOutcome redrive = error(interactionNeeded);
                String interactiveBinding = cookiePair(redrive.setCookieHeaders().getFirst());
                String interactiveState = wideningCalls.getFirst().context().state();

                CallbackOutcome second = endpoint.handle("error=" + interactionNeeded + "&state=" + interactiveState,
                        interactiveBinding + "; " + sessionCookie, CALLBACK_AT);

                assertRefused(second);
                assertEquals(1, wideningCalls.size(), "the interactive attempt is never re-driven — no loop");
                assertEquals(live, resolveServerSession());
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.format(interactionNeeded));
            }

            @ParameterizedTest(name = "{0} is terminal")
            @ValueSource(strings = {"invalid_scope", "access_denied"})
            @DisplayName("Should refuse invalid_scope and access_denied 403 with the session unchanged")
            void shouldRefuseTerminalErrors(String refusal) {
                CallbackOutcome outcome = error(refusal);

                assertRefused(outcome);
                assertTrue(wideningCalls.isEmpty(), "a refusal is never re-driven");
                assertEquals(live, resolveServerSession(), "the live session keeps its tokens and scopes");
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.format(refusal));
            }

            @Test
            @DisplayName("Should log an unknown error code as 'other', never the IdP-supplied value")
            void shouldBoundUnknownErrorReason() {
                CallbackOutcome outcome = error("attacker_chosen_text");

                assertRefused(outcome);
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.format("other"));
                LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "attacker_chosen_text");
            }
        }

        @Nested
        @DisplayName("Forged or unbound error")
        class ForgedError {

            @BeforeEach
            void setUpLiveSilentWidening() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);
            }

            @Test
            @DisplayName("Should reject a login_required without the binding cookie 403 and never re-drive")
            void shouldRejectErrorWithoutBindingCookie() {
                CallbackOutcome outcome = endpoint.handle("error=login_required&state=" + wideningState, sessionCookie,
                        CALLBACK_AT);

                assertRefused(outcome);
                assertTrue(wideningCalls.isEmpty(), "a forged error can never trigger a re-drive");
            }

            @Test
            @DisplayName("Should reject a login_required with a foreign state 403 and never re-drive")
            void shouldRejectErrorWithForeignState() {
                CallbackOutcome outcome = endpoint.handle("error=login_required&state=forged-state", requestCookies(),
                        CALLBACK_AT);

                assertRefused(outcome);
                assertTrue(wideningCalls.isEmpty(), "a forged error can never trigger a re-drive");
            }
        }

        @Nested
        @DisplayName("Successful grant")
        class SuccessfulGrant {

            @Test
            @DisplayName("Should merge into the same server-mode session with A and S widened")
            void shouldMergeIntoServerSession() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(fullGrant(), sessionBinding).handle(
                        "code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertEquals(302, outcome.status());
                assertEquals(WIDEN_RETURN_URL, outcome.location());
                assertEquals(2, outcome.setCookieHeaders().size(), "the re-issued session cookie + the binding clear");
                assertEquals(bindingCodec.toClearingSetCookieHeader(), outcome.setCookieHeaders().get(1));
                String previousCookie = sessionCookie;
                followReissuedCookie(outcome);
                String reissuedSetCookie = outcome.setCookieHeaders().getFirst();
                assertAll("the widening re-issued the cookie value",
                        () -> assertNotEquals(previousCookie, sessionCookie,
                                "the browser is handed a cookie value that differs from the one it sent"),
                        () -> assertTrue(sessionBinding.resolve(previousCookie, CALLBACK_AT).isEmpty(),
                                "the value the request carried no longer resolves"),
                        () -> assertTrue(
                                reissuedSetCookie.contains("; Max-Age=" + SESSION_TTL.minusSeconds(30).toSeconds() + ";"),
                                "the re-issued cookie lives as long as the session still does: " + reissuedSetCookie),
                        () -> assertFalse(reissuedSetCookie.contains(live.sessionId()),
                                "the session id is not the cookie value"),
                        () -> assertEquals(1, sessionStore.size(), "the widening created no second session"));
                SessionRecord merged = resolveServerSession();
                assertAll("merged into the live session",
                        () -> assertEquals(live.sessionId(), merged.sessionId(), "the same session, never a new one"),
                        () -> assertEquals(WIDENED_ACCESS_TOKEN, merged.accessToken()),
                        () -> assertEquals(WIDENED_REFRESH_TOKEN, merged.refreshToken()),
                        () -> assertEquals(WIDENED_ID_TOKEN, merged.idToken()),
                        () -> assertEquals(WIDENED_ACR, merged.acr()),
                        () -> assertEquals(SUBJECT, merged.sub()),
                        () -> assertEquals(WIDENED_SID, merged.sid(), "the session takes the sid of the grant's ID token"),
                        () -> assertEquals(LIVE_AUTH_TIME, merged.authTime(), "the session keeps its auth_time"),
                        () -> assertEquals(live.expiresAt(), merged.expiresAt(), "the absolute expiry is unchanged"),
                        () -> assertEquals(WIDENING_REQUEST, merged.activeScopes(), "A is the granted scope"),
                        () -> assertEquals(WIDENING_REQUEST, merged.grantedScopes(), "S is the granted scope"));
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.INFO,
                        BffLogMessages.INFO.SESSION_WIDENED.format("orders:read"));
            }

            @Test
            @DisplayName("Should set both A and S to the grant when the grant no longer returns a scope of S")
            void shouldSetGrantedScopesToTheGrantWhenItNarrows() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                followReissuedCookie(
                        endpoint(grant(SUBJECT, ClaimValue.forPlainString("openid orders:read")), sessionBinding)
                                .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT));

                SessionRecord merged = resolveServerSession();
                assertEquals(Set.of("openid", "orders:read"), merged.activeScopes(), "A is what the token carries");
                assertEquals(Set.of("openid", "orders:read"), merged.grantedScopes(),
                        "S is what the identity provider granted now — 'profile', which it no longer returned, leaves S");
            }

            @Test
            @DisplayName("Should fall back to the requested set when the widened token carries no scope claim")
            void shouldFallBackToRequestedSet() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(grant(SUBJECT, null), sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertTrue(outcome.isRedirect());
                followReissuedCookie(outcome);
                SessionRecord merged = resolveServerSession();
                assertEquals(WIDENING_REQUEST, merged.activeScopes());
                assertEquals(WIDENING_REQUEST, merged.grantedScopes());
            }

            @Test
            @DisplayName("Should merge an interactive attempt's grant exactly like a silent one")
            void shouldMergeInteractiveGrant() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.INTERACTIVE);

                CallbackOutcome outcome = endpoint(fullGrant(), sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertTrue(outcome.isRedirect());
                followReissuedCookie(outcome);
                assertEquals(live.sessionId(), resolveServerSession().sessionId());
                assertEquals(WIDENING_REQUEST, resolveServerSession().activeScopes());
            }

            @Test
            @DisplayName("Should merge into the same cookie-mode session identity with A and S widened")
            void shouldMergeIntoCookieSession() {
                SessionBinding cookieBinding = cookieBinding();
                bindLive(cookieBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(fullGrant(), cookieBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertEquals(302, outcome.status());
                assertEquals(2, outcome.setCookieHeaders().size(), "the re-sealed session + the binding clear");
                assertEquals(bindingCodec.toClearingSetCookieHeader(), outcome.setCookieHeaders().get(1));
                SessionRecord merged = cookieBinding
                        .resolve(cookiePair(outcome.setCookieHeaders().getFirst()), CALLBACK_AT).orElseThrow();
                assertAll("merged into the live cookie-mode session",
                        () -> assertEquals(live.sessionId(), merged.sessionId(),
                                "the derived identity is unchanged — the same session, re-sealed"),
                        () -> assertEquals(live.sessionNonce(), merged.sessionNonce()),
                        () -> assertEquals(live.expiresAt(), merged.expiresAt()),
                        () -> assertEquals(WIDENED_ACCESS_TOKEN, merged.accessToken()),
                        () -> assertEquals(WIDENED_SID, merged.sid(),
                                "the re-sealed cookie carries the sid of the grant's ID token"),
                        () -> assertEquals(WIDENING_REQUEST, merged.activeScopes()),
                        () -> assertEquals(WIDENING_REQUEST, merged.grantedScopes()));
            }

            @Test
            @DisplayName("Should answer 500 when the binding cannot hold the widened session, leaving it unchanged")
            void shouldMapPersistFailureTo500() {
                SessionBinding cookieBinding = cookieBinding();
                bindLive(cookieBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = assertDoesNotThrow(() -> endpoint(oversizedExchange(), cookieBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT));

                assertEquals(500, outcome.status());
                assertTrue(outcome.setCookieHeaders().isEmpty(), "the browser keeps its existing session cookie");
                assertEquals(live, cookieBinding.resolve(sessionCookie, CALLBACK_AT).orElseThrow());
                LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO,
                        BffLogMessages.INFO.SESSION_WIDENED.resolveIdentifierString());
            }
        }

        @Nested
        @DisplayName("Refused grant")
        class RefusedGrant {

            @Test
            @DisplayName("Should refuse a grant for another subject 403 and leave the session unchanged")
            void shouldRefuseSubjectMismatch() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(grant(OTHER_SUBJECT, ClaimValue.forPlainString(
                        "openid profile orders:read")), sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertRefused(outcome);
                assertEquals(live, resolveServerSession(), "a session is never swapped to another identity");
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_IDENTITY_MISMATCH.format());
            }

            @Test
            @DisplayName("Should refuse when the widening was issued for another subject than the live session 403")
            void shouldRefusePendingSubjectMismatch() {
                bindLive(sessionBinding);
                pendWidening(OTHER_SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(fullGrant(), sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertRefused(outcome);
                assertEquals(live, resolveServerSession());
            }

            @Test
            @DisplayName("Should refuse a widening callback that carries no live session 403")
            void shouldRefuseWithoutLiveSession() {
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(fullGrant(), sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, wideningBindingCookie, CALLBACK_AT);

                assertRefused(outcome);
                LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO,
                        BffLogMessages.INFO.SESSION_WIDENED.resolveIdentifierString());
            }

            @Test
            @DisplayName("Should refuse a grant lacking a scope sought beyond S 403, session unchanged, no loop")
            void shouldRefuseGrantMissingSoughtScope() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);

                CallbackOutcome outcome = endpoint(grant(SUBJECT, ClaimValue.forPlainString("openid profile")),
                        sessionBinding).handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertRefused(outcome);
                assertTrue(wideningCalls.isEmpty(), "the browser is not sent round the widening again");
                assertEquals(live, resolveServerSession(), "a narrower grant is never merged");
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.format("scope-not-granted"));
            }
        }

        /**
         * A logout or a back-channel logout that lands between the callback's resolve and its persist.
         * The interleaving is driven by {@link TerminatingAfterResolve}, which terminates the session
         * the moment the callback has resolved it — no thread and no timing is involved. The merge must
         * not bring the session back: the callback answers like one that carried no live session.
         * <p>
         * The last case is the matched control. It runs the same callback through the same hook with a
         * termination that does nothing, and merges — so the refusals above are caused by the
         * termination and not by the hook.
         */
        @Nested
        @DisplayName("Session terminated between the resolve and the persist (server mode)")
        class TerminatedBeforePersist {

            static Stream<Arguments> terminations() {
                BiConsumer<SessionBinding, SessionRecord> bySessionIdentity = SessionBinding::destroy;
                BiConsumer<SessionBinding, SessionRecord> bySid = (binding, session) -> binding.destroyBySid(IDP_SID);
                BiConsumer<SessionBinding, SessionRecord> bySub = (binding, session) -> binding.destroyBySub(SUBJECT);
                return Stream.of(
                        Arguments.of("a logout destroying the session by its identity", bySessionIdentity),
                        Arguments.of("a back-channel logout destroying it by sid", bySid),
                        Arguments.of("a back-channel logout destroying it by sub", bySub));
            }

            @ParameterizedTest(name = "{0}")
            @MethodSource("terminations")
            @DisplayName("Should answer 403 with no Location and no Set-Cookie, and leave the session gone")
            void shouldNotBringTerminatedSessionBack(String label,
                    BiConsumer<SessionBinding, SessionRecord> termination) {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);
                TerminatingAfterResolve interleaved = new TerminatingAfterResolve(sessionBinding, termination);

                CallbackOutcome outcome = endpoint(fullGrant(), interleaved)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                assertRefused(outcome);
                assertAll(label,
                        () -> assertEquals(1, interleaved.terminationsRun(),
                                "the termination ran after the resolve, so the refusal is the persist's"),
                        () -> assertTrue(sessionBinding.resolve(sessionCookie, CALLBACK_AT).isEmpty(),
                                "the terminated session does not resolve again"),
                        () -> assertEquals(0, sessionStore.size(), "the merge stored nothing"),
                        () -> assertEquals(0, sessionBinding.destroyBySid(WIDENED_SID),
                                "no session exists under the sid of the refused grant"),
                        () -> assertEquals(0, sessionBinding.destroyBySid(IDP_SID),
                                "and none under the sid the session was created with"));
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.format("session-terminated"));
                LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO,
                        BffLogMessages.INFO.SESSION_WIDENED.resolveIdentifierString());
            }

            @Test
            @DisplayName("Should merge through the same hook when nothing terminates the session (matched control)")
            void shouldMergeWhenNothingTerminatesTheSession() {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);
                BiConsumer<SessionBinding, SessionRecord> nothing = (binding, session) -> assertTrue(
                        binding.resolve(sessionCookie, CALLBACK_AT).isPresent(),
                        "the control leaves the session in place at the point the terminations strike");
                TerminatingAfterResolve interleaved = new TerminatingAfterResolve(sessionBinding, nothing);

                CallbackOutcome outcome = endpoint(fullGrant(), interleaved)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);

                followReissuedCookie(outcome);
                SessionRecord merged = resolveServerSession();
                assertAll("the same widening merges when the session is still there at the persist",
                        () -> assertEquals(302, outcome.status()),
                        () -> assertEquals(WIDEN_RETURN_URL, outcome.location()),
                        () -> assertEquals(1, interleaved.terminationsRun(), "the hook ran at the same point"),
                        () -> assertEquals(live.sessionId(), merged.sessionId(), "the same session, never a new one"),
                        () -> assertEquals(WIDENED_ACCESS_TOKEN, merged.accessToken()),
                        () -> assertEquals(WIDENING_REQUEST, merged.activeScopes()));
                LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.resolveIdentifierString());
            }
        }

        /**
         * The granted set {@code S} after a merge is what the grant returned, never the union with an
         * earlier grant. The live session here was granted {@code orders:read} before and a refresh
         * narrowed it out of {@code A}, so a widening for it seeks nothing beyond {@code S}. When the
         * identity provider no longer returns the scope, the merge takes it out of {@code S}; the round
         * that follows then seeks it beyond {@code S} and is refused instead of merged.
         */
        @Nested
        @DisplayName("Granted set S follows the grant")
        class TruthfulGrantedSet {

            /** Runs one widening round for {@link #WIDENING_REQUEST} answered by {@code exchange}. */
            private CallbackOutcome widenWith(CodeExchange exchange) {
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);
                CallbackOutcome outcome = endpoint(exchange, sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);
                followReissuedCookie(outcome);
                return outcome;
            }

            @Test
            @DisplayName("Should take a scope the grant no longer returns out of S, then refuse the next round 403")
            void shouldDropRevokedScopeThenRefuseTheNextRound() {
                bindLive(sessionBinding, WIDENING_REQUEST);
                CodeExchange withoutOrdersRead = grant(SUBJECT, ClaimValue.forPlainString("openid profile"));

                CallbackOutcome firstRound = widenWith(withoutOrdersRead);
                SessionRecord afterFirstRound = resolveServerSession();
                CallbackOutcome secondRound = widenWith(withoutOrdersRead);

                assertAll("a scope the identity provider stopped granting cannot keep the browser going round",
                        () -> assertEquals(302, firstRound.status(),
                                "nothing was sought beyond S, so the first grant is merged"),
                        () -> assertEquals(LIVE_SCOPES, afterFirstRound.grantedScopes(),
                                "S lost the scope the grant did not return"),
                        () -> assertEquals(403, secondRound.status(),
                                "the scope now lies beyond S, so a grant lacking it again is refused"),
                        () -> assertNull(secondRound.location(), "the refusal is terminal — no further redirect"),
                        () -> assertTrue(secondRound.setCookieHeaders().isEmpty()),
                        () -> assertEquals(afterFirstRound, resolveServerSession(),
                                "the refused round leaves the session as the first round left it"));
                LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.format("scope-not-granted"));
            }

            @Test
            @DisplayName("Should keep a scope of S the grant still returns, round after round (matched control)")
            void shouldKeepStillGrantedScope() {
                bindLive(sessionBinding, WIDENING_REQUEST);

                CallbackOutcome firstRound = widenWith(fullGrant());
                CallbackOutcome secondRound = widenWith(fullGrant());

                SessionRecord merged = resolveServerSession();
                assertAll("the same session and request are merged when the scope is still granted",
                        () -> assertEquals(302, firstRound.status()),
                        () -> assertEquals(302, secondRound.status(), "nothing left S, so nothing is refused"),
                        () -> assertEquals(WIDENING_REQUEST, merged.grantedScopes(), "S keeps the scope"),
                        () -> assertEquals(WIDENING_REQUEST, merged.activeScopes(), "and A carries it again"));
                LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN,
                        BffLogMessages.WARN.SESSION_WIDENING_REFUSED.resolveIdentifierString());
            }
        }

        /**
         * The merged session is indexed under the {@code sid} of the identity-provider session that
         * answered the widening, so a back-channel logout for that session finds it. The receiver is the
         * production {@link BackchannelLogoutReceiver} over the same binding the callback merged through;
         * only its signature seam is bound to a hand-built logout token.
         */
        @Nested
        @DisplayName("IdP session id follows the grant")
        class GrantSid {

            private static final String ISSUER = "https://idp.example.com";
            private static final String CLIENT_ID = "bff-client";

            private void widenWith(CodeExchange exchange) {
                bindLive(sessionBinding);
                pendWidening(SUBJECT, PendingAuthorizationRecord.Widening.Attempt.SILENT);
                CallbackOutcome outcome = endpoint(exchange, sessionBinding)
                        .handle("code=widen-code&state=" + wideningState, requestCookies(), CALLBACK_AT);
                assertEquals(302, outcome.status(), "precondition: the widening is merged");
                followReissuedCookie(outcome);
            }

            /** Delivers a spec-shaped, sid-only back-channel logout token naming {@code sid}. */
            private BackchannelLogoutReceiver.BackchannelResult backchannelLogout(String sid) {
                Map<String, ClaimValue> claims = Map.of(
                        "iss", ClaimValue.forPlainString(ISSUER),
                        "aud", ClaimValue.forList("aud", List.of(CLIENT_ID)),
                        "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(CALLBACK_AT, ZoneOffset.UTC)),
                        "events", ClaimValue.forPlainString(
                                "{" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "={}}"),
                        "sid", ClaimValue.forPlainString(sid));
                return new BackchannelLogoutReceiver(raw -> new IdTokenContent(claims, raw),
                        new LogoutTokenValidator(ISSUER, CLIENT_ID, Duration.ofMinutes(2)), sessionBinding)
                        .receive("raw.logout.token", CALLBACK_AT);
            }

            private boolean sessionIsLive() {
                return sessionBinding.resolve(sessionCookie, CALLBACK_AT).isPresent();
            }

            @Test
            @DisplayName("Should be destroyed by a back-channel logout naming the sid of the grant's ID token")
            void shouldBeDestroyedByLogoutForTheGrantSid() {
                widenWith(fullGrant());

                BackchannelLogoutReceiver.BackchannelResult result = backchannelLogout(WIDENED_SID);

                assertAll("the session is found under the sid of the IdP session its tokens belong to",
                        () -> assertTrue(result.accepted()),
                        () -> assertEquals(1, result.destroyed(), "exactly the widened session is destroyed"),
                        () -> assertFalse(sessionIsLive(), "the session no longer resolves"));
            }

            @Test
            @DisplayName("Should no longer be matched by a logout naming the sid it was created under")
            void shouldNotBeMatchedByLogoutForThePreviousSid() {
                widenWith(fullGrant());

                BackchannelLogoutReceiver.BackchannelResult result = backchannelLogout(IDP_SID);

                assertAll("the store re-indexed the session on the merge, so the previous sid names nothing",
                        () -> assertTrue(result.accepted()),
                        () -> assertEquals(0, result.destroyed()),
                        () -> assertTrue(sessionIsLive(), "the session is untouched"));
            }

            @Test
            @DisplayName("Should keep its sid, and stay reachable under it, when the grant's ID token carries none (matched control)")
            void shouldKeepSidWhenGrantCarriesNone() {
                widenWith(grant(SUBJECT, ClaimValue.forPlainString("openid profile orders:read"), null));
                SessionRecord merged = resolveServerSession();

                BackchannelLogoutReceiver.BackchannelResult result = backchannelLogout(IDP_SID);

                assertAll("without a sid on the new ID token the session stays under the one it had",
                        () -> assertEquals(IDP_SID, merged.sid(), "the live sid is kept"),
                        () -> assertEquals(WIDENED_ID_TOKEN, merged.idToken(), "although the new ID token was taken"),
                        () -> assertEquals(1, result.destroyed(), "a logout naming that sid still destroys it"),
                        () -> assertFalse(sessionIsLive()));
            }
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null raw query")
        void shouldRejectNullRawQuery() {
            assertThrows(NullPointerException.class, () -> endpoint.handle(null, bindingCookieHeader, T0));
        }

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> endpoint.handle("code=abc&state=" + state, bindingCookieHeader,
                    null));
        }
    }
}
