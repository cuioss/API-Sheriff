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
 * The per-route {@code security_filter} block: allowlists and limits (explicitly
 * not a WAF). The optional baseline {@code profile} is overridden by the limits
 * below.
 * <p>
 * An omitted {@code profile} inherits the gateway-wide value from
 * {@code security_defaults} (which itself resolves to {@link SecurityProfile#DEFAULT_PROFILE} when
 * omitted). A {@code none} profile disables only the url-parameter name/value validation and the
 * per-route pipeline re-run — the limits below, including {@code max_body_bytes}, keep being
 * enforced against the nearest non-{@code none} profile's preset.
 *
 * @param profile              the inbound-filter mode — {@code strict} / {@code lenient} /
 *                             {@code none}, see {@link SecurityProfile} — {@code null} when the
 *                             {@code security_defaults} fallback applies
 * @param allowedPaths         the path allowlist entries, empty when none
 * @param maxHeaderCount       the maximum header count, {@code null} when omitted
 * @param maxHeaderValueLength the maximum single-header-value length in
 *                             characters, {@code null} when omitted
 * @param maxQueryParams       the maximum query-parameter count, {@code null} when omitted
 * @param maxParamValueLength  the maximum single-parameter-value length in
 *                             characters, {@code null} when omitted
 * @param maxBodyBytes         the maximum request-body size in bytes, {@code null} when
 *                             omitted
 * @param allowedHeaderNames   the header-name allowlist, empty when none
 * @param blockedHeaderNames   the header-name blocklist, empty when none
 * @param allowedContentTypes  the request {@code Content-Type} allowlist, empty
 *                             when none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record SecurityFilterConfig(@Nullable
        String profile, List<String> allowedPaths,
@Nullable
Integer maxHeaderCount,
@Nullable
Integer maxHeaderValueLength, @Nullable
        Integer maxQueryParams, @Nullable
        Integer maxParamValueLength,
@Nullable
Integer maxBodyBytes, List<String> allowedHeaderNames, List<String> blockedHeaderNames,
List<String> allowedContentTypes) {

    /**
     * Canonical constructor defensively copying collections.
     */
    public SecurityFilterConfig {
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        allowedHeaderNames = allowedHeaderNames == null ? List.of() : List.copyOf(allowedHeaderNames);
        blockedHeaderNames = blockedHeaderNames == null ? List.of() : List.copyOf(blockedHeaderNames);
        allowedContentTypes = allowedContentTypes == null ? List.of() : List.copyOf(allowedContentTypes);
    }
}
