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

import java.util.List;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The global {@code security_headers} block of {@code gateway.yaml}: response
 * header middleware applied to every response.
 *
 * @param hsts               the HSTS settings, {@code null} when omitted
 * @param contentTypeNosniff whether {@code X-Content-Type-Options: nosniff} is
 *                           emitted, {@code null} when omitted
 * @param frameDeny          whether {@code X-Frame-Options: DENY} is emitted,
 *                           {@code null} when omitted
 * @param cors               the CORS settings, {@code null} when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record SecurityHeadersConfig(@Nullable
        Hsts hsts, @Nullable
        Boolean contentTypeNosniff,
@Nullable
Boolean frameDeny, @Nullable
        Cors cors) {

    /**
     * {@code Strict-Transport-Security} settings.
     *
     * @param maxAge            the {@code max-age} in seconds, {@code null} when omitted
     * @param includeSubdomains whether {@code includeSubDomains} is set, {@code null} when
     *                          omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Hsts(@Nullable
    Integer maxAge, @Nullable
    Boolean includeSubdomains) {
    }

    /**
     * CORS preflight / response handling. Disabled by default.
     *
     * @param enabled          whether CORS handling is enabled, {@code null} when omitted
     * @param allowedOrigins   the exact allowed origins, empty when none
     * @param allowedMethods   the allowed methods, empty when none
     * @param allowedHeaders   the allowed request headers, empty when none
     * @param allowCredentials whether credentials are allowed, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Cors(@Nullable
            Boolean enabled, List<String> allowedOrigins, List<String> allowedMethods,
    List<String> allowedHeaders, @Nullable
            Boolean allowCredentials) {

        /**
         * Canonical constructor defensively copying collections.
         */
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
            allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
        }
    }
}
