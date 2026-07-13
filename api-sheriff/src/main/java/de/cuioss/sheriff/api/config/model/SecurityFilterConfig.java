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
import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The per-route {@code security_filter} block: allowlists and limits (explicitly
 * not a WAF). The optional baseline {@code profile} is overridden by the limits
 * below.
 *
 * @param profile              the baseline preset, empty when the global default
 *                             applies
 * @param allowedPaths         the path allowlist entries, empty when none
 * @param maxHeaderCount       the maximum header count, empty when omitted
 * @param maxHeaderValueLength the maximum single-header-value length in
 *                             characters, empty when omitted
 * @param maxQueryParams       the maximum query-parameter count, empty when omitted
 * @param maxParamValueLength  the maximum single-parameter-value length in
 *                             characters, empty when omitted
 * @param maxBodyBytes         the maximum request-body size in bytes, empty when
 *                             omitted
 * @param allowedHeaderNames   the header-name allowlist, empty when none
 * @param blockedHeaderNames   the header-name blocklist, empty when none
 * @param allowedContentTypes  the request {@code Content-Type} allowlist, empty
 *                             when none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record SecurityFilterConfig(Optional<String> profile, List<String> allowedPaths, Optional<Integer> maxHeaderCount,
                                   Optional<Integer> maxHeaderValueLength, Optional<Integer> maxQueryParams, Optional<Integer> maxParamValueLength,
                                   Optional<Integer> maxBodyBytes, List<String> allowedHeaderNames, List<String> blockedHeaderNames,
                                   List<String> allowedContentTypes) {

    /**
     * Canonical constructor defensively copying collections and normalizing absent
     * components.
     */
    public SecurityFilterConfig {
        profile = Objects.requireNonNullElse(profile, Optional.empty());
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        maxHeaderCount = Objects.requireNonNullElse(maxHeaderCount, Optional.empty());
        maxHeaderValueLength = Objects.requireNonNullElse(maxHeaderValueLength, Optional.empty());
        maxQueryParams = Objects.requireNonNullElse(maxQueryParams, Optional.empty());
        maxParamValueLength = Objects.requireNonNullElse(maxParamValueLength, Optional.empty());
        maxBodyBytes = Objects.requireNonNullElse(maxBodyBytes, Optional.empty());
        allowedHeaderNames = allowedHeaderNames == null ? List.of() : List.copyOf(allowedHeaderNames);
        blockedHeaderNames = blockedHeaderNames == null ? List.of() : List.copyOf(blockedHeaderNames);
        allowedContentTypes = allowedContentTypes == null ? List.of() : List.copyOf(allowedContentTypes);
    }
}
