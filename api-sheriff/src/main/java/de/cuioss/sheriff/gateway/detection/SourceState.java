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

import java.util.Map;
import java.util.Objects;

/**
 * A point-in-time snapshot of one source's bucket, as the {@link SourceLedger} query API and
 * {@link SourceLedger#admit admission} answer it.
 * <p>
 * The snapshot is taken under the ledger's per-key lock and never changes afterwards; the ledger's
 * own state moves on as further events are admitted and the level drains.
 *
 * @param key               the source the snapshot describes
 * @param status            the state machine's state for the source at the snapshot instant
 * @param level             the bucket level at the snapshot instant, after the lazy drain; {@code 0}
 *                          for an {@link Status#UNTRACKED} source
 * @param threshold         the level at which the source trips
 * @param blockedUntilNanos the monotonic clock reading at which the block ends; meaningful only while
 *                          {@code status} is {@link Status#BLOCKED} and {@code 0} otherwise. A clock
 *                          reading of {@code 0} is a legal instant, so read {@code status}, never this
 *                          value, to learn whether the source is blocked
 * @param contributions     the weight each caller tag contributed since the bucket was last empty,
 *                          unmodifiable; empty for an {@link Status#UNTRACKED} source
 * @author API Sheriff Team
 * @since 1.0
 */
public record SourceState(SourceKey key, Status status, double level, int threshold, long blockedUntilNanos,
        Map<Enum<?>, Long> contributions) {

    /**
     * The states of the per-source state machine; see {@link SourceLedger} for the transitions.
     */
    public enum Status {

        /** The level is below the threshold and has not reached it since the bucket was last empty. */
        CLEAR,

        /**
         * The level reached the threshold in the current period and the trip was emitted; the source
         * returns to {@link #CLEAR} once the level has drained back to zero.
         */
        TRIPPED,

        /**
         * The source tripped under the {@code block} action and its block has not expired; it returns
         * to {@link #TRIPPED} or {@link #CLEAR} when the block expires.
         */
        BLOCKED,

        /**
         * The ledger was at its cardinality bound and could not make room, so the source is not
         * tracked: the event was not counted and the source can neither trip nor be blocked.
         */
        UNTRACKED
    }

    /**
     * Canonical constructor rejecting an absent component and copying the contributions.
     *
     * @throws NullPointerException when {@code key}, {@code status} or {@code contributions} is
     *                              {@code null}
     */
    public SourceState {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        contributions = Map.copyOf(Objects.requireNonNull(contributions, "contributions"));
    }

    /**
     * The snapshot of a source the ledger could not track.
     *
     * @param key       the untracked source
     * @param threshold the ledger's threshold
     * @return an {@link Status#UNTRACKED} snapshot with level {@code 0} and no contributions
     */
    public static SourceState untracked(SourceKey key, int threshold) {
        return new SourceState(key, Status.UNTRACKED, 0.0, threshold, 0L, Map.of());
    }

    /**
     * @return {@code true} while the source is {@link Status#BLOCKED}
     */
    public boolean isBlocked() {
        return status == Status.BLOCKED;
    }

    /**
     * @return {@code true} when the source has tripped in its current period —
     *         {@link Status#TRIPPED} or {@link Status#BLOCKED}
     */
    public boolean hasTripped() {
        return status == Status.TRIPPED || status == Status.BLOCKED;
    }

    /**
     * The time left until the block ends, measured against a reading of the same monotonic clock the
     * ledger was built with.
     *
     * @param nowNanos a reading of the ledger's clock
     * @return the nanoseconds until the block ends, never negative; {@code 0} when the source is not
     *         blocked
     */
    public long remainingBlockNanos(long nowNanos) {
        return isBlocked() ? Math.max(0L, blockedUntilNanos - nowNanos) : 0L;
    }
}
