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
package de.cuioss.sheriff.api;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;

import lombok.experimental.UtilityClass;

/**
 * DSL-style {@link LogRecord} catalogue for the API Sheriff gateway request-pipeline edge.
 * <p>
 * Structured {@code INFO} (1-99) and {@code WARN} (100-199) messages carry the
 * {@code ApiSheriff} prefix and a stable numeric identifier, so they are greppable and
 * assertable. This catalogue's identifier ranges are disjoint from
 * {@link de.cuioss.sheriff.api.config.ConfigLogMessages}'s (the boot-time configuration
 * subsystem catalogue), which shares the same {@code ApiSheriff} prefix: {@code 1-2} /
 * {@code 100} / {@code 103-104} here vs {@code 2-3} / {@code 101-102} / {@code 200-201}
 * there — never renumber one catalogue without checking the other for a collision.
 * Security-relevant {@code WARN}s record only the failure <em>type</em> and route id —
 * never the raw offending payload. {@code DEBUG} / {@code TRACE} diagnostics use the logger
 * directly and are not catalogued here.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class ApiSheriffLogMessages {

    private static final String PREFIX = "ApiSheriff";

    /**
     * Info-level messages (INFO range 1-99).
     */
    @UtilityClass
    public static final class INFO {

        /** The route table was compiled into the immutable per-route runtime at boot. */
        public static final LogRecord ROUTE_TABLE_COMPILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(1)
                .template("Route table compiled: %s route runtime(s) assembled")
                .build();
    }

    /**
     * Warn-level messages (WARN range 100-199).
     */
    @UtilityClass
    public static final class WARN {

        /**
         * A cui-http security filter rejected a request. Records the route id and the
         * failure type only — the raw offending payload is never logged.
         */
        public static final LogRecord SECURITY_FILTER_VIOLATION = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(100)
                .template("Security filter rejected a request on route '%s': failure type %s")
                .build();

        /** The circuit breaker opened for an upstream after consecutive failures. */
        public static final LogRecord CIRCUIT_BREAKER_OPEN = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(103)
                .template("Circuit breaker opened for upstream '%s'")
                .build();

        /** The circuit breaker closed again for an upstream after recovery. */
        public static final LogRecord CIRCUIT_BREAKER_CLOSED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(104)
                .template("Circuit breaker closed for upstream '%s'")
                .build();
    }
}
