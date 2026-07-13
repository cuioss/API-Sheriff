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

/**
 * The {@code upstream_defaults} block declarable at two scopes — global
 * ({@code gateway.yaml}) and per endpoint ({@code endpoints/*.yaml}).
 * <p>
 * It carries the retry and HTTP-304 not-modified toggles, both defaulting to
 * {@code true}. When an endpoint file declares its own {@code upstream_defaults}
 * block, it <em>replaces</em> the global block wholesale for that endpoint's
 * routes (no field merging); the effective per-route values are materialized by
 * the route-table builder.
 *
 * @param retryEnabled       whether upstream retry is enabled (default {@code true})
 * @param notModifiedEnabled whether HTTP-304 not-modified handling is enabled
 *                           (default {@code true})
 * @author API Sheriff Team
 * @since 1.0
 */
public record UpstreamDefaultsConfig(boolean retryEnabled, boolean notModifiedEnabled) {

    /**
     * The default {@code upstream_defaults} — both toggles {@code true} — applied
     * when no block is declared at a given scope.
     *
     * @return a defaults instance with retry and not-modified both enabled
     */
    public static UpstreamDefaultsConfig defaults() {
        return new UpstreamDefaultsConfig(true, true);
    }
}
