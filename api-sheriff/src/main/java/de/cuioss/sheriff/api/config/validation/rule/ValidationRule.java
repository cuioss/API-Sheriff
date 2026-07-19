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
package de.cuioss.sheriff.api.config.validation.rule;

import java.util.List;


import de.cuioss.sheriff.api.config.load.ConfigError;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;

/**
 * A single cross-cutting configuration validation rule.
 * <p>
 * A rule inspects the bound gateway document, the enabled endpoints, and the
 * resolved topology, and <em>appends</em> a {@link ConfigError} for each violation
 * it finds to the shared error list. A rule never throws for a validation failure
 * and never stops at the first problem — the aggregating
 * {@link de.cuioss.sheriff.api.config.validation.ConfigValidator} runs every rule so
 * all violations surface in one pass.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@FunctionalInterface
public interface ValidationRule {

    /**
     * Validates the configuration, appending an error per violation.
     *
     * @param gateway          the bound gateway document
     * @param enabledEndpoints the endpoints filtered to those enabled
     * @param topology         the resolved topology for the enabled endpoints
     * @param errors           the shared, mutable error accumulator to append to
     */
    void validate(GatewayConfig gateway, List<EndpointConfig> enabledEndpoints, ResolvedTopology topology,
            List<ConfigError> errors);
}
