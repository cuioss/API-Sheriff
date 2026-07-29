/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import java.util.Objects;
import java.util.Optional;

/**
 * The global {@code security_defaults} block of {@code gateway.yaml}.
 * <p>
 * {@code profile} selects the gateway-wide inbound-filter mode — {@code strict} /
 * {@code lenient} / {@code none}, see {@link SecurityProfile}. The value range is enforced by the
 * bundled JSON Schema (an unrecognized value, the dropped {@code default} preset included, fails
 * boot there), not by this model and not by the configuration validator.
 * <p>
 * <strong>Fallback.</strong> Every route resolves its effective profile through
 * {@code route → endpoint → anchor security_filter → security_defaults}. A route that declares no
 * {@code security_filter} — or one whose block omits {@code profile} — therefore inherits the value
 * carried here, and an entirely omitted {@code security_defaults} block resolves to
 * {@link SecurityProfile#DEFAULT_PROFILE}.
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
