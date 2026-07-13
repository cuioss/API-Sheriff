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

import java.util.Objects;
import java.util.Optional;

/**
 * The global {@code security_defaults} block of {@code gateway.yaml}.
 * <p>
 * {@code profile} selects the inbound-filter baseline preset
 * ({@code default} / {@code strict} / {@code lenient}) applied to every route
 * that does not declare its own {@code security_filter}. The value set is
 * enforced by the configuration validator, not by the model.
 *
 * @param profile the baseline security-filter profile, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
public record SecurityDefaultsConfig(Optional<String> profile) {

    /**
     * Canonical constructor normalizing an absent {@code profile} to
     * {@link Optional#empty()}.
     */
    public SecurityDefaultsConfig {
        profile = Objects.requireNonNullElse(profile, Optional.empty());
    }
}
