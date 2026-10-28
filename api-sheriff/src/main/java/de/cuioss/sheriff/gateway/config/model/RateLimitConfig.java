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
package de.cuioss.sheriff.gateway.config.model;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The per-route {@code rate_limit} block — planned, not implemented.
 * <p>
 * The block is parsed and bound into this record, and every route that declares it is reported at
 * boot with the WARN {@code ApiSheriff-140} ({@code LimitRules.validateRateLimitNotEnforced}). It is
 * never enforced: no component reads it at request time, so no rate limit is applied and the block
 * never causes a {@code 429}. A future implementation builds on the shipped per-source detection
 * substrate ({@code de.cuioss.sheriff.gateway.detection.SourceLedger}, ADR-0075) rather than on these
 * two fields as they stand; every {@code 429} the gateway sends today comes from that substrate
 * (the {@code block} action, and under {@code delay} a tripped source whose held-answer share is used up).
 *
 * @param requestsPerSecond the sustained request rate, {@code null} when omitted
 * @param burst             the burst allowance, {@code null} when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record RateLimitConfig(@Nullable Integer requestsPerSecond, @Nullable Integer burst) {
}
