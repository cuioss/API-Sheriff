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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;

import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MtlsServerCustomizer}'s config → client-auth mapping: enabled mTLS switches
 * the terminated HTTPS listener to {@link ClientAuth#REQUIRED} with a trust anchor, disabled /
 * absent mTLS leaves client-auth off, and enabled-with-missing-{@code client_ca} fails the boot.
 * <p>
 * The real accept / reject handshake behaviour (valid cert, wrong CA, no cert) is proven as an
 * integration behaviour test in deliverable 4 (GW-06 behaviour, not a flag read); this test governs
 * only the deterministic option mapping, so it needs no running TLS server.
 */
@DisplayName("MtlsServerCustomizer")
class MtlsServerCustomizerTest {

    private static final String CLIENT_CA_PATH = "/etc/api-sheriff/client-ca.crt";

    @Test
    @DisplayName("enabled mTLS requires client-auth and binds the client_ca trust anchor")
    void enabledRequiresClientAuthWithTrustAnchor() {
        // Arrange
        MtlsServerCustomizer customizer = customizerFor(mtls(true, Optional.of(CLIENT_CA_PATH)));
        HttpServerOptions options = new HttpServerOptions();

        // Act
        customizer.customizeHttpsServer(options);

        // Assert
        assertEquals(ClientAuth.REQUIRED, options.getClientAuth(), "enabled mTLS requires a client certificate");
        assertNotNull(options.getTrustOptions(), "the client_ca trust anchor is bound as the listener trust store");
    }

    @Test
    @DisplayName("disabled mTLS leaves client-auth off")
    void disabledLeavesClientAuthOff() {
        // Arrange
        MtlsServerCustomizer customizer = customizerFor(mtls(false, Optional.of(CLIENT_CA_PATH)));
        HttpServerOptions options = new HttpServerOptions();

        // Act
        customizer.customizeHttpsServer(options);

        // Assert
        assertEquals(ClientAuth.NONE, options.getClientAuth(), "disabled mTLS never requires a client certificate");
        assertNull(options.getTrustOptions(), "disabled mTLS binds no trust store");
    }

    @Test
    @DisplayName("an absent tls.mtls block is a no-op")
    void absentMtlsIsNoOp() {
        // Arrange
        GatewayConfig gateway = GatewayConfig.builder().version(1)
                .tls(Optional.of(TlsConfig.builder().build())).build();
        MtlsServerCustomizer customizer = new MtlsServerCustomizer(gateway);
        HttpServerOptions options = new HttpServerOptions();

        // Act
        customizer.customizeHttpsServer(options);

        // Assert
        assertEquals(ClientAuth.NONE, options.getClientAuth());
        assertNull(options.getTrustOptions());
    }

    @Test
    @DisplayName("enabled mTLS with no client_ca fails the boot (fail-closed)")
    void enabledWithoutClientCaFailsBoot() {
        // Arrange
        MtlsServerCustomizer customizer = customizerFor(mtls(true, Optional.empty()));
        HttpServerOptions options = new HttpServerOptions();

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> customizer.customizeHttpsServer(options),
                "enabled mTLS without a client_ca trust anchor must not start a require-client-auth listener");
    }

    private static MtlsServerCustomizer customizerFor(TlsConfig.Mtls mtls) {
        GatewayConfig gateway = GatewayConfig.builder().version(1)
                .tls(Optional.of(TlsConfig.builder().mtls(Optional.of(mtls)).build()))
                .build();
        return new MtlsServerCustomizer(gateway);
    }

    private static TlsConfig.Mtls mtls(boolean enabled, Optional<String> clientCa) {
        return new TlsConfig.Mtls(enabled, clientCa);
    }
}
