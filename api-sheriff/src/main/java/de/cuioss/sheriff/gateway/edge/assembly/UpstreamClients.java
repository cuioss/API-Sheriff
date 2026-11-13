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

import java.util.Map;
import java.util.Optional;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.http.HttpClient;

/**
 * The immutable lookup of the shared upstream clients and resilience guards the proxy dispatch
 * serves a route with, keyed by the route's {@link UpstreamTarget} and {@link ResilienceShape}.
 * <p>
 * <strong>Thread safety.</strong> Both maps are copied into unmodifiable form, so the lookup is
 * safe to share across threads.
 *
 * @param clients one Vert.x client per upstream target tuple
 * @param guards  one Fault-Tolerance guard per resilience shape
 * @author API Sheriff Team
 * @since 1.0
 */
public record UpstreamClients(Map<UpstreamTarget, HttpClient> clients, Map<ResilienceShape, Guard> guards) {

    /**
     * Canonical constructor copying both maps into unmodifiable form, an absent one becoming empty.
     */
    public UpstreamClients {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
        guards = guards == null ? Map.of() : Map.copyOf(guards);
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
}
