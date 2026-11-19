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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.http.HttpClient;

/**
 * The lookup of the shared upstream clients and resilience guards the proxy dispatch serves a route
 * with, keyed by the route's {@link UpstreamTarget} and {@link ResilienceShape}, together with the
 * request-body traffic of each client ({@link UpstreamBodyTraffic}).
 * <p>
 * The body traffic is derived here, one per client, never supplied: it belongs to the client it is
 * built with, so every dispatch on that client — the HTTP and the gRPC dispatch alike — shares it,
 * and a lookup built for another route table, or for another test, shares nothing with this one.
 * <p>
 * <strong>Thread safety.</strong> The three maps are unmodifiable, and every body traffic is
 * thread-safe, so the lookup is safe to share across threads.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UpstreamClients {

    private final Map<UpstreamTarget, HttpClient> clients;
    private final Map<ResilienceShape, Guard> guards;
    private final Map<UpstreamTarget, UpstreamBodyTraffic> bodyTraffic;

    /**
     * Copies both maps into unmodifiable form and builds one body traffic per client.
     *
     * @param clients one Vert.x client per upstream target tuple
     * @param guards  one Fault-Tolerance guard per resilience shape
     */
    public UpstreamClients(Map<UpstreamTarget, HttpClient> clients, Map<ResilienceShape, Guard> guards) {
        this.clients = Map.copyOf(clients);
        this.guards = Map.copyOf(guards);
        Map<UpstreamTarget, UpstreamBodyTraffic> traffic = HashMap.newHashMap(this.clients.size());
        this.clients.keySet().forEach(target -> traffic.put(target, new UpstreamBodyTraffic()));
        this.bodyTraffic = Map.copyOf(traffic);
    }

    /** @return one Vert.x client per upstream target tuple, unmodifiable */
    public Map<UpstreamTarget, HttpClient> clients() {
        return clients;
    }

    /** @return one Fault-Tolerance guard per resilience shape, unmodifiable */
    public Map<ResilienceShape, Guard> guards() {
        return guards;
    }

    /**
     * @param target the upstream target tuple
     * @return the shared client for {@code target}, or empty when no dispatched route targets it
     */
    public Optional<HttpClient> client(UpstreamTarget target) {
        return Optional.ofNullable(clients.get(target));
    }

    /**
     * @param shape the resilience shape
     * @return the shared guard for {@code shape}, or empty when no dispatched route has it
     */
    public Optional<Guard> guard(ResilienceShape shape) {
        return Optional.ofNullable(guards.get(shape));
    }

    /**
     * @param target the upstream target tuple
     * @return the request-body traffic of {@code target}'s shared client, or empty exactly when there is no
     *         such client
     */
    public Optional<UpstreamBodyTraffic> bodyTraffic(UpstreamTarget target) {
        return Optional.ofNullable(bodyTraffic.get(target));
    }
}
