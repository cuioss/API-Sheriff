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

import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The per-route {@code upstream} block.
 * <p>
 * The per-route {@code retry.enabled} / {@code not_modified.enabled} toggles are
 * optional overrides of the resolved endpoint/global {@code upstream_defaults};
 * an absent toggle inherits the resolved value. The effective values are
 * materialized once by the route-table builder.
 *
 * @param path             the upstream path that replaces the matched prefix,
 *                         empty when omitted
 * @param connectTimeoutMs the connect timeout in milliseconds, empty when omitted
 * @param readTimeoutMs    the read timeout in milliseconds, empty when omitted
 * @param retry            the retry settings, empty when omitted
 * @param notModified      the HTTP-304 not-modified settings, empty when omitted
 * @param circuitBreaker   the circuit-breaker settings, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record UpstreamConfig(Optional<String> path, Optional<Integer> connectTimeoutMs, Optional<Integer> readTimeoutMs,
Optional<Retry> retry, Optional<NotModified> notModified, Optional<CircuitBreaker> circuitBreaker) {

    /**
     * Canonical constructor normalizing absent components to {@link Optional#empty()}.
     */
    public UpstreamConfig {
        path = Objects.requireNonNullElse(path, Optional.empty());
        connectTimeoutMs = Objects.requireNonNullElse(connectTimeoutMs, Optional.empty());
        readTimeoutMs = Objects.requireNonNullElse(readTimeoutMs, Optional.empty());
        retry = Objects.requireNonNullElse(retry, Optional.empty());
        notModified = Objects.requireNonNullElse(notModified, Optional.empty());
        circuitBreaker = Objects.requireNonNullElse(circuitBreaker, Optional.empty());
    }

    /**
     * Per-route retry settings.
     *
     * @param enabled        whether retry is enabled for this route; empty inherits
     *                       the resolved endpoint/global default
     * @param maxAttempts    the maximum retry attempts, empty when omitted
     * @param idempotentOnly whether only idempotent methods are retried, empty when
     *                       omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Retry(Optional<Boolean> enabled, Optional<Integer> maxAttempts, Optional<Boolean> idempotentOnly) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public Retry {
            enabled = Objects.requireNonNullElse(enabled, Optional.empty());
            maxAttempts = Objects.requireNonNullElse(maxAttempts, Optional.empty());
            idempotentOnly = Objects.requireNonNullElse(idempotentOnly, Optional.empty());
        }
    }

    /**
     * Per-route HTTP-304 not-modified settings.
     *
     * @param enabled whether not-modified handling is enabled for this route; empty
     *                inherits the resolved endpoint/global default
     * @author API Sheriff Team
     * @since 1.0
     */
    public record NotModified(Optional<Boolean> enabled) {

        /**
         * Canonical constructor normalizing an absent {@code enabled} to
         * {@link Optional#empty()}.
         */
        public NotModified {
            enabled = Objects.requireNonNullElse(enabled, Optional.empty());
        }
    }

    /**
     * Per-route circuit-breaker settings.
     *
     * @param failures the failure threshold before opening, empty when omitted
     * @param resetMs  the reset window in milliseconds, empty when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record CircuitBreaker(Optional<Integer> failures, Optional<Integer> resetMs) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public CircuitBreaker {
            failures = Objects.requireNonNullElse(failures, Optional.empty());
            resetMs = Objects.requireNonNullElse(resetMs, Optional.empty());
        }
    }
}
