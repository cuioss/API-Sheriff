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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;


import de.cuioss.sheriff.gateway.detection.SourceLedger.Action;
import de.cuioss.sheriff.gateway.detection.SourceLedger.Settings;
import de.cuioss.sheriff.gateway.detection.SourceState.Status;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The detection substrate under pressure, on a small-bound ledger driven by a controllable clock: from
 * the high-water mark on, a new IPv6 source is tracked under its {@code /48} aggregate while a tracked
 * key keeps its key, a {@code /64} finds its tracked aggregate, and a source already accruing weight
 * stays tracked when the rest of the ledger fills.
 */
@EnableGeneratorController
@DisplayName("SourceLedger — the high-water aggregation and the full ledger")
class SourceLedgerPressureTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int BOUND = 8;
    /** The high-water mark the ledger derives from {@link #BOUND}: three quarters of it. */
    private static final int MARK = 6;
    private static final int THRESHOLD = 100;

    private static final SourceKey PREFIX = ipv6("2001:db8:5:7::/64");
    private static final SourceKey SIBLING_PREFIX = ipv6("2001:db8:5:9::/64");
    private static final SourceKey AGGREGATE = ipv6("2001:db8:5::/48");

    private enum Tag {
        PROBE
    }

    private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - Duration.ofMinutes(5).toNanos());
    private final List<SourceTrip> trips = new CopyOnWriteArrayList<>();

    private SourceLedger ledger(int threshold) {
        return new SourceLedger(new Settings(threshold, WINDOW, Action.BLOCK, WINDOW), clock::get, trips::add,
                BOUND);
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    private static SourceKey ipv6(String source) {
        return new SourceKey(source, SourceKey.UNDECLARED_HOST);
    }

    private static SourceKey ipv4(int index) {
        return new SourceKey("192.0.2." + index, SourceKey.UNDECLARED_HOST);
    }

    /** Tracks {@code count} live IPv4 keys, none of which drains while the clock stands still. */
    private static void fill(SourceLedger ledger, int count) {
        for (int index = 0; index < count; index++) {
            ledger.admit(ipv4(index), 1, Tag.PROBE);
        }
    }

    @Test
    @DisplayName("at the mark a new IPv6 source is tracked under its /48 aggregate")
    void newIpv6SourceAtTheMarkIsAggregated() {
        SourceLedger ledger = ledger(THRESHOLD);
        fill(ledger, MARK);

        SourceState admitted = ledger.admit(PREFIX, 1, Tag.PROBE);

        assertAll("the /64 folds to its /48",
                () -> assertEquals(AGGREGATE, admitted.key()),
                () -> assertEquals(Status.CLEAR, admitted.status()),
                () -> assertTrue(ledger.state(AGGREGATE).isPresent(), "the aggregate is tracked"),
                () -> assertEquals(MARK + 1, ledger.trackedSources(), "one slot was taken, not two"));
    }

    @Test
    @DisplayName("below the mark a new IPv6 source keeps its /64")
    void newIpv6SourceBelowTheMarkKeepsItsPrefix() {
        SourceLedger ledger = ledger(THRESHOLD);
        fill(ledger, MARK - 1);

        SourceState admitted = ledger.admit(PREFIX, 1, Tag.PROBE);

        assertAll("no pressure, no aggregation",
                () -> assertEquals(PREFIX, admitted.key()),
                () -> assertTrue(ledger.state(AGGREGATE).isEmpty(), "no aggregate was tracked"));
    }

    @Test
    @DisplayName("a /64 tracked before the mark keeps its key at any fill")
    void trackedPrefixKeepsItsKey() {
        SourceLedger ledger = ledger(THRESHOLD);
        ledger.admit(PREFIX, 1, Tag.PROBE);
        fill(ledger, BOUND - 1);

        SourceState again = ledger.admit(PREFIX, 1, Tag.PROBE);

        assertAll("a tracked key is never re-keyed",
                () -> assertEquals(PREFIX, again.key()),
                () -> assertEquals(2.0, again.level(), 1e-6),
                () -> assertTrue(ledger.state(AGGREGATE).isEmpty()));
    }

    @Test
    @DisplayName("a /64 whose aggregate is tracked is answered and counted on the aggregate")
    void prefixResolvesToItsTrackedAggregate() {
        SourceLedger ledger = ledger(THRESHOLD);
        fill(ledger, MARK);
        ledger.admit(PREFIX, 1, Tag.PROBE);

        SourceState sibling = ledger.admit(SIBLING_PREFIX, 1, Tag.PROBE);

        assertAll("both /64s of the /48 share its one entry",
                () -> assertEquals(AGGREGATE, ledger.state(PREFIX).orElseThrow().key()),
                () -> assertEquals(AGGREGATE, sibling.key()),
                () -> assertEquals(2.0, sibling.level(), 1e-6),
                () -> assertEquals(MARK + 1, ledger.trackedSources()));
    }

    @Test
    @DisplayName("an IPv4 source at the mark is tracked whole")
    void ipv4SourceIsNeverAggregated() {
        SourceLedger ledger = ledger(THRESHOLD);
        fill(ledger, MARK);

        SourceState admitted = ledger.admit(ipv4(200), 1, Tag.PROBE);

        assertEquals(ipv4(200), admitted.key());
    }

    @Test
    @DisplayName("the room query is false on a ledger full of live keys and true once they drained and the sweep ran")
    void roomQueryFollowsTheSweep() {
        SourceLedger ledger = ledger(THRESHOLD);
        fill(ledger, BOUND);
        SourceKey newcomer = ipv4(200);

        boolean whileFull = ledger.hasRoomFor(newcomer);
        advance(WINDOW.plusSeconds(1));
        boolean afterDrain = ledger.hasRoomFor(newcomer);

        assertAll("the query asks what admit would decide, and admits nothing",
                () -> assertFalse(whileFull),
                () -> assertTrue(afterDrain),
                () -> assertEquals(0, ledger.trackedSources(), "the sweep the query ran removed the drained keys"),
                () -> assertEquals(0L, ledger.untrackedCount(), "a query is never counted as an overflow"),
                () -> assertTrue(ledger.state(newcomer).isEmpty(), "nothing was tracked by asking"));
    }

    @Test
    @DisplayName("a full ledger has room for a tracked key and for a /64 whose aggregate it tracks, not for a new key")
    void roomQueryAnswersTrackedKeysWhenFull() {
        SourceLedger ledger = ledger(THRESHOLD);
        fill(ledger, MARK);
        ledger.admit(PREFIX, 1, Tag.PROBE);
        fill(ledger, BOUND - 1);
        SourceKey newcomer = ipv4(200);

        boolean newcomerRoom = ledger.hasRoomFor(newcomer);
        SourceState newcomerAdmitted = ledger.admit(newcomer, 1, Tag.PROBE);

        assertAll("only an admission that needs a new slot is refused",
                () -> assertEquals(BOUND, ledger.trackedSources(), "precondition: the ledger is full"),
                () -> assertTrue(ledger.hasRoomFor(ipv4(0)), "a tracked key"),
                () -> assertTrue(ledger.hasRoomFor(SIBLING_PREFIX), "a /64 of the tracked /48"),
                () -> assertFalse(newcomerRoom, "a new key"),
                () -> assertEquals(Status.UNTRACKED, newcomerAdmitted.status(), "admit agrees with the query"));
    }

    @Test
    @DisplayName("a source accruing weight stays tracked and trips when the rest of the ledger is full")
    void accruingSourceStaysTrackedInAFullLedger() {
        SourceLedger ledger = ledger(5);
        SourceKey accruing = ipv4(100);
        ledger.admit(accruing, 1, Tag.PROBE);
        fill(ledger, BOUND - 1);
        SourceState newcomer = ledger.admit(ipv4(101), 1, Tag.PROBE);

        Status last = null;
        for (int event = 0; event < 4; event++) {
            last = ledger.admit(accruing, 1, Tag.PROBE).status();
        }

        Status tripped = last;
        assertAll("the full ledger refuses a newcomer, never the tracked source",
                () -> assertEquals(Status.UNTRACKED, newcomer.status(), "precondition: the ledger is full"),
                () -> assertEquals(Status.BLOCKED, tripped),
                () -> assertEquals(1, trips.size()),
                () -> assertEquals(accruing, trips.getFirst().key()));
    }
}
