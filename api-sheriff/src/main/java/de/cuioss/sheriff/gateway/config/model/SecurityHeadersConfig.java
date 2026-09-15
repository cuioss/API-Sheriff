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
package de.cuioss.sheriff.gateway.config.model;

import java.util.List;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A {@code security_headers} block: the gateway-owned response security headers, declared on the
 * global {@code gateway.yaml} document and optionally on an anchor.
 * <p>
 * The global block is seeded onto every response at stage 0; once a route is selected, the route's
 * resolved block — its anchor's block when the anchor declares one, otherwise the global block — replaces
 * it <em>wholesale</em> (ADR-0007 and its Amendment A1): an anchor block that omits a header drops the
 * global value of that header for its routes, {@code content_security_policy} included. {@code cors} is
 * meaningful on the global block only; the bundled schema refuses it on an anchor. Every header value is
 * served verbatim.
 *
 * @param hsts                  the HSTS settings, {@code null} when omitted
 * @param contentTypeNosniff    whether {@code X-Content-Type-Options: nosniff} is
 *                              emitted, {@code null} when omitted
 * @param frameDeny             whether {@code X-Frame-Options: DENY} is emitted,
 *                              {@code null} when omitted
 * @param contentSecurityPolicy the {@code Content-Security-Policy} value served verbatim,
 *                              {@code null} when omitted (no header emitted). A value carrying a
 *                              control character is refused at boot, since it would inject a header
 * @param cors                  the CORS settings, {@code null} when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record SecurityHeadersConfig(
@Nullable Hsts hsts,
@Nullable Boolean contentTypeNosniff,
@Nullable Boolean frameDeny,
@Nullable String contentSecurityPolicy,
@Nullable Cors cors) {

    /**
     * {@code Strict-Transport-Security} settings.
     *
     * @param maxAge            the {@code max-age} in seconds, {@code null} when omitted
     * @param includeSubdomains whether {@code includeSubDomains} is set, {@code null} when
     *                          omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Hsts(@Nullable Integer maxAge, @Nullable Boolean includeSubdomains) {
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
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Cors(
    @Nullable Boolean enabled,
    List<String> allowedOrigins,
    List<String> allowedMethods,
    List<String> allowedHeaders,
    @Nullable Boolean allowCredentials) {

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
