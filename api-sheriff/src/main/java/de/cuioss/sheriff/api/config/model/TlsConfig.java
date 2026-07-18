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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The global {@code tls} block of {@code gateway.yaml}.
 *
 * @param minVersion     the minimum negotiated TLS version, empty when omitted
 * @param alpn           the advertised ALPN protocols, empty when omitted
 * @param passthroughSni a map of SNI hostname to topology alias relayed at L4
 *                       without decryption, empty when omitted
 * @param mtls           the mutual-TLS settings, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record TlsConfig(Optional<String> minVersion, List<String> alpn, Map<String, String> passthroughSni,
Optional<Mtls> mtls) {

    /**
     * Canonical constructor defensively copying collections and normalizing absent
     * components.
     */
    public TlsConfig {
        minVersion = Objects.requireNonNullElse(minVersion, Optional.empty());
        alpn = alpn == null ? List.of() : List.copyOf(alpn);
        passthroughSni = passthroughSni == null ? Map.of() : Map.copyOf(passthroughSni);
        mtls = Objects.requireNonNullElse(mtls, Optional.empty());
    }

    /**
     * Mutual-TLS settings for terminated connections.
     *
     * @param enabled  whether client-certificate verification is required
     * @param clientCa the client-CA path, empty when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Mtls(boolean enabled, Optional<String> clientCa) {

        /**
         * Canonical constructor normalizing an absent {@code clientCa} to
         * {@link Optional#empty()}.
         */
        public Mtls {
            clientCa = Objects.requireNonNullElse(clientCa, Optional.empty());
        }
    }
}
