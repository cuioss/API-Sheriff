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
package de.cuioss.sheriff.gateway.tls;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Maps the resolved global {@code tls} block of {@code gateway.yaml} onto the terminated Quarkus
 * HTTPS listener: {@code tls.min_version} becomes the listener's enabled protocol set,
 * {@code tls.cipher_suites} its cipher allowlist, and {@code tls.alpn} its advertised ALPN
 * protocols. {@code gateway.yaml} is thereby the single effective source of terminated-listener TLS
 * policy — the raw {@code quarkus.tls.*} keys that previously declared the same policy never reached
 * the listener and have been deleted (ADR-0025).
 * <p>
 * Every mapping is a no-op when its key is absent, so an omitted {@code tls} block — or an omitted
 * individual key — leaves the corresponding Quarkus default untouched.
 * <p>
 * This customizer governs the <em>terminated</em> listener only. The SNI front listener
 * ({@link SniFrontListener}) is a raw Vert.x {@code NetServer} that terminates nothing — it splits
 * passthrough connections off at L4 before any handshake — so it is unaffected by this policy
 * (ADR-0017). Client-auth on the terminated listener is owned by the sibling
 * {@link MtlsServerCustomizer} and is deliberately not touched here.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TlsServerCustomizer implements HttpServerOptionsCustomizer {

    private static final CuiLogger LOGGER = new CuiLogger(TlsServerCustomizer.class);

    private static final String TLS_V1_2 = "TLSv1.2";
    private static final String TLS_V1_3 = "TLSv1.3";

    private final GatewayConfig gatewayConfig;

    /**
     * @param gatewayConfig the bound global gateway document (source of the {@code tls} block)
     */
    @Inject
    public TlsServerCustomizer(GatewayConfig gatewayConfig) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
    }

    /**
     * Applies the declared TLS floor, cipher allowlist and ALPN protocol list to the terminated
     * HTTPS listener; each mapping is skipped when its {@code gateway.yaml} key is absent.
     *
     * @param options the HTTPS server options Quarkus is about to start the terminated listener with
     */
    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        Optional<TlsConfig> tls = gatewayConfig.tls();
        if (tls.isEmpty()) {
            return;
        }
        TlsConfig config = tls.get();
        applyMinVersion(config, options);
        applyCipherSuites(config, options);
        applyAlpn(config, options);
    }

    /**
     * Maps the declared floor onto the enabled protocol set: a {@code 1.2} floor enables TLSv1.2 and
     * TLSv1.3, a {@code 1.3} floor enables TLSv1.3 only.
     */
    private static void applyMinVersion(TlsConfig config, HttpServerOptions options) {
        Optional<String> minVersion = config.minVersion();
        if (minVersion.isEmpty()) {
            return;
        }
        Set<String> enabledProtocols = switch (minVersion.get()) {
            case "1.2" -> Set.of(TLS_V1_2, TLS_V1_3);
            case "1.3" -> Set.of(TLS_V1_3);
            default -> throw new IllegalStateException(
                    "Refusing to start — unsupported tls.min_version '%s'; expected '1.2' or '1.3'"
                            .formatted(minVersion.get()));
        };
        options.setEnabledSecureTransportProtocols(enabledProtocols);
        LOGGER.debug("Terminated HTTPS listener TLS floor '%s' enables protocols %s", minVersion.get(),
                enabledProtocols);
    }

    /**
     * Adds each declared cipher suite to the listener's allowlist; an empty list leaves the Quarkus
     * default suite selection untouched.
     */
    private static void applyCipherSuites(TlsConfig config, HttpServerOptions options) {
        List<String> cipherSuites = config.cipherSuites();
        if (cipherSuites.isEmpty()) {
            return;
        }
        cipherSuites.forEach(options::addEnabledCipherSuite);
        LOGGER.debug("Terminated HTTPS listener cipher-suite allowlist: %s", cipherSuites);
    }

    /**
     * Enables ALPN and advertises the declared protocols; an empty list leaves ALPN as Quarkus
     * configured it.
     */
    private static void applyAlpn(TlsConfig config, HttpServerOptions options) {
        List<String> alpn = config.alpn();
        if (alpn.isEmpty()) {
            return;
        }
        List<HttpVersion> alpnVersions = new ArrayList<>(alpn.size());
        for (String protocol : alpn) {
            alpnVersions.add(toHttpVersion(protocol));
        }
        options.setUseAlpn(true);
        options.setAlpnVersions(alpnVersions);
        LOGGER.debug("Terminated HTTPS listener ALPN protocols: %s", alpn);
    }

    /**
     * Resolves an ALPN protocol identifier to its Vert.x {@link HttpVersion}. An unrecognized
     * identifier aborts the boot rather than being silently dropped from the advertised set.
     */
    private static HttpVersion toHttpVersion(String alpnProtocol) {
        return switch (alpnProtocol) {
            case "h2" -> HttpVersion.HTTP_2;
            case "http/1.1" -> HttpVersion.HTTP_1_1;
            case "http/1.0" -> HttpVersion.HTTP_1_0;
            default -> throw new IllegalStateException(
                    "Refusing to start — unsupported tls.alpn protocol '%s'; expected 'h2', 'http/1.1' or 'http/1.0'"
                            .formatted(alpnProtocol));
        };
    }
}
