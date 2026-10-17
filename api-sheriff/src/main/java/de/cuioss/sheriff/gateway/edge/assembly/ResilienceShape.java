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

import de.cuioss.sheriff.gateway.routing.Route;

/**
 * The resilience shape keying Fault-Tolerance guard dedup: routes sharing an upstream target
 * and retry posture share one guard instance.
 *
 * @param target       the upstream target
 * @param retryEnabled the materialized retry toggle
 * @author API Sheriff Team
 * @since 1.0
 */
public record ResilienceShape(UpstreamTarget target, boolean retryEnabled) {

    /**
     * @param route the proxy route
     * @return the resilience shape of the route: its target tuple and retry toggle
     * @throws IllegalStateException when the route resolves no upstream (an asset or redirect route)
     */
    public static ResilienceShape of(Route route) {
        return new ResilienceShape(UpstreamTarget.of(route), route.retryEnabled());
    }
}
