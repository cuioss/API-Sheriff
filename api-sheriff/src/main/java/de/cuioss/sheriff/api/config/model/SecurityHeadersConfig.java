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

import java.util.List;
import java.util.Optional;

import lombok.Builder;

/**
 * The global {@code security_headers} block of {@code gateway.yaml}: response
 * header middleware applied to every response.
 *
 * @param hsts               the HSTS settings, empty when omitted
 * @param contentTypeNosniff whether {@code X-Content-Type-Options: nosniff} is
 *                           emitted, empty when omitted
 * @param frameDeny          whether {@code X-Frame-Options: DENY} is emitted,
 *                           empty when omitted
 * @param cors               the CORS settings, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record SecurityHeadersConfig(Optional<Hsts> hsts, Optional<Boolean> contentTypeNosniff,
                                    Optional<Boolean> frameDeny, Optional<Cors> cors) {

    /**
     * Canonical constructor normalizing absent components to {@link Optional#empty()}.
     */
    public SecurityHeadersConfig {
        hsts = hsts == null ? Optional.empty() : hsts;
        contentTypeNosniff = contentTypeNosniff == null ? Optional.empty() : contentTypeNosniff;
        frameDeny = frameDeny == null ? Optional.empty() : frameDeny;
        cors = cors == null ? Optional.empty() : cors;
    }

    /**
     * {@code Strict-Transport-Security} settings.
     *
     * @param maxAge            the {@code max-age} in seconds, empty when omitted
     * @param includeSubdomains whether {@code includeSubDomains} is set, empty when
     *                          omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Hsts(Optional<Integer> maxAge, Optional<Boolean> includeSubdomains) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public Hsts {
            maxAge = maxAge == null ? Optional.empty() : maxAge;
            includeSubdomains = includeSubdomains == null ? Optional.empty() : includeSubdomains;
        }
    }

    /**
     * CORS preflight / response handling. Disabled by default.
     *
     * @param enabled          whether CORS handling is enabled, empty when omitted
     * @param allowedOrigins   the exact allowed origins, empty when none
     * @param allowedMethods   the allowed methods, empty when none
     * @param allowedHeaders   the allowed request headers, empty when none
     * @param allowCredentials whether credentials are allowed, empty when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Cors(Optional<Boolean> enabled, List<String> allowedOrigins, List<String> allowedMethods,
                       List<String> allowedHeaders, Optional<Boolean> allowCredentials) {

        /**
         * Canonical constructor defensively copying collections and normalizing
         * absent components.
         */
        public Cors {
            enabled = enabled == null ? Optional.empty() : enabled;
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
            allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
            allowCredentials = allowCredentials == null ? Optional.empty() : allowCredentials;
        }
    }
}
