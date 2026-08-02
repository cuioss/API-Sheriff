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
package de.cuioss.sheriff.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
        JwksTrustProfileResolver resolver = new JwksTrustProfileResolver(registry);

        // Act
        SSLContext context = resolver.resolve(ISSUER, PROFILE);

        // Assert — identity, not mere non-nullness: the JWKS client fabricates its own default
        // context when no profile applies, so assertNotNull would pass even if the mapping never ran
        assertSame(registry.profileContext(), context,
                "a defined trust profile must yield the context carrying that profile's anchors");
    }

    @Test
    @DisplayName("a profile whose anchors are expressed as TrustOptions resolves just as well")
    void resolvesProfileBoundThroughTrustOptions() {
        // Arrange — a PEM-bound bucket carries its anchors as Vert.x TrustOptions rather than as a
        // loaded KeyStore; both are material, and refusing this shape would break real deployments
        TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.withTrustOptionsOnly(PROFILE);
        JwksTrustProfileResolver resolver = new JwksTrustProfileResolver(registry);

        // Act
        SSLContext context = resolver.resolve(ISSUER, PROFILE);

        // Assert
        assertSame(registry.profileContext(), context,
                "trust material expressed as TrustOptions must be accepted as material");
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
    @DisplayName("a bound but anchor-free profile fails rather than silently using default trust")
    void trustMaterialFreeProfileFailsFast() {
        // Arrange — the bucket exists (any quarkus.tls.<name>.* key registers one) but holds no
        // anchors; the gateway ships exactly such a bucket for the plain-HTTP management marker
        JwksTrustProfileResolver resolver =
                new JwksTrustProfileResolver(TestTlsConfigurationRegistry.withoutTrustMaterial(PROFILE));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(ISSUER, PROFILE));

        // Assert — accepting it would reintroduce the default-trust fallback through the back door:
        // startup succeeds, gateway.yaml still reads as though the named anchors were in force, and
        // the JWKS fetch verifies against whatever the JVM happens to trust
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        String message = thrown.getMessage();
        assertTrue(message.contains("quarkus.tls." + PROFILE + ".trust-store"),
                () -> "the diagnostic must name the concrete key that supplies the anchors, got: " + message);
        assertTrue(message.contains("default trust store"),
                () -> "the diagnostic must name the fallback being refused, got: " + message);
    }

    @Test
    @DisplayName("a profile that disables verification is refused, not accepted as material")
    void trustAllProfileFailsFast() {
        // Arrange — quarkus.tls.<name>.trust-all binds the bucket to a trust-everything TrustOptions,
        // so it carries "material" by the anchor-free guard's measure while verifying nothing
        JwksTrustProfileResolver resolver =
                new JwksTrustProfileResolver(TestTlsConfigurationRegistry.withTrustAll(PROFILE));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(ISSUER, PROFILE));

        // Assert — this is the anchor-free failure one step worse: the JWKS client would accept any
        // certificate, so a machine on the path to the IdP could serve the signing keys the gateway
        // then validates every bearer token against
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        String message = thrown.getMessage();
        assertTrue(message.contains("trust-all"),
                () -> "the diagnostic must name the key that disabled verification, got: " + message);
        // And it must fail for THIS reason: a message about missing material would mean the trust-all
        // signal was never consulted and the bucket merely happened to lack a loaded KeyStore
        assertTrue(message.contains("accept any certificate"),
                () -> "the diagnostic must name what trust-all actually does, got: " + message);
    }

    @Test
    @DisplayName("a defined profile whose trust material cannot be loaded also fails config-invalid")
    void unloadableTrustMaterialFailsFast() {
        // Arrange — the profile exists but its trust store is unreadable or the password is wrong
        JwksTrustProfileResolver resolver =
                new JwksTrustProfileResolver(TestTlsConfigurationRegistry.withBrokenMaterial(PROFILE));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(ISSUER, PROFILE));

        // Assert — and it must fail for THIS reason: the bucket does carry material, so a message
        // about a missing one would mean the anchor-free guard swallowed the load failure
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        String message = thrown.getMessage();
        assertTrue(message.contains(PROFILE),
                () -> "the diagnostic must name the failing profile, got: " + message);
        assertTrue(message.contains("could not be loaded"),
                () -> "the diagnostic must report the load failure, not a missing profile, got: " + message);
    }
}
