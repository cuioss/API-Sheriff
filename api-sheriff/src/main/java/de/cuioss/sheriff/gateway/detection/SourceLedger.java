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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;


import org.jspecify.annotations.Nullable;

/**
 * The per-source detection substrate: one leaky bucket per {@link SourceKey}, fed with weighted
 * events, with a small state machine on top and a bounded number of tracked sources.
 * <p>
 * <strong>The bucket.</strong> Every admitted event adds its weight to the source's level. The level
 * drains linearly at {@code threshold / window} per second, so a source that keeps exactly the
 * threshold's worth of weight per window holds a steady level just under it, and a full bucket empties
 * in one window. The drain is computed lazily from the injected monotonic clock whenever the bucket is
 * touched — no timer and no thread run. A leaky bucket approximates a sliding window; it is not one,
 * and the exact sliding window is deliberately not served.
 * <p>
 * <strong>The state machine.</strong>
 * <ul>
 *   <li>{@code CLEAR -> TRIPPED}: the admission whose weight brings the level to the threshold or above
 *       trips the source and hands one {@link SourceTrip} to the trip listener. A source trips at most
 *       once per {@code CLEAR} period: the trip re-arms only after the level drained back to
 *       zero;</li>
 *   <li>{@code TRIPPED -> BLOCKED}: under {@link Action#BLOCK} the source is blocked for the configured
 *       block duration whenever its level is at or above the threshold and it is not already blocked —
 *       on the trip itself, and again on a later admission if the level is still or again at the
 *       threshold after a block expired;</li>
 *   <li>{@code BLOCKED -> TRIPPED / CLEAR}: when the block expires the source is {@code TRIPPED} until
 *       its level has drained to zero, then {@code CLEAR}.</li>
 * </ul>
 * {@link Action#OBSERVE} and {@link Action#DELAY} never block; what a trip changes on the wire is the
 * caller's decision, made from the returned {@link SourceState}.
 * <p>
 * <strong>Tags.</strong> Every admission names a caller tag — a constant of a caller-supplied enum,
 * bounded by construction. Per source the ledger keeps the weight each tag contributed since the
 * bucket was last empty, and reports it in {@link SourceState} and {@link SourceTrip}.
 * <p>
 * <strong>Cardinality.</strong> At most {@link #MAX_TRACKED_SOURCES} keys are tracked. Admitting a new
 * key at the bound first sweeps the map, removing every entry whose level drained to zero and that is
 * not blocked — a sweep runs at most once per {@link #SWEEP_INTERVAL}, so a flood of new keys cannot
 * turn every admission into a full scan. When the map is still full the key is not tracked:
 * {@link #admit} returns an {@link SourceState.Status#UNTRACKED} state and the overflow is counted
 * ({@link #untrackedCount()}) for the caller to report. A tripped or blocked key is never evicted, and
 * neither is a key still holding weight, so a source accruing toward the threshold stays tracked however
 * full the map gets. The bound is soft: the size check and the insert are not one atomic step, so the
 * map can exceed the bound by at most the number of threads inserting new keys at the same moment.
 * <p>
 * <strong>Aggregation under pressure.</strong> From a fixed high-water mark of
 * {@value #HIGH_WATER_PERCENT}% of the bound on, a NEW IPv6 key is tracked under its {@code /48}
 * aggregate ({@link SourceKeyResolver#aggregateSource}) instead of its {@code /64}, so one site cannot
 * fill the ledger by rotating through the {@code /64}s of its allocation. Below the mark a new key keeps
 * its {@code /64}; a key already tracked keeps its key at any fill; an IPv4 or unknown source is never
 * aggregated. Every lookup — {@link #admit}, {@link #state} and {@link #hasRoomFor} — answers a
 * {@code /64} that is not tracked itself from its tracked {@code /48}, so a caller keeps asking with the
 * {@code /64} it resolved and finds the aggregated entry; the reported key ({@link SourceState#key()},
 * {@link SourceTrip#key()}) is then the {@code /48}. The mark is a constant, not configuration, and it
 * scales with the bound. The rule is the substrate's: every ledger built on this class follows it.
 * <p>
 * <strong>The room query.</strong> {@link #hasRoomFor} answers whether an admission would track a key
 * now, by the same lookup, sweep and size check {@link #admit} uses, without admitting anything. It
 * claims no slot: a caller that refuses on its answer decides on a moment that admissions on other
 * threads move on from, and {@link #admit} decides again when it runs.
 * <p>
 * <strong>Threading.</strong> Thread-safe. Every read-modify-write of one key — drain, add, trip,
 * block — runs inside one {@link ConcurrentHashMap#compute} call, so two concurrent admissions for the
 * same source cannot both perform the {@code CLEAR -> TRIPPED} transition, and exactly one trip is
 * emitted per {@code CLEAR} period. The trip listener is called by the admitting thread, after the
 * key's lock is released, and only by the admission that performed the transition; it must not block.
 * <p>
 * Single node and in memory: the state is not shared across gateway instances and does not survive a
 * restart.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SourceLedger {

    /** The upper bound on tracked keys per ledger instance; a fixed constant, no configuration reaches it. */
    public static final int MAX_TRACKED_SOURCES = 65_536;

    /** The least time between two sweeps of the map at the cardinality bound. */
    public static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);

    /**
     * The fill, in percent of the bound, from which a new IPv6 key is tracked under its {@code /48}
     * aggregate; a fixed constant, no configuration reaches it.
     */
    public static final int HIGH_WATER_PERCENT = 75;

    /**
     * The response a ledger is configured with. The ledger itself only distinguishes {@link #BLOCK},
     * which drives the {@code BLOCKED} state; the other two are carried into the {@link SourceTrip} for
     * the caller to act on.
     */
    public enum Action {

        /** A trip is recorded and changes nothing else. */
        OBSERVE,

        /** A trip is recorded; the caller holds the tripped source's answers. */
        DELAY,

        /** A trip is recorded and the source is blocked for the configured block duration. */
        BLOCK
    }

    /**
     * The tuning of one ledger instance.
     *
     * @param threshold     the level at which a source trips, at least {@code 1}
     * @param window        the time over which a full threshold drains, positive
     * @param action        the configured response to a trip
     * @param blockDuration how long a source stays blocked under {@link Action#BLOCK}, positive
     */
    public record Settings(int threshold, Duration window, Action action, Duration blockDuration) {

        /**
         * Canonical constructor validating the tuning.
         *
         * @throws NullPointerException     when {@code window}, {@code action} or {@code blockDuration}
         *                                  is {@code null}
         * @throws IllegalArgumentException when {@code threshold} is below {@code 1}, or
         *                                  {@code window} or {@code blockDuration} is not positive
         */
        public Settings {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(blockDuration, "blockDuration");
            if (threshold < 1) {
                throw new IllegalArgumentException("threshold must be at least 1 (was " + threshold + ")");
            }
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("window must be positive (was " + window + ")");
            }
            if (blockDuration.isNegative() || blockDuration.isZero()) {
                throw new IllegalArgumentException("blockDuration must be positive (was " + blockDuration + ")");
            }
        }
    }

    private final Settings settings;
    private final double drainPerNano;
    private final long blockNanos;
    private final int maxTrackedSources;
    private final int highWaterMark;
    private final LongSupplier nanoClock;
    private final Consumer<SourceTrip> tripListener;
    private final ConcurrentHashMap<SourceKey, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong untracked = new AtomicLong();
    private final AtomicLong nextSweepAtNanos;

    /**
     * @param settings     the ledger's tuning
     * @param nanoClock    a monotonic nanosecond clock, {@code System::nanoTime} in production
     * @param tripListener receives one {@link SourceTrip} per {@code CLEAR} period of a source; called
     *                     on the admitting thread and must not block
     */
    public SourceLedger(Settings settings, LongSupplier nanoClock, Consumer<SourceTrip> tripListener) {
        this(settings, nanoClock, tripListener, MAX_TRACKED_SOURCES);
    }

    SourceLedger(Settings settings, LongSupplier nanoClock, Consumer<SourceTrip> tripListener,
            int maxTrackedSources) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.tripListener = Objects.requireNonNull(tripListener, "tripListener");
        if (maxTrackedSources < 1) {
            throw new IllegalArgumentException("maxTrackedSources must be at least 1 (was " + maxTrackedSources + ")");
        }
        this.maxTrackedSources = maxTrackedSources;
        this.highWaterMark = Math.max(1, maxTrackedSources * HIGH_WATER_PERCENT / 100);
        this.drainPerNano = settings.threshold() / (double) settings.window().toNanos();
        this.blockNanos = settings.blockDuration().toNanos();
        this.nextSweepAtNanos = new AtomicLong(nanoClock.getAsLong());
    }

    /**
     * @return the tuning this ledger was built with
     */
    public Settings settings() {
        return settings;
    }

    /**
     * Admits one weighted event for a source and returns the source's state right after it.
     * <p>
     * The event is counted on the key the source is tracked under: the key itself, the tracked
     * {@code /48} aggregate of an IPv6 {@code /64} that is not tracked itself, or — for a new key — the
     * key as given, or its {@code /48} from the high-water mark on. When the admission trips the source,
     * the trip listener is called on this thread before the method returns.
     *
     * @param key    the source the event is attributed to
     * @param weight the event's weight, at least {@code 1}
     * @param tag    the caller's tag for the event, reported per tag in the state and the trip
     * @return the post-admission state, carrying the key the event was counted on;
     *         {@link SourceState.Status#UNTRACKED} when the key could not be tracked, in which case the
     *         event was not counted
     * @throws IllegalArgumentException when {@code weight} is below {@code 1}
     */
    public SourceState admit(SourceKey key, int weight, Enum<?> tag) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tag, "tag");
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be at least 1 (was " + weight + ")");
        }
        long now = nanoClock.getAsLong();
        SourceKey slot = slotFor(key, now);
        if (slot == null) {
            untracked.incrementAndGet();
            return SourceState.untracked(key, settings.threshold());
        }
        AdmitOutcome outcome = new AdmitOutcome();
        entries.compute(slot, (sourceKey, existing) -> {
            Entry entry = existing != null ? existing : new Entry(now);
            drain(entry, now);
            entry.level += weight;
            entry.contributions.merge(tag, (long) weight, Long::sum);
            boolean atThreshold = entry.level >= settings.threshold();
            if (atThreshold && !entry.tripped) {
                entry.tripped = true;
                outcome.trip = new SourceTrip(sourceKey, entry.level, settings.threshold(), settings.window(),
                        entry.contributions, settings.action(), now);
            }
            if (atThreshold && settings.action() == Action.BLOCK && !entry.blocked) {
                entry.blocked = true;
                entry.blockedUntilNanos = now + blockNanos;
            }
            outcome.state = snapshot(sourceKey, entry);
            return entry;
        });
        SourceTrip trip = outcome.trip;
        if (trip != null) {
            tripListener.accept(trip);
        }
        return Objects.requireNonNull(outcome.state, "state");
    }

    /**
     * Answers the current state of a source without admitting an event. The level is drained to the
     * current instant first. An IPv6 {@code /64} that is not tracked itself is answered from its tracked
     * {@code /48} aggregate, whose key the state then carries.
     *
     * @param key the source to query
     * @return the source's state, or empty when the source is not tracked — it never admitted an
     *         event, was swept after draining, or was refused at the cardinality bound
     */
    public Optional<SourceState> state(SourceKey key) {
        Objects.requireNonNull(key, "key");
        SourceKey tracked = trackedKeyOf(key, aggregateOf(key));
        if (tracked == null) {
            return Optional.empty();
        }
        long now = nanoClock.getAsLong();
        AdmitOutcome outcome = new AdmitOutcome();
        entries.computeIfPresent(tracked, (sourceKey, entry) -> {
            drain(entry, now);
            outcome.state = snapshot(sourceKey, entry);
            return entry;
        });
        return Optional.ofNullable(outcome.state);
    }

    /**
     * Answers whether an admission for {@code key} would track it now: {@code true} when the key — or
     * the {@code /48} aggregate of an IPv6 {@code /64} — is tracked, or when a new key fits, after the
     * rate-limited sweep {@link #admit} would run at the bound. Nothing is admitted, counted or
     * reserved, so the answer is a decision about this instant only; {@link #admit} decides again.
     *
     * @param key the source to query
     * @return {@code false} exactly when an admission now would return
     *         {@link SourceState.Status#UNTRACKED}
     */
    public boolean hasRoomFor(SourceKey key) {
        Objects.requireNonNull(key, "key");
        return slotFor(key, nanoClock.getAsLong()) != null;
    }

    /**
     * @param key the source to query
     * @return {@code true} while the source is blocked; always {@code false} unless the ledger is
     *         configured with {@link Action#BLOCK}
     */
    public boolean isBlocked(SourceKey key) {
        return state(key).map(SourceState::isBlocked).orElse(false);
    }

    /**
     * @return the number of keys currently tracked
     */
    public int trackedSources() {
        return entries.size();
    }

    /**
     * @return how many admissions were refused at the cardinality bound since the ledger was built
     */
    public long untrackedCount() {
        return untracked.get();
    }

    /**
     * The key an admission for {@code key} counts on now — the tracked key or aggregate, else the key a
     * new entry is created under — or {@code null} when a new entry does not fit even after the sweep.
     */
    private @Nullable SourceKey slotFor(SourceKey key, long now) {
        SourceKey aggregate = aggregateOf(key);
        SourceKey tracked = trackedKeyOf(key, aggregate);
        if (tracked != null) {
            return tracked;
        }
        if (entries.size() >= maxTrackedSources && !makeRoom(now)) {
            return null;
        }
        return aggregate != null && entries.size() >= highWaterMark ? aggregate : key;
    }

    /** The key itself when tracked, else its tracked {@code /48} aggregate, else {@code null}. */
    private @Nullable SourceKey trackedKeyOf(SourceKey key, @Nullable SourceKey aggregate) {
        if (entries.containsKey(key)) {
            return key;
        }
        return aggregate != null && entries.containsKey(aggregate) ? aggregate : null;
    }

    /** The {@code /48} aggregate of an IPv6 {@code /64} key on the same host, {@code null} for any other key. */
    private static @Nullable SourceKey aggregateOf(SourceKey key) {
        String aggregate = SourceKeyResolver.aggregateSource(key.source());
        return aggregate.equals(key.source()) ? null : new SourceKey(aggregate, key.host());
    }

    /**
     * Sweeps the map when the sweep interval allows it, and reports whether a new key fits afterwards.
     */
    private boolean makeRoom(long now) {
        long sweepAt = nextSweepAtNanos.get();
        if (now - sweepAt >= 0 && nextSweepAtNanos.compareAndSet(sweepAt, now + SWEEP_INTERVAL.toNanos())) {
            for (SourceKey key : entries.keySet()) {
                entries.computeIfPresent(key, (sourceKey, entry) -> {
                    drain(entry, now);
                    return entry.level <= 0.0 && !entry.blocked ? null : entry;
                });
            }
        }
        return entries.size() < maxTrackedSources;
    }

    /**
     * Brings an entry to {@code now}: drains the level, ends an expired block, and — once the level is
     * empty and no block holds — clears the per-tag contributions and re-arms the trip.
     */
    private void drain(Entry entry, long now) {
        long elapsed = now - entry.updatedAtNanos;
        if (elapsed > 0) {
            entry.level = Math.max(0.0, entry.level - elapsed * drainPerNano);
            entry.updatedAtNanos = now;
        }
        if (entry.blocked && now - entry.blockedUntilNanos >= 0) {
            entry.blocked = false;
            entry.blockedUntilNanos = 0L;
        }
        if (entry.level <= 0.0) {
            entry.contributions.clear();
            if (!entry.blocked) {
                entry.tripped = false;
            }
        }
    }

    private SourceState snapshot(SourceKey key, Entry entry) {
        SourceState.Status status;
        if (entry.blocked) {
            status = SourceState.Status.BLOCKED;
        } else if (entry.tripped) {
            status = SourceState.Status.TRIPPED;
        } else {
            status = SourceState.Status.CLEAR;
        }
        return new SourceState(key, status, entry.level, settings.threshold(),
                entry.blocked ? entry.blockedUntilNanos : 0L, entry.contributions);
    }

    /** One source's bucket; mutated only inside the map's per-key {@code compute} calls. */
    private static final class Entry {

        private double level;
        private long updatedAtNanos;
        private boolean tripped;
        private boolean blocked;
        private long blockedUntilNanos;
        private final Map<Enum<?>, Long> contributions = new HashMap<>();

        private Entry(long nowNanos) {
            this.updatedAtNanos = nowNanos;
        }
    }

    /** Carries what a {@code compute} lambda decided out of it. */
    private static final class AdmitOutcome {

        private @Nullable SourceTrip trip;
        private @Nullable SourceState state;
    }
}
