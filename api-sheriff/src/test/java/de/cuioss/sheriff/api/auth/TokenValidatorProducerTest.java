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
        TokenValidatorProducer producer = new TokenValidatorProducer(GatewayConfig.builder().version(1).build());

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
            return TokenValidatorProducer.toHttpJwksLoaderConfig(issuer, jwks).getEgressPolicy();
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
        GatewayConfig config = GatewayConfig.builder()
                .version(1)
                .tokenValidation(Optional.of(new TokenValidationConfig(List.of(issuer))))
                .build();
        return new TokenValidatorProducer(config);
    }
}
