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

import java.util.Objects;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A single {@code routes[]} entry of an endpoint file.
 * <p>
 * An absent {@code auth} block inherits the endpoint's (or anchor's) {@code auth}
 * wholesale; a route-level block replaces it wholesale (no field merging). An
 * absent {@code protocol} means {@link Protocol#HTTP}. {@code anchor}, when
 * present, overrides the endpoint's default anchor membership for this route
 * (ADR-0007).
 *
 * @param id             the route id, unique across all endpoint files (mandatory)
 * @param protocol       the served protocol, {@code null} meaning HTTP
 * @param anchor         the per-route anchor override, {@code null} when the endpoint
 *                       anchor applies
 * @param match          the matcher set (mandatory)
 * @param auth           the route-level auth override, {@code null} when inheriting the
 *                       endpoint/anchor default
 * @param securityFilter the route-level security filter, {@code null} when the anchor or
 *                       global default applies
 * @param forward        the forward filter, {@code null} when the route declares no
 *                       {@code forward} block — which is the forward-all posture on
 *                       both dimensions, not a nothing-crosses one
 * @param upstream       the upstream target settings, {@code null} when omitted
 * @param asset          the asset terminal-action settings, {@code null} when omitted; a
 *                       route carries at most one terminal action, so {@code asset}
 *                       and {@code upstream} are mutually exclusive (ADR-0014)
 * @param rateLimit      the reserved rate-limit block, {@code null} when omitted
 * @param websocket      the per-route WebSocket settings ({@code allowed_origins},
 *                       {@code idle_timeout_seconds}), {@code null} for non-WebSocket routes
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record RouteConfig(
String id,
@Nullable Protocol protocol,
@Nullable String anchor,
MatchConfig match,
@Nullable AuthConfig auth,
@Nullable SecurityFilterConfig securityFilter,
@Nullable ForwardConfig forward,
@Nullable UpstreamConfig upstream,
@Nullable AssetConfig asset,
@Nullable RateLimitConfig rateLimit,
@Nullable WebSocketConfig websocket) {

    /**
     * Canonical constructor requiring {@code id} and {@code match}.
     */
    public RouteConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(match, "match");
    }
}
