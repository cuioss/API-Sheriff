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
import java.util.Map;
import java.util.Objects;

/**
 * The raw record a {@link SourceLedger} hands its trip listener when a source reaches the threshold:
 * the structured shape a consumer formats from.
 * <p>
 * It is emitted exactly once per {@code CLEAR} period of a source, by the admission that performed the
 * transition. It carries no rendering: no log template, no ECS or OCSF field names and no wire format
 * — a consumer formats it into whatever its own surface needs.
 *
 * @param key            the source that tripped
 * @param levelAtTrip    the bucket level right after the admission that reached the threshold
 * @param threshold      the threshold of the ledger that tripped
 * @param window         the window over which that ledger drains a full threshold
 * @param contributions  the weight each caller tag contributed since the bucket was last empty,
 *                       unmodifiable
 * @param action         the response the ledger was configured with when the source tripped
 * @param trippedAtNanos the reading of the ledger's monotonic clock at the trip
 * @author API Sheriff Team
 * @since 1.0
 */
public record SourceTrip(SourceKey key, double levelAtTrip, int threshold, Duration window,
Map<Enum<?>, Long> contributions, SourceLedger.Action action, long trippedAtNanos) {

    /**
     * Canonical constructor rejecting an absent component and copying the contributions.
     *
     * @throws NullPointerException when {@code key}, {@code window}, {@code contributions} or
     *                              {@code action} is {@code null}
     */
    public SourceTrip {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(action, "action");
        contributions = Map.copyOf(Objects.requireNonNull(contributions, "contributions"));
    }
}
