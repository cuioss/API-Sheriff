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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The bounded, single-node in-memory store — the only implementation for server mode.
 * <p>
 * Unauthenticated browsers create these records, so the store is bounded (an unbounded map would be
 * a denial-of-service amplifier) and the bound is enforced <em>fairly</em>: one requester's login
 * flood never displaces another requester's live login. {@link #store} admits a record in this order:
 * <ol>
 *   <li><strong>Expired first.</strong> Records expired at the given instant are purged, walking from
 *       the oldest, before any live record is considered.</li>
 *   <li><strong>Per-source cap.</strong> When the record's
 *       {@linkplain PendingAuthorizationRecord#quotaKey() quota key} already holds
 *       {@value #MAX_PENDING_PER_SOURCE} live records, that key's own oldest record is evicted and the
 *       new one inserted — no net growth, and no other source's record is touched.</li>
 *   <li><strong>Refuse at saturation.</strong> Otherwise, when the store holds its capacity of live
 *       records, the record is refused with
 *       {@link EventType#PENDING_AUTHORIZATION_SATURATED} and nothing stored is touched.</li>
 *   <li>Otherwise the record is inserted.</li>
 * </ol>
 * A refusal is logged as {@link BffLogMessages.WARN#PENDING_AUTHORIZATION_STORE_SATURATED} once per
 * saturation episode — the latch is set by the first refusal and released when the store next accepts
 * a net-new record — and every further refusal of the episode at {@code DEBUG}. Neither carries any
 * request data. TTL expiry is still enforced on {@link #consume} as well.
 * <p>
 * <strong>Thread safety.</strong> Every operation runs inside the one instance monitor. Each
 * check-then-act — the purge, the per-source count check, the capacity check, the own-oldest
 * eviction, the insert, the latch flip, and the per-source bookkeeping {@link #consume} updates — reads
 * and writes under that monitor, so no request can act on a count another request has already
 * changed. Callers must therefore not pre-check capacity outside {@link #store}: such a check would
 * reopen exactly the window the monitor closes.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class InMemoryPendingAuthorizationStore implements PendingAuthorizationStore {

    /**
     * The most live records one quota key may hold. A further record from that key evicts the key's
     * own oldest record. A documented constant, not a configuration key.
     */
    public static final int MAX_PENDING_PER_SOURCE = 256;

    private static final CuiLogger LOGGER = new CuiLogger(InMemoryPendingAuthorizationStore.class);

    private final int maxEntries;
    private final Map<String, PendingAuthorizationRecord> records = new LinkedHashMap<>();

    /**
     * The ids of each quota key's records, oldest first. An entry is dropped once its key holds no
     * record, so the map is bounded by the stored records.
     */
    private final Map<String, SequencedSet<String>> idsBySource = new HashMap<>();

    /** Whether the current saturation episode's WARN has been logged. */
    private boolean saturationLogged;

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
    public synchronized void store(PendingAuthorizationRecord pending, Instant now) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        SequencedSet<String> ownIds = idsBySource.get(pending.quotaKey());
        if (ownIds != null && ownIds.size() >= MAX_PENDING_PER_SOURCE) {
            records.remove(ownIds.removeFirst());
            insert(pending);
            return;
        }
        if (records.size() >= maxEntries) {
            refuseSaturated();
        }
        insert(pending);
        saturationLogged = false;
    }

    @Override
    public synchronized Optional<PendingAuthorizationRecord> consume(String recordId, Instant now) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(now, "now");
        PendingAuthorizationRecord pending = records.remove(recordId);
        if (pending == null) {
            return Optional.empty();
        }
        forgetId(pending);
        if (pending.isExpired(now)) {
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

    private void purgeExpired(Instant now) {
        Iterator<PendingAuthorizationRecord> oldestFirst = records.values().iterator();
        while (oldestFirst.hasNext()) {
            PendingAuthorizationRecord oldest = oldestFirst.next();
            if (!oldest.isExpired(now)) {
                return;
            }
            oldestFirst.remove();
            forgetId(oldest);
        }
    }

    private void insert(PendingAuthorizationRecord pending) {
        records.put(pending.id(), pending);
        idsBySource.computeIfAbsent(pending.quotaKey(), _ -> new LinkedHashSet<>()).add(pending.id());
    }

    private void forgetId(PendingAuthorizationRecord removed) {
        SequencedSet<String> ownIds = idsBySource.get(removed.quotaKey());
        if (ownIds != null) {
            ownIds.remove(removed.id());
            if (ownIds.isEmpty()) {
                idsBySource.remove(removed.quotaKey());
            }
        }
    }

    private void refuseSaturated() {
        if (saturationLogged) {
            LOGGER.debug("Pending-authorization store still saturated; new initiation refused");
        } else {
            saturationLogged = true;
            LOGGER.warn(BffLogMessages.WARN.PENDING_AUTHORIZATION_STORE_SATURATED);
        }
        throw new GatewayException(EventType.PENDING_AUTHORIZATION_SATURATED);
    }
}
