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
package de.cuioss.sheriff.gateway.routing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable, exact-first then longest-prefix-ordered table of fully materialized
 * {@link Route routes} the gateway serves.
 * <p>
 * The table is assembled once from the enabled endpoints (disabled endpoints contribute no rows)
 * by the route-table builder, on the boot path and on the offline {@code --validate-config} path
 * alike. Its {@code routes} are ordered most-specific-first: every exact ({@code match.path})
 * route precedes every prefix ({@code match.path_prefix}) route, and prefix routes follow by
 * descending {@code path_prefix} length. The first match found by {@link #lookup(String)} is
 * therefore the most specific route for a request path — an exact route for the same address
 * always wins over a prefix route. The hot path consumes the already materialized {@link Route}
 * values and never re-derives inheritance.
 * <p>
 * <strong>Thread safety.</strong> Instances are immutable and safe to share across threads.
 *
 * @param routes the resolved routes, exact routes first, then longest {@code path_prefix} first,
 *               empty when no enabled endpoint declares a route
 * @author API Sheriff Team
 * @since 1.0
 */
public record RouteTable(List<Route> routes) {

    /**
     * Canonical constructor defensively copying {@code routes} into an unmodifiable list and
     * normalizing an absent list to empty.
     */
    public RouteTable {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    /**
     * Finds the most specific route whose path matcher covers the given request path.
     * <p>
     * The path test is each route's compiled {@link RouteMatcher#matchesPath(String)} — the same
     * rule the request pipeline selects through, so this lookup cannot drift from it. Because the
     * routes are ordered exact-first, then longest-prefix-first, the first match is the most
     * specific one. This is a path lookup only; the full matcher set (host, methods, headers) is
     * applied by the request pipeline.
     *
     * @param path the request path to resolve
     * @return the most specific path-matching route, or empty when none matches
     */
    public Optional<Route> lookup(String path) {
        Objects.requireNonNull(path, "path");
        return routes.stream().filter(route -> route.matcher().matchesPath(path)).findFirst();
    }
}
