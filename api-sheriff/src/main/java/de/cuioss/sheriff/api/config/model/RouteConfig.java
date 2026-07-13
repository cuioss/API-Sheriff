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
 * An absent {@code auth} block inherits the endpoint's {@code auth} wholesale; a
 * route-level block replaces it wholesale (no field merging). An absent
 * {@code protocol} means {@link Protocol#HTTP}.
 *
 * @param id             the route id, unique across all endpoint files (mandatory)
 * @param protocol       the served protocol, empty meaning HTTP
 * @param match          the matcher set (mandatory)
 * @param auth           the route-level auth override, empty when inheriting the
 *                       endpoint default
 * @param securityFilter the route-level security filter, empty when the global
 *                       default applies
 * @param forward        the forwarding allowlist, empty when nothing is forwarded
 * @param upstream       the upstream target settings, empty when omitted
 * @param rateLimit      the reserved rate-limit block, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record RouteConfig(String id, Optional<Protocol> protocol, MatchConfig match, Optional<AuthConfig> auth,
                          Optional<SecurityFilterConfig> securityFilter, Optional<ForwardConfig> forward, Optional<UpstreamConfig> upstream,
                          Optional<RateLimitConfig> rateLimit) {

    /**
     * Canonical constructor requiring {@code id} and {@code match} and normalizing
     * absent optionals to {@link Optional#empty()}.
     */
    public RouteConfig {
        id = Objects.requireNonNull(id, "id");
        match = Objects.requireNonNull(match, "match");
        protocol = protocol == null ? Optional.empty() : protocol;
        auth = auth == null ? Optional.empty() : auth;
        securityFilter = securityFilter == null ? Optional.empty() : securityFilter;
        forward = forward == null ? Optional.empty() : forward;
        upstream = upstream == null ? Optional.empty() : upstream;
        rateLimit = rateLimit == null ? Optional.empty() : rateLimit;
    }
}
