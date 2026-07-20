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
package de.cuioss.sheriff.api.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import java.util.Optional;


import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.IssuerConfig;
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.commons.transport.EgressPolicy;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import de.cuioss.sheriff.token.validation.TokenValidator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TokenValidatorProducer — builds the shared gateway validator from token_validation")
class TokenValidatorProducerTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String JWKS_URL = "https://issuer.example/jwks";

    @Test
    @DisplayName("fails config-invalid when no token_validation block is present")
    void failsWhenTokenValidationAbsent() {
        // Arrange
        TokenValidatorProducer producer = new TokenValidatorProducer(GatewayConfig.builder().version(1).build(),
                new JwksTrustProfileResolver(TestTlsConfigurationRegistry.empty()));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Test
    @DisplayName("builds a validator from an http-JWKS issuer with an explicit expected audience")
    void buildsFromHttpIssuerWithAudience() {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .audience(Optional.of("api-sheriff"))
                .jwks(Optional.of(IssuerConfig.Jwks.builder().source("http").url(Optional.of(JWKS_URL)).build()))
                .build());

        // Act
        TokenValidator validator = producer.gatewayTokenValidator();

        // Assert
        assertNotNull(validator, "an http issuer with a jwks url yields a built validator");
    }

    @Test
    @DisplayName("builds a validator from an http-JWKS issuer that configures no audience (validation disabled)")
    void buildsFromHttpIssuerWithoutAudience() {
        // Arrange — no audience means audience validation is explicitly disabled at build time
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .jwks(Optional.of(IssuerConfig.Jwks.builder().source("http").url(Optional.of(JWKS_URL)).build()))
                .build());

        // Act
        TokenValidator validator = producer.gatewayTokenValidator();

        // Assert
        assertNotNull(validator, "an audience-less issuer still yields a built validator");
    }

    @Test
    @DisplayName("fails config-invalid when an issuer declares no jwks source")
    void failsWhenIssuerHasNoJwks() {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .build());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Test
    @DisplayName("fails config-invalid when an http jwks source declares no url")
    void failsWhenHttpJwksHasNoUrl() {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .jwks(Optional.of(IssuerConfig.Jwks.builder().source("http").build()))
                .build());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Test
    @DisplayName("fails config-invalid when a file jwks source declares no file path")
    void failsWhenFileJwksHasNoFile() {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .jwks(Optional.of(IssuerConfig.Jwks.builder().source("file").build()))
                .build());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Test
    @DisplayName("fails config-invalid for an unsupported jwks source")
    void failsForUnsupportedJwksSource() {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .jwks(Optional.of(IssuerConfig.Jwks.builder().source("ldap").build()))
                .build());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Nested
    @DisplayName("jwks.allowed_egress_hosts — SSRF egress allowlist")
    class AllowedEgressHosts {

        /**
         * Resolves to 127.0.0.1 from the hosts file, so the loopback branch of
         * {@link EgressPolicy}'s address check fires deterministically without a DNS
         * lookup. An unresolvable name would be waved through (the policy fails open on
         * {@code UnknownHostException}) and would therefore prove nothing.
         */
        private static final String BLOCKED_HOST = "localhost";
        private static final URI BLOCKED_JWKS_URI = URI.create("https://localhost:8443/jwks");

        @Test
        @DisplayName("omitting the field keeps the secure default — a private-address JWKS URL stays blocked")
        void omittedFieldKeepsSecureDefault() {
            // Arrange — an http issuer that says nothing about egress
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert — structurally the secure default, and behaviourally still blocking
            assertEquals(EgressPolicy.secureDefault(), policy,
                    "an absent allowed_egress_hosts must not widen egress");
            assertThrows(TransportException.class, () -> policy.check(BLOCKED_JWKS_URI),
                    "the secure default must refuse a JWKS URL resolving to a loopback address");
        }

        @Test
        @DisplayName("an empty list keeps the secure default — an empty allowlist is not a wildcard")
        void emptyListKeepsSecureDefault() {
            // Arrange — the field is present but carries no entries
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .allowedEgressHosts(List.of())
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertEquals(EgressPolicy.secureDefault(), policy,
                    "an empty allowed_egress_hosts must not widen egress");
            assertThrows(TransportException.class, () -> policy.check(BLOCKED_JWKS_URI),
                    "an empty allowlist must still refuse a loopback-resolving JWKS URL");
        }

        @Test
        @DisplayName("a listed host is exempted from the egress check")
        void listedHostIsExempted() {
            // Arrange — the trusted IdP host is named explicitly
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .allowedEgressHosts(List.of(BLOCKED_HOST))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertDoesNotThrow(() -> policy.check(BLOCKED_JWKS_URI),
                    "the host named in allowed_egress_hosts must be reachable");
        }

        @Test
        @DisplayName("the exemption is host-exact — an unrelated entry does not widen egress for another host")
        void exemptionIsScopedToTheListedHost() {
            // Arrange — a different host is allowlisted than the one being checked
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .allowedEgressHosts(List.of("some-other-idp.internal"))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertThrows(TransportException.class, () -> policy.check(BLOCKED_JWKS_URI),
                    "allowlisting one host must not exempt any other host");
        }

        @Test
        @DisplayName("several trusted hosts can be allowlisted independently")
        void severalHostsAreAllowlisted() {
            // Arrange
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .allowedEgressHosts(List.of("some-other-idp.internal", BLOCKED_HOST))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertDoesNotThrow(() -> policy.check(BLOCKED_JWKS_URI),
                    "every entry in allowed_egress_hosts must be applied, not just the first");
        }

        @Test
        @DisplayName("the allowlist is carried through the full producer path, not only the seam")
        void allowlistSurvivesTheProducerPath() {
            // Arrange — drive the public producer entry point rather than the helper
            TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                    .name("benchmark-keycloak")
                    .issuer(ISSUER)
                    .jwks(Optional.of(IssuerConfig.Jwks.builder()
                            .source("http")
                            .url(Optional.of(JWKS_URL))
                            .allowedEgressHosts(List.of(BLOCKED_HOST))
                            .build()))
                    .build());

            // Act
            TokenValidator validator = producer.gatewayTokenValidator();

            // Assert
            assertNotNull(validator, "an issuer carrying an egress allowlist still builds a validator");
        }

        private static EgressPolicy egressPolicyFor(IssuerConfig.Jwks jwks) {
            IssuerConfig issuer = IssuerConfig.builder().name("primary").issuer(ISSUER)
                    .jwks(Optional.of(jwks)).build();
            return producerFor(issuer).toHttpJwksLoaderConfig(issuer, jwks).getEgressPolicy();
        }
    }

    @Nested
    @DisplayName("jwks.tls_profile — logical trust profile for the JWKS client")
    class TlsProfile {

        private static final String PROFILE = "corporate-idp";

        @Test
        @DisplayName("omitting the field keeps default trust — the profile's anchors are not applied")
        void omittedProfileKeepsDefaultTrust() {
            // Arrange — the profile IS defined, but this issuer does not name it
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act
            HttpJwksLoaderConfig config = producerFor(issuer, registry).toHttpJwksLoaderConfig(issuer, jwks);

            // Assert — an absent tls_profile must leave the client on whatever trust it had before
            // the feature existed. Asserting identity (not nullness) is deliberate: the JWKS client
            // fabricates its own default context, so a null check would prove nothing.
            assertNotSame(registry.profileContext(), config.getHttpHandler().getSslContext(),
                    "an absent tls_profile must not apply any profile's trust anchors");
        }

        @Test
        @DisplayName("an absent tls_profile never consults the resolver at all")
        void omittedProfileNeverConsultsTheResolver() {
            // Arrange — a resolver whose registry would fail any lookup
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act & Assert — reaching the resolver would throw, so completing proves it was skipped
            assertDoesNotThrow(() -> producerFor(issuer, TestTlsConfigurationRegistry.empty())
                            .toHttpJwksLoaderConfig(issuer, jwks),
                    "an absent tls_profile must short-circuit before the mapping seam");
        }

        @Test
        @DisplayName("a named profile is resolved and applied to the JWKS client")
        void namedProfileIsApplied() {
            // Arrange
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .tlsProfile(Optional.of(PROFILE))
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            HttpJwksLoaderConfig config = producerFor(issuer, registry).toHttpJwksLoaderConfig(issuer, jwks);

            // Assert — the exact context the profile resolved to reaches the JWKS client
            assertSame(registry.profileContext(), config.getHttpHandler().getSslContext(),
                    "a named tls_profile must put its own trust anchors on the JWKS client");
        }

        @Test
        @DisplayName("an unresolvable profile fails the whole producer path rather than degrading")
        void unresolvableProfileFailsTheProducer() {
            // Arrange — the profile is named but the deployment bound nothing
            IssuerConfig issuer = issuerWith(IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of(JWKS_URL))
                    .tlsProfile(Optional.of(PROFILE))
                    .build());

            // Act
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> producerFor(issuer, TestTlsConfigurationRegistry.empty()).gatewayTokenValidator());

            // Assert — the failure surfaces at validator assembly, which boot forces, so the
            // gateway refuses to start instead of silently validating against default trust
            assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        }

        @Test
        @DisplayName("tls_profile and allowed_egress_hosts apply together on one issuer")
        void profileAndEgressAllowlistCombine() {
            // Arrange — the real deployment shape: a private-network IdP behind an internal CA,
            // which needs BOTH the egress widening and the trust profile to work at all
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(Optional.of("https://localhost:8443/jwks"))
                    .allowedEgressHosts(List.of("localhost"))
                    .tlsProfile(Optional.of(PROFILE))
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            HttpJwksLoaderConfig config = producerFor(issuer, registry).toHttpJwksLoaderConfig(issuer, jwks);

            // Assert — the two knobs are independent; applying one must not drop the other
            assertSame(registry.profileContext(), config.getHttpHandler().getSslContext(),
                    "the trust profile must survive alongside the egress allowlist");
            assertDoesNotThrow(() -> config.getEgressPolicy().check(URI.create("https://localhost:8443/jwks")),
                    "the egress allowlist must survive alongside the trust profile");
        }

        private static IssuerConfig issuerWith(IssuerConfig.Jwks jwks) {
            return IssuerConfig.builder().name("corporate").issuer(ISSUER).jwks(Optional.of(jwks)).build();
        }
    }

    @Nested
    @DisplayName("onStartup — forces eager validator assembly at boot")
    class OnStartup {

        /**
         * The {@code @GatewayValidator TokenValidator} is injected as a lazy {@code @ApplicationScoped}
         * client proxy, so ArC does not run {@link TokenValidatorProducer#gatewayTokenValidator()}
         * until a business method is called on that proxy. {@code onStartup} therefore MUST invoke a
         * method on the injected validator — the pre-fix no-op body ignored the parameter entirely and
         * would not dereference it. Passing a {@code null} validator proves a method is dereferenced:
         * the fixed body throws {@link NullPointerException}, the old body would complete silently.
         */
        @Test
        @DisplayName("dereferences the injected validator so contextual-instance creation is forced")
        void forcesEagerAssemblyByInvokingTheValidator() {
            // Arrange — config is irrelevant; onStartup only touches the validator parameter
            TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                    .name("primary")
                    .issuer(ISSUER)
                    .jwks(Optional.of(IssuerConfig.Jwks.builder().source("http").url(Optional.of(JWKS_URL)).build()))
                    .build());

            // Act & Assert — a no-op onStartup would not dereference the validator and would not throw
            assertThrows(NullPointerException.class, () -> producer.onStartup(null, null),
                    "onStartup must invoke a method on the injected validator to force eager assembly");
        }

        @Test
        @DisplayName("completes cleanly when the injected validator assembles without error")
        void completesForAValidlyConfiguredValidator() {
            // Arrange — a good http issuer whose validator assembles without a config error
            TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                    .name("primary")
                    .issuer(ISSUER)
                    .jwks(Optional.of(IssuerConfig.Jwks.builder().source("http").url(Optional.of(JWKS_URL)).build()))
                    .build());
            TokenValidator validator = producer.gatewayTokenValidator();

            // Act & Assert — forcing assembly of a validly-configured validator must not fail startup
            assertDoesNotThrow(() -> producer.onStartup(null, validator),
                    "forcing eager assembly of a valid validator must not abort startup");
        }
    }

    @Test
    @DisplayName("an absent allowed_egress_hosts binds to an empty list on the model")
    void jwksNormalizesAbsentEgressAllowlist() {
        // Arrange & Act
        IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder().source("http").build();

        // Assert
        assertEquals(List.of(), jwks.allowedEgressHosts(),
                "an omitted allowed_egress_hosts normalizes to an empty list, never null");
    }

    private static TokenValidatorProducer producerFor(IssuerConfig issuer) {
        return producerFor(issuer, TestTlsConfigurationRegistry.empty());
    }

    private static TokenValidatorProducer producerFor(IssuerConfig issuer, TestTlsConfigurationRegistry registry) {
        GatewayConfig config = GatewayConfig.builder()
                .version(1)
                .tokenValidation(Optional.of(new TokenValidationConfig(List.of(issuer))))
                .build();
        return new TokenValidatorProducer(config, new JwksTrustProfileResolver(registry));
    }
}
