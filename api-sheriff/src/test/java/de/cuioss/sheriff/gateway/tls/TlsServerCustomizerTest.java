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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
 * <p>
 * Both fail-closed cipher checks are covered: a suite <em>name</em> the JDK does not know, and an
 * allowlist that is <em>unreachable</em> under one of the listener's enabled protocols. The second
 * one is asserted in both directions — a TLS 1.3-only list stranding TLSv1.2 and a TLS 1.2-only list
 * stranding TLSv1.3 — plus a positive control proving the guard does not over-reject a coherent
 * policy. Duplicate handling is asserted on the declared list; the sibling case of a JSSE provider
 * reporting a duplicate <em>supported</em> name is not reachable without installing a custom security
 * provider, so it is covered by construction ({@code Set.copyOf} over {@code Set.of}) rather than by
 * a test.
 * <p>
 * The integration fixture's own allowlist is deliberately <em>not</em> mirrored here: that guard
 * lives in the module owning the fixture ({@code CipherSuiteFixtureWiringTest} in integration-tests),
 * where it reads {@code gateway.yaml} directly and cannot drift from it.
 */
@DisplayName("TlsServerCustomizer")
class TlsServerCustomizerTest {

    private static final String TLS_V1_2 = "TLSv1.2";
    private static final String TLS_V1_3 = "TLSv1.3";
    private static final String SUITE_AES_256 = "TLS_AES_256_GCM_SHA384";
    private static final String SUITE_CHACHA20 = "TLS_CHACHA20_POLY1305_SHA256";
    private static final String SUITE_ECDHE_RSA_AES_256 = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
    private static final String SUITE_ECDHE_RSA_AES_128 = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";

    @Nested
    @DisplayName("min_version")
    class MinVersion {

        @Test
        @DisplayName("a 1.2 floor enables TLSv1.2 and TLSv1.3")
        void floor12EnablesBothModernProtocols() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().minVersion("1.2").build())
                    .customizeHttpsServer(options);

            assertEquals(Set.of(TLS_V1_2, TLS_V1_3), options.getEnabledSecureTransportProtocols(),
                    "a 1.2 floor must admit TLSv1.2 and TLSv1.3");
        }

        @Test
        @DisplayName("a 1.3 floor enables TLSv1.3 only")
        void floor13EnablesOnlyTls13() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().minVersion("1.3").build())
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
                    TlsConfig.builder().minVersion("1.1").build());
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
            // Arrange — the floor is declared alongside the suites because these two are TLS 1.3-only:
            // under the default 1.2/1.3 protocol set they would (correctly) fail the reachability guard.
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().minVersion("1.3")
                    .cipherSuites(List.of(SUITE_AES_256, SUITE_CHACHA20)).build())
                    .customizeHttpsServer(options);

            assertEquals(Set.of(SUITE_AES_256, SUITE_CHACHA20), options.getEnabledCipherSuites(),
                    "each declared cipher suite must be enabled on the listener");
        }

        @Test
        @DisplayName("a duplicate entry in the declared allowlist is tolerated")
        void duplicateDeclaredSuiteIsTolerated() {
            // Arrange — a repeated YAML entry is an operator typo with no security consequence; it
            // must not abort the boot the way a Set.of(...) copy of the list would.
            HttpServerOptions options = new HttpServerOptions();

            // Act
            customizerFor(TlsConfig.builder().minVersion("1.3")
                    .cipherSuites(List.of(SUITE_AES_256, SUITE_AES_256, SUITE_CHACHA20)).build())
                    .customizeHttpsServer(options);

            // Assert
            assertEquals(Set.of(SUITE_AES_256, SUITE_CHACHA20), options.getEnabledCipherSuites(),
                    "a duplicate declaration must collapse into the allowlist rather than abort the boot");
        }

        @Test
        @DisplayName("an absent cipher_suites list leaves the default suite selection untouched")
        void absentCipherSuitesIsNoOp() {
            HttpServerOptions options = new HttpServerOptions();

            customizerFor(TlsConfig.builder().build()).customizeHttpsServer(options);

            assertTrue(options.getEnabledCipherSuites().isEmpty(),
                    "an omitted allowlist must not restrict the platform default suite selection");
        }

        @Test
        @DisplayName("an unsupported cipher suite fails the boot (fail-closed)")
        void unsupportedCipherSuiteFailsBoot() {
            // Arrange — nothing downstream rejects this name: Vert.x binds the listener and the
            // defect surfaces only as an SSLHandshakeException on every client connection.
            TlsServerCustomizer customizer = customizerFor(
                    TlsConfig.builder().cipherSuites(List.of("TLS_TOTALLY_BOGUS_SUITE")).build());
            HttpServerOptions options = new HttpServerOptions();

            // Act
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> customizer.customizeHttpsServer(options),
                    "an unsupported suite must not bind a listener that fails every handshake");

            // Assert
            assertTrue(failure.getMessage().contains("TLS_TOTALLY_BOGUS_SUITE"),
                    "the abort message must name the offending suite: " + failure.getMessage());
        }

        @Test
        @DisplayName("one bad entry among supported ones still fails the boot (no silent narrowing)")
        void partiallyMistypedAllowlistFailsBoot() {
            // Arrange — the dangerous case: the valid entries would still negotiate, so the typo
            // would silently narrow the declared policy instead of announcing itself.
            TlsServerCustomizer customizer = customizerFor(TlsConfig.builder()
                    .cipherSuites(List.of(SUITE_AES_256, "TLS_AES_256_GCM_SHA385")).build());
            HttpServerOptions options = new HttpServerOptions();

            // Act
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> customizer.customizeHttpsServer(options),
                    "a single mistyped entry must abort rather than silently narrow the allowlist");

            // Assert — the near-miss must be diagnosable, so the closest supported suite is named.
            assertTrue(failure.getMessage().contains(SUITE_AES_256),
                    "the abort message must offer the closest supported suite: " + failure.getMessage());
        }

        @Test
        @DisplayName("an allowlist spanning both enabled protocols is accepted under a 1.2 floor")
        void allowlistSpanningBothProtocolsIsAccepted() {
            // Arrange — the shape a 1.2 floor requires: TLS 1.3 suites for TLSv1.3 and the ECDHE_RSA
            // pair for TLSv1.2. Neither check may reject a policy that is coherent on both protocols.
            List<String> spanning = List.of(SUITE_AES_256, SUITE_ECDHE_RSA_AES_256);
            HttpServerOptions options = new HttpServerOptions();

            // Act
            customizerFor(TlsConfig.builder().minVersion("1.2")
                    .cipherSuites(spanning).build()).customizeHttpsServer(options);

            // Assert
            assertEquals(Set.copyOf(spanning), options.getEnabledCipherSuites(),
                    "validation must not reject an allowlist every enabled protocol can negotiate");
        }
    }

    /**
     * The second fail-closed cipher check: every name may be JDK-supported and the listener still bind
     * while a whole enabled protocol has nothing to negotiate. That is the same fail-open outcome an
     * unsupported name produces — a listener that accepts TCP but completes no handshake — reached by
     * a different operator error, so it must abort the boot identically.
     */
    @Nested
    @DisplayName("cipher_suites reachability")
    class CipherSuiteReachability {

        @Test
        @DisplayName("a TLS 1.3-only allowlist under a 1.2 floor fails the boot")
        void tls13OnlyAllowlistUnderA12FloorFailsBoot() {
            // Arrange — the concrete hazard: a 1.2 floor enables TLSv1.2 as well as TLSv1.3, so these
            // two valid TLS 1.3 suite names leave every TLS 1.2 client unable to complete a handshake.
            TlsServerCustomizer customizer = customizerFor(TlsConfig.builder()
                    .minVersion("1.2")
                    .cipherSuites(List.of(SUITE_AES_256, SUITE_CHACHA20)).build());
            HttpServerOptions options = new HttpServerOptions();

            // Act
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> customizer.customizeHttpsServer(options),
                    "an allowlist no enabled protocol can negotiate must not bind a listener");

            // Assert — the message has to name the stranded protocol and both knobs that resolve it.
            assertAll("actionable abort",
                    () -> assertTrue(failure.getMessage().contains(TLS_V1_2),
                            "the abort must name the stranded protocol: " + failure.getMessage()),
                    () -> assertTrue(failure.getMessage().contains("tls.min_version"),
                            "the abort must point at tls.min_version: " + failure.getMessage()),
                    () -> assertTrue(failure.getMessage().contains("tls.cipher_suites"),
                            "the abort must point at tls.cipher_suites: " + failure.getMessage()));
        }

        @Test
        @DisplayName("the guard reads the listener's effective protocol set, so it fires without min_version")
        void tls13OnlyAllowlistUnderDefaultProtocolsFailsBoot() {
            // Arrange — no floor is declared, so the enabled set is the Vert.x default (TLSv1.2 and
            // TLSv1.3). That is still the set the listener binds with, so it is the set to check.
            TlsServerCustomizer customizer = customizerFor(
                    TlsConfig.builder().cipherSuites(List.of(SUITE_AES_256)).build());
            HttpServerOptions options = new HttpServerOptions();

            // Act
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> customizer.customizeHttpsServer(options),
                    "an omitted floor must not exempt the allowlist from the reachability check");

            // Assert
            assertTrue(failure.getMessage().contains(TLS_V1_2),
                    "the abort must name the stranded default protocol: " + failure.getMessage());
        }

        @Test
        @DisplayName("a TLS 1.3-only allowlist is accepted once the 1.3 floor excludes TLSv1.2")
        void tls13OnlyAllowlistUnderA13FloorIsAccepted() {
            // Arrange — the same allowlist that strands TLSv1.2 above is correct here, because the
            // floor removes TLSv1.2 from the enabled set entirely. The guard must not over-reject.
            HttpServerOptions options = new HttpServerOptions();

            // Act
            customizerFor(TlsConfig.builder().minVersion("1.3")
                    .cipherSuites(List.of(SUITE_AES_256, SUITE_CHACHA20)).build())
                    .customizeHttpsServer(options);

            // Assert
            assertAll("coherent 1.3-only policy",
                    () -> assertEquals(Set.of(TLS_V1_3), options.getEnabledSecureTransportProtocols()),
                    () -> assertEquals(Set.of(SUITE_AES_256, SUITE_CHACHA20),
                            options.getEnabledCipherSuites()));
        }

        @Test
        @DisplayName("a TLS 1.2-only allowlist under a 1.2 floor strands TLSv1.3 and fails the boot")
        void tls12OnlyAllowlistUnderA12FloorFailsBoot() {
            // Arrange — the mirror case, proving the guard checks every enabled protocol rather than
            // just the lowest one: these ECDHE suites cannot be negotiated under TLSv1.3.
            TlsServerCustomizer customizer = customizerFor(TlsConfig.builder()
                    .minVersion("1.2")
                    .cipherSuites(List.of(SUITE_ECDHE_RSA_AES_256, SUITE_ECDHE_RSA_AES_128)).build());
            HttpServerOptions options = new HttpServerOptions();

            // Act
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> customizer.customizeHttpsServer(options),
                    "an allowlist that strands the upper protocol must abort just as loudly");

            // Assert
            assertTrue(failure.getMessage().contains(TLS_V1_3),
                    "the abort must name the stranded protocol: " + failure.getMessage());
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

        customizerFor(TlsConfig.builder().minVersion("1.3")
                .cipherSuites(List.of(SUITE_AES_256)).alpn(List.of("h2")).build())
                .customizeHttpsServer(options);

        assertEquals(untouched.getClientAuth(), options.getClientAuth(),
                "the TLS-policy customizer must not compete with MtlsServerCustomizer over client-auth");
    }

    private static TlsServerCustomizer customizerFor(TlsConfig tls) {
        GatewayConfig gateway = GatewayConfig.builder().version(1).tls(tls).build();
        return new TlsServerCustomizer(gateway);
    }
}
