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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;


import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.IssuerConfig;
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.validation.TokenValidator;

import org.junit.jupiter.api.DisplayName;
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

    private static TokenValidatorProducer producerFor(IssuerConfig issuer) {
        GatewayConfig config = GatewayConfig.builder()
                .version(1)
                .tokenValidation(Optional.of(new TokenValidationConfig(List.of(issuer))))
                .build();
        return new TokenValidatorProducer(config);
    }
}
