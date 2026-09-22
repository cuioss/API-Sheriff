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

import java.util.List;
import java.util.Objects;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * One {@code endpoints/*.yaml} file's {@code endpoint} block.
 * <p>
 * {@code id} is mandatory and unique across all endpoint files (a duplicate
 * fails the boot). {@code enabled} defaults to {@code true}; a disabled endpoint
 * is inert (its routes are not merged and its alias need not resolve).
 * {@code baseUrl} is conditionally mandatory: an endpoint must declare it when at
 * least one of its routes is a proxy route
 * ({@link RouteConfig#isProxyRoute()}); an endpoint serving only {@code asset} and/or
 * {@code redirect} routes may omit it. Only the <em>obligation</em> is conditional —
 * declaring an alias is never refused for want of a proxy route, and a declared alias
 * must resolve in the topology whether or not a proxy route uses it. Absence is
 * modelled as {@code null} and the conditional rule is enforced by the configuration
 * validator, not the record.
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
 * {@code scopes} names the OIDC scopes this endpoint needs <em>in addition to</em> the
 * gateway's {@code oidc.scopes}. It is additive only — an endpoint can add scopes but never
 * remove one — and it lives on the endpoint, outside {@code auth}, so a route- or anchor-level
 * {@code auth} block never replaces it. The route-table builder unites it with
 * {@code oidc.scopes} into each route's {@link ResolvedRoute#neededScopes()}.
 *
 * @param id               the unique endpoint id (mandatory)
 * @param enabled          whether the endpoint is active
 * @param baseUrl          the topology alias, {@code null} when the endpoint declares
 *                         none (permitted only without proxy routes)
 * @param anchor           the default anchor membership, {@code null} when the endpoint
 *                         declares none
 * @param auth             the default auth posture for the routes, {@code null} when the
 *                         endpoint relies on an anchor- or route-provided posture
 * @param scopes           the additional OIDC scopes this endpoint needs, empty when none
 * @param allowedMethods   the per-endpoint verb allowlist, empty meaning the
 *                         global/anchor list applies
 * @param upstreamDefaults the endpoint-level retry/not-modified defaults, {@code null}
 *                         when the global block applies
 * @param routes           the routes declared by this endpoint, empty when none
 * @param catalog          the portal overview entry, {@code null} when the endpoint is not listed.
 *                         A catalog block makes the endpoint an overview entry, active exactly when
 *                         the endpoint is enabled after placeholder resolution
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record EndpointConfig(
String id,
boolean enabled,
@Nullable String baseUrl,
@Nullable String anchor,
@Nullable AuthConfig auth,
List<String> scopes,
List<HttpMethod> allowedMethods,
@Nullable UpstreamDefaultsConfig upstreamDefaults,
List<RouteConfig> routes,
@Nullable CatalogConfig catalog) {

    /**
     * Canonical constructor requiring {@code id}, defensively copying the collections and
     * normalizing an absent collection to empty.
     */
    public EndpointConfig {
        Objects.requireNonNull(id, "id");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
