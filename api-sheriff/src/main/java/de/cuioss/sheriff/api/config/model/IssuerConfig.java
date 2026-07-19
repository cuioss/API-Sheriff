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
 * A single {@code token_validation.issuers[]} entry.
 *
 * @param name     the unique issuer label used in logs and metrics (mandatory)
 * @param issuer   the expected {@code iss} claim (mandatory)
 * @param audience the expected {@code aud} claim, empty when omitted
 * @param jwks     the key material for signature verification, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record IssuerConfig(String name, String issuer, Optional<String> audience, Optional<Jwks> jwks) {

    /**
     * Canonical constructor requiring the mandatory fields and normalizing absent
     * optionals to {@link Optional#empty()}.
     */
    public IssuerConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(issuer, "issuer");
        audience = Objects.requireNonNullElse(audience, Optional.empty());
        jwks = Objects.requireNonNullElse(jwks, Optional.empty());
    }

    /**
     * Key material for signature verification.
     *
     * @param source             the key source ({@code http} / {@code file}), mandatory
     * @param url                the JWKS URL (for {@code source: http}), empty otherwise
     * @param file               the JWKS file path (for {@code source: file}), empty
     *                           otherwise
     * @param allowedEgressHosts the SSRF egress allowlist for {@code source: http}, empty
     *                           when omitted. An empty list keeps token-sheriff's
     *                           {@code EgressPolicy.secureDefault()}, which refuses a JWKS
     *                           URL resolving to a loopback, link-local, site-local,
     *                           any-local, multicast, or unique-local address. Each entry
     *                           exempts exactly one trusted IdP host from that check
     *                           (threat model GW-05 / BFF-07); there is no wildcard form.
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Jwks(String source, Optional<String> url, Optional<String> file,
    List<String> allowedEgressHosts) {

        /**
         * Canonical constructor requiring {@code source}, normalizing absent optionals to
         * {@link Optional#empty()}, and defensively copying the egress allowlist — an
         * absent list normalizes to {@link List#of()}, which preserves the secure egress
         * default rather than widening it.
         */
        public Jwks {
            Objects.requireNonNull(source, "source");
            url = Objects.requireNonNullElse(url, Optional.empty());
            file = Objects.requireNonNullElse(file, Optional.empty());
            allowedEgressHosts = allowedEgressHosts == null ? List.of() : List.copyOf(allowedEgressHosts);
        }
    }
}
