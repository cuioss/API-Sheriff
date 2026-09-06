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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.TrustManagerFactory;


import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.TrustOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry double is a sibling of {@code auth.TestTlsConfigurationRegistry} rather than that
 * class itself: the existing double is package-private in {@code ..gateway.auth} so it is invisible
 * here, and its shapes are built around the JWKS resolver's {@code createSSLContext()} — its
 * broken-material shape reports no trust options at all, which this resolver could not tell apart
 * from an anchor-free bucket. Mockito is forbidden project-wide, so the shapes are literal
 * implementations of the small {@link TlsConfiguration} contract.
 */
@EnableGeneratorController
@DisplayName("EgressTrustProfileResolver — maps upstream_tls_profile to concrete egress trust anchors")
class EgressTrustProfileResolverTest {

    /**
     * Generated rather than literal on purpose: the diagnostics-are-concrete assertions below check
     * that the message interpolates the profile name into {@code quarkus.tls.<name>.trust-store.*},
     * and a fixed name would pass just as happily against a hard-coded string that never read it.
     */
    private String profile;

    @BeforeEach
    void generateProfileName() {
        profile = Generators.letterStrings(6, 14).next();
    }

    @Test
    @DisplayName("a bound profile resolves to the trust options the egress clients consume")
    void resolvesBoundProfile() {
        TrustOptions anchors = trustOptions();
        EgressTrustProfileResolver resolver = resolverFor(new StubTlsConfiguration(null, anchors, false));

        TrustOptions resolved = resolver.resolve(profile);

        assertSame(anchors, resolved,
                "a bound profile must yield the exact trust options the deployment supplied");
    }

    @Test
    @DisplayName("an undefined profile fails config-invalid rather than falling back to default trust")
    void undefinedProfileFailsFast() {
        EgressTrustProfileResolver resolver = new EgressTrustProfileResolver(new StubRegistry());

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Test
    @DisplayName("the startup diagnostic names the concrete runtime key the operator must set")
    void diagnosticNamesTheConcreteRuntimeKey() {
        EgressTrustProfileResolver resolver = new EgressTrustProfileResolver(new StubRegistry());

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        String message = thrown.getMessage();
        assertAllDiagnosticMarkers(message, "egress_tls.upstream_tls_profile",
                "quarkus.tls." + profile + ".trust-store");
    }

    @Test
    @DisplayName("a bound but anchor-free profile fails rather than silently using default trust")
    void anchorFreeProfileFailsFast() {
        EgressTrustProfileResolver resolver = resolverFor(new StubTlsConfiguration(null, null, false));

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        String message = thrown.getMessage();
        assertTrue(message.contains("carries no trust material"),
                () -> "the diagnostic must name the missing anchors, got: " + message);
        assertTrue(message.contains("JVM default trust store"),
                () -> "the diagnostic must name the fallback being refused, got: " + message);
        assertTrue(message.contains("quarkus.tls." + profile + ".trust-store"),
                () -> "the diagnostic must name the concrete key that supplies the anchors, got: " + message);
    }

    @Test
    @DisplayName("a bucket holding a trust store but no trust options is refused too")
    void trustStoreWithoutTrustOptionsFailsFast() {
        EgressTrustProfileResolver resolver = resolverFor(new StubTlsConfiguration(trustStore(), null, false));

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        assertTrue(thrown.getMessage().contains("carries no trust material"),
                () -> "material this resolver cannot hand to the clients is no material at all, got: "
                        + thrown.getMessage());
    }

    @Test
    @DisplayName("a profile that disables verification is refused, not accepted as material")
    void trustAllProfileFailsFast() {
        EgressTrustProfileResolver resolver =
                resolverFor(new StubTlsConfiguration(null, trustOptions(), true));

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        String message = thrown.getMessage();
        assertTrue(message.contains("trust-all"),
                () -> "the diagnostic must name the key that disabled verification, got: " + message);
        assertTrue(message.contains("accept any certificate"),
                () -> "the diagnostic must name what trust-all actually does, got: " + message);
    }

    @Test
    @DisplayName("the trust-all refusal is reached ahead of the anchor-free guard")
    void trustAllIsCheckedBeforeTheAnchorFreeGuard() {
        EgressTrustProfileResolver resolver = resolverFor(new StubTlsConfiguration(null, null, true));

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        String message = thrown.getMessage();
        assertTrue(message.contains("trust-all"),
                () -> "a bucket that is both trust-all and anchor-free must be refused on the "
                        + "trust-all signal — an anchor-free message means the guards ran in the "
                        + "wrong order and a real trust-all bucket, which does carry options, would "
                        + "slip through; got: " + message);
    }

    @Test
    @DisplayName("a defined profile whose trust material cannot be loaded fails with the cause attached")
    void unloadableTrustMaterialFailsFast() {
        EgressTrustProfileResolver resolver = resolverFor(new UnloadableTlsConfiguration());

        GatewayException thrown = assertThrows(GatewayException.class, () -> resolver.resolve(profile));

        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        String message = thrown.getMessage();
        assertTrue(message.contains("could not be loaded"),
                () -> "the diagnostic must report the load failure, not a missing profile, got: " + message);
        assertTrue(message.contains(profile),
                () -> "the diagnostic must name the failing profile, got: " + message);
        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "the underlying load failure must be attached, or the operator loses the only "
                        + "detail that says which store failed and why");
    }

    private EgressTrustProfileResolver resolverFor(TlsConfiguration configuration) {
        StubRegistry registry = new StubRegistry();
        registry.register(profile, configuration);
        return new EgressTrustProfileResolver(registry);
    }

    private static void assertAllDiagnosticMarkers(String message, String... markers) {
        for (String marker : markers) {
            assertTrue(message.contains(marker),
                    () -> "the diagnostic must contain '" + marker + "', got: " + message);
        }
    }

    /**
     * A loaded, empty trust store. Presence is what the resolver's guards react to — it never
     * inspects which anchors a bound bucket holds — so an empty store is the faithful stand-in for
     * "the deployment supplied material in KeyStore shape".
     *
     * @return a trust store instance
     */
    private static KeyStore trustStore() {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            return store;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("PKCS12 must be available to the test JVM", e);
        }
    }

    /**
     * @return the anchors expressed the way a bound bucket exposes them to the Vert.x clients
     */
    private static TrustOptions trustOptions() {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore());
            return TrustOptions.wrap(factory);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the default trust-manager algorithm must be available", e);
        }
    }

    /** An in-memory stand-in for the runtime's registry, so the mapping runs without booting Quarkus. */
    private static final class StubRegistry implements TlsConfigurationRegistry {

        private final Map<String, TlsConfiguration> byName = new HashMap<>();

        @Override
        public Optional<TlsConfiguration> get(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public Optional<TlsConfiguration> getDefault() {
            return Optional.empty();
        }

        @Override
        public void register(String name, TlsConfiguration configuration) {
            byName.put(name, configuration);
        }
    }

    /**
     * A bucket carrying whichever of the two trust-material shapes the scenario needs, and reporting
     * its own {@code trust-all} signal independently of them — the combination is what lets the
     * ordering control above pin the guards' sequence rather than merely their presence.
     */
    private static final class StubTlsConfiguration extends BaseTlsConfiguration {

        private final KeyStore trustStore;
        private final TrustOptions trustOptions;
        private final boolean trustAll;

        private StubTlsConfiguration(KeyStore trustStore, TrustOptions trustOptions, boolean trustAll) {
            this.trustStore = trustStore;
            this.trustOptions = trustOptions;
            this.trustAll = trustAll;
        }

        @Override
        public KeyStore getTrustStore() {
            return trustStore;
        }

        @Override
        public TrustOptions getTrustStoreOptions() {
            return trustOptions;
        }

        @Override
        public boolean isTrustAll() {
            return trustAll;
        }
    }

    /**
     * A bucket whose material exists but fails when the runtime turns it into Vert.x options — an
     * unreadable store, a wrong password or an unsupported format in a real deployment.
     */
    private static final class UnloadableTlsConfiguration extends BaseTlsConfiguration {

        private final KeyStore trustStore = trustStore();

        @Override
        public KeyStore getTrustStore() {
            return trustStore;
        }

        @Override
        public TrustOptions getTrustStoreOptions() {
            throw new IllegalStateException("trust store could not be read");
        }
    }
}
