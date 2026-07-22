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
import java.util.Optional;

/**
 * The immutable, longest-prefix-ordered table of fully materialized routes the
 * gateway serves.
 * <p>
 * The table is assembled once at boot from the enabled endpoints (disabled
 * endpoints contribute no rows) by the route-table builder. Its {@code routes}
 * are ordered most-specific-first — by descending {@code path_prefix} length — so
 * the first prefix match found by {@link #lookup(String)} is the most specific
 * route for a request path. The hot path consumes the already materialized
 * {@link ResolvedRoute} values and never re-derives inheritance.
 *
 * @param routes the resolved routes, ordered longest {@code path_prefix} first,
 *               empty when no enabled endpoint declares a route
 * @author API Sheriff Team
 * @since 1.0
 */
public record RouteTable(List<ResolvedRoute> routes) {

    /**
     * Canonical constructor defensively copying {@code routes} into an unmodifiable
     * list and normalizing an absent list to empty.
     */
    public RouteTable {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    /**
     * Finds the most specific route whose {@code path_prefix} is a prefix of the
     * given request path.
     * <p>
     * Because the routes are ordered longest-prefix-first, the first prefix match
     * is the most specific one. This is a path-prefix lookup only; the full matcher
     * set (host, methods, headers) is applied by the request pipeline.
     *
     * @param path the request path to resolve
     * @return the most specific prefix-matching route, or empty when none matches
     */
    public Optional<ResolvedRoute> lookup(String path) {
        Objects.requireNonNull(path, "path");
        return routes.stream().filter(route -> matchesPrefix(path, route.pathPrefix())).findFirst();
    }

    /**
     * Tests whether {@code path} is covered by {@code prefix} on segment boundaries.
     * <p>
     * A path matches a prefix when it equals the prefix exactly or continues it at a
     * segment boundary, so {@code /proxy} matches {@code /proxy} and {@code /proxy/x}
     * but not {@code /proxy-helper}. A prefix that already ends in {@code /} is treated
     * as a segment boundary directly.
     *
     * @param path   the request path to test
     * @param prefix the route {@code path_prefix} to test against
     * @return {@code true} when the path is at or below the prefix on a segment boundary
     */
    private static boolean matchesPrefix(String path, String prefix) {
        if (path.equals(prefix)) {
            return true;
        }
        return prefix.endsWith("/") ? path.startsWith(prefix) : path.startsWith(prefix + "/");
    }
}
