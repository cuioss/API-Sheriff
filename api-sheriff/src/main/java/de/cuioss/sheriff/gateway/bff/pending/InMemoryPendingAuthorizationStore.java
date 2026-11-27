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
import java.util.function.Consumer;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The bounded, single-node in-memory store — the only implementation, used in both session modes:
 * a pending login is held server-side whether the session it completes into is held in the server
 * store or sealed into a cookie.
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
 *       new one inserted — no net growth, and no other source's record is touched. The eviction is not
 *       silent: see <em>Own-oldest eviction signal</em> below.</li>
 *   <li><strong>Refuse at saturation.</strong> Otherwise, when the store holds its capacity of live
 *       records, the record is refused with
 *       {@link EventType#PENDING_AUTHORIZATION_SATURATED} and nothing stored is touched.</li>
 *   <li>Otherwise the record is inserted. A record stored under an id the store already holds replaces
 *       the held record in every index.</li>
 * </ol>
 * A refusal is logged as {@link BffLogMessages.WARN#PENDING_AUTHORIZATION_STORE_SATURATED} once per
 * saturation episode and every further refusal of the episode at {@code DEBUG}; neither carries any
 * request data. The latch is set by the first refusal and released with hysteresis: only once the
 * store has drained below half its capacity (fewer live records than {@code maxEntries / 2}). Freeing
 * one place and filling it again therefore keeps the episode open, so a caller cannot turn every
 * refusal into a {@code WARN} line by completing one login between two floods. TTL expiry is still
 * enforced on {@link #consume} as well.
 * <p>
 * <strong>Own-oldest eviction signal.</strong> Every own-oldest eviction hands
 * {@link EventType#PENDING_AUTHORIZATION_OWN_OLDEST_EVICTED} to the event counter the store was built
 * with, so the eviction rate is countable, and is logged as
 * {@link BffLogMessages.WARN#PENDING_AUTHORIZATION_OWN_OLDEST_EVICTED} once per eviction episode and at
 * {@code DEBUG} for every further eviction of the episode. Neither the event nor either log line carries
 * any request data — no source address, quota key, record id, {@code state} or nonce. The episode is
 * store-wide and has its own hysteresis, mirroring the saturation latch: it starts with the first
 * own-oldest eviction and ends only once no quota key holds half its per-source share or more (every
 * key below {@code MAX_PENDING_PER_SOURCE / 2} live records). A key that is evicting is at its full
 * share, so one source cannot end the episode by letting a single record expire, and a source that keeps
 * flooding keeps the episode open. The saturation rule — drained below half the store's capacity — is not
 * reused for this latch: one source at its share of {@value #MAX_PENDING_PER_SOURCE} leaves a store of
 * the default capacity far below half full, so that rule would release the latch before every
 * eviction and let a single source set the volume of {@code WARN} lines.
 * <p>
 * <strong>Thread safety.</strong> Every operation runs inside the one instance monitor. Each
 * check-then-act — the purge, the per-source count check, the capacity check, the own-oldest
 * eviction, the insert, both latch flips, the count of keys holding half their share or more, and the
 * expiry-index and per-source bookkeeping {@link #consume} updates — reads and writes under that monitor,
 * so no request can act on a count another request has already changed. Callers must therefore not
 * pre-check capacity outside {@link #store}: such a check would reopen exactly the window the monitor
 * closes. The event counter is called under the monitor too, so it must not block and must not call back
 * into the store.
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

    /**
     * The live-record count from which a quota key counts as holding half its per-source share or more —
     * the eviction latch is released only once no key does.
     */
    private static final int HALF_SOURCE_SHARE = MAX_PENDING_PER_SOURCE / 2;

    /** Expiry order; the unique id breaks ties, so two records never compare equal. */
    private static final Comparator<PendingAuthorizationRecord> BY_EXPIRY = Comparator
            .comparing(PendingAuthorizationRecord::expiresAt).thenComparing(PendingAuthorizationRecord::id);

    private final int maxEntries;
    private final Consumer<EventType> eventCounter;
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
     * Whether the current own-oldest eviction episode's WARN has been logged. Set by the first own-oldest
     * eviction, cleared only once {@link #halfShareSources} is zero.
     */
    private boolean evictionLogged;

    /**
     * How many quota keys hold {@link #HALF_SOURCE_SHARE} live records or more. Kept up to date on every
     * insert and every removal, so the eviction latch's release point is read in constant time.
     */
    private int halfShareSources;

    /**
     * Creates a store bounded to {@code maxEntries} live records.
     *
     * @param maxEntries   the hard capacity (DoS guard); must be positive
     * @param eventCounter counts the store's own events — called with
     *                     {@link EventType#PENDING_AUTHORIZATION_OWN_OLDEST_EVICTED} on every own-oldest
     *                     eviction, under the store's monitor, so it must not block and must not call
     *                     back into the store
     * @throws IllegalArgumentException when {@code maxEntries} is not positive
     */
    public InMemoryPendingAuthorizationStore(int maxEntries, Consumer<EventType> eventCounter) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, but was " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.eventCounter = Objects.requireNonNull(eventCounter, "eventCounter");
    }

    @Override
    public synchronized void store(PendingAuthorizationRecord pending, Instant now) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        if (drainedBelowHalf()) {
            saturationLogged = false;
        }
        if (halfShareSources == 0) {
            evictionLogged = false;
        }
        SequencedSet<String> ownIds = idsBySource.get(pending.quotaKey());
        if (ownIds != null && ownIds.size() >= MAX_PENDING_PER_SOURCE) {
            remove(ownIds.getFirst());
            insert(pending);
            signalOwnOldestEviction();
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

    /**
     * Removes every record expired at {@code now}, earliest expiry first, wherever it was stored. Each
     * iteration takes its entry off the expiry index, so the loop always terminates.
     */
    private void purgeExpired(Instant now) {
        while (!byExpiry.isEmpty() && byExpiry.getFirst().isExpired(now)) {
            forget(byExpiry.removeFirst());
        }
    }

    /** Whether the store holds fewer live records than half its capacity — the latch's release point. */
    private boolean drainedBelowHalf() {
        return 2L * records.size() < maxEntries;
    }

    /**
     * Inserts {@code pending} into every index. A record already stored under the same id is removed
     * first, so the three indexes never hold an entry for a record {@link #records} no longer maps.
     */
    private void insert(PendingAuthorizationRecord pending) {
        remove(pending.id());
        records.put(pending.id(), pending);
        byExpiry.add(pending);
        SequencedSet<String> ownIds = idsBySource.computeIfAbsent(pending.quotaKey(), _ -> new LinkedHashSet<>());
        if (ownIds.add(pending.id()) && ownIds.size() == HALF_SOURCE_SHARE) {
            halfShareSources++;
        }
    }

    /**
     * Removes the record stored under {@code recordId} from every index.
     *
     * @return the removed record, or {@code null} when none is stored under that id
     */
    private @Nullable PendingAuthorizationRecord remove(String recordId) {
        PendingAuthorizationRecord stored = records.get(recordId);
        if (stored != null) {
            byExpiry.remove(stored);
            forget(stored);
        }
        return stored;
    }

    /**
     * Drops a record already taken off the expiry index from the id map and its source's ids — only
     * when it is still the record stored under its id, so a stale entry never removes a live record.
     */
    private void forget(PendingAuthorizationRecord removed) {
        if (!records.remove(removed.id(), removed)) {
            return;
        }
        SequencedSet<String> ownIds = idsBySource.get(removed.quotaKey());
        if (ownIds != null) {
            if (ownIds.remove(removed.id()) && ownIds.size() == HALF_SOURCE_SHARE - 1) {
                halfShareSources--;
            }
            if (ownIds.isEmpty()) {
                idsBySource.remove(removed.quotaKey());
            }
        }
    }

    /**
     * Counts one own-oldest eviction and logs it: {@code WARN} for the first eviction of an episode,
     * {@code DEBUG} for every further one. Carries no request data.
     */
    private void signalOwnOldestEviction() {
        eventCounter.accept(EventType.PENDING_AUTHORIZATION_OWN_OLDEST_EVICTED);
        if (evictionLogged) {
            LOGGER.debug("Pending-authorization source still at its per-source share; its oldest record was evicted");
        } else {
            evictionLogged = true;
            LOGGER.warn(BffLogMessages.WARN.PENDING_AUTHORIZATION_OWN_OLDEST_EVICTED);
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
