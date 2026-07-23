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

import java.util.Objects;
import java.util.Optional;

import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemTrustOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Maps the resolved {@code tls.mtls} block onto the terminated Quarkus HTTPS listener's client-auth
 * options: when {@code tls.mtls.enabled} is set, the terminated listener is switched to
 * {@link ClientAuth#REQUIRED} and its trust material is bound from the configured {@code client_ca}
 * CA anchor, so every terminated connection must present a certificate the CA signed.
 * <p>
 * The mapping is strict by design (GW-06): there is no HTTP-level fallback and no
 * optional / want-style mode — a client that fails verification is rejected at the handshake. When
 * {@code tls.mtls} is absent or {@code enabled=false} the listener's client-auth is left untouched
 * (off). {@code enabled} with a missing {@code client_ca} fails the boot fast rather than starting a
 * require-client-auth listener with no trust anchor.
 * <p>
 * Passthrough connections are never affected: they are split off at L4 by the
 * {@link SniFrontListener} before any handshake, so the gateway never participates in a passthrough
 * TLS handshake and this customizer only ever governs the terminated listener.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class MtlsServerCustomizer implements HttpServerOptionsCustomizer {

    private static final CuiLogger LOGGER = new CuiLogger(MtlsServerCustomizer.class);

    private final GatewayConfig gatewayConfig;

    /**
     * @param gatewayConfig the bound global gateway document (source of the {@code tls.mtls} block)
     */
    @Inject
    public MtlsServerCustomizer(GatewayConfig gatewayConfig) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
    }

    /**
     * Applies the mTLS client-auth requirement to the terminated HTTPS listener when
     * {@code tls.mtls.enabled} is set; a no-op otherwise.
     *
     * @param options the HTTPS server options Quarkus is about to start the terminated listener with
     */
    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        Optional<TlsConfig.Mtls> mtls = gatewayConfig.tls().flatMap(TlsConfig::mtls);
        if (mtls.isEmpty() || !mtls.get().enabled()) {
            return;
        }
        String clientCa = mtls.get().clientCa().orElseThrow(() -> new IllegalStateException(
                "Refusing to start — tls.mtls.enabled requires a client_ca trust anchor"));
        options.setClientAuth(ClientAuth.REQUIRED);
        options.setTrustOptions(new PemTrustOptions().addCertPath(clientCa));
        LOGGER.debug("mTLS enabled: terminated HTTPS listener requires a client certificate signed by '%s'",
                clientCa);
    }
}
