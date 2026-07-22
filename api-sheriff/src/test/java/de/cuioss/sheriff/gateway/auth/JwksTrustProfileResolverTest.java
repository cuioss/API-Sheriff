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
package de.cuioss.sheriff.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.net.ssl.SSLContext;


import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwksTrustProfileResolver — maps a logical tls_profile name to concrete trust anchors")
class JwksTrustProfileResolverTest {

    private static final String PROFILE = "corporate-idp";

    private static final IssuerConfig ISSUER = IssuerConfig.builder()
            .name("corporate")
            .issuer("https://idp.corp.internal/realms/main")
            .build();

    @Test
    @DisplayName("a defined profile resolves to the SSL context carrying its trust anchors")
    void resolvesDefinedProfile() {
        // Arrange
        JwksTrustProfileResolver resolver = new JwksTrustProfileResolver(TestTlsConfigurationRegistry.with(PROFILE));

        // Act
        SSLContext context = resolver.resolve(ISSUER, PROFILE);

        // Assert
        assertNotNull(context, "a defined trust profile must yield a usable SSL context");
    }

    @Test
    @DisplayName("an undefined profile fails config-invalid rather than falling back to default trust")
    void undefinedProfileFailsFast() {
        // Arrange — the operator named a profile the deployment never bound
        JwksTrustProfileResolver resolver = new JwksTrustProfileResolver(TestTlsConfigurationRegistry.empty());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(ISSUER, PROFILE));

        // Assert — refusing to start is the point: a silent fallback to default trust would either
        // fail obscurely on the first JWKS fetch or succeed against anchors nobody intended
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Test
    @DisplayName("the startup diagnostic names the concrete runtime key the operator must set")
    void diagnosticNamesTheConcreteRuntimeKey() {
        // Arrange
        JwksTrustProfileResolver resolver = new JwksTrustProfileResolver(TestTlsConfigurationRegistry.empty());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(ISSUER, PROFILE));

        // Assert — "config neutral, diagnostics concrete": gateway.yaml never names the runtime,
        // but the error must, or the operator is handed an abstraction instead of a next step
        String message = thrown.getMessage();
        assertTrue(message.contains(PROFILE),
                () -> "the diagnostic must name the unresolved profile, got: " + message);
        assertTrue(message.contains("quarkus.tls." + PROFILE + ".trust-store"),
                () -> "the diagnostic must name the concrete key that binds the profile, got: " + message);
        assertTrue(message.contains(ISSUER.name()),
                () -> "the diagnostic must name the issuer that declared the profile, got: " + message);
    }

    @Test
    @DisplayName("a defined profile whose trust material cannot be loaded also fails config-invalid")
    void unloadableTrustMaterialFailsFast() {
        // Arrange — the profile exists but its trust store is unreadable or the password is wrong
        JwksTrustProfileResolver resolver =
                new JwksTrustProfileResolver(TestTlsConfigurationRegistry.withBrokenMaterial(PROFILE));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(ISSUER, PROFILE));

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        assertTrue(thrown.getMessage().contains(PROFILE),
                () -> "the diagnostic must name the failing profile, got: " + thrown.getMessage());
    }
}
