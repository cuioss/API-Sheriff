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

import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import org.jspecify.annotations.Nullable;

/**
 * Factory resolving one {@link SecurityPosture} for a security-filter shape. Invoked for every
 * route, so {@code filter} is {@code null} for a route that declares no
 * {@code security_filter} block and the gateway-wide fallback applies.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@FunctionalInterface
public interface SecurityConfigurationFactory {

    /**
     * @param filter the route's effective security-filter shape, {@code null} when it declares
     *               none
     * @return the resolved posture
     */
    SecurityPosture create(@Nullable SecurityFilterConfig filter);
}
