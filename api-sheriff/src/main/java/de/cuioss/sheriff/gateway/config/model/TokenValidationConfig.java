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
package de.cuioss.sheriff.gateway.config.model;

import java.util.List;

/**
 * The global {@code token_validation} block of {@code gateway.yaml}.
 * <p>
 * It configures offline bearer validation for {@code require: bearer} routes;
 * it is required when any route's effective auth is {@code bearer}.
 *
 * @param issuers the accepted identity providers, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
public record TokenValidationConfig(List<IssuerConfig> issuers) {

    /**
     * Canonical constructor defensively copying {@code issuers} into an
     * unmodifiable list and normalizing an absent list to empty.
     */
    public TokenValidationConfig {
        issuers = issuers == null ? List.of() : List.copyOf(issuers);
    }
}
