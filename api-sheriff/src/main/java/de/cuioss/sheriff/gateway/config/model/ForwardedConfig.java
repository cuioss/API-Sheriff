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
package de.cuioss.sheriff.gateway.config.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The global {@code forwarded} block of {@code gateway.yaml}: the
 * forwarded-header trust policy.
 * <p>
 * {@code trusted_proxies} is a mandatory CIDR allowlist (which may be empty,
 * meaning forwarding headers are never trusted). {@code emit} is one of
 * {@code x-forwarded} / {@code both}; the value set is enforced by the
 * configuration validator.
 *
 * @param trustedProxies  the trusted-proxy CIDRs, empty when forwarding headers
 *                        are never trusted
 * @param trustSchemeHost whether the sanitized forwarded scheme/host/port/prefix
 *                        is honored, empty when omitted
 * @param emit            the canonical output mode, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record ForwardedConfig(List<String> trustedProxies, Optional<Boolean> trustSchemeHost, Optional<String> emit) {

    /**
     * Canonical constructor defensively copying {@code trustedProxies} and
     * normalizing absent components.
     */
    public ForwardedConfig {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        trustSchemeHost = Objects.requireNonNullElse(trustSchemeHost, Optional.empty());
        emit = Objects.requireNonNullElse(emit, Optional.empty());
    }
}
