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
package de.cuioss.sheriff.gateway.detection;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.cuioss.sheriff.gateway.detection.SourceLedger.Action;
import de.cuioss.sheriff.gateway.detection.SourceLedger.Settings;
import de.cuioss.sheriff.gateway.detection.SourceState.Status;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The detection substrate driven by a controllable clock: when a source trips, how the level drains,
 * that a trip is emitted once per {@code CLEAR} period under concurrent admission, how a block expires,
 * what each tag contributed, and that the number of tracked keys stays bounded.
 */
@EnableGeneratorController
@DisplayName("SourceLedger — the per-source leaky bucket and its state machine")
class SourceLedgerTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final double LEVEL_TOLERANCE = 1e-6;

    private enum Tag {
        PROBE, LOGIN
    }

    /**
     * The clock starts five minutes before the {@code nanoTime} wrap, so every case whose time advances
     * that far also proves the drain and the block are computed on differences, never on absolute
     * readings.
     */
    private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - Duration.ofMinutes(5).toNanos());
    private final List<SourceTrip> trips = new CopyOnWriteArrayList<>();

    private SourceLedger ledger(int threshold, Action action, Duration blockDuration) {
        return new SourceLedger(new Settings(threshold, WINDOW, action, blockDuration), clock::get, trips::add);
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    private static SourceKey key(int index) {
        return new SourceKey("192.0." + (index / 256) % 256 + "." + index % 256 + "#" + index, "*");
    }

    @Nested
    @DisplayName("admission and the trip")
    class Admission {

        @Test
        @DisplayName("a scan pattern trips exactly at the threshold and emits one raw trip record")
        void scanTripsAtTheThreshold() {
            SourceLedger ledger = ledger(10, Action.OBSERVE, WINDOW);
            SourceKey scanner = key(1);

            List<Status> statuses = new ArrayList<>();
            for (int event = 0; event < 10; event++) {
                statuses.add(ledger.admit(scanner, 1, Tag.PROBE).status());
            }

            assertAll("nine events stay clear, the tenth trips",
                    () -> assertEquals(Status.CLEAR, statuses.get(8)),
                    () -> assertEquals(Status.TRIPPED, statuses.get(9)),
                    () -> assertEquals(1, trips.size()),
                    () -> assertEquals(scanner, trips.getFirst().key()),
                    () -> assertEquals(10.0, trips.getFirst().levelAtTrip(), LEVEL_TOLERANCE),
                    () -> assertEquals(10, trips.getFirst().threshold()),
                    () -> assertEquals(WINDOW, trips.getFirst().window()),
                    () -> assertEquals(Action.OBSERVE, trips.getFirst().action()),
                    () -> assertEquals(clock.get(), trips.getFirst().trippedAtNanos()));
        }

        @Test
        @DisplayName("weights accumulate: three events of weight 4 trip a threshold of 10")
        void weightsAccumulate() {
            SourceLedger ledger = ledger(10, Action.OBSERVE, WINDOW);
            SourceKey source = key(2);

            SourceState first = ledger.admit(source, 4, Tag.PROBE);
            SourceState second = ledger.admit(source, 4, Tag.PROBE);
            SourceState third = ledger.admit(source, 4, Tag.PROBE);

            assertAll("4 + 4 + 4 reaches 10 on the third event",
                    () -> assertEquals(4.0, first.level(), LEVEL_TOLERANCE),
                    () -> assertEquals(Status.CLEAR, second.status()),
                    () -> assertEquals(8.0, second.level(), LEVEL_TOLERANCE),
                    () -> assertEquals(Status.TRIPPED, third.status()),
                    () -> assertEquals(12.0, third.level(), LEVEL_TOLERANCE));
        }

        @Test
        @DisplayName("each tag's weight since the bucket was last empty is reported")
        void perTagContributions() {
            SourceLedger ledger = ledger(5, Action.OBSERVE, WINDOW);
            SourceKey source = key(3);

            ledger.admit(source, 2, Tag.PROBE);
            ledger.admit(source, 1, Tag.LOGIN);
            SourceState state = ledger.admit(source, 2, Tag.PROBE);

            assertAll("the breakdown is per tag",
                    () -> assertEquals(Map.of(Tag.PROBE, 4L, Tag.LOGIN, 1L), state.contributions()),
                    () -> assertEquals(state.contributions(), trips.getFirst().contributions()));
        }

        @Test
        @DisplayName("an honest source with sub-threshold events never trips")
        void honestSourceNeverTrips() {
            SourceLedger ledger = ledger(100, Action.BLOCK, WINDOW);
            SourceKey honest = key(4);

            for (int second = 0; second < 600; second++) {
                ledger.admit(honest, 1, Tag.PROBE);
                advance(Duration.ofSeconds(1));
            }

            assertAll("one miss a second against a hundred a minute",
                    () -> assertTrue(trips.isEmpty()),
                    () -> assertEquals(Status.CLEAR, ledger.state(honest).orElseThrow().status()),
                    () -> assertFalse(ledger.isBlocked(honest)));
        }
    }

    @Nested
    @DisplayName("drain and re-arm")
    class Drain {

        @Test
        @DisplayName("the level drains linearly at threshold per window and clears the breakdown at zero")
        void linearDrain() {
            SourceLedger ledger = ledger(10, Action.OBSERVE, WINDOW);
            SourceKey source = key(5);
            ledger.admit(source, 8, Tag.PROBE);

            advance(Duration.ofSeconds(30));
            SourceState half = ledger.state(source).orElseThrow();
            advance(Duration.ofSeconds(30));
            SourceState drained = ledger.state(source).orElseThrow();

            assertAll("10 per 60 s drains 5 in 30 s",
                    () -> assertEquals(3.0, half.level(), LEVEL_TOLERANCE),
                    () -> assertEquals(0.0, drained.level(), LEVEL_TOLERANCE),
                    () -> assertTrue(drained.contributions().isEmpty()));
        }

        @Test
        @DisplayName("one trip per CLEAR period: no second trip until the level drained back to zero")
        void oneTripPerClearPeriod() {
            SourceLedger ledger = ledger(10, Action.OBSERVE, WINDOW);
            SourceKey source = key(6);

            ledger.admit(source, 10, Tag.PROBE);
            ledger.admit(source, 10, Tag.PROBE);
            advance(Duration.ofSeconds(90));
            SourceState stillTripped = ledger.state(source).orElseThrow();
            advance(Duration.ofSeconds(30));
            SourceState cleared = ledger.state(source).orElseThrow();
            ledger.admit(source, 10, Tag.PROBE);

            assertAll("the trip re-arms only at an empty bucket",
                    () -> assertEquals(Status.TRIPPED, stillTripped.status()),
                    () -> assertEquals(Status.CLEAR, cleared.status()),
                    () -> assertEquals(2, trips.size()));
        }

        @Test
        @DisplayName("exactly one trip per CLEAR period under concurrent admission from many threads")
        void oneTripUnderConcurrency() throws Exception {
            int threads = 16;
            int eventsPerThread = 50;
            for (int round = 0; round < 20; round++) {
                trips.clear();
                SourceLedger ledger = ledger(100, Action.BLOCK, WINDOW);
                SourceKey source = key(7);
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Void>> workers = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    workers.add(() -> {
                        start.await();
                        for (int event = 0; event < eventsPerThread; event++) {
                            ledger.admit(source, 1, Tag.PROBE);
                        }
                        return null;
                    });
                }

                try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                    List<Future<Void>> futures = workers.stream().map(pool::submit).toList();
                    start.countDown();
                    for (Future<Void> future : futures) {
                        future.get();
                    }
                }

                int finalRound = round;
                assertAll("round " + round,
                        () -> assertEquals(1, trips.size(), "round " + finalRound + " emitted one trip"),
                        () -> assertEquals(threads * eventsPerThread,
                                ledger.state(source).orElseThrow().level(), LEVEL_TOLERANCE));
            }
        }
    }

    @Nested
    @DisplayName("the block action")
    class Block {

        @Test
        @DisplayName("a trip blocks for the block duration, then the source is tripped until drained")
        void blockExpires() {
            SourceLedger ledger = ledger(10, Action.BLOCK, Duration.ofSeconds(30));
            SourceKey source = key(8);

            SourceState blocked = ledger.admit(source, 10, Tag.PROBE);
            long remaining = blocked.remainingBlockNanos(clock.get());
            advance(Duration.ofSeconds(30));
            SourceState afterBlock = ledger.state(source).orElseThrow();
            advance(Duration.ofSeconds(30));
            SourceState drained = ledger.state(source).orElseThrow();

            assertAll("BLOCKED -> TRIPPED -> CLEAR",
                    () -> assertEquals(Status.BLOCKED, blocked.status()),
                    () -> assertEquals(Duration.ofSeconds(30).toNanos(), remaining),
                    () -> assertEquals(Status.TRIPPED, afterBlock.status()),
                    () -> assertFalse(ledger.isBlocked(source)),
                    () -> assertEquals(Status.CLEAR, drained.status()),
                    () -> assertEquals(0L, drained.remainingBlockNanos(clock.get())));
        }

        @Test
        @DisplayName("a source back at the threshold after its block expired is blocked again without a new trip")
        void reBlockWithoutSecondTrip() {
            SourceLedger ledger = ledger(10, Action.BLOCK, Duration.ofSeconds(10));
            SourceKey source = key(9);
            ledger.admit(source, 10, Tag.PROBE);

            advance(Duration.ofSeconds(12));
            SourceState reBlocked = ledger.admit(source, 5, Tag.PROBE);

            assertAll("the block follows the level; the trip follows the CLEAR period",
                    () -> assertEquals(Status.BLOCKED, reBlocked.status()),
                    () -> assertEquals(1, trips.size()));
        }

        @Test
        @DisplayName("observe and delay never block")
        void observeAndDelayNeverBlock() {
            SourceLedger observe = ledger(1, Action.OBSERVE, WINDOW);
            SourceLedger delay = ledger(1, Action.DELAY, WINDOW);
            SourceKey source = key(10);

            assertAll("only block drives BLOCKED",
                    () -> assertEquals(Status.TRIPPED, observe.admit(source, 1, Tag.PROBE).status()),
                    () -> assertEquals(Status.TRIPPED, delay.admit(source, 1, Tag.PROBE).status()),
                    () -> assertFalse(observe.isBlocked(source)),
                    () -> assertEquals(Action.DELAY, trips.get(1).action()));
        }
    }

    @Nested
    @DisplayName("the cardinality bound")
    class Cardinality {

        @Test
        @DisplayName("70,000 distinct sources: the bound holds, blocked keys survive, the overflow is reported")
        void boundHoldsUnderSeventyThousandSources() {
            AtomicInteger tripCount = new AtomicInteger();
            SourceLedger ledger = new SourceLedger(new Settings(1, Duration.ofSeconds(1), Action.BLOCK,
                    Duration.ofHours(1)), clock::get, trip -> tripCount.incrementAndGet());
            List<SourceKey> firstBlocked = List.of(key(0), key(1), key(2));
            firstBlocked.forEach(source -> ledger.admit(source, 1, Tag.PROBE));
            advance(Duration.ofSeconds(5));

            int untracked = 0;
            for (int index = 3; index < 70_000; index++) {
                if (ledger.admit(key(index), 1, Tag.PROBE).status() == Status.UNTRACKED) {
                    untracked++;
                }
            }

            int finalUntracked = untracked;
            assertAll("single-threaded insertion stays exactly at the bound",
                    () -> assertTrue(ledger.trackedSources() <= SourceLedger.MAX_TRACKED_SOURCES),
                    () -> assertEquals(70_000 - SourceLedger.MAX_TRACKED_SOURCES, finalUntracked),
                    () -> assertEquals(finalUntracked, ledger.untrackedCount()),
                    () -> firstBlocked.forEach(source -> assertTrue(ledger.isBlocked(source),
                            "a blocked key is never evicted")));
        }

        @Test
        @DisplayName("a sweep at the bound evicts drained keys only, at most once per sweep interval")
        void sweepEvictsDrainedKeysOnly() {
            SourceLedger ledger = new SourceLedger(new Settings(2, Duration.ofSeconds(1), Action.BLOCK,
                    Duration.ofHours(1)), clock::get, trips::add, 4);
            ledger.admit(key(0), 2, Tag.PROBE);
            ledger.admit(key(1), 2, Tag.PROBE);
            ledger.admit(key(2), 1, Tag.PROBE);
            ledger.admit(key(3), 1, Tag.PROBE);
            advance(SourceLedger.SWEEP_INTERVAL.plusSeconds(1));

            SourceState admittedAfterSweep = ledger.admit(key(4), 1, Tag.PROBE);
            ledger.admit(key(5), 1, Tag.PROBE);
            SourceState refusedWithinInterval = ledger.admit(key(6), 1, Tag.PROBE);

            assertAll("the two drained keys made room; the blocked ones stayed",
                    () -> assertEquals(Status.CLEAR, admittedAfterSweep.status()),
                    () -> assertTrue(ledger.isBlocked(key(0))),
                    () -> assertTrue(ledger.isBlocked(key(1))),
                    () -> assertTrue(ledger.state(key(2)).isEmpty()),
                    () -> assertEquals(Status.UNTRACKED, refusedWithinInterval.status()),
                    () -> assertEquals(1L, ledger.untrackedCount()));
        }

        @Test
        @DisplayName("an untracked key answers no state and is never blocked")
        void untrackedKeyHasNoState() {
            SourceLedger ledger = ledger(1, Action.BLOCK, WINDOW);

            assertAll("nothing is known of a key never admitted",
                    () -> assertTrue(ledger.state(key(42)).isEmpty()),
                    () -> assertFalse(ledger.isBlocked(key(42))));
        }
    }

    @Test
    @DisplayName("settings and admission refuse values that cannot mean anything")
    void refusesMeaninglessValues() {
        SourceLedger ledger = ledger(1, Action.OBSERVE, WINDOW);
        Duration negativeBlock = Duration.ofSeconds(-1);
        SourceKey source = key(1);

        assertAll("each bound is enforced",
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Settings(0, WINDOW, Action.OBSERVE, WINDOW)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Settings(1, Duration.ZERO, Action.OBSERVE, WINDOW)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Settings(1, WINDOW, Action.BLOCK, negativeBlock)),
                () -> assertThrows(IllegalArgumentException.class, () -> ledger.admit(source, 0, Tag.PROBE)));
    }
}
