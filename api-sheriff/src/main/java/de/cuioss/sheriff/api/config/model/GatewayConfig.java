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
 * The root {@code gateway.yaml} configuration document: the global settings the
 * gateway applies across all endpoints.
 * <p>
 * {@code allowedMethods} is the global positive HTTP-verb allowlist (empty means
 * the standard set applies, materialized per route by the route-table builder).
 * {@code upstreamDefaults} carries the global retry/not-modified defaults; an
 * endpoint block replaces it wholesale for that endpoint's routes.
 *
 * @param version          the config schema version (unknown values are refused
 *                         by the validator)
 * @param metadata         the audit-stamp metadata, empty when omitted
 * @param tls              the TLS settings, empty when omitted
 * @param securityHeaders  the response-header middleware settings, empty when
 *                         omitted
 * @param securityDefaults the global security-filter baseline, empty when omitted
 * @param allowedMethods   the global verb allowlist, empty meaning the standard set
 * @param upstreamDefaults the global retry/not-modified defaults, empty when omitted
 * @param forwarded        the forwarded-header trust policy, empty when omitted
 * @param tokenValidation  the offline bearer-validation settings, empty when omitted
 * @param oidc             the confidential-client settings, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record GatewayConfig(int version, Optional<Metadata> metadata, Optional<TlsConfig> tls,
        Optional<SecurityHeadersConfig> securityHeaders, Optional<SecurityDefaultsConfig> securityDefaults,
        List<HttpMethod> allowedMethods, Optional<UpstreamDefaultsConfig> upstreamDefaults,
        Optional<ForwardedConfig> forwarded, Optional<TokenValidationConfig> tokenValidation, Optional<OidcConfig> oidc) {

    /**
     * Canonical constructor defensively copying {@code allowedMethods} and
     * normalizing absent optional blocks to {@link Optional#empty()}.
     */
    public GatewayConfig {
        metadata = metadata == null ? Optional.empty() : metadata;
        tls = tls == null ? Optional.empty() : tls;
        securityHeaders = securityHeaders == null ? Optional.empty() : securityHeaders;
        securityDefaults = securityDefaults == null ? Optional.empty() : securityDefaults;
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        upstreamDefaults = upstreamDefaults == null ? Optional.empty() : upstreamDefaults;
        forwarded = forwarded == null ? Optional.empty() : forwarded;
        tokenValidation = tokenValidation == null ? Optional.empty() : tokenValidation;
        oidc = oidc == null ? Optional.empty() : oidc;
    }
}
