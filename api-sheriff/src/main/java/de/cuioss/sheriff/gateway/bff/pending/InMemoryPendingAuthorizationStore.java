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
package de.cuioss.sheriff.gateway.bff.pending;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounded, single-node in-memory store — the only implementation for server mode.
 * <p>
 * Backed by an insertion-ordered map with a hard capacity: because unauthenticated browsers
 * create these records, an unbounded map would be a denial-of-service amplifier, so the
 * oldest record is evicted once the capacity is exceeded (a DoS guard, not a correctness
 * feature — a legitimately in-flight record is never evicted before the bound is reached).
 * TTL expiry is enforced lazily on {@link PendingAuthorizationStore#consume}. Every operation is guarded by the
 * instance monitor, so concurrent redirect/callback requests never corrupt the map.
 */
public final class InMemoryPendingAuthorizationStore implements PendingAuthorizationStore {

    private final int maxEntries;
    private final Map<String, PendingAuthorizationRecord> records = new LinkedHashMap<>();

    /**
     * Creates a store bounded to {@code maxEntries} live records.
     *
     * @param maxEntries the hard capacity (DoS guard); must be positive
     * @throws IllegalArgumentException when {@code maxEntries} is not positive
     */
    public InMemoryPendingAuthorizationStore(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, but was " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized void store(PendingAuthorizationRecord pending) {
        Objects.requireNonNull(pending, "pending");
        records.put(pending.id(), pending);
        evictOldestBeyondCapacity();
    }

    @Override
    public synchronized Optional<PendingAuthorizationRecord> consume(String recordId, Instant now) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(now, "now");
        PendingAuthorizationRecord pending = records.remove(recordId);
        if (pending == null || pending.isExpired(now)) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /**
     * @return the current number of stored records
     */
    public synchronized int size() {
        return records.size();
    }

    private void evictOldestBeyondCapacity() {
        Iterator<String> oldestFirst = records.keySet().iterator();
        while (records.size() > maxEntries && oldestFirst.hasNext()) {
            oldestFirst.next();
            oldestFirst.remove();
        }
    }
}
