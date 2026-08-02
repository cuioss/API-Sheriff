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
import java.util.Map;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The root {@code gateway.yaml} configuration document: the global settings the
 * gateway applies across all endpoints.
 * <p>
 * {@code allowedMethods} is the global positive HTTP-verb allowlist (empty means
 * the standard set applies, materialized per route by the route-table builder).
 * {@code upstreamDefaults} carries the global retry/not-modified defaults; an
 * endpoint block replaces it wholesale for that endpoint's routes. {@code anchors}
 * declares the optional named, namespace-scoped policy anchors (ADR-0007) keyed by
 * anchor name; an empty map means no anchors are configured and anchor semantics
 * do not apply.
 *
 * @param version          the config schema version (unknown values are refused
 *                         by the validator)
 * @param metadata         the audit-stamp metadata, {@code null} when omitted
 * @param tls              the TLS settings, {@code null} when omitted
 * @param management       the management-interface policy settings, {@code null} when omitted. Carries TLS
 *                         policy only and declares no port — the management port stays
 *                         deployment-bound at {@code quarkus.management.port} (ADR-0025)
 * @param securityHeaders  the response-header middleware settings, {@code null} when
 *                         omitted
 * @param securityDefaults the global security-filter baseline, {@code null} when omitted
 * @param allowedMethods   the global verb allowlist, empty meaning the standard set
 * @param anchors          the named policy anchors keyed by name, empty when none
 *                         are configured
 * @param upstreamDefaults the global retry/not-modified defaults, {@code null} when omitted
 * @param assetDefaults    the add-only asset content-type additions, {@code null} when omitted —
 *                         the built-in extension map then governs alone
 * @param forwarded        the forwarded-header trust policy, {@code null} when omitted
 * @param tokenValidation  the offline bearer-validation settings, {@code null} when omitted
 * @param oidc             the confidential-client settings, {@code null} when omitted
 * @param edgeHardening    the admission-budget settings ({@code admission_cap} and the WebSocket
 *                         relay sub-budget), {@code null} when omitted — the documented defaults then apply
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record GatewayConfig(
int version,
@Nullable Metadata metadata,
@Nullable TlsConfig tls,
@Nullable ManagementConfig management,
@Nullable SecurityHeadersConfig securityHeaders,
@Nullable SecurityDefaultsConfig securityDefaults,
List<HttpMethod> allowedMethods,
Map<String, AnchorConfig> anchors,
@Nullable UpstreamDefaultsConfig upstreamDefaults,
@Nullable AssetDefaultsConfig assetDefaults,
@Nullable ForwardedConfig forwarded,
@Nullable TokenValidationConfig tokenValidation,
@Nullable OidcConfig oidc,
@Nullable EdgeHardeningConfig edgeHardening) {

    /**
     * Canonical constructor defensively copying {@code allowedMethods} and
     * {@code anchors}.
     */
    public GatewayConfig {
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        anchors = anchors == null ? Map.of() : Map.copyOf(anchors);
    }
}
