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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.AccessTokenExpiry;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshExchange;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
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

    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16);
    }

    private static SessionRecord session(String refreshToken) {
        return SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken("access-current")
                .refreshToken(Optional.ofNullable(refreshToken))
                .idToken("id-current")
                .sub("sub-1")
                .sid(Optional.empty())
                .expiresAt(NOW.plus(SESSION_TTL))
                .acr(Optional.empty())
                .authTime(Optional.empty())
                .build();
    }

    private static RotationResult rotation() {
        Map<String, ClaimValue> claims = new HashMap<>();
        claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString("sub-1"));
        AccessTokenContent rotatedAccess = new AccessTokenContent(claims, ROTATED_ACCESS);
        return new RotationResult(rotatedAccess, ROTATED_REFRESH, ROTATED_ID, 300L, true);
    }

    private TokenRefreshCoordinator coordinator(Instant accessExpiry, RefreshExchange exchange) {
        return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, exchange, store);
    }

    @Nested
    @DisplayName("No refresh needed")
    class NoRefresh {

        @Test
        @DisplayName("Should return the current session unchanged when not within the expiry leeway")
        void shouldReturnCurrentWhenNotNearExpiry() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live);
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, rt -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, NOW);

            assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind());
            assertSame(live, outcome.session().orElseThrow(), "the same session is returned unchanged");
            assertEquals(0, calls.get(), "no engine refresh is attempted when the token is not near expiry");
        }

        @Test
        @DisplayName("Should return the current session when near expiry but the session carries no refresh token")
        void shouldReturnCurrentWhenNoRefreshToken() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = session(null);
            store.create(live);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, NOW);

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
            store.create(live);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(live, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            SessionRecord rotated = outcome.session().orElseThrow();
            assertEquals(ROTATED_ACCESS, rotated.accessToken());
            assertEquals(Optional.of(ROTATED_REFRESH), rotated.refreshToken());
            assertEquals(ROTATED_ID, rotated.idToken());
            assertEquals(live.expiresAt(), rotated.expiresAt(), "the absolute session cap is unchanged by a refresh");
        }

        @Test
        @DisplayName("Should pass the session's current refresh token to the engine")
        void shouldPresentCurrentRefreshToken() {
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, presented -> {
                calls.incrementAndGet();
                assertEquals(CURRENT_REFRESH, presented, "the coordinator presents the stored refresh token");
                return rotation();
            });

            coordinator.refresh(live, NOW);

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
            store.create(live);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                throw new ClientProtocolException("token endpoint rejected the refresh grant");
            });

            RefreshOutcome outcome = coordinator.refresh(live, NOW);

            assertTrue(outcome.isFailure());
            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertTrue(outcome.session().isEmpty(), "a failed outcome carries no session");
            assertTrue(store.resolve(SESSION_ID, NOW).isEmpty(), "the session is destroyed on refresh failure");
        }

        @Test
        @DisplayName("Should destroy the session when the engine reports refresh-token reuse (family revoked)")
        void shouldFailOnReuseDetection() {
            SessionRecord live = session(CURRENT_REFRESH);
            store.create(live);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> {
                throw new ClientProtocolException("refresh token family is revoked");
            });

            RefreshOutcome outcome = coordinator.refresh(live, NOW);

            assertTrue(outcome.isFailure(), "a revoked refresh-token family fails the refresh");
            assertTrue(store.resolve(SESSION_ID, NOW).isEmpty(), "a reused-token session is destroyed");
        }

        @Test
        @DisplayName("Should fail when the session was destroyed between the near-expiry check and the refresh")
        void shouldFailWhenSessionGone() {
            SessionRecord live = session(CURRENT_REFRESH);
            // Deliberately NOT stored — models a session destroyed concurrently before the lead resolves it.
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            RefreshOutcome outcome = coordinator.refresh(live, NOW);

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
            store.create(live);
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
                Future<RefreshOutcome> leader = pool.submit(() -> coordinator.refresh(live, NOW));
                assertTrue(entered.await(2, TimeUnit.SECONDS), "the leader entered the engine refresh");
                Future<RefreshOutcome> follower = pool.submit(() -> coordinator.refresh(live, NOW));
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
    @DisplayName("Argument and outcome contracts")
    class Contracts {

        @Test
        @DisplayName("Should reject a null session and a null instant")
        void shouldRejectNullArguments() {
            TokenRefreshCoordinator coordinator = coordinator(NEAR, rt -> rotation());

            var session = session(CURRENT_REFRESH);
            assertThrows(NullPointerException.class, () -> coordinator.refresh(null, NOW));
            assertThrows(NullPointerException.class, () -> coordinator.refresh(session, null));
        }

        @Test
        @DisplayName("Should reject constructing a non-failed outcome without a session")
        void shouldRejectPresentContractViolation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.CURRENT, Optional.empty()));
        }

        @Test
        @DisplayName("Should expose an empty session on a failed outcome")
        void failedOutcomeCarriesNoSession() {
            RefreshOutcome failed = RefreshOutcome.failed();

            assertTrue(failed.isFailure());
            assertTrue(failed.session().isEmpty());
        }
    }
}
