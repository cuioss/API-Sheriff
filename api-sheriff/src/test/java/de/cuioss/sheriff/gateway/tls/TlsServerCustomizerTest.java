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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;

import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TlsServerCustomizer}'s {@code gateway.yaml} → listener-option mapping: the
 * declared TLS floor expands to an enabled protocol set, the declared cipher suites become the
 * listener allowlist, and the declared ALPN protocols switch ALPN on. Each assertion runs against a
 * real {@link HttpServerOptions} instance — the same object Quarkus hands the customizer — so the
 * test proves the neutral key actually mutates the listener rather than that a flag was read.
 * <p>
 * Absent keys must remain no-ops, because an omitted {@code tls} block has to preserve today's
 * Quarkus defaults exactly.
 */
@DisplayName("TlsServerCustomizer")
class TlsServerCustomizerTest {

    private static final String TLS_V1_2 = "TLSv1.2";
    private static final String TLS_V1_3 = "TLSv1.3";
    private static final String SUITE_AES_256 = "TLS_AES_256_GCM_SHA384";
    private static final String SUITE_CHACHA20 = "TLS_CHACHA20_POLY1305_SHA256";

    @Nested
    @DisplayName("min_version")
    class MinVersion {

        @Test
        @DisplayName("a 1.2 floor enables TLSv1.2 and TLSv1.3")
        void floor12EnablesBothModernProtocols() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().minVersion(Optional.of("1.2")).build())
                    .customizeHttpsServer(options);

            assertEquals(Set.of(TLS_V1_2, TLS_V1_3), options.getEnabledSecureTransportProtocols(),
                    "a 1.2 floor must admit TLSv1.2 and TLSv1.3");
        }

        @Test
        @DisplayName("a 1.3 floor enables TLSv1.3 only")
        void floor13EnablesOnlyTls13() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().minVersion(Optional.of("1.3")).build())
                    .customizeHttpsServer(options);

            assertEquals(Set.of(TLS_V1_3), options.getEnabledSecureTransportProtocols(),
                    "a 1.3 floor must exclude TLSv1.2");
        }

        @Test
        @DisplayName("an absent min_version leaves the platform default protocol set untouched")
        void absentMinVersionIsNoOp() {
            HttpServerOptions untouched = new HttpServerOptions();
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().build()).customizeHttpsServer(options);

            assertEquals(untouched.getEnabledSecureTransportProtocols(),
                    options.getEnabledSecureTransportProtocols(),
                    "an omitted floor must preserve the default protocol set");
        }

        @Test
        @DisplayName("an unsupported min_version fails the boot (fail-closed)")
        void unsupportedMinVersionFailsBoot() {
            TlsServerCustomizer customizer = customizerFor(
                    TlsConfig.builder().minVersion(Optional.of("1.1")).build());
            HttpServerOptions options = new HttpServerOptions();

            assertThrows(IllegalStateException.class, () -> customizer.customizeHttpsServer(options),
                    "an unrecognized TLS floor must not silently start a listener with default protocols");
        }
    }

    @Nested
    @DisplayName("cipher_suites")
    class CipherSuites {

        @Test
        @DisplayName("declared suites become the listener allowlist")
        void declaredSuitesBecomeAllowlist() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().cipherSuites(List.of(SUITE_AES_256, SUITE_CHACHA20)).build())
                    .customizeHttpsServer(options);

            assertEquals(Set.of(SUITE_AES_256, SUITE_CHACHA20), options.getEnabledCipherSuites(),
                    "each declared cipher suite must be enabled on the listener");
        }

        @Test
        @DisplayName("an absent cipher_suites list leaves the default suite selection untouched")
        void absentCipherSuitesIsNoOp() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().build()).customizeHttpsServer(options);

            assertTrue(options.getEnabledCipherSuites().isEmpty(),
                    "an omitted allowlist must not restrict the platform default suite selection");
        }
    }

    @Nested
    @DisplayName("alpn")
    class Alpn {

        @Test
        @DisplayName("declared protocols enable ALPN and are advertised in order")
        void declaredProtocolsEnableAlpn() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().alpn(List.of("h2", "http/1.1")).build())
                    .customizeHttpsServer(options);

            assertAll("ALPN mapping",
                    () -> assertTrue(options.isUseAlpn(), "declaring ALPN protocols must switch ALPN on"),
                    () -> assertEquals(List.of(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1),
                            options.getAlpnVersions(), "the advertised order must follow the declaration"));
        }

        @Test
        @DisplayName("an absent alpn list leaves ALPN as Quarkus configured it")
        void absentAlpnIsNoOp() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().build()).customizeHttpsServer(options);

            assertFalse(options.isUseAlpn(), "an omitted ALPN list must not switch ALPN on");
        }

        @Test
        @DisplayName("an unsupported alpn protocol fails the boot (fail-closed)")
        void unsupportedAlpnProtocolFailsBoot() {
            TlsServerCustomizer customizer = customizerFor(
                    TlsConfig.builder().alpn(List.of("h3")).build());
            HttpServerOptions options = new HttpServerOptions();

            assertThrows(IllegalStateException.class, () -> customizer.customizeHttpsServer(options),
                    "an unrecognized ALPN identifier must not be silently dropped from the advertised set");
        }
    }

    @Test
    @DisplayName("an absent tls block leaves every listener option at its default")
    void absentTlsBlockIsNoOp() {
        HttpServerOptions untouched = new HttpServerOptions();
        HttpServerOptions options = new HttpServerOptions();
        GatewayConfig gateway = GatewayConfig.builder().version(1).build();

        new TlsServerCustomizer(gateway).customizeHttpsServer(options);

        assertAll("untouched defaults",
                () -> assertEquals(untouched.getEnabledSecureTransportProtocols(),
                        options.getEnabledSecureTransportProtocols()),
                () -> assertEquals(untouched.getEnabledCipherSuites(), options.getEnabledCipherSuites()),
                () -> assertEquals(untouched.isUseAlpn(), options.isUseAlpn()));
    }

    @Test
    @DisplayName("client-auth stays untouched — it is owned by MtlsServerCustomizer")
    void clientAuthIsNotTouched() {
        HttpServerOptions untouched = new HttpServerOptions();
        HttpServerOptions options = new HttpServerOptions();

        customizerFor(TlsConfig.builder().minVersion(Optional.of("1.3"))
                .cipherSuites(List.of(SUITE_AES_256)).alpn(List.of("h2")).build())
                .customizeHttpsServer(options);

        assertEquals(untouched.getClientAuth(), options.getClientAuth(),
                "the TLS-policy customizer must not compete with MtlsServerCustomizer over client-auth");
    }

    private static TlsServerCustomizer customizerFor(TlsConfig tls) {
        GatewayConfig gateway = GatewayConfig.builder().version(1).tls(Optional.of(tls)).build();
        return new TlsServerCustomizer(gateway);
    }
}
