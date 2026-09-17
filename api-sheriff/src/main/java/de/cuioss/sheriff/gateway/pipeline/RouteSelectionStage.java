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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

/**
 * Stage 2 — deny-by-default route selection on the single canonical path.
 * <p>
 * The stage walks the boot-assembled {@link RouteRuntime} list — already ordered by the route-table
 * builder <em>exact-first</em> (every {@code match.path} route before every {@code match.path_prefix}
 * route), then longest-prefix-first — and selects the first route whose compiled
 * {@link RouteRuntime#matcher() matcher} accepts the request — exact path or prefix <em>and</em>
 * match-methods <em>and</em> host <em>and</em> every header matcher. First match wins, so for the same
 * address an exact route is selected before any prefix route, and among prefix routes the most
 * specific one is selected. An exact path is compared un-normalized: {@code /a} does not match
 * {@code /a/}. No candidate is a hard 404
 * ({@link EventType#NO_ROUTE_MATCHED}); the gateway never forwards an unmatched request.
 * <p>
 * Selection consumes the {@link PipelineRequest#canonicalPath() canonical path} set at stage 1
 * (GW-01 single-path invariant), never the raw inbound path.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteSelectionStage {

    private final List<RouteRuntime> routes;

    /**
     * @param routes the boot-assembled routes, ordered exact routes first, then longest
     *               {@code path_prefix} first
     */
    public RouteSelectionStage(List<RouteRuntime> routes) {
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    /**
     * Selects the most specific matching route and records it on the request.
     *
     * @param request the in-flight request context; its canonical path must be set (stage 1)
     * @throws GatewayException with {@link EventType#NO_ROUTE_MATCHED} when no route matches
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        String canonicalPath = requireCanonicalPath(request);
        Map<String, String> headers = request.singleValueHeaders();
        for (RouteRuntime route : routes) {
            if (route.getMatcher().matches(canonicalPath, request.method(), request.host(), headers)) {
                request.selectedRoute(route);
                return;
            }
        }
        throw new GatewayException(EventType.NO_ROUTE_MATCHED,
                "No route matched canonical path (method " + request.method() + ")");
    }

    private static String requireCanonicalPath(PipelineRequest request) {
        String canonicalPath = request.canonicalPath();
        if (canonicalPath == null) {
            throw new IllegalStateException("Route selection requires the canonical path resolved at stage 1");
        }
        return canonicalPath;
    }
}
