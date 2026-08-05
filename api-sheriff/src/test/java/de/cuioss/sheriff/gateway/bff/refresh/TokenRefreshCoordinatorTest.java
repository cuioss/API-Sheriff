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
package de.cuioss.sheriff.gateway.bff.refresh;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.AccessTokenExpiry;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshExchange;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenRefreshCoordinator}: the single-flight, per-session transparent refresh.
 * <p>
 * The engine refresh is driven through the {@link RefreshExchange} seam and the near-expiry decision
 * through the {@link AccessTokenExpiry} seam, so every path — not-needed, refreshed, failed, reuse,
 * and the concurrent single-flight coalesce — is exercised with hand-built engine objects and no
 * test-double framework. The failed-refresh outcome's downstream content negotiation
 * (navigation → login, XHR → 401) belongs to the session runtime stage and is tested there; this
 * suite covers the coordinator's own contract: which {@link RefreshOutcome} it returns and its
 * session-store side effects.
 */
class TokenRefreshCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration LEEWAY = Duration.ofSeconds(60);
    private static final Instant NOT_NEAR = NOW.plusSeconds(600);
    private static final Instant NEAR = NOW.plusSeconds(30);
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SESSION_ID = "session-1";
    private static final String CURRENT_REFRESH = "refresh-current";
    private static final String ROTATED_ACCESS = "rotated-access-token";
    private static final String ROTATED_REFRESH = "rotated-refresh-token";
    private static final String ROTATED_ID = "rotated-id-token";

    private static final String COOKIE_HEADER = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + SESSION_ID;

    private InMemorySessionStore store;
    private SessionBinding binding;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16);
        binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
    }

    private static SessionRecord session(String refreshToken) {
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
        return new RotationResult(rotatedAccess, ROTATED_REFRESH, ROTATED_ID, 300L, true);
    }

    private TokenRefreshCoordinator coordinator(Instant accessExpiry, RefreshExchange exchange) {
        return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, exchange, binding);
    }

    @Nested
    @DisplayName("No refresh needed")
    class NoRefresh {

        @Test
        @DisplayName("Should return the current session unchanged when not within the expiry leeway")
        void shouldReturnCurrentWhenNotNearExpiry() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live, NOW);
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
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live, NOW);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            SessionRecord rotated = outcome.session();
            assertEquals(ROTATED_ACCESS, rotated.accessToken());
            assertEquals(ROTATED_REFRESH, rotated.refreshToken());
            assertEquals(ROTATED_ID, rotated.idToken());
            assertEquals(live.expiresAt(), rotated.expiresAt(), "the absolute session cap is unchanged by a refresh");
        }

        @Test
        @DisplayName("Should pass the session's current refresh token to the engine")
        void shouldPresentCurrentRefreshToken() {
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live, NOW);
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

    @Nested
    @DisplayName("Failure and reuse detection")
    class Failure {

        @Test
        @DisplayName("Should destroy the session and fail when the engine refresh is rejected")
        void shouldFailAndDestroyOnEngineRejection() {
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live, NOW);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                throw new ClientProtocolException("token endpoint rejected the refresh grant");
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertTrue(outcome.isFailure());
            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertNull(outcome.session(), "a failed outcome carries no session");
            assertTrue(store.resolve(SESSION_ID, NOW).isEmpty(), "the session is destroyed on refresh failure");
        }

        @Test
        @DisplayName("Should destroy the session when the engine reports refresh-token reuse (family revoked)")
        void shouldFailOnReuseDetection() {
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live, NOW);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                throw new ClientProtocolException("refresh token family is revoked");
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertTrue(outcome.isFailure(), "a revoked refresh-token family fails the refresh");
            assertTrue(store.resolve(SESSION_ID, NOW).isEmpty(), "a reused-token session is destroyed");
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
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live, NOW);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                calls.incrementAndGet();
                entered.countDown();
                awaitUninterruptibly(proceed);
                return rotation();
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader = pool.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                assertTrue(entered.await(2, TimeUnit.SECONDS), "the leader entered the engine refresh");
                Future<RefreshOutcome> follower = pool.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                // Let the follower reach the in-flight join before the leader is released. There is no
                // observable hook for a thread reaching CompletableFuture#join, so this best-effort
                // ordering sleep cannot be made deterministic without an added dependency.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = leader.get(2, TimeUnit.SECONDS);
                RefreshOutcome followerOutcome = follower.get(2, TimeUnit.SECONDS);

                assertEquals(1, calls.get(), "concurrent requests on one session share a single engine refresh");
                assertFalse(leaderOutcome.isFailure());
                assertFalse(followerOutcome.isFailure(), "the coalesced follower shares the successful refresh");
                assertEquals(ROTATED_ACCESS, store.resolve(SESSION_ID, NOW).orElseThrow().accessToken());
            } finally {
                pool.shutdownNow();
            }
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
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
            return new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, cookieBinding);
        }

        @Test
        @DisplayName("Should re-seal the rotated material into a new Set-Cookie rather than a store write")
        void shouldResealIntoANewCookie() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            assertEquals(ROTATED_ACCESS, outcome.session().accessToken());
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

            assertEquals(cookieSession.sessionNonce(), outcome.session().sessionNonce(),
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

            assertEquals(cookieSession.sessionId(), outcome.session().sessionId(),
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
                awaitUninterruptibly(proceed);
                return rotation();
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader =
                        pool.submit(() -> coordinator.refresh(cookieSession, sealedCookieHeader, NOW));
                assertTrue(entered.await(2, TimeUnit.SECONDS), "the leader entered the engine refresh");
                Future<RefreshOutcome> follower =
                        pool.submit(() -> coordinator.refresh(cookieSession, sealedCookieHeader, NOW));
                // Best-effort ordering: there is no observable hook for a thread reaching
                // CompletableFuture#join, so the follower's arrival cannot be awaited deterministically.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = leader.get(2, TimeUnit.SECONDS);
                RefreshOutcome followerOutcome = follower.get(2, TimeUnit.SECONDS);

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
        @DisplayName("Should fail and destroy the session when the engine rejects the cookie-mode refresh")
        void shouldFailOnEngineRejection() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(rt -> {
                throw new ClientProtocolException("invalid_grant");
            });

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertTrue(outcome.isFailure(),
                    "reuse-as-failure is identical in cookie mode — the stage clears the cookie and re-negotiates");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a failed refresh emits no re-seal");
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
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
        @DisplayName("Should reject constructing a non-failed outcome without a session")
        void shouldRejectPresentContractViolation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.CURRENT, null, List.of()));
        }

        @Test
        @DisplayName("Should expose an empty session and no cookies on a failed outcome")
        void failedOutcomeCarriesNoSession() {
            RefreshOutcome failed = RefreshOutcome.failed();

            assertTrue(failed.isFailure());
            assertNull(failed.session());
            assertTrue(failed.setCookieHeaders().isEmpty());
        }
    }
}
