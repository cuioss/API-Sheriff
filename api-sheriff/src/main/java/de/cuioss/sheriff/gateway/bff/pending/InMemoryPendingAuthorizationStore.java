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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.TreeSet;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The bounded, single-node in-memory store — the only implementation for server mode.
 * <p>
 * Unauthenticated browsers create these records, so the store is bounded (an unbounded map would be
 * a denial-of-service amplifier) and the bound is enforced <em>fairly</em>: one requester's login
 * flood never displaces another requester's live login. {@link #store} admits a record in this order:
 * <ol>
 *   <li><strong>Expired first.</strong> Every record expired at the given instant is purged before any
 *       live record is considered. The purge reads the records in
 *       {@linkplain PendingAuthorizationRecord#expiresAt() expiry} order, not in the order they were
 *       stored: a caller stamps {@code createdAt} before its authorization push and stores the record
 *       after it, so a record stored later can expire earlier. Each purged record costs one removal from
 *       a sorted index, and a store call that finds nothing expired reads one entry.</li>
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
 * saturation episode and every further refusal of the episode at {@code DEBUG}; neither carries any
 * request data. The latch is set by the first refusal and released with hysteresis: only once the
 * store has drained below half its capacity (fewer live records than {@code maxEntries / 2}). Freeing
 * one place and filling it again therefore keeps the episode open, so a caller cannot turn every
 * refusal into a {@code WARN} line by completing one login between two floods. TTL expiry is still
 * enforced on {@link #consume} as well.
 * <p>
 * <strong>Thread safety.</strong> Every operation runs inside the one instance monitor. Each
 * check-then-act — the purge, the per-source count check, the capacity check, the own-oldest
 * eviction, the insert, the latch flips, and the expiry-index and per-source bookkeeping
 * {@link #consume} updates — reads and writes under that monitor, so no request can act on a count
 * another request has already changed. Callers must therefore not pre-check capacity outside
 * {@link #store}: such a check would reopen exactly the window the monitor closes.
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

    /** Expiry order; the unique id breaks ties, so two records never compare equal. */
    private static final Comparator<PendingAuthorizationRecord> BY_EXPIRY = Comparator
            .comparing(PendingAuthorizationRecord::expiresAt).thenComparing(PendingAuthorizationRecord::id);

    private final int maxEntries;
    private final Map<String, PendingAuthorizationRecord> records = new HashMap<>();

    /** The stored records, earliest expiry first — exactly the records {@link #records} holds. */
    private final NavigableSet<PendingAuthorizationRecord> byExpiry = new TreeSet<>(BY_EXPIRY);

    /**
     * The ids of each quota key's records, oldest first. An entry is dropped once its key holds no
     * record, so the map is bounded by the stored records.
     */
    private final Map<String, SequencedSet<String>> idsBySource = new HashMap<>();

    /**
     * Whether the current saturation episode's WARN has been logged. Set by the first refusal, cleared
     * only once the store has drained below half its capacity.
     */
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
        if (drainedBelowHalf()) {
            saturationLogged = false;
        }
        SequencedSet<String> ownIds = idsBySource.get(pending.quotaKey());
        if (ownIds != null && ownIds.size() >= MAX_PENDING_PER_SOURCE) {
            remove(ownIds.getFirst());
            insert(pending);
            return;
        }
        if (records.size() >= maxEntries) {
            refuseSaturated();
        }
        insert(pending);
    }

    @Override
    public synchronized Optional<PendingAuthorizationRecord> consume(String recordId, Instant now) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(now, "now");
        PendingAuthorizationRecord pending = remove(recordId);
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

    /** Removes every record expired at {@code now}, earliest expiry first, wherever it was stored. */
    private void purgeExpired(Instant now) {
        while (!byExpiry.isEmpty() && byExpiry.getFirst().isExpired(now)) {
            PendingAuthorizationRecord expired = byExpiry.removeFirst();
            records.remove(expired.id());
            forgetId(expired);
        }
    }

    /** Whether the store holds fewer live records than half its capacity — the latch's release point. */
    private boolean drainedBelowHalf() {
        return 2L * records.size() < maxEntries;
    }

    private void insert(PendingAuthorizationRecord pending) {
        records.put(pending.id(), pending);
        byExpiry.add(pending);
        idsBySource.computeIfAbsent(pending.quotaKey(), _ -> new LinkedHashSet<>()).add(pending.id());
    }

    /**
     * Removes the record stored under {@code recordId} from every index.
     *
     * @return the removed record, or {@code null} when none is stored under that id
     */
    private @Nullable PendingAuthorizationRecord remove(String recordId) {
        PendingAuthorizationRecord removed = records.remove(recordId);
        if (removed != null) {
            byExpiry.remove(removed);
            forgetId(removed);
        }
        return removed;
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
