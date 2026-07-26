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
package de.cuioss.sheriff.gateway.bff.pending;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The storage seam for {@link PendingAuthorizationRecord}s (D2b).
 * <p>
 * The seam is deliberately pluggable: server mode stores the record here and carries only the
 * record id in the browser-binding cookie ({@link #InMemory}), whereas the cookie variant
 * (PLAN-08) can supply an implementation where the sealed record travels in the cookie itself
 * and no server map exists. The two operations are the whole contract — persist a record, and
 * consume it exactly once.
 * <p>
 * <strong>Single-use is a store invariant, not a caller contract.</strong> {@link #consume}
 * removes the record as it returns it, so a second consumption of the same id resolves to
 * empty even if the record has not expired — this is the enforcement the engine's
 * {@link de.cuioss.sheriff.token.client.flow.FlowContext} deliberately leaves open.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface PendingAuthorizationStore {

    /**
     * Persists a pending-authorization record under its {@link PendingAuthorizationRecord#id()}.
     *
     * @param pending the record to store
     */
    void store(PendingAuthorizationRecord pending);

    /**
     * Atomically resolves and removes the record for {@code recordId} (single-use). Returns
     * empty when no record is stored under that id, when it has already been consumed, or when
     * it has expired at {@code now}.
     *
     * @param recordId the record id (from the browser-binding cookie)
     * @param now      the reference instant for the TTL check
     * @return the live record, removed from the store; empty when absent/consumed/expired
     */
    Optional<PendingAuthorizationRecord> consume(String recordId, Instant now);

    /**
     * The bounded, single-node in-memory store — the only implementation for server mode.
     * <p>
     * Backed by an insertion-ordered map with a hard capacity: because unauthenticated browsers
     * create these records, an unbounded map would be a denial-of-service amplifier, so the
     * oldest record is evicted once the capacity is exceeded (a DoS guard, not a correctness
     * feature — a legitimately in-flight record is never evicted before the bound is reached).
     * TTL expiry is enforced lazily on {@link #consume}. Every operation is guarded by the
     * instance monitor, so concurrent redirect/callback requests never corrupt the map.
     */
    final class InMemory implements PendingAuthorizationStore {

        private final int maxEntries;
        private final Map<String, PendingAuthorizationRecord> records = new LinkedHashMap<>();

        /**
         * Creates a store bounded to {@code maxEntries} live records.
         *
         * @param maxEntries the hard capacity (DoS guard); must be positive
         * @throws IllegalArgumentException when {@code maxEntries} is not positive
         */
        public InMemory(int maxEntries) {
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
}
