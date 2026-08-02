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

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import lombok.Builder;

/**
 * One {@code endpoints/*.yaml} file's {@code endpoint} block.
 * <p>
 * {@code id} is mandatory and unique across all endpoint files (a duplicate
 * fails the boot). {@code enabled} defaults to {@code true}; a disabled endpoint
 * is inert (its routes are not merged and its alias need not resolve).
 * {@code anchor}, when present, is the default anchor membership for this
 * endpoint's routes (ADR-0007); a route may override it. {@code auth} is the
 * default auth posture for the routes; it is optional — an anchored endpoint whose
 * anchor declares {@code auth} may omit it. The conditional mandatoriness (every
 * route resolves to an unambiguous effective auth) is enforced by the configuration
 * validator, not the record. {@code allowedMethods}, when non-empty,
 * <em>replaces</em> the global {@code gateway.allowed_methods} (and any anchor
 * allowlist) wholesale for this endpoint (no inheritance); an empty list means the
 * global/anchor list applies. The endpoint-level {@code upstreamDefaults}, when
 * present, replaces the global block wholesale for this endpoint's routes.
 *
 * @param id               the unique endpoint id (mandatory)
 * @param enabled          whether the endpoint is active
 * @param baseUrl          the topology alias (mandatory)
 * @param anchor           the default anchor membership, {@code null} when the endpoint
 *                         declares none
 * @param auth             the default auth posture for the routes, {@code null} when the
 *                         endpoint relies on an anchor- or route-provided posture
 * @param allowedMethods   the per-endpoint verb allowlist, empty meaning the
 *                         global/anchor list applies
 * @param upstreamDefaults the endpoint-level retry/not-modified defaults, {@code null}
 *                         when the global block applies
 * @param routes           the routes declared by this endpoint, empty when none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record EndpointConfig(String id, boolean enabled, String baseUrl, @Nullable String anchor,
@Nullable AuthConfig auth, List<HttpMethod> allowedMethods, @Nullable UpstreamDefaultsConfig upstreamDefaults,
List<RouteConfig> routes) {

    /**
     * Canonical constructor requiring the mandatory fields and defensively copying
     * the collections.
     */
    public EndpointConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUrl, "baseUrl");
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
