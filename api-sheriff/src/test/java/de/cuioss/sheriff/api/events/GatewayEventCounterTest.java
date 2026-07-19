/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GatewayEventCounter — lock-free in-process counter")
class GatewayEventCounterTest {

    @Test
    @DisplayName("Should report zero for an event that was never incremented")
    void shouldReportZeroForUncountedEvent() {
        var counter = new GatewayEventCounter();

        long count = counter.getCount(EventType.REQUEST_FORWARDED);

        assertEquals(0L, count, "An uncounted event must report zero");
    }

    @Test
    @DisplayName("Should increment a single event type")
    void shouldIncrementSingleEventType() {
        var counter = new GatewayEventCounter();

        counter.increment(EventType.TOKEN_INVALID);
        counter.increment(EventType.TOKEN_INVALID);
        counter.increment(EventType.TOKEN_INVALID);

        assertEquals(3L, counter.getCount(EventType.TOKEN_INVALID), "Three increments must yield three");
    }

    @Test
    @DisplayName("Should count concurrent increments without loss (lock-free)")
    void shouldCountConcurrentIncrementsWithoutLoss() {
        var counter = new GatewayEventCounter();
        int increments = 10_000;

        IntStream.range(0, increments).parallel()
                .forEach(i -> counter.increment(EventType.SECURITY_FILTER_VIOLATION));

        assertEquals(increments, counter.getCount(EventType.SECURITY_FILTER_VIOLATION),
                "Every concurrent increment must be counted");
    }

    @Test
    @DisplayName("Should expose an unmodifiable snapshot of all non-zero counters")
    void shouldExposeUnmodifiableSnapshot() {
        var counter = new GatewayEventCounter();
        counter.increment(EventType.UPSTREAM_ERROR);
        counter.increment(EventType.SCOPE_MISSING);

        Map<EventType, Long> snapshot = counter.getCounters();

        assertEquals(2, snapshot.size(), "Only incremented events appear in the snapshot");
        assertEquals(1L, snapshot.get(EventType.UPSTREAM_ERROR), "Snapshot value must match the count");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(EventType.CONFIG_LOADED, 5L),
                "The snapshot must be unmodifiable");
    }

    @Test
    @DisplayName("Should clear every counter on reset")
    void shouldClearEveryCounterOnReset() {
        var counter = new GatewayEventCounter();
        counter.increment(EventType.NO_ROUTE_MATCHED);

        counter.reset();

        assertEquals(0L, counter.getCount(EventType.NO_ROUTE_MATCHED), "Reset must zero the counter");
        assertTrue(counter.getCounters().isEmpty(), "Reset must empty the snapshot");
    }
}
