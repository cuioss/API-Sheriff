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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.TrustOptions;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DefaultTrustSourceAudit}, the client-side counterpart of the two server-side TLS
 * audits.
 * <p>
 * The load-bearing test here is {@link #reportsThroughTheRealStartupEventPath()}: it fires a
 * {@link StartupEvent} through the container's own event bus and asserts the record appears, rather
 * than calling the observer method directly. Lesson 2026-07-20-18-002 is precisely that a
 * normal-scoped observer bean can get a lazy CDI proxy that is never touched — the check silently
 * never runs in production while a test invoking the method directly stays green. Only delivery
 * through the real event bus proves the observer is registered and reachable.
 * <p>
 * <strong>Why the two-tier matrix is exercised on a stub rather than on a booted deployment.</strong>
 * One of the two tiers is a JVM system property, which no Quarkus test profile can set — the property
 * is read by the JDK's own {@code TrustManagerFactory}, not by the configuration system. Driving the
 * four combinations therefore means moving the property itself, so the surrounding
 * {@link #captureManagedProperties()} / {@link #restoreManagedProperties()} pair captures and restores
 * every property this class touches, including the ambient value the surefire JVM may already carry.
 * Each case sets or clears the property EXPLICITLY rather than relying on it being absent, so the
 * matrix means the same thing whatever the JVM was started with.
 */
@QuarkusTest
@EnableTestLogger
@DisplayName("Effective default trust-source audit")
class DefaultTrustSourceAuditTest {

    /**
     * Read by nothing in production — named here only so a test can set it and then assert it never
     * reaches a record. The audit must never look it up.
     */
    private static final String TRUST_STORE_PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";

    private static final List<String> MANAGED_PROPERTIES = List.of(
            DefaultTrustSourceAudit.TRUST_STORE_PROPERTY,
            DefaultTrustSourceAudit.TRUST_STORE_TYPE_PROPERTY,
            TRUST_STORE_PASSWORD_PROPERTY);

    private static final String STORE_PATH = "/app/certificates/private-ca-truststore.p12";

    private static final String STORE_PASSWORD = "s3cr3t-trust-password";

    private static final String RAW_TIER_PLATFORM_BUNDLE =
            "legs holding a raw JDK TrustManager: platform default trust bundle";

    private static final String REGISTRY_TIER_PLATFORM_BUNDLE =
            "legs resolving through the Quarkus TLS registry: platform default trust bundle";

    private static final String REPLACEMENT_WARNING =
            "An operator-supplied default trust store is in effect";

    private final Map<String, String> capturedProperties = new HashMap<>();

    @Inject
    Event<StartupEvent> startupEvent;

    /**
     * Captures every property this class moves, so a case that sets one cannot leak into the next
     * test — or into any other test sharing this JVM.
     */
    @BeforeEach
    void captureManagedProperties() {
        capturedProperties.clear();
        MANAGED_PROPERTIES.forEach(name -> capturedProperties.put(name, System.getProperty(name)));
    }

    /**
     * Restores each captured property to exactly what it was, distinguishing "was unset" from "was
     * set to something" — collapsing the two would leave a property behind that the JVM never had.
     */
    @AfterEach
    void restoreManagedProperties() {
        capturedProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    @DisplayName("Reports the effective trust source through the real startup-event path, not by direct invocation")
    void reportsThroughTheRealStartupEventPath() {
        startupEvent.fire(new StartupEvent());

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                "Effective default trust source");
    }

    /**
     * The four combinations of (system property set / unset) x (default-bucket trust material present
     * / absent), plus the two shapes "absent" takes in the registry.
     * <p>
     * Every case asserts the WARN's presence or its absence, because an audit that always warns and
     * one that never warns are indistinguishable from the warning cases alone.
     */
    @Nested
    @DisplayName("The two tiers, and the divergence between them")
    class Tiers {

        @Test
        @DisplayName("Neither tier moved: both report the platform bundle, and nothing is warned")
        void neitherTierMoved() {
            System.clearProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY);

            boolean replaced = audit(registryWithoutDefaultBucket()).auditDefaultTrustSource();

            assertFalse(replaced, "the shipped posture supplies no store on either tier");
            assertAll("the unconditional record still names both tiers, and no replacement is claimed",
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            RAW_TIER_PLATFORM_BUNDLE),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            REGISTRY_TIER_PLATFORM_BUNDLE),
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REPLACEMENT_WARNING));
        }

        @Test
        @DisplayName("A default bucket carrying no trust material is not an operator-supplied store")
        void defaultBucketWithoutTrustMaterialIsNotAReplacement() {
            System.clearProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY);

            boolean replaced = audit(registryWithDefaultBucket(null)).auditDefaultTrustSource();

            assertAll("a bucket registered for other reasons must not be reported as trust replacement",
                    () -> assertFalse(replaced, "the bucket exists but carries no anchors"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            REGISTRY_TIER_PLATFORM_BUNDLE),
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REPLACEMENT_WARNING));
        }

        @Test
        @DisplayName("Only the system property is set: the divergence is named and the registry legs stay behind")
        void onlyTheSystemPropertyTierMoved() {
            System.setProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY, STORE_PATH);
            System.setProperty(DefaultTrustSourceAudit.TRUST_STORE_TYPE_PROPERTY, "PKCS12");

            boolean replaced = audit(registryWithoutDefaultBucket()).auditDefaultTrustSource();

            assertTrue(replaced, "an operator-supplied store is in effect on one tier");
            assertAll("the half that did NOT move is what the operator would never think to check",
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "operator-supplied store (kind=PKCS12, path=" + STORE_PATH + ")"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "The two tiers DIVERGE: only the raw-TrustManager legs were moved"));
        }

        @Test
        @DisplayName("Only the registry tier is set: the divergence names the raw-TrustManager legs left behind")
        void onlyTheRegistryTierMoved() {
            System.clearProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY);

            boolean replaced = audit(registryWithDefaultBucket(new PfxOptions()))
                    .auditDefaultTrustSource();

            assertTrue(replaced, "an operator-supplied store is in effect on one tier");
            assertAll("the confidential-client OIDC engine is the leg this case strands",
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "operator-supplied trust material (kind=PKCS12)"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "The two tiers DIVERGE: only the registry-resolved legs were moved"));
        }

        @Test
        @DisplayName("Both tiers set: the replacement is warned and the two halves are reported as agreeing")
        void bothTiersMoved() {
            System.setProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY, STORE_PATH);

            boolean replaced = audit(registryWithDefaultBucket(new PfxOptions()))
                    .auditDefaultTrustSource();

            assertTrue(replaced, "an operator-supplied store is in effect on both tiers");
            assertAll("agreement is still a replacement, so the warning must fire without a divergence",
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            REPLACEMENT_WARNING),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "Both tiers name an operator-supplied store"),
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "DIVERGE"));
        }

        @Test
        @DisplayName("A store named without its kind falls back to the JDK's own default store type")
        void reportsTheJdkDefaultTypeWhenTheKindIsNotNamed() {
            System.setProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY, STORE_PATH);
            System.clearProperty(DefaultTrustSourceAudit.TRUST_STORE_TYPE_PROPERTY);

            audit(registryWithoutDefaultBucket()).auditDefaultTrustSource();

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    "operator-supplied store (kind=" + KeyStore.getDefaultType());
        }

        @Test
        @DisplayName("Names the store format in operator vocabulary, not in the runtime's own type names")
        void namesTheStoreFormatInOperatorVocabulary() {
            System.clearProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY);

            audit(registryWithDefaultBucket(new JksOptions())).auditDefaultTrustSource();
            audit(registryWithDefaultBucket(new PemTrustOptions())).auditDefaultTrustSource();

            assertAll("PfxOptions/JksOptions/PemTrustOptions are Vert.x type names, not operator ones",
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "operator-supplied trust material (kind=JKS)"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "operator-supplied trust material (kind=PEM)"));
        }

        @Test
        @DisplayName("Never emits the trust-store password, at any level")
        void emitsNoPassword() {
            System.setProperty(DefaultTrustSourceAudit.TRUST_STORE_PROPERTY, STORE_PATH);
            System.setProperty(TRUST_STORE_PASSWORD_PROPERTY, STORE_PASSWORD);

            audit(registryWithDefaultBucket(new PfxOptions())).auditDefaultTrustSource();

            assertAll("the password IS set here, so its absence is a measurement rather than a vacuous pass",
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO, STORE_PASSWORD),
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, STORE_PASSWORD));
        }
    }

    /**
     * @param registry the registry the audit reads its {@code <default>} bucket from
     * @return the audit under test, constructed directly rather than injected, so each case controls
     *         the registry it sees
     */
    private static DefaultTrustSourceAudit audit(TlsConfigurationRegistry registry) {
        return new DefaultTrustSourceAudit(registry);
    }

    /**
     * @return a registry holding no default bucket at all — one of the two shapes "no operator trust
     *         material" takes
     */
    private static TlsConfigurationRegistry registryWithoutDefaultBucket() {
        return registryOver(null);
    }

    /**
     * @param trustOptions the bucket's trust material, {@code null} for a bucket registered without
     *                     any — the other shape "no operator trust material" takes
     * @return a registry whose default bucket carries exactly that material
     */
    private static TlsConfigurationRegistry registryWithDefaultBucket(TrustOptions trustOptions) {
        return registryOver(new BaseTlsConfiguration() {
            @Override
            public TrustOptions getTrustStoreOptions() {
                return trustOptions;
            }
        });
    }

    /**
     * A registry stub answering exactly the one lookup the audit performs.
     *
     * @param defaultBucket the configuration returned as the default bucket, {@code null} when none
     *                      is registered
     * @return a registry over that single answer
     */
    private static TlsConfigurationRegistry registryOver(TlsConfiguration defaultBucket) {
        return new TlsConfigurationRegistry() {

            @Override
            public Optional<TlsConfiguration> get(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<TlsConfiguration> getDefault() {
                return Optional.ofNullable(defaultBucket);
            }

            @Override
            public void register(String name, TlsConfiguration configuration) {
                throw new UnsupportedOperationException("the audit never registers");
            }
        };
    }
}
