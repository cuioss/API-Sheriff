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
package de.cuioss.sheriff.api.config.model;

import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The reserved per-route {@code rate_limit} block.
 * <p>
 * The rate-limiting feature is deferred; the block is accepted and ignored so
 * the schema need not change when the feature ships.
 *
 * @param requestsPerSecond the sustained request rate, empty when omitted
 * @param burst             the burst allowance, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record RateLimitConfig(Optional<Integer> requestsPerSecond, Optional<Integer> burst) {

    /**
     * Canonical constructor normalizing absent components to {@link Optional#empty()}.
     */
    public RateLimitConfig {
        requestsPerSecond = Objects.requireNonNullElse(requestsPerSecond, Optional.empty());
        burst = Objects.requireNonNullElse(burst, Optional.empty());
    }
}
