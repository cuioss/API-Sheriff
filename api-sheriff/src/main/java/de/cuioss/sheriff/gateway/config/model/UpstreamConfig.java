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
package de.cuioss.sheriff.gateway.config.model;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The per-route {@code upstream} block.
 * <p>
 * The per-route {@code retry.enabled} / {@code not_modified.enabled} toggles are
 * optional overrides of the resolved endpoint/global {@code upstream_defaults};
 * an absent toggle inherits the resolved value. The effective values are
 * materialized once by the route-table builder.
 *
 * @param path             the upstream path that replaces the matched prefix,
 *                         {@code null} when omitted
 * @param connectTimeoutMs the connect timeout in milliseconds, {@code null} when omitted
 * @param readTimeoutMs    the read timeout in milliseconds, {@code null} when omitted
 * @param retry            the retry settings, {@code null} when omitted
 * @param notModified      the HTTP-304 not-modified settings, {@code null} when omitted
 * @param circuitBreaker   the circuit-breaker settings, {@code null} when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record UpstreamConfig(@Nullable
        String path, @Nullable
        Integer connectTimeoutMs,
@Nullable
Integer readTimeoutMs,
@Nullable
Retry retry, @Nullable
        NotModified notModified, @Nullable
        CircuitBreaker circuitBreaker) {

    /**
     * Per-route retry settings.
     *
     * @param enabled        whether retry is enabled for this route; {@code null} inherits
     *                       the resolved endpoint/global default
     * @param maxAttempts    the maximum retry attempts, {@code null} when omitted
     * @param idempotentOnly whether only idempotent methods are retried, {@code null} when
     *                       omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Retry(@Nullable
            Boolean enabled, @Nullable
            Integer maxAttempts,
    @Nullable
    Boolean idempotentOnly) {
    }

    /**
     * Per-route HTTP-304 not-modified settings.
     *
     * @param enabled whether not-modified handling is enabled for this route;
     *                {@code null} inherits the resolved endpoint/global default
     * @author API Sheriff Team
     * @since 1.0
     */
    public record NotModified(@Nullable
    Boolean enabled) {
    }

    /**
     * Per-route circuit-breaker settings.
     *
     * @param failures the failure threshold before opening, {@code null} when omitted
     * @param resetMs  the reset window in milliseconds, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record CircuitBreaker(@Nullable
    Integer failures, @Nullable
    Integer resetMs) {
    }
}
