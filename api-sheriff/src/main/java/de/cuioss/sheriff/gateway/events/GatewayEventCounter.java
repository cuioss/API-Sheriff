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
package de.cuioss.sheriff.gateway.events;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, lock-free in-process event counter, modelled on {@code token-sheriff}'s
 * {@code SecurityEventCounter}. It feeds the metrics and error-mapping edges without
 * pulling in a broker, an observer bus, or a Micrometer dependency — keeping the
 * {@code events} package framework-agnostic (ADR-0005).
 * <p>
 * Increments use {@link ConcurrentHashMap#computeIfAbsent} plus
 * {@link AtomicLong#incrementAndGet}, so concurrent callers never block one another.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GatewayEventCounter {

    private final ConcurrentHashMap<EventType, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * Atomically increments the counter for the given event type.
     *
     * @param eventType the event to count
     */
    public void increment(EventType eventType) {
        counters.computeIfAbsent(eventType, key -> new AtomicLong()).incrementAndGet();
    }

    /**
     * @param eventType the event to query
     * @return the current count for {@code eventType}, or {@code 0} if never incremented
     */
    public long getCount(EventType eventType) {
        AtomicLong current = counters.get(eventType);
        return current == null ? 0L : current.get();
    }

    /**
     * @return an unmodifiable point-in-time snapshot of all non-zero counters
     */
    public Map<EventType, Long> getCounters() {
        Map<EventType, Long> snapshot = new EnumMap<>(EventType.class);
        counters.forEach((eventType, value) -> snapshot.put(eventType, value.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Resets every counter to zero. Intended for tests and controlled restarts.
     */
    public void reset() {
        counters.clear();
    }
}
