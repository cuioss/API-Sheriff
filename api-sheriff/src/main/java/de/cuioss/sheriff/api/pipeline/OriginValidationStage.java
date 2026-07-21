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
package de.cuioss.sheriff.api.pipeline;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.api.ApiSheriffLogMessages;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The WebSocket-upgrade Origin gate (GW-09, cross-site WebSocket hijacking).
 * <p>
 * On a {@code protocol: websocket} upgrade, the handshake {@code Origin} header is validated against
 * the route's effective, boot-materialized allowlist — exact-match, case-insensitive on host (the
 * allowlist is lower-cased once at route-table assembly, so the inbound {@code Origin} is compared
 * lower-cased here). The allowlist is <strong>fail-closed</strong>: an absent {@code Origin}, or one
 * that is not in the allowlist, rejects the upgrade with a {@link EventType#WEBSOCKET_ORIGIN_REJECTED}
 * {@link GatewayException} (rendered as HTTP {@code 403}) <em>before</em> the upstream is dialed —
 * there is no "any origin" default. A route with an empty allowlist declares no enforcement (only a
 * non-bearer route reaches boot with an empty allowlist; a bearer WebSocket route is fail-closed to a
 * non-empty allowlist at boot), so its upgrade proceeds without an Origin check.
 * <p>
 * Framework-agnostic: the stage consumes the {@link PipelineRequest} carrier and the resolved
 * allowlist, and carries no Vert.x / Quarkus types. Security-relevant rejections are logged with the
 * route id and the rejection disposition only — never the raw offending {@code Origin} value.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class OriginValidationStage {

    private static final CuiLogger LOGGER = new CuiLogger(OriginValidationStage.class);

    private static final String ORIGIN_HEADER = "Origin";
    private static final String ABSENT = "absent";
    private static final String FOREIGN = "foreign";

    /**
     * Validates the handshake {@code Origin} against the route's effective allowlist.
     *
     * @param request        the pipeline request carrier
     * @param routeId        the route id, for the security-relevant rejection log
     * @param allowedOrigins the route's effective, lower-cased exact-match Origin allowlist; empty
     *                       means no enforcement (a non-bearer route without an allowlist)
     * @throws GatewayException carrying {@link EventType#WEBSOCKET_ORIGIN_REJECTED} when the origin
     *                          is absent or not in a non-empty allowlist
     */
    public void validate(PipelineRequest request, String routeId, Set<String> allowedOrigins) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(allowedOrigins, "allowedOrigins");
        if (allowedOrigins.isEmpty()) {
            return;
        }
        Optional<String> origin = request.firstHeader(ORIGIN_HEADER);
        if (origin.isEmpty()) {
            throw rejected(routeId, ABSENT);
        }
        if (!allowedOrigins.contains(origin.get().toLowerCase(Locale.ROOT))) {
            throw rejected(routeId, FOREIGN);
        }
    }

    private static GatewayException rejected(String routeId, String disposition) {
        LOGGER.warn(ApiSheriffLogMessages.WARN.WEBSOCKET_ORIGIN_REJECTED, routeId, disposition);
        return new GatewayException(EventType.WEBSOCKET_ORIGIN_REJECTED,
                "WebSocket upgrade rejected on route '" + routeId + "': " + disposition + " origin");
    }
}
