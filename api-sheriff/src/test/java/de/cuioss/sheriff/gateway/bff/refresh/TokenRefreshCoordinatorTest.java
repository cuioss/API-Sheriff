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
package de.cuioss.sheriff.gateway.bff.refresh;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.AccessTokenExpiry;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshExchange;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshOutcome;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshTokenRevocation;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.token.client.flow.CredentialRejectedException;
import de.cuioss.sheriff.token.client.flow.RedeemedResponseException;
import de.cuioss.sheriff.token.client.flow.RedeemedScopeRefusalException;
import de.cuioss.sheriff.token.client.flow.RefreshRedemption;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenRefreshCoordinator}: the single-flight, per-session transparent refresh and the
 * per-kind disposition of a refused refresh.
 * <p>
 * The engine refresh is driven through the {@link RefreshExchange} seam, the near-expiry decision
 * through the {@link AccessTokenExpiry} seam and revocation through a recording
 * {@link RefreshTokenRevocation}, so every path — not-needed, refreshed, each failure kind, the
 * post-exchange persist failure, the pre-redemption back-off and the concurrent single-flight coalesce
 * — is exercised with the engine's own exception types and no test-double framework. The engine's
 * {@code RefreshFlow.classify} is NOT stubbed: each test throws the exception the engine actually raises
 * for that situation, so a change in the classification is observed here. The downstream content
 * negotiation belongs to the session runtime stage and is tested there; this suite covers the
 * coordinator's own contract: which {@link RefreshOutcome} it returns, its session-binding side effects,
 * which refresh token it revokes, and which record it logs.
 */
@EnableTestLogger
class TokenRefreshCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration LEEWAY = Duration.ofSeconds(60);
    private static final Instant NOT_NEAR = NOW.plusSeconds(600);
    private static final Instant NEAR = NOW.plusSeconds(30);
    private static final Instant EXPIRED = NOW.minusSeconds(1);
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SESSION_ID = "session-1";
    private static final String CURRENT_REFRESH = "refresh-current";
    private static final String ROTATED_ACCESS = "rotated-access-token";
    private static final String ROTATED_REFRESH = "rotated-refresh-token";
    private static final String ROTATED_ID = "rotated-id-token";

    private static final String COOKIE_HEADER = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + SESSION_ID;

    /**
     * Runs a dispatched revocation on the dispatching thread, so every test that only asserts WHICH token
     * was revoked observes it synchronously. The ordering tests in {@link RevocationOrdering} bind a real
     * asynchronous executor instead.
     */
    private static final Executor DIRECT = Runnable::run;

    private static final String REFRESH_FAILED_ID = BffLogMessages.WARN.SESSION_REFRESH_FAILED.resolveIdentifierString();
    private static final String REFRESH_DEFERRED_ID =
            BffLogMessages.WARN.SESSION_REFRESH_DEFERRED.resolveIdentifierString();

    private InMemorySessionStore store;
    private SessionBinding binding;
    private List<String> revoked;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16);
        binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
        revoked = new CopyOnWriteArrayList<>();
    }

    private static SessionRecord session(@Nullable String refreshToken) {
        return SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken("access-current")
                .refreshToken(refreshToken)
                .idToken("id-current")
                .sub("sub-1")
                .sid(null)
                .expiresAt(NOW.plus(SESSION_TTL))
                .acr(null)
                .authTime(null)
                .build();
    }

    private static RotationResult rotation() {
        Map<String, ClaimValue> claims = new HashMap<>();
        claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString("sub-1"));
        AccessTokenContent rotatedAccess = new AccessTokenContent(claims, ROTATED_ACCESS);
        // token-sheriff 0.9.5 widened RotationResult with grantedScope + scopeDelta. Both are set to
        // the "the IdP declared no scope on the refresh response" pair here — a null grantedScope
        // (the component is nullable; only scopeDelta is requireNonNull) with UNDECLARED — because
        // that is the neutral value for THESE tests: every case below exercises refresh scheduling,
        // single-flight coordination, session rebinding and failure disposition, none of which reads
        // either component. Picking EQUAL instead would assert a scope comparison the fixture never
        // performs. Nothing in the gateway reads scopeDelta yet, so a NARROWED or BROADENED scope on
        // refresh is currently unobserved; that needs its own plan and its own assertions.
        return new RotationResult(rotatedAccess, ROTATED_REFRESH, ROTATED_ID, 300L, true,
                null, RotationResult.ScopeDelta.UNDECLARED);
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            Awaits.connect(latch, "the release latch to reach zero");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the release latch");
        } catch (TimeoutException e) {
            // Previously the boolean return was discarded, so an expiry let the test continue
            // silently. The ceiling reaching zero now fails the test instead.
            throw new AssertionError("the release latch never reached zero", e);
        }
    }

    private TokenRefreshCoordinator coordinator(Instant accessExpiry, RefreshExchange exchange) {
        return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, exchange, binding, revoked::add, DIRECT,
                EndedRefreshTokens.inert());
    }

    private SessionRecord storedSession() {
        SessionRecord live = session(CURRENT_REFRESH);
        store.create(live, NOW);
        return live;
    }

    private boolean sessionResolvable(Instant at) {
        return store.resolve(SESSION_ID, at).isPresent();
    }

    private static RefreshExchange throwing(RuntimeException failure) {
        return presented -> {
            throw failure;
        };
    }

    private static CredentialRejectedException credentialRejected() {
        return new CredentialRejectedException("Token endpoint rejected the credential with HTTP 400");
    }

    @Nested
    @DisplayName("No refresh needed")
    class NoRefresh {

        @Test
        @DisplayName("Should return the current session unchanged when not within the expiry leeway")
        void shouldReturnCurrentWhenNotNearExpiry() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, rt -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind());
            assertSame(live, outcome.session(), "the same session is returned unchanged");
            assertEquals(0, calls.get(), "no engine refresh is attempted when the token is not near expiry");
        }

        @Test
        @DisplayName("Should return the current session when near expiry but the session carries no refresh token")
        void shouldReturnCurrentWhenNoRefreshToken() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = session(null);
            store.create(live, NOW);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind());
            assertEquals(0, calls.get(), "a session without a refresh token cannot be refreshed");
        }
    }

    @Nested
    @DisplayName("Successful refresh")
    class SuccessfulRefresh {

        @Test
        @DisplayName("Should refresh through the engine and persist the rotated token material")
        void shouldRefreshAndPersist() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            SessionRecord rotated = outcome.session();
            assertNotNull(rotated);
            assertEquals(ROTATED_ACCESS, rotated.accessToken());
            assertEquals(ROTATED_REFRESH, rotated.refreshToken());
            assertEquals(ROTATED_ID, rotated.idToken());
            assertEquals(live.expiresAt(), rotated.expiresAt(), "the absolute session cap is unchanged by a refresh");
            assertTrue(revoked.isEmpty(), "a successful refresh revokes nothing");
        }

        @Test
        @DisplayName("Should pass the session's current refresh token to the engine")
        void shouldPresentCurrentRefreshToken() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, presented -> {
                calls.incrementAndGet();
                assertEquals(CURRENT_REFRESH, presented, "the coordinator presents the stored refresh token");
                return rotation();
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(1, calls.get());
            SessionRecord persisted = store.resolve(SESSION_ID, NOW).orElseThrow();
            assertEquals(ROTATED_ACCESS, persisted.accessToken(), "the store now serves the rotated token");
        }
    }

    /**
     * A refusal the engine classifies {@code PRE_REDEMPTION}: the identity provider never processed the
     * grant, so the presented refresh token is still valid and the session must survive.
     */
    @Nested
    @DisplayName("Pre-redemption failure — the session is kept")
    class PreRedemption {

        @Test
        @DisplayName("Should defer and keep the session when the access token is still valid")
        void shouldDeferWhileAccessTokenIsValid() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    throwing(new TransportException("Connection refused by the token endpoint")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.DEFERRED, outcome.kind());
            assertEquals(live, outcome.session(), "the unchanged session is mediated with its still-valid token");
            assertFalse(outcome.isFailure(), "a deferred refresh did not end the session");
            assertFalse(outcome.requestFailed(), "a deferred refresh still has a token to mediate");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "nothing was re-bound, so no cookie is emitted");
            assertTrue(sessionResolvable(NOW), "a pre-redemption failure never destroys the session");
            assertTrue(revoked.isEmpty(), "the presented token is still valid and must not be revoked");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should report unavailable yet keep the session when the access token has expired")
        void shouldBeUnavailableOnceAccessTokenExpired() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(EXPIRED,
                    throwing(new IllegalStateException("provider metadata is missing the token endpoint")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.UNAVAILABLE, outcome.kind());
            assertNull(outcome.session(), "an expired access token leaves nothing to mediate");
            assertTrue(outcome.requestFailed(), "this request fails");
            assertFalse(outcome.isFailure(), "but the session did not end");
            assertTrue(sessionResolvable(NOW), "an expired access token does not turn a transient fault into a logout");
            assertTrue(revoked.isEmpty());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should keep the session on a non-redeemed client-protocol refusal, which the engine reads as pre-redemption")
        void shouldKeepSessionOnPlainClientProtocolRefusal() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    throwing(new ClientProtocolException("token endpoint rejected the refresh grant")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.DEFERRED, outcome.kind(),
                    "a ClientProtocolException carrying no redemption is PRE_REDEMPTION, not a rejected credential");
            assertTrue(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should make no engine call while the back-off is in force, and retry once it has elapsed")
        void shouldBackOffBeforeRetrying() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, presented -> {
                calls.incrementAndGet();
                throw new TransportException("Token endpoint returned HTTP 503");
            });
            Instant insideWindow = NOW.plusSeconds(2);
            Instant windowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);

            RefreshOutcome first = coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome backedOff = coordinator.refresh(live, COOKIE_HEADER, insideWindow);
            int callsInsideWindow = calls.get();
            RefreshOutcome retried = coordinator.refresh(live, COOKIE_HEADER, windowElapsed);

            assertEquals(RefreshOutcome.Kind.DEFERRED, first.kind());
            assertEquals(RefreshOutcome.Kind.DEFERRED, backedOff.kind(),
                    "a backed-off request takes the same disposition without reaching the engine");
            assertEquals(1, callsInsideWindow, "no second engine call is made inside the back-off window");
            assertEquals(RefreshOutcome.Kind.DEFERRED, retried.kind());
            assertEquals(2, calls.get(), "the next near-expiry request after the window attempts the refresh again");
        }

        @Test
        @DisplayName("Should report unavailable for a backed-off request whose access token has expired meanwhile")
        void shouldBeUnavailableWhileBackingOffOnceExpired() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            Instant accessExpiry = NOW.plusSeconds(1);
            TokenRefreshCoordinator coordinator = coordinator(accessExpiry, presented -> {
                calls.incrementAndGet();
                throw new TransportException("Token endpoint returned HTTP 503");
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome backedOff = coordinator.refresh(live, COOKIE_HEADER, NOW.plusSeconds(3));

            assertEquals(RefreshOutcome.Kind.UNAVAILABLE, backedOff.kind());
            assertEquals(1, calls.get(), "the back-off still suppresses the engine call");
            assertTrue(sessionResolvable(NOW.plusSeconds(3)), "the session is kept throughout");
        }

        @Test
        @DisplayName("Should refresh normally once the identity provider recovers after the back-off")
        void shouldRefreshAfterRecovery() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, presented -> {
                if (calls.incrementAndGet() == 1) {
                    throw new TransportException("Token endpoint returned HTTP 503");
                }
                return rotation();
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome recovered = coordinator.refresh(live, COOKIE_HEADER,
                    NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF));

            assertEquals(RefreshOutcome.Kind.REFRESHED, recovered.kind());
            assertEquals(ROTATED_ACCESS, store.resolve(SESSION_ID, NOW).orElseThrow().accessToken());
        }
    }

    /**
     * The back-off map at its bound: {@link TokenRefreshCoordinator#MAX_BACKOFF_ENTRIES} sessions each
     * hold an unexpired window from a real {@code PRE_REDEMPTION} failure, so a further failing session
     * cannot be tracked individually and must open the shared overflow window rather than dropping the
     * throttle. The saturating failures run with the coordinator's logger raised to {@code ERROR} so the
     * ten thousand expected {@code ApiSheriff-127} records are neither retained nor printed; the level is
     * lowered again before anything this suite asserts about records.
     */
    @Nested
    @DisplayName("Pre-redemption back-off at capacity — the overflow window")
    class BackOffSaturation {

        private static final String SATURATING_PREFIX = "saturating-";
        private static final TransportException OUTAGE = new TransportException("Token endpoint returned HTTP 503");

        private final AtomicInteger calls = new AtomicInteger();
        private InMemorySessionStore saturationStore;
        private SessionBinding saturationBinding;

        @BeforeEach
        void setUpSaturation() {
            saturationStore = new InMemorySessionStore(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES + 16);
            saturationBinding = new ServerSessionBinding(saturationStore,
                    new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
        }

        private TokenRefreshCoordinator failingCoordinator(Instant accessExpiry) {
            return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, presented -> {
                calls.incrementAndGet();
                throw OUTAGE;
            }, saturationBinding, revoked::add, DIRECT, EndedRefreshTokens.inert());
        }

        private SessionRecord stored(String sessionId) {
            SessionRecord live = SessionRecord.builder()
                    .sessionId(sessionId)
                    .accessToken("access-" + sessionId)
                    .refreshToken("refresh-" + sessionId)
                    .idToken("id-" + sessionId)
                    .sub("sub-" + sessionId)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
            saturationStore.create(live, NOW);
            return live;
        }

        private RefreshOutcome refresh(TokenRefreshCoordinator coordinator, SessionRecord live, Instant at) {
            return coordinator.refresh(live, SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + live.sessionId(), at);
        }

        /** Fills the back-off map with unexpired windows opened at {@link #NOW} through real failures. */
        private void saturate(TokenRefreshCoordinator coordinator) {
            TestLogLevel.ERROR.addLogger(TokenRefreshCoordinator.class);
            for (int i = 0; i < TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES; i++) {
                refresh(coordinator, stored(SATURATING_PREFIX + i), NOW);
            }
            TestLogLevel.INFO.addLogger(TokenRefreshCoordinator.class);
            TestLoggerFactory.getTestHandler().clearRecords();
            assertEquals(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES, calls.get(),
                    "every saturating session reached the engine exactly once");
        }

        @Test
        @DisplayName("Should throttle a session without its own window through the overflow window, with no engine call and no record")
        void shouldThrottleUntrackedSessionInsideOverflowWindow() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NOW.plusSeconds(3));
            saturate(coordinator);
            SessionRecord overflowing = stored("overflowing");
            SessionRecord untracked = stored("untracked");

            RefreshOutcome overflowed = refresh(coordinator, overflowing, NOW);
            int callsAfterOverflow = calls.get();
            TestLoggerFactory.getTestHandler().clearRecords();
            RefreshOutcome whileValid = refresh(coordinator, untracked, NOW.plusSeconds(2));
            RefreshOutcome onceExpired = refresh(coordinator, untracked, NOW.plusSeconds(4));

            assertAll("a saturated map degrades the throttle to one shared window instead of dropping it",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, overflowed.kind(),
                            "the session that could not be tracked is still kept"),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES + 1, callsAfterOverflow,
                            "the overflowing failure itself reached the engine once"),
                    () -> assertEquals(callsAfterOverflow, calls.get(),
                            "a session without its own window makes no engine call inside the overflow window"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, whileValid.kind(),
                            "its still-valid access token is mediated"),
                    () -> assertEquals(RefreshOutcome.Kind.UNAVAILABLE, onceExpired.kind(),
                            "an access token that expired inside the window leaves the request without one"),
                    () -> assertTrue(saturationStore.resolve("untracked", NOW.plusSeconds(4)).isPresent(),
                            "the overflow window never ends a session"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
        }

        @Test
        @DisplayName("Should let a session without its own window attempt the refresh again once the overflow window elapsed")
        void shouldRetryUntrackedSessionAfterOverflowWindow() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NEAR);
            saturate(coordinator);
            refresh(coordinator, stored("overflowing"), NOW);
            int callsAfterOverflow = calls.get();

            RefreshOutcome retried = refresh(coordinator, stored("untracked"),
                    NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF));

            assertEquals(RefreshOutcome.Kind.DEFERRED, retried.kind());
            assertEquals(callsAfterOverflow + 1, calls.get(),
                    "the overflow window expires on its own after the fixed back-off");
        }

        @Test
        @DisplayName("Should govern a session holding its own window by that window alone, even while the overflow window is open")
        void shouldKeepPerSessionBehaviourForTrackedSession() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NEAR);
            saturate(coordinator);
            SessionRecord tracked = saturationStore.resolve(SATURATING_PREFIX + 0, NOW).orElseThrow();
            Instant overflowOpened = NOW.plusSeconds(3);
            Instant ownWindowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            refresh(coordinator, stored("overflowing"), overflowOpened);
            int callsAfterOverflow = calls.get();

            RefreshOutcome insideOwnWindow = refresh(coordinator, tracked, NOW.plusSeconds(4));
            int callsInsideOwnWindow = calls.get();
            RefreshOutcome untracked = refresh(coordinator, stored("untracked"), ownWindowElapsed);
            int callsForUntracked = calls.get();
            RefreshOutcome afterOwnWindow = refresh(coordinator, tracked, ownWindowElapsed);

            assertAll("the overflow window applies only to sessions without their own window",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, insideOwnWindow.kind()),
                    () -> assertEquals(callsAfterOverflow, callsInsideOwnWindow,
                            "the tracked session's own window still suppresses its engine call"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, untracked.kind()),
                    () -> assertEquals(callsInsideOwnWindow, callsForUntracked,
                            "the overflow window opened at +3s is still in force at +5s for an untracked session"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, afterOwnWindow.kind()),
                    () -> assertEquals(callsForUntracked + 1, calls.get(),
                            "once its own window elapsed the tracked session attempts again, overflow window or not"));
        }
    }

    /**
     * A refusal that ends the session: the identity provider rejected the credential, the gateway
     * refused a response the provider had already redeemed, or the rotated session could not be
     * persisted.
     */
    @Nested
    @DisplayName("Session-ending failures")
    class SessionEnding {

        @Test
        @DisplayName("Should destroy the session and revoke nothing when the identity provider rejects the credential")
        void shouldFailAndDestroyOnCredentialRejection() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(credentialRejected()));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertTrue(outcome.isFailure());
            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertNull(outcome.session(), "a failed outcome carries no session");
            assertFalse(sessionResolvable(NOW), "the session is destroyed on a rejected credential");
            assertTrue(revoked.isEmpty(), "nothing was redeemed, so there is no live refresh token to revoke");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_CREDENTIAL_REJECTED);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
        }

        /**
         * Reuse detection is the identity provider's strict rotation: a replayed superseded refresh token
         * is answered {@code invalid_grant}, which the engine raises as {@link CredentialRejectedException}.
         */
        @Test
        @DisplayName("Should destroy the session when the identity provider rejects a replayed refresh token")
        void shouldFailOnReplayRejectedByIdentityProvider() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, presented -> {
                calls.incrementAndGet();
                throw credentialRejected();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome afterwards = coordinator.refresh(live, COOKIE_HEADER, NOW.plusSeconds(1));

            assertTrue(outcome.isFailure(), "a replay rejected under strict rotation fails the refresh");
            assertFalse(sessionResolvable(NOW), "the replaying session is destroyed");
            assertTrue(afterwards.isFailure(), "the destroyed session cannot be resumed on a later request");
            assertEquals(1, calls.get(), "a destroyed session never reaches the engine again");
        }

        @Test
        @DisplayName("Should destroy the session and revoke the successor when a rotated response is refused")
        void shouldRevokeSuccessorOnRefusedRotatedRedemption() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(new RedeemedScopeRefusalException(
                    "granted a broader scope than requested", RefreshRedemption.rotated(ROTATED_REFRESH))));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertFalse(sessionResolvable(NOW), "a refused redeemed response ends the session");
            assertEquals(List.of(ROTATED_REFRESH), revoked,
                    "the presented token is burned, so the successor is the one live refresh token");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_REDEEMED_RESPONSE_REFUSED);
        }

        @Test
        @DisplayName("Should destroy the session and revoke the presented token when a non-rotated response is refused")
        void shouldRevokePresentedTokenOnRefusedNonRotatedRedemption() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(new RedeemedScopeRefusalException(
                    "granted a broader scope than requested", RefreshRedemption.notRotated())));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertFalse(sessionResolvable(NOW));
            assertEquals(List.of(CURRENT_REFRESH), revoked,
                    "without rotation the presented token survived the exchange and is the live one");
        }

        @Test
        @DisplayName("Should destroy the session and revoke nothing when rotation is unknown")
        void shouldRevokeNothingWhenRotationUnknown() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    throwing(new RedeemedResponseException("Token endpoint returned an unparseable 2xx body")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertFalse(sessionResolvable(NOW), "an unknown rotation fails closed");
            assertTrue(revoked.isEmpty(), "no successor is known, so nothing can be named for revocation");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_REDEEMED_RESPONSE_REFUSED);
        }

        @Test
        @DisplayName("Should destroy the session and revoke the returned refresh token when persisting the rotation fails")
        void shouldEndSessionOnPersistFailure() {
            storedSession();
            SessionBinding persistFailing = new PersistFailingBinding(binding);
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    rt -> rotation(), persistFailing, revoked::add, DIRECT, EndedRefreshTokens.inert());

            RefreshOutcome outcome = coordinator.refresh(session(CURRENT_REFRESH), COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind(),
                    "the presented token is already redeemed, so a persist failure cannot keep the session");
            assertFalse(sessionResolvable(NOW), "the session holding the burned token is destroyed");
            assertEquals(List.of(ROTATED_REFRESH), revoked, "the refresh token the exchange returned is revoked");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_PERSIST_FAILURE);
        }

        @Test
        @DisplayName("Should still fail and destroy the session when the revocation endpoint fails")
        void shouldIgnoreRevocationFailure() {
            SessionRecord live = storedSession();
            AtomicInteger attempts = new AtomicInteger();
            RefreshTokenRevocation failingRevocation = token -> {
                attempts.incrementAndGet();
                throw new TransportException("Revocation endpoint returned unexpected HTTP status 500");
            };
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    throwing(new RedeemedScopeRefusalException("granted a broader scope than requested",
                            RefreshRedemption.rotated(ROTATED_REFRESH))),
                    binding, failingRevocation, DIRECT, EndedRefreshTokens.inert());

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(1, attempts.get(), "revocation was attempted");
            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind(), "a failing revocation never changes the disposition");
            assertFalse(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should fail when the session was destroyed between the near-expiry check and the refresh")
        void shouldFailWhenSessionGone() {
            SessionRecord live = session(CURRENT_REFRESH);
            // Deliberately NOT stored — models a session destroyed concurrently before the lead resolves it.
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertTrue(outcome.isFailure(), "a session absent from the store cannot be refreshed");
        }
    }

    @Nested
    @DisplayName("Single-flight coalescing")
    class SingleFlight {

        @Test
        @DisplayName("Should coalesce concurrent refreshes on one session into a single engine call")
        void shouldCoalesceConcurrentRefreshes() throws Exception {
            CoalescedRun run = coalesce(rotation -> rotation);

            assertEquals(1, run.calls(), "concurrent requests on one session share a single engine refresh");
            assertEquals(RefreshOutcome.Kind.REFRESHED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.REFRESHED, run.follower().kind(),
                    "the coalesced follower shares the successful refresh");
            assertEquals(ROTATED_ACCESS, store.resolve(SESSION_ID, NOW).orElseThrow().accessToken());
        }

        @Test
        @DisplayName("Should hand the coalesced follower the leader's deferred outcome on a pre-redemption failure")
        void shouldShareDeferredOutcome() throws Exception {
            CoalescedRun run = coalesce(rotation -> {
                throw new TransportException("Connection reset by the token endpoint");
            });

            assertEquals(1, run.calls());
            assertEquals(RefreshOutcome.Kind.DEFERRED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.DEFERRED, run.follower().kind());
            assertTrue(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should hand the coalesced follower the leader's failed outcome on a rejected credential")
        void shouldShareCredentialRejectedOutcome() throws Exception {
            CoalescedRun run = coalesce(rotation -> {
                throw credentialRejected();
            });

            assertEquals(1, run.calls());
            assertEquals(RefreshOutcome.Kind.FAILED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.FAILED, run.follower().kind());
            assertFalse(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should hand the coalesced follower the leader's failed outcome on a refused redemption")
        void shouldShareRedeemedOutcome() throws Exception {
            CoalescedRun run = coalesce(rotation -> {
                throw new RedeemedScopeRefusalException("granted a broader scope than requested",
                        RefreshRedemption.rotated(ROTATED_REFRESH));
            });

            assertEquals(1, run.calls());
            assertEquals(RefreshOutcome.Kind.FAILED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.FAILED, run.follower().kind());
            assertEquals(List.of(ROTATED_REFRESH), revoked, "the successor is revoked exactly once");
        }

        /**
         * Runs one leader and one coalesced follower on the stored session. The leader enters the
         * exchange and blocks there until the follower has been submitted, then applies
         * {@code afterRelease} to the rotation — returning it, or throwing the failure under test.
         */
        private CoalescedRun coalesce(UnaryOperator<RotationResult> afterRelease) throws Exception {
            SessionRecord live = storedSession();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                calls.incrementAndGet();
                entered.countDown();
                awaitRelease(proceed);
                return afterRelease.apply(rotation());
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader = pool.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                Awaits.connect(entered, "the leader entered the engine refresh");
                Future<RefreshOutcome> follower = pool.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                // Let the follower reach the in-flight join before the leader is released. There is no
                // observable hook for a thread reaching CompletableFuture#join, so this best-effort
                // ordering sleep cannot be made deterministic without an added dependency.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                RefreshOutcome followerOutcome = Awaits.connect(follower,
                        "the coalesced follower refresh to complete");
                return new CoalescedRun(leaderOutcome, followerOutcome, calls.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private record CoalescedRun(RefreshOutcome leader, RefreshOutcome follower, int calls) {
    }

    /**
     * The session-ended outcome is published before the best-effort revocation, and the revocation runs
     * off the request path. Every wait here is bounded, so against a coordinator that revokes inline the
     * tests fail on a timeout rather than hang.
     */
    @Nested
    @DisplayName("Revocation ordering — outcome first, revocation off the request path")
    class RevocationOrdering {

        private static final long OUTCOME_WAIT_SECONDS = 5;
        private static final long REVOCATION_BLOCK_SECONDS = 30;

        @Test
        @DisplayName("Should publish FAILED to the leader and a coalesced waiter while a refused redemption's revocation is still blocked")
        void shouldPublishRedeemedOutcomeBeforeRevocation() throws Exception {
            storedSession();

            BlockedRevocationRun run = runWithBlockedRevocation(binding, () -> {
                throw new RedeemedScopeRefusalException("granted a broader scope than requested",
                        RefreshRedemption.rotated(ROTATED_REFRESH));
            });

            assertBlockedRevocationRun(run);
        }

        @Test
        @DisplayName("Should publish FAILED to the leader and a coalesced waiter while a persist failure's revocation is still blocked")
        void shouldPublishPersistFailureOutcomeBeforeRevocation() throws Exception {
            storedSession();

            BlockedRevocationRun run = runWithBlockedRevocation(new PersistFailingBinding(binding), TokenRefreshCoordinatorTest::rotation);

            assertBlockedRevocationRun(run);
        }

        @Test
        @DisplayName("Should keep the FAILED disposition and record nothing above DEBUG when the dispatched revocation throws")
        void shouldSwallowFailingDispatchedRevocation() throws Exception {
            SessionRecord live = storedSession();
            CountDownLatch attempted = new CountDownLatch(1);
            RefreshTokenRevocation failingRevocation = token -> {
                attempted.countDown();
                throw new TransportException("Revocation endpoint returned unexpected HTTP status 500");
            };
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                        throwing(new RedeemedScopeRefusalException("granted a broader scope than requested",
                                RefreshRedemption.rotated(ROTATED_REFRESH))),
                        binding, failingRevocation, executor, EndedRefreshTokens.inert());

                RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);
                boolean revocationAttempted = attempted.await(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                executor.shutdown();
                boolean drained = executor.awaitTermination(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);

                assertAll("a failing revocation is best-effort and invisible above DEBUG",
                        () -> assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind()),
                        () -> assertTrue(revocationAttempted, "the revocation was dispatched and attempted"),
                        () -> assertTrue(drained, "the dispatched revocation finished"),
                        () -> assertFalse(sessionResolvable(NOW)),
                        () -> assertTrue(TestLoggerFactory.getTestHandler().resolveLogMessages(TestLogLevel.ERROR)
                                .isEmpty(), "a failing revocation records no ERROR"),
                        () -> assertTrue(TestLoggerFactory.getTestHandler().resolveLogMessages(TestLogLevel.WARN)
                                .stream().allMatch(entry -> String.valueOf(entry.getMessage()).contains(REFRESH_FAILED_ID)),
                                "the only WARN is the session end's own ApiSheriff-111"));
            }
        }

        @Test
        @DisplayName("Should skip a revocation at DEBUG once the in-flight bound is saturated, and dispatch again once a permit is released")
        void shouldSkipRevocationBeyondInFlightBound() {
            TestLogLevel.DEBUG.addLogger(TokenRefreshCoordinator.class);
            InMemorySessionStore wideStore = new InMemorySessionStore(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS + 8);
            SessionBinding wideBinding = new ServerSessionBinding(wideStore,
                    new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
            List<Runnable> parked = new CopyOnWriteArrayList<>();
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    throwing(new RedeemedScopeRefusalException("granted a broader scope than requested",
                            RefreshRedemption.rotated(ROTATED_REFRESH))),
                    wideBinding, revoked::add, parked::add, EndedRefreshTokens.inert());

            for (int i = 0; i <= TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS; i++) {
                endSession(coordinator, wideStore, "ending-" + i);
            }
            int parkedAtBound = parked.size();
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG, "revocation skipped");
            parked.forEach(Runnable::run);
            int revokedAfterDrain = revoked.size();
            endSession(coordinator, wideStore, "after-drain");

            assertAll("the fixed bound acts and its permits are returned",
                    () -> assertEquals(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS, parkedAtBound,
                            "one ending beyond the bound is not dispatched"),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS, revokedAfterDrain),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS + 1, parked.size(),
                            "a finished revocation returns its permit, so the next ending is dispatched again"));
        }

        private void endSession(TokenRefreshCoordinator coordinator, InMemorySessionStore target, String sessionId) {
            SessionRecord live = SessionRecord.builder()
                    .sessionId(sessionId)
                    .accessToken("access-" + sessionId)
                    .refreshToken("refresh-" + sessionId)
                    .idToken("id-" + sessionId)
                    .sub("sub-" + sessionId)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
            target.create(live, NOW);
            assertEquals(RefreshOutcome.Kind.FAILED,
                    coordinator.refresh(live, SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId, NOW).kind());
        }

        /**
         * Runs one leader and one coalesced waiter on the stored session against {@code sessionBinding}.
         * The leader's exchange blocks until the waiter has been submitted, then yields {@code exchange};
         * the revocation that follows blocks until this method has observed both outcomes.
         */
        private BlockedRevocationRun runWithBlockedRevocation(SessionBinding sessionBinding,
                Supplier<RotationResult> exchange) throws Exception {
            CountDownLatch exchangeEntered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            CountDownLatch revocationEntered = new CountDownLatch(1);
            CountDownLatch releaseRevocation = new CountDownLatch(1);
            CountDownLatch revocationDone = new CountDownLatch(1);
            RefreshTokenRevocation blockingRevocation = token -> {
                revocationEntered.countDown();
                try {
                    if (releaseRevocation.await(REVOCATION_BLOCK_SECONDS, TimeUnit.SECONDS)) {
                        revoked.add(token);
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    revocationDone.countDown();
                }
            };
            ExecutorService requests = Executors.newFixedThreadPool(2);
            ExecutorService revocations = Executors.newVirtualThreadPerTaskExecutor();
            try {
                SessionRecord live = session(CURRENT_REFRESH);
                TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, rt -> {
                    exchangeEntered.countDown();
                    awaitRelease(proceed);
                    return exchange.get();
                }, sessionBinding, blockingRevocation, revocations, EndedRefreshTokens.inert());

                Future<RefreshOutcome> leader = requests.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                Awaits.connect(exchangeEntered, "the leader entered the engine refresh");
                Future<RefreshOutcome> waiter = requests.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                // Best-effort ordering, as in SingleFlight: no observable hook for the waiter reaching the join.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the waiter reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = leader.get(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                RefreshOutcome waiterOutcome = waiter.get(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                boolean revocationStarted = revocationEntered.await(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                boolean stillBlocked = revocationDone.getCount() == 1;
                releaseRevocation.countDown();
                boolean revocationFinished = revocationDone.await(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                return new BlockedRevocationRun(leaderOutcome, waiterOutcome, revocationStarted, stillBlocked,
                        revocationFinished);
            } finally {
                releaseRevocation.countDown();
                requests.shutdownNow();
                revocations.shutdownNow();
            }
        }

        private void assertBlockedRevocationRun(BlockedRevocationRun run) {
            assertAll("the outcome is published before the revocation, which runs off the request path",
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, run.leader(),
                            "the failing request returned FAILED while its revocation was blocked"),
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, run.waiter(),
                            "the coalesced waiter observed FAILED while the revocation was blocked"),
                    () -> assertTrue(run.revocationStarted(), "the revocation was dispatched"),
                    () -> assertTrue(run.stillBlockedWhenObserved(),
                            "both outcomes were observed while the revocation had not returned"),
                    () -> assertTrue(run.revocationFinished(), "the released revocation completed"),
                    () -> assertEquals(List.of(ROTATED_REFRESH), revoked,
                            "the revocation was invoked with the one live refresh token"),
                    () -> assertFalse(sessionResolvable(NOW), "the session was ended"));
        }
    }

    private record BlockedRevocationRun(RefreshOutcome.Kind leader, RefreshOutcome.Kind waiter,
            boolean revocationStarted, boolean stillBlockedWhenObserved, boolean revocationFinished) {

        BlockedRevocationRun(RefreshOutcome leader, RefreshOutcome waiter, boolean revocationStarted,
                boolean stillBlockedWhenObserved, boolean revocationFinished) {
            this(leader.kind(), waiter.kind(), revocationStarted, stillBlockedWhenObserved, revocationFinished);
        }
    }

    @Nested
    @DisplayName("Cookie-mode refresh (stateless binding)")
    class CookieMode {

        private static final String COOKIE_NAME = "__Host-sheriff-session";

        private CookieSessionBinding cookieBinding;
        private String sealedCookieHeader;
        private SessionRecord cookieSession;

        @BeforeEach
        void setUpCookieMode() {
            byte[] keyMaterial = new byte[32];
            Arrays.fill(keyMaterial, (byte) 0x11);
            SecretKey key = new SecretKeySpec(keyMaterial, "AES");
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            cookieBinding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(COOKIE_NAME, SESSION_TTL,
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, key, (byte) 1), salt);

            SessionBinding.BoundSession bound = cookieBinding.bind(session(CURRENT_REFRESH), NOW);
            String setCookie = bound.setCookieHeaders().getFirst();
            sealedCookieHeader = setCookie.substring(0, setCookie.indexOf(';'));
            // The sealed cookie IS the session, so the record to refresh is the resolved one — its
            // derived sessionId is what single-flight keys on.
            cookieSession = cookieBinding.resolve(sealedCookieHeader, NOW).orElseThrow();
        }

        private TokenRefreshCoordinator cookieCoordinator(RefreshExchange exchange) {
            return cookieCoordinator(exchange, cookieBinding, EndedRefreshTokens.inert());
        }

        private TokenRefreshCoordinator cookieCoordinator(RefreshExchange exchange, SessionBinding sessionBinding,
                EndedRefreshTokens marker) {
            return new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, sessionBinding, revoked::add, DIRECT,
                    marker);
        }

        @Test
        @DisplayName("Should mark the presented refresh token ended on each of the three session-ending dispositions")
        void shouldMarkPresentedTokenOnEverySessionEnd() {
            EndedRefreshTokens credentialRejectedMarker = EndedRefreshTokens.bounded();
            EndedRefreshTokens redeemedMarker = EndedRefreshTokens.bounded();
            EndedRefreshTokens persistFailureMarker = EndedRefreshTokens.bounded();

            RefreshOutcome credentialRejected = cookieCoordinator(throwing(credentialRejected()), cookieBinding,
                    credentialRejectedMarker).refresh(cookieSession, sealedCookieHeader, NOW);
            RefreshOutcome redeemed = cookieCoordinator(throwing(new RedeemedScopeRefusalException(
                    "granted a broader scope than requested", RefreshRedemption.rotated(ROTATED_REFRESH))),
                    cookieBinding, redeemedMarker).refresh(cookieSession, sealedCookieHeader, NOW);
            RefreshOutcome persistFailure = cookieCoordinator(rt -> rotation(), new PersistFailingBinding(cookieBinding),
                    persistFailureMarker).refresh(cookieSession, sealedCookieHeader, NOW);

            assertAll("every session-ending path marks the token the ended session presented",
                    () -> assertTrue(credentialRejected.isFailure()),
                    () -> assertTrue(credentialRejectedMarker.isEnded(CURRENT_REFRESH, NOW), "credential-rejected"),
                    () -> assertTrue(redeemed.isFailure()),
                    () -> assertTrue(redeemedMarker.isEnded(CURRENT_REFRESH, NOW), "redeemed-response-refused"),
                    () -> assertTrue(persistFailure.isFailure()),
                    () -> assertTrue(persistFailureMarker.isEnded(CURRENT_REFRESH, NOW), "persist-failure"),
                    () -> assertFalse(redeemedMarker.isEnded(ROTATED_REFRESH, NOW),
                            "only the presented token is marked, never the revoked successor"));
        }

        @Test
        @DisplayName("Should refuse a replayed ended cookie locally with no engine call, no revocation and no ApiSheriff-111")
        void shouldRefuseReplayedEndedTokenLocally() {
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = cookieCoordinator(presented -> {
                calls.incrementAndGet();
                throw new RedeemedScopeRefusalException("granted a broader scope than requested",
                        RefreshRedemption.rotated(ROTATED_REFRESH));
            }, cookieBinding, EndedRefreshTokens.bounded());
            RefreshOutcome ended = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            int revokedAfterEnd = revoked.size();
            TestLoggerFactory.getTestHandler().clearRecords();

            RefreshOutcome replayed = coordinator.refresh(cookieSession, sealedCookieHeader, NOW.plusSeconds(1));

            assertAll("the replay takes the session-ended disposition without reaching the identity provider",
                    () -> assertTrue(ended.isFailure()),
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, replayed.kind(),
                            "the stage still clears the cookie and negotiates on_failure"),
                    () -> assertEquals(1, calls.get(), "the replayed ended token makes no engine exchange"),
                    () -> assertEquals(1, revokedAfterEnd, "the original session end revoked its successor once"),
                    () -> assertEquals(revokedAfterEnd, revoked.size(), "the replay revokes nothing"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should let a successor cookie of the same session reach the identity provider once, then mark it")
        void shouldNotRefuseSuccessorWithDifferentRefreshToken() {
            AtomicInteger calls = new AtomicInteger();
            EndedRefreshTokens marker = EndedRefreshTokens.bounded();
            TokenRefreshCoordinator coordinator = cookieCoordinator(presented -> {
                if (calls.incrementAndGet() == 1) {
                    return rotation();
                }
                throw credentialRejected();
            }, cookieBinding, marker);
            RefreshOutcome rotated = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            String successorSetCookie = rotated.setCookieHeaders().getFirst();
            String successorCookieHeader = successorSetCookie.substring(0, successorSetCookie.indexOf(';'));
            SessionRecord successor = cookieBinding.resolve(successorCookieHeader, NOW).orElseThrow();
            RefreshOutcome replayOfOriginal = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            int callsBeforeSuccessor = calls.get();

            RefreshOutcome successorOutcome = coordinator.refresh(successor, successorCookieHeader, NOW);

            assertAll("the marker is keyed on the token generation, not the shared session identity",
                    () -> assertEquals(cookieSession.sessionId(), successor.sessionId(),
                            "precondition: both cookies derive the same session identity"),
                    () -> assertTrue(replayOfOriginal.isFailure(), "the IdP refused the replayed original"),
                    () -> assertTrue(marker.isEnded(CURRENT_REFRESH, NOW)),
                    () -> assertEquals(callsBeforeSuccessor + 1, calls.get(),
                            "the successor's different refresh token is not marked and reaches the exchange"),
                    () -> assertTrue(successorOutcome.isFailure(), "the IdP refuses the successor too"),
                    () -> assertTrue(marker.isEnded(ROTATED_REFRESH, NOW), "and the successor is then marked itself"));
        }

        @Test
        @DisplayName("Should let a replay reach the exchange as before when the inert server-mode marker is bound")
        void shouldReachExchangeWithInertMarker() {
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = cookieCoordinator(presented -> {
                calls.incrementAndGet();
                throw credentialRejected();
            });

            coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            RefreshOutcome replayed = coordinator.refresh(cookieSession, sealedCookieHeader, NOW.plusSeconds(1));

            assertTrue(replayed.isFailure());
            assertEquals(2, calls.get(), "the inert marker refuses nothing locally");
        }

        @Test
        @DisplayName("Should re-seal the rotated material into a new Set-Cookie rather than a store write")
        void shouldResealIntoANewCookie() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            assertEquals(ROTATED_ACCESS, rotatedSession(outcome).accessToken());
            assertEquals(1, outcome.setCookieHeaders().size(),
                    "a stateless refresh persists by emitting exactly one re-sealed cookie");
            String reSealed = outcome.setCookieHeaders().getFirst();
            assertTrue(reSealed.startsWith(COOKIE_NAME + "="), reSealed);
            assertFalse(reSealed.contains(ROTATED_ACCESS), "the rotated token is sealed, never emitted in the clear");
            assertFalse(reSealed.contains(ROTATED_REFRESH), "the rotated refresh token is sealed, never emitted");
        }

        @Test
        @DisplayName("Should carry the rotated material in the re-sealed cookie the next request presents")
        void shouldServeTheRotatedMaterialFromTheReSealedCookie() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            String reSealed = outcome.setCookieHeaders().getFirst();
            String nextRequestCookie = reSealed.substring(0, reSealed.indexOf(';'));
            SessionRecord nextRequest = cookieBinding.resolve(nextRequestCookie, NOW).orElseThrow();
            assertEquals(ROTATED_ACCESS, nextRequest.accessToken());
            assertEquals(ROTATED_REFRESH, nextRequest.refreshToken());
            assertEquals(cookieSession.expiresAt(), nextRequest.expiresAt(),
                    "the re-seal preserves the original absolute deadline — a refresh never extends the session");
        }

        @Test
        @DisplayName("Should propagate the session nonce from the previous record onto the rotated one")
        void shouldPropagateSessionNonceAcrossRotate() {
            // rotate() rebuilds the record component-by-component, so an uncopied component is
            // silently dropped. Dropping the nonce would make persist() refuse the re-seal outright —
            // this asserts the copy directly rather than inferring it from identity stability.
            assertNotNull(cookieSession.sessionNonce(),
                    "a resolved cookie-mode record always carries the nonce sealed at login");
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(cookieSession.sessionNonce(), rotatedSession(outcome).sessionNonce(),
                    "the rotated record carries the previous record's nonce verbatim — never a fresh one");
        }

        @Test
        @DisplayName("Should leave a server-mode record's session nonce absent")
        void shouldLeaveServerModeNonceAbsent() {
            SessionRecord serverModeRecord = session(CURRENT_REFRESH);

            assertNull(serverModeRecord.sessionNonce(),
                    "the nonce is cookie-mode-only; server mode's minted opaque id is already unique");
        }

        @Test
        @DisplayName("Should keep the derived identity stable across the re-seal, so single-flight keys the same")
        void shouldKeepTheSingleFlightKeyStable() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(cookieSession.sessionId(), rotatedSession(outcome).sessionId(),
                    "the single-flight key expression is unchanged in cookie mode");
        }

        @Test
        @DisplayName("Should coalesce two concurrent cookie-mode refreshes into exactly one engine exchange")
        void shouldCoalesceConcurrentCookieRefreshes() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> {
                calls.incrementAndGet();
                entered.countDown();
                awaitRelease(proceed);
                return rotation();
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader =
                        pool.submit(() -> coordinator.refresh(cookieSession, sealedCookieHeader, NOW));
                Awaits.connect(entered, "the leader entered the engine refresh");
                Future<RefreshOutcome> follower =
                        pool.submit(() -> coordinator.refresh(cookieSession, sealedCookieHeader, NOW));
                // Best-effort ordering: there is no observable hook for a thread reaching
                // CompletableFuture#join, so the follower's arrival cannot be awaited deterministically.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                RefreshOutcome followerOutcome = Awaits.connect(follower,
                        "the coalesced follower refresh to complete");

                assertEquals(1, calls.get(),
                        "two threads on the same cookie produce exactly one engine exchange — single-flight is "
                                + "per instance in cookie mode, which is the documented accepted trade-off");
                assertFalse(leaderOutcome.isFailure());
                assertFalse(followerOutcome.isFailure(), "the coalesced follower shares the successful refresh");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("Should fail when the identity provider rejects the cookie-mode refresh token")
        void shouldFailOnCredentialRejection() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(throwing(credentialRejected()));

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertTrue(outcome.isFailure(),
                    "a rejected credential ends the session in cookie mode too — the stage clears the cookie");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a failed refresh emits no re-seal");
        }

        @Test
        @DisplayName("Should keep the sealed cookie and mediate it on a cookie-mode pre-redemption failure")
        void shouldDeferOnPreRedemptionFailure() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(
                    throwing(new TransportException("Token endpoint returned HTTP 502")));

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(RefreshOutcome.Kind.DEFERRED, outcome.kind());
            assertEquals(cookieSession, outcome.session(), "the still-valid sealed session is mediated as it is");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "nothing was re-sealed, so the browser keeps its cookie");
        }

        private static SessionRecord rotatedSession(RefreshOutcome outcome) {
            SessionRecord rotated = outcome.session();
            assertNotNull(rotated, "a refreshed outcome carries the rotated session");
            return rotated;
        }
    }

    @Nested
    @DisplayName("Argument and outcome contracts")
    class Contracts {

        @Test
        @DisplayName("Should reject a null session and a null instant")
        void shouldRejectNullArguments() {
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            var session = session(CURRENT_REFRESH);
            assertThrows(NullPointerException.class, () -> coordinator.refresh(null, COOKIE_HEADER, NOW));
            assertThrows(NullPointerException.class, () -> coordinator.refresh(session, COOKIE_HEADER, null));
        }

        @Test
        @DisplayName("Should reject a missing revocation seam, revocation executor or ended-token marker")
        void shouldRejectMissingRevocationSeam() {
            RefreshExchange exchange = rt -> rotation();
            RefreshTokenRevocation revocation = revoked::add;
            EndedRefreshTokens marker = EndedRefreshTokens.inert();

            assertThrows(NullPointerException.class,
                    () -> new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, binding, null, DIRECT, marker));
            assertThrows(NullPointerException.class,
                    () -> new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, binding, revocation, null, marker));
            assertThrows(NullPointerException.class,
                    () -> new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, binding, revocation, DIRECT, null));
        }

        @Test
        @DisplayName("Should reject a session-carrying kind constructed without a session")
        void shouldRejectPresentContractViolation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.CURRENT, null, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.DEFERRED, null, List.of()));
        }

        @Test
        @DisplayName("Should reject a session-less kind constructed with a session")
        void shouldRejectAbsentContractViolation() {
            SessionRecord live = session(CURRENT_REFRESH);

            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.UNAVAILABLE, live, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.FAILED, live, List.of()));
        }

        @Test
        @DisplayName("Should expose an empty session and no cookies on a failed outcome")
        void failedOutcomeCarriesNoSession() {
            RefreshOutcome failed = RefreshOutcome.failed();

            assertTrue(failed.isFailure());
            assertFalse(failed.requestFailed());
            assertNull(failed.session());
            assertTrue(failed.setCookieHeaders().isEmpty());
        }

        @Test
        @DisplayName("Should tell an unavailable outcome apart from a failed one")
        void unavailableOutcomeIsNotASessionEnd() {
            RefreshOutcome unavailable = RefreshOutcome.unavailable();

            assertTrue(unavailable.requestFailed());
            assertFalse(unavailable.isFailure());
            assertNull(unavailable.session());
        }
    }

    /**
     * A binding that behaves like the wrapped one except that re-binding a rotated session fails, the
     * way {@link CookieSessionBinding#persist} does when the sealed cookie exceeds its size budget.
     */
    private static final class PersistFailingBinding implements SessionBinding {

        private final SessionBinding delegate;

        PersistFailingBinding(SessionBinding delegate) {
            this.delegate = delegate;
        }

        @Override
        public BoundSession bind(SessionRecord session, Instant now) {
            return delegate.bind(session, now);
        }

        @Override
        public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
            return delegate.resolve(cookieHeader, now);
        }

        @Override
        public BoundSession persist(SessionRecord rotated, Instant now) {
            throw new IllegalStateException("sealed session cookie exceeds the size budget");
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
        public String clearingSetCookieHeader() {
            return delegate.clearingSetCookieHeader();
        }
    }
}
