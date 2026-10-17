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
package de.cuioss.sheriff.gateway.edge.assembly;

import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.routing.Route;

/**
 * The upstream-target tuple keying Vert.x client dedup: routes sharing
 * (scheme, host, port, forced-h2) share one client instance. The {@code forcedHttp2} dimension
 * separates a gRPC route's forced-HTTP/2 client from an HTTP/1.1 client to the same host:port.
 *
 * @param scheme      the upstream scheme
 * @param host        the upstream host
 * @param port        the upstream port
 * @param forcedHttp2 whether the client is forced to HTTP/2 (a gRPC route)
 * @author API Sheriff Team
 * @since 1.0
 */
public record UpstreamTarget(String scheme, String host, int port, boolean forcedHttp2) {

    /**
     * @param upstream    the resolved upstream
     * @param forcedHttp2 whether the client is forced to HTTP/2 (a gRPC route)
     * @return the target tuple for {@code upstream}
     */
    public static UpstreamTarget of(ResolvedUpstream upstream, boolean forcedHttp2) {
        return new UpstreamTarget(upstream.scheme(), upstream.host(), upstream.port(), forcedHttp2);
    }

    /**
     * The target tuple of a proxy route: its resolved upstream, forced to HTTP/2 for a gRPC route,
     * since gRPC requires HTTP/2 end-to-end.
     *
     * @param route the proxy route
     * @return the target tuple for the route's upstream
     * @throws IllegalStateException when the route resolves no upstream (an asset or redirect route)
     */
    public static UpstreamTarget of(Route route) {
        ResolvedUpstream upstream = route.upstream();
        if (upstream == null) {
            throw new IllegalStateException("route '" + route.id() + "' resolves no proxy upstream");
        }
        return of(upstream, route.protocol() == Protocol.GRPC);
    }
}
