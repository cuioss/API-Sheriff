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

import java.util.Objects;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;

/**
 * A route's resolved inbound-filter posture: the effective {@link SecurityProfile} and the
 * concrete cui-http {@link SecurityConfiguration} that carries its limits.
 * <p>
 * The configuration is always present — even under {@link SecurityProfile#MINIMAL}, which
 * disables validation but never limits, so the retained {@code max_body_bytes} guard keeps a
 * concrete {@code maxBodySize} to enforce.
 *
 * @param profile       the effective mode after the
 *                      {@code security_filter → security_defaults} fallback
 * @param configuration the limits policy: the nearest non-{@code minimal} profile's preset, with
 *                      the route's declared limit overrides applied on top
 * @author API Sheriff Team
 * @since 1.0
 */
public record SecurityPosture(SecurityProfile profile, SecurityConfiguration configuration) {

    /**
     * Canonical constructor rejecting an absent component — a posture is fully resolved or it
     * is a boot defect.
     */
    public SecurityPosture {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(configuration, "configuration");
    }
}
