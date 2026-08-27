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
import java.util.Map;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The global {@code tls} block of {@code gateway.yaml}.
 *
 * @param minVersion     the minimum negotiated TLS version, {@code null} when omitted
 * @param cipherSuites   the terminated listener's cipher-suite allowlist, empty
 *                       when omitted
 * @param alpn           the advertised ALPN protocols, empty when omitted
 * @param passthroughSni a map of SNI hostname to topology alias relayed at L4
 *                       without decryption, empty when omitted
 * @param mtls           the mutual-TLS settings, {@code null} when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record TlsConfig(
@Nullable String minVersion,
List<String> cipherSuites,
List<String> alpn,
Map<String, String> passthroughSni,
@Nullable Mtls mtls) {

    /**
     * Canonical constructor defensively copying collections.
     */
    public TlsConfig {
        cipherSuites = cipherSuites == null ? List.of() : List.copyOf(cipherSuites);
        alpn = alpn == null ? List.of() : List.copyOf(alpn);
        passthroughSni = passthroughSni == null ? Map.of() : Map.copyOf(passthroughSni);
    }

    /**
     * Mutual-TLS settings for terminated connections.
     *
     * @param enabled  whether client-certificate verification is required
     * @param clientCa the client-CA path, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Mtls(boolean enabled, @Nullable String clientCa) {
    }
}
