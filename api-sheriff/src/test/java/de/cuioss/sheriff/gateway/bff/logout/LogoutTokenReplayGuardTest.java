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
package de.cuioss.sheriff.gateway.bff.logout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenReplayGuard.Admission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link LogoutTokenReplayGuard}: a token identifier is admitted once for as long as its
 * token could be accepted, the memory is bounded, and a memory full of identifiers still inside their
 * window refuses a new one instead of forgetting an old one.
 * <p>
 * The {@code Bound} cases assert the refusal together with what it protects: after a refused
 * admission every identifier that was held is still recognised as a repeat. A guard that made room by
 * dropping a live identifier would pass a bare "the new one is refused or admitted" assertion and
 * fail that one.
 */
class LogoutTokenReplayGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(2);
    private static final Instant WINDOW_END = NOW.plus(WINDOW);

    @Nested
    @DisplayName("Admission")
    class Admitting {

        @Test
        @DisplayName("Should admit an identifier seen for the first time and refuse it the second time")
        void shouldAdmitOnce() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(4);

            assertEquals(Admission.ADMITTED, guard.admit("jti-1", WINDOW_END, NOW));
            assertEquals(Admission.REPLAYED, guard.admit("jti-1", WINDOW_END, NOW.plusSeconds(1)));
        }

        @Test
        @DisplayName("Should still refuse a repeat at the last instant of the window")
        void shouldRefuseRepeatAtWindowEnd() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(4);
            guard.admit("jti-1", WINDOW_END, NOW);

            assertEquals(Admission.REPLAYED, guard.admit("jti-1", WINDOW_END, WINDOW_END));
        }

        @Test
        @DisplayName("Should admit an identifier again once its window has passed")
        void shouldAdmitAfterWindow() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(4);
            guard.admit("jti-1", WINDOW_END, NOW);
            Instant afterWindow = WINDOW_END.plusSeconds(1);

            assertEquals(Admission.ADMITTED, guard.admit("jti-1", afterWindow.plus(WINDOW), afterWindow));
        }

        @Test
        @DisplayName("Should keep distinct identifiers apart")
        void shouldKeepIdentifiersApart() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(4);
            guard.admit("jti-1", WINDOW_END, NOW);

            assertEquals(Admission.ADMITTED, guard.admit("jti-2", WINDOW_END, NOW));
            assertEquals(Admission.REPLAYED, guard.admit("jti-1", WINDOW_END, NOW));
            assertEquals(Admission.REPLAYED, guard.admit("jti-2", WINDOW_END, NOW));
        }
    }

    @Nested
    @DisplayName("Bound")
    class Bound {

        @Test
        @DisplayName("Should refuse a new identifier when every held one is inside its window, and forget none")
        void shouldRefuseWhenFullOfLiveIdentifiers() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(3);
            for (int index = 0; index < 3; index++) {
                assertEquals(Admission.ADMITTED, guard.admit("held-" + index, WINDOW_END, NOW));
            }

            assertEquals(Admission.FULL, guard.admit("one-too-many", WINDOW_END, NOW));

            for (int index = 0; index < 3; index++) {
                assertEquals(Admission.REPLAYED, guard.admit("held-" + index, WINDOW_END, NOW),
                        "an identifier inside its window is never dropped to make room");
            }
        }

        @Test
        @DisplayName("Should not remember an identifier it refused for lack of room")
        void shouldNotRememberRefusedIdentifier() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(1);
            guard.admit("held", WINDOW_END, NOW);
            assertEquals(Admission.FULL, guard.admit("refused", WINDOW_END.plusSeconds(60), NOW));
            Instant afterHeldWindow = WINDOW_END.plusSeconds(1);

            assertEquals(Admission.ADMITTED, guard.admit("refused", WINDOW_END.plusSeconds(60), afterHeldWindow),
                    "a refused identifier was not stored, so its next delivery is a first sight");
        }

        @Test
        @DisplayName("Should make room by dropping an identifier whose window has passed")
        void shouldDropPassedIdentifierAtTheBound() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(2);
            guard.admit("passed", NOW.plusSeconds(10), NOW);
            guard.admit("live", WINDOW_END, NOW);
            Instant later = NOW.plusSeconds(11);

            assertEquals(Admission.ADMITTED, guard.admit("new", later.plus(WINDOW), later));
            assertEquals(Admission.REPLAYED, guard.admit("live", WINDOW_END, later),
                    "the identifier still inside its window survived the clean-up");
        }

        @ParameterizedTest(name = "capacity {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Should refuse a capacity that is not positive")
        void shouldRefuseNonPositiveCapacity(int capacity) {
            assertThrows(IllegalArgumentException.class, () -> new LogoutTokenReplayGuard(capacity));
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("Should admit exactly one of many simultaneous deliveries of one identifier")
        void shouldAdmitExactlyOneOfConcurrentDeliveries() throws Exception {
            int deliveries = 16;
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(4);
            CyclicBarrier start = new CyclicBarrier(deliveries);
            List<Future<Admission>> outcomes = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(deliveries)) {
                for (int index = 0; index < deliveries; index++) {
                    outcomes.add(executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return guard.admit("jti-1", WINDOW_END, NOW);
                    }));
                }
                int admitted = 0;
                for (Future<Admission> outcome : outcomes) {
                    if (outcome.get(10, TimeUnit.SECONDS) == Admission.ADMITTED) {
                        admitted++;
                    }
                }

                assertEquals(1, admitted, "one token delivered concurrently is acted on once");
            }
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject null arguments")
        void shouldRejectNullArguments() {
            LogoutTokenReplayGuard guard = new LogoutTokenReplayGuard(1);

            assertThrows(NullPointerException.class, () -> guard.admit(null, WINDOW_END, NOW));
            assertThrows(NullPointerException.class, () -> guard.admit("jti", null, NOW));
            assertThrows(NullPointerException.class, () -> guard.admit("jti", WINDOW_END, null));
        }
    }
}
