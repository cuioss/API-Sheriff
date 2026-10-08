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
package de.cuioss.sheriff.gateway.bff.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry.Tracked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SessionRelayRegistry}: a tracked relay is told to close when its session ends,
 * exactly once and whichever of the two — the end or the relay's close action — comes first; a relay
 * of another session is left alone; the registry is bounded and refuses a relay beyond the bound; and
 * an entry leaves on release, so the entry count returns to zero.
 */
class SessionRelayRegistryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant EXPIRES_AT = NOW.plusSeconds(3600);
    private static final String SESSION = "session-one";
    private static final String OTHER_SESSION = "session-two";

    private final SessionRelayRegistry registry = new SessionRelayRegistry(4, CLOCK);

    @Nested
    @DisplayName("Ending a session")
    class SessionEnd {

        @Test
        @DisplayName("Should run the close action of a tracked relay once when its session ends")
        void shouldCloseTrackedRelayOnce() {
            AtomicInteger closes = new AtomicInteger();
            Tracked relay = registry.track(SESSION, EXPIRES_AT).orElseThrow();
            relay.onSessionEnd(closes::incrementAndGet);

            registry.sessionEnded(SESSION);
            registry.sessionEnded(SESSION);

            assertEquals(1, closes.get());
            assertTrue(relay.sessionEnded());
            assertEquals(0, registry.size(), "an ended session's relays are no longer tracked");
        }

        @Test
        @DisplayName("Should close every relay of the ended session and none of another session")
        void shouldCloseOnlyTheEndedSessionsRelays() {
            AtomicInteger endedCloses = new AtomicInteger();
            AtomicInteger otherCloses = new AtomicInteger();
            registry.track(SESSION, EXPIRES_AT).orElseThrow().onSessionEnd(endedCloses::incrementAndGet);
            registry.track(SESSION, EXPIRES_AT).orElseThrow().onSessionEnd(endedCloses::incrementAndGet);
            Tracked other = registry.track(OTHER_SESSION, EXPIRES_AT).orElseThrow();
            other.onSessionEnd(otherCloses::incrementAndGet);

            registry.sessionEnded(SESSION);

            assertEquals(2, endedCloses.get(), "both relays of the ended session are closed");
            assertEquals(0, otherCloses.get(), "the relay of the other session is left open");
            assertFalse(other.sessionEnded());
            assertEquals(1, registry.size());
        }

        @Test
        @DisplayName("Should run a close action at once when it is registered after the session ended")
        void shouldCloseRelayWiredAfterTheEnd() {
            AtomicInteger closes = new AtomicInteger();
            Tracked relay = registry.track(SESSION, EXPIRES_AT).orElseThrow();
            registry.sessionEnded(SESSION);

            relay.onSessionEnd(closes::incrementAndGet);

            assertEquals(1, closes.get(), "a session that ended during the dial still closes the relay");
        }

        @Test
        @DisplayName("Should ignore the end of a session with no tracked relay")
        void shouldIgnoreUnknownSession() {
            AtomicInteger closes = new AtomicInteger();
            registry.track(SESSION, EXPIRES_AT).orElseThrow().onSessionEnd(closes::incrementAndGet);

            registry.sessionEnded(OTHER_SESSION);

            assertEquals(0, closes.get());
            assertEquals(1, registry.size());
        }
    }

    @Nested
    @DisplayName("Releasing a relay")
    class Release {

        @Test
        @DisplayName("Should stop tracking a released relay and not close it when its session ends later")
        void shouldNotCloseReleasedRelay() {
            AtomicInteger closes = new AtomicInteger();
            Tracked relay = registry.track(SESSION, EXPIRES_AT).orElseThrow();
            relay.onSessionEnd(closes::incrementAndGet);

            relay.release();
            registry.sessionEnded(SESSION);

            assertEquals(0, closes.get(), "a relay that was torn down is not closed a second time");
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("Should count a relay once however often it is released, also after its session ended")
        void shouldTolerateRepeatedRelease() {
            Tracked released = registry.track(SESSION, EXPIRES_AT).orElseThrow();
            Tracked ended = registry.track(OTHER_SESSION, EXPIRES_AT).orElseThrow();
            Tracked kept = registry.track(OTHER_SESSION + "-kept", EXPIRES_AT).orElseThrow();

            released.release();
            released.release();
            registry.sessionEnded(OTHER_SESSION);
            ended.release();

            assertEquals(1, registry.size(), "only the relay that was neither released nor ended is counted");
            assertFalse(kept.sessionEnded());
        }
    }

    @Nested
    @DisplayName("Bound")
    class Bound {

        @Test
        @DisplayName("Should refuse a relay beyond the bound and keep closing the ones it tracks")
        void shouldRefuseBeyondCapacity() {
            AtomicInteger closes = new AtomicInteger();
            for (int index = 0; index < 4; index++) {
                registry.track(SESSION, EXPIRES_AT).orElseThrow().onSessionEnd(closes::incrementAndGet);
            }

            Optional<Tracked> beyond = registry.track(SESSION, EXPIRES_AT);
            registry.sessionEnded(SESSION);

            assertTrue(beyond.isEmpty(), "a relay that cannot be tracked is not handed a handle");
            assertEquals(4, closes.get(), "every tracked relay is still closed with its session");
        }

        @Test
        @DisplayName("Should track a new relay in the place a released one left")
        void shouldReuseReleasedPlace() {
            List<Tracked> relays = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                relays.add(registry.track(SESSION, EXPIRES_AT).orElseThrow());
            }
            assertTrue(registry.track(OTHER_SESSION, EXPIRES_AT).isEmpty(), "precondition: the registry is full");

            relays.getFirst().release();

            assertTrue(registry.track(OTHER_SESSION, EXPIRES_AT).isPresent());
            assertEquals(4, registry.size());
        }

        @ParameterizedTest(name = "capacity {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Should refuse a capacity that is not positive")
        void shouldRefuseNonPositiveCapacity(int capacity) {
            assertThrows(IllegalArgumentException.class, () -> new SessionRelayRegistry(capacity, CLOCK));
        }
    }

    @Nested
    @DisplayName("Absolute expiry")
    class AbsoluteExpiry {

        @Test
        @DisplayName("Should report the time left until the session's absolute expiry")
        void shouldReportRemainingLifetime() {
            Tracked relay = registry.track(SESSION, NOW.plusSeconds(90)).orElseThrow();

            assertEquals(Optional.of(Duration.ofSeconds(90)), relay.untilAbsoluteExpiry());
        }

        @Test
        @DisplayName("Should report zero at the expiry and a negative time after it")
        void shouldReportElapsedLifetime() {
            Tracked atExpiry = registry.track(SESSION, NOW).orElseThrow();
            Tracked pastExpiry = registry.track(SESSION, NOW.minusSeconds(5)).orElseThrow();

            assertEquals(Optional.of(Duration.ZERO), atExpiry.untilAbsoluteExpiry());
            assertEquals(Optional.of(Duration.ofSeconds(-5)), pastExpiry.untilAbsoluteExpiry());
        }
    }

    @Nested
    @DisplayName("The no-session handle")
    class Untracked {

        @Test
        @DisplayName("Should never run an action, have no expiry and never be ended")
        void shouldBeInert() {
            AtomicInteger closes = new AtomicInteger();
            Tracked first = SessionRelayRegistry.untracked();
            Tracked second = SessionRelayRegistry.untracked();

            first.onSessionEnd(closes::incrementAndGet);
            second.onSessionEnd(closes::incrementAndGet);
            first.release();
            registry.sessionEnded("");

            assertSame(first, second, "the no-session handle is shared");
            assertEquals(0, closes.get(), "an action handed to it by one relay is never run for another");
            assertEquals(Optional.empty(), first.untilAbsoluteExpiry());
            assertFalse(first.sessionEnded());
        }
    }

    @Nested
    @DisplayName("Text form")
    class TextForm {

        @Test
        @DisplayName("Should not print the session identity")
        void shouldNotPrintSessionIdentity() {
            Tracked relay = registry.track(SESSION, EXPIRES_AT).orElseThrow();

            assertFalse(relay.toString().contains(SESSION), relay.toString());
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("Should leave no entry behind after concurrent tracking, releasing and ending")
        void shouldReturnToZeroUnderConcurrency() throws Exception {
            int workers = 8;
            int rounds = 200;
            SessionRelayRegistry shared = new SessionRelayRegistry(workers * rounds, CLOCK);
            Queue<AtomicInteger> closesPerRelay = new ConcurrentLinkedQueue<>();
            CyclicBarrier start = new CyclicBarrier(workers);
            List<Future<?>> running = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
                for (int worker = 0; worker < workers; worker++) {
                    String session = "session-" + (worker % 2);
                    boolean ends = worker % 4 == 0;
                    running.add(executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        for (int round = 0; round < rounds; round++) {
                            Tracked relay = shared.track(session, EXPIRES_AT).orElseThrow();
                            AtomicInteger closes = new AtomicInteger();
                            closesPerRelay.add(closes);
                            relay.onSessionEnd(closes::incrementAndGet);
                            if (ends) {
                                shared.sessionEnded(session);
                            }
                            relay.release();
                        }
                        return null;
                    }));
                }
                for (Future<?> worker : running) {
                    worker.get(30, TimeUnit.SECONDS);
                }
            }

            assertEquals(0, shared.size(), "every relay was released or ended, so nothing is tracked");
            assertEquals(workers * rounds, closesPerRelay.size(), "every tracked relay registered its close action");
            assertTrue(closesPerRelay.stream().allMatch(closes -> closes.get() <= 1),
                    "no close action ran more than once");
        }
    }
}
