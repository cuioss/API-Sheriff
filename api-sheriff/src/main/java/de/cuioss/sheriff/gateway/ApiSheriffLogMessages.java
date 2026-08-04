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
package de.cuioss.sheriff.gateway;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;

import lombok.experimental.UtilityClass;

/**
 * DSL-style {@link LogRecord} catalogue for the API Sheriff gateway request-pipeline edge.
 * <p>
 * Structured {@code INFO} (1-99) and {@code WARN} (100-199) messages carry the
 * {@code ApiSheriff} prefix and a stable numeric identifier, so they are greppable and
 * assertable. This catalogue's identifier ranges are disjoint from the two other catalogues that
 * share the same {@code ApiSheriff} prefix —
 * {@link de.cuioss.sheriff.gateway.config.ConfigLogMessages} (the boot-time configuration
 * subsystem) and {@link de.cuioss.sheriff.gateway.bff.BffLogMessages} (the {@code require: session}
 * BFF surface). This catalogue owns {@code 1} / {@code 4} / {@code 6-7} (INFO) and {@code 100} /
 * {@code 103-109} / {@code 117} (WARN); config owns {@code 2-3} / {@code 101-102} /
 * {@code 115-116} / {@code 200-201}; BFF owns {@code 10-16} / {@code 110-114}. Never renumber one
 * catalogue without checking the others for a collision.
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

        /** A WebSocket upgrade was accepted and the opaque bidirectional relay was established. */
        public static final LogRecord WEBSOCKET_RELAY_ESTABLISHED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(4)
                .template("WebSocket relay established for route '%s'")
                .build();

        /** An opaque L4 passthrough relay to the resolved backend was established for a mapped SNI. */
        public static final LogRecord PASSTHROUGH_RELAY_ESTABLISHED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(6)
                .template("Passthrough relay established for SNI '%s' to upstream '%s'")
                .build();

        /** The accept-time SNI front listener bound the public TLS port at boot. */
        public static final LogRecord SNI_FRONT_LISTENER_STARTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(7)
                .template("Accept-time SNI front listener started on port %s (%s passthrough SNI mapping(s))")
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

        /**
         * A WebSocket upgrade was rejected by the Origin allowlist (GW-09 / CSWSH). Records the
         * route id and the rejection disposition ({@code absent} or {@code foreign}) only — the raw
         * offending {@code Origin} value is never logged.
         */
        public static final LogRecord WEBSOCKET_ORIGIN_REJECTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(105)
                .template("WebSocket upgrade rejected on route '%s': %s origin")
                .build();

        /** An established WebSocket relay was reclaimed after exceeding its idle timeout. */
        public static final LogRecord WEBSOCKET_IDLE_RECLAIM = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(106)
                .template("WebSocket relay on route '%s' reclaimed after idle timeout of %s seconds")
                .build();

        /**
         * A TLS ClientHello was failed closed to the terminated-strict path (GW-06): it carried no
         * usable SNI, was malformed, or exceeded the reassembly bound. Records the disposition only —
         * never the raw ClientHello bytes.
         */
        public static final LogRecord CLIENT_HELLO_FAIL_CLOSED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(107)
                .template("TLS ClientHello failed closed to terminated path: %s")
                .build();

        /**
         * A terminated request's {@code Host} header named a reserved passthrough SNI (Host-vs-SNI
         * smuggle), rejected 404 before route selection. Records a fixed disposition only — never the
         * raw {@code Host} value.
         */
        public static final LogRecord PASSTHROUGH_HOST_SMUGGLED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(108)
                .template("Host-vs-SNI smuggle rejected before route selection: %s")
                .build();

        /**
         * A gateway-terminated reserved POST path exceeded the edge's reserved-body byte ceiling and
         * was rejected {@code 413}. Records the ceiling and a fixed disposition
         * ({@code declared-content-length} or {@code streamed-body}) only — never the offending body,
         * which is precisely the unauthenticated payload this bound exists to refuse.
         */
        public static final LogRecord RESERVED_BODY_TOO_LARGE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(109)
                .template("Reserved-path request body exceeded the %s byte ceiling (%s) — rejected 413")
                .build();

        /**
         * The directory asset source could not obtain a {@code SecureDirectoryStream} on its root,
         * so it serves assets through the resolved path rather than by descending from the root one
         * confined component at a time. Emitted once per source: it reports a fixed property of the
         * platform, not a per-request event.
         * <p>
         * The consequence is concrete and is stated rather than glossed: the descriptor-relative
         * walk closes the check-then-act window on every path component, the fallback closes it on
         * the final component only, so an ancestor directory of the asset can still be replaced by a
         * symlink after the in-root check and be traversed by the read. The template names the
         * configured asset root — operator-supplied configuration, never request content.
         */
        public static final LogRecord ASSET_CONFINED_WALK_UNAVAILABLE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(117)
                .template("Asset root '%s' offers no SecureDirectoryStream on this platform — falling back to the resolved-path read, which leaves an ancestor-directory symlink swap after the in-root check unrefused")
                .build();
    }
}
