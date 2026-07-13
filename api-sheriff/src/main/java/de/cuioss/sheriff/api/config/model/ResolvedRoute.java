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

import java.util.List;
import java.util.Objects;

import lombok.Builder;

/**
 * One route with every inherited setting materialized once, at boot, into its
 * effective value.
 * <p>
 * The route-table builder resolves the three inheritance chains — effective auth
 * (route override wholesale, else the endpoint default), effective
 * {@code allowed_methods} (endpoint list if declared, else the global list, else
 * the standard set), and the effective retry / not-modified toggles (global
 * {@code upstream_defaults}, wholesale-replaced by an endpoint block, overridden
 * per route) — so downstream pipeline code consumes the already resolved values
 * and never re-implements the inheritance.
 *
 * @param id                     the route id (mandatory)
 * @param protocol               the effective protocol (defaults to
 *                               {@link Protocol#HTTP})
 * @param match                  the matcher set (mandatory)
 * @param effectiveAuth          the materialized auth posture (mandatory)
 * @param effectiveAllowedMethods the materialized verb allowlist, never empty for
 *                               a resolved route
 * @param retryEnabled           the materialized upstream-retry toggle
 * @param notModifiedEnabled     the materialized HTTP-304 not-modified toggle
 * @param upstream               the resolved upstream target for the route's
 *                               endpoint (mandatory)
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record ResolvedRoute(String id, Protocol protocol, MatchConfig match, AuthConfig effectiveAuth,
                            List<HttpMethod> effectiveAllowedMethods, boolean retryEnabled, boolean notModifiedEnabled,
                            ResolvedUpstream upstream) {

    /**
     * Canonical constructor requiring the mandatory components, defensively copying
     * {@code effectiveAllowedMethods}, and defaulting an absent {@code protocol} to
     * {@link Protocol#HTTP}.
     */
    public ResolvedRoute {
        id = Objects.requireNonNull(id, "id");
        protocol = protocol == null ? Protocol.HTTP : protocol;
        match = Objects.requireNonNull(match, "match");
        effectiveAuth = Objects.requireNonNull(effectiveAuth, "effectiveAuth");
        effectiveAllowedMethods = effectiveAllowedMethods == null ? List.of() : List.copyOf(effectiveAllowedMethods);
        upstream = Objects.requireNonNull(upstream, "upstream");
    }

    /**
     * The route's {@code path_prefix} — the precedence and ordering key carried by
     * the {@link #match()} block.
     *
     * @return the literal path prefix of this route
     */
    public String pathPrefix() {
        return match.pathPrefix();
    }
}
