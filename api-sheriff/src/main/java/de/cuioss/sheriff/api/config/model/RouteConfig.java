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
 * A single {@code routes[]} entry of an endpoint file.
 * <p>
 * An absent {@code auth} block inherits the endpoint's (or anchor's) {@code auth}
 * wholesale; a route-level block replaces it wholesale (no field merging). An
 * absent {@code protocol} means {@link Protocol#HTTP}. {@code anchor}, when
 * present, overrides the endpoint's default anchor membership for this route
 * (ADR-0007).
 *
 * @param id             the route id, unique across all endpoint files (mandatory)
 * @param protocol       the served protocol, empty meaning HTTP
 * @param anchor         the per-route anchor override, empty when the endpoint
 *                       anchor applies
 * @param match          the matcher set (mandatory)
 * @param auth           the route-level auth override, empty when inheriting the
 *                       endpoint/anchor default
 * @param securityFilter the route-level security filter, empty when the anchor or
 *                       global default applies
 * @param forward        the forwarding allowlist, empty when nothing is forwarded
 * @param upstream       the upstream target settings, empty when omitted
 * @param asset          the asset terminal-action settings, empty when omitted; a
 *                       route carries at most one terminal action, so {@code asset}
 *                       and {@code upstream} are mutually exclusive (ADR-0014)
 * @param rateLimit      the reserved rate-limit block, empty when omitted
 * @param websocket      the per-route WebSocket settings ({@code allowed_origins},
 *                       {@code idle_timeout_seconds}), empty for non-WebSocket routes
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record RouteConfig(String id, Optional<Protocol> protocol, Optional<String> anchor, MatchConfig match,
Optional<AuthConfig> auth, Optional<SecurityFilterConfig> securityFilter, Optional<ForwardConfig> forward,
Optional<UpstreamConfig> upstream, Optional<AssetConfig> asset, Optional<RateLimitConfig> rateLimit,
Optional<WebSocketConfig> websocket) {

    /**
     * Canonical constructor requiring {@code id} and {@code match} and normalizing
     * absent optionals to {@link Optional#empty()}.
     */
    public RouteConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(match, "match");
        protocol = Objects.requireNonNullElse(protocol, Optional.empty());
        anchor = Objects.requireNonNullElse(anchor, Optional.empty());
        auth = Objects.requireNonNullElse(auth, Optional.empty());
        securityFilter = Objects.requireNonNullElse(securityFilter, Optional.empty());
        forward = Objects.requireNonNullElse(forward, Optional.empty());
        upstream = Objects.requireNonNullElse(upstream, Optional.empty());
        asset = Objects.requireNonNullElse(asset, Optional.empty());
        rateLimit = Objects.requireNonNullElse(rateLimit, Optional.empty());
        websocket = Objects.requireNonNullElse(websocket, Optional.empty());
    }
}
