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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.token.quarkus.config.IssuerConfigResolver;
import de.cuioss.sheriff.token.quarkus.producer.TokenValidatorStartupInitializer;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ExtensionIssuerNamespaceGate}: a key of the token-validation extension's own issuer
 * namespace refuses the boot with {@code ApiSheriff-203}, in its property spelling and in its
 * environment spelling, and a configuration without such a key is accepted.
 * <p>
 * <strong>What is proven here, and what is not.</strong> The refusal cases construct the gate over a
 * hand-built {@link Config}, because the boot configuration of this test run must carry no such key —
 * {@link DefaultProfileReadinessTest} and {@link ExtensionUnqualifiedBeanExclusionTest} assert exactly
 * that. The real-startup-event case proves the observer is registered and reached through the
 * container's own event bus on that effective configuration, and the ordering case pins the observer
 * ahead of the extension's startup initializer and of the scheduler that drives the extension's metrics
 * collector, read from the container's resolved observer set rather than from the annotation alone. No
 * in-process start-failure harness is on the test classpath: that a refused start of the real binary
 * never becomes ready is proven on the native image by {@code ExtensionIssuerNamespaceRefusalIT}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@EnableTestLogger(debug = ExtensionIssuerNamespaceGate.class)
@DisplayName("Extension issuer-namespace gate — sheriff.token.issuers.* refuses the boot")
@Tag("quarkus-fork")
class ExtensionIssuerNamespaceGateTest {

    private static final String RECORD =
            ConfigLogMessages.ERROR.EXTENSION_ISSUER_NAMESPACE_CONFIGURED.resolveIdentifierString();

    private static final String DOTTED_ISSUER_KEY = "sheriff.token.issuers.default.enabled";

    private static final String ENVIRONMENT_ISSUER_KEY = "SHERIFF_TOKEN_ISSUERS_DEFAULT_ENABLED";

    /** The DEBUG line the gate logs when the namespace is empty — its trace on the startup path. */
    private static final String ACCEPTED_TRACE = "No sheriff.token.issuers.* key configured";

    @Inject
    Event<StartupEvent> startupEvent;

    @Inject
    BeanManager beanManager;

    private static Config configOf(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "gate-fixture", 300))
                .build();
    }

    @Test
    @DisplayName("Refuses a configuration carrying one sheriff.token.issuers. key")
    void refusesADottedIssuerKey() {
        // Arrange
        ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(
                configOf(Map.of(DOTTED_ISSUER_KEY, "true")));

        // Act
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                gate::refuseConfiguredExtensionIssuers,
                "a sheriff.token.issuers. key makes the extension build its own validator, so it must refuse");

        // Assert
        assertTrue(refusal.getMessage().contains(DOTTED_ISSUER_KEY),
                "the refusal names the offending key: " + refusal.getMessage());
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.ERROR, RECORD);
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, DOTTED_ISSUER_KEY);
    }

    @Test
    @DisplayName("Refuses the environment spelling SHERIFF_TOKEN_ISSUERS_* through the environment source")
    void refusesTheEnvironmentSpelling() {
        // Arrange — the shape a deployment produces: one variable, read through the environment source.
        Config environment = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(Map.of(ENVIRONMENT_ISSUER_KEY, "true"), 300))
                .build();
        ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(environment);

        // Act + Assert
        assertThrows(IllegalStateException.class, gate::refuseConfiguredExtensionIssuers,
                "an issuer enabled only through the environment is discovered by the extension too");
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.ERROR, RECORD);
    }

    @Test
    @DisplayName("Refuses a key carried only in its environment spelling, upper or lower case")
    void refusesTheEnvironmentSpellingAlone() {
        // Arrange — a source that publishes the raw environment name and nothing else, so only the
        // environment-prefix match can catch it; the dotted-prefix match cannot.
        ExtensionIssuerNamespaceGate upperCase = new ExtensionIssuerNamespaceGate(
                configOf(Map.of(ENVIRONMENT_ISSUER_KEY, "true")));
        ExtensionIssuerNamespaceGate lowerCase = new ExtensionIssuerNamespaceGate(
                configOf(Map.of("sheriff_token_issuers_default_enabled", "true")));

        // Act + Assert
        assertAll(
                () -> assertThrows(IllegalStateException.class, upperCase::refuseConfiguredExtensionIssuers,
                        "the upper-case environment spelling is refused"),
                () -> assertThrows(IllegalStateException.class, lowerCase::refuseConfiguredExtensionIssuers,
                        "the environment source resolves a lower-case variable too, so it is refused"));
    }

    @Test
    @DisplayName("Accepts a configuration without either spelling (matched control)")
    void acceptsAConfigurationWithoutTheNamespace() {
        // Arrange — near misses on both spellings: the extension's other namespaces and look-alike prefixes.
        ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(configOf(Map.of(
                "sheriff.token.metrics.collection.interval", "10s",
                "sheriff.token.keycloak.default-roles-mapper.enabled", "false",
                "sheriff.token.issuer.default.enabled", "true",
                "SHERIFF_TOKEN_KEYCLOAK_DEFAULT_GROUPS_MAPPER_ENABLED", "false",
                "gateway.sheriff.token.issuers.default.enabled", "true")));

        // Act + Assert
        assertDoesNotThrow(gate::refuseConfiguredExtensionIssuers,
                "without a key in the namespace the extension builds nothing, so the boot proceeds");
        LogAsserts.assertNoLogMessagePresent(TestLogLevel.ERROR, ExtensionIssuerNamespaceGate.class);
    }

    @Test
    @DisplayName("Renders an offending key escaped, so it cannot forge a log line")
    void rendersTheOffendingKeyEscaped() {
        // Arrange
        ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(
                configOf(Map.of("sheriff.token.issuers.forged\nApiSheriff-2: ok.enabled", "true")));

        // Act
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                gate::refuseConfiguredExtensionIssuers);

        // Assert
        assertTrue(refusal.getMessage().contains("forged\\u000AApiSheriff-2"),
                "the line feed is rendered as an escape: " + refusal.getMessage());
        assertFalse(refusal.getMessage().contains("\n"),
                "no raw line terminator reaches the refusal: " + refusal.getMessage());
    }

    @Test
    @DisplayName("Never logs the configured value of an offending key")
    void neverLogsTheConfiguredValue() {
        // Arrange — an issuer key can carry a secret; only the key name may be echoed.
        String secret = "s3cr3t-client-value";
        ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(
                configOf(Map.of("sheriff.token.issuers.default.client-secret", secret)));

        // Act
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                gate::refuseConfiguredExtensionIssuers);

        // Assert
        assertFalse(refusal.getMessage().contains(secret), "the refusal carries the key, never its value");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, "sheriff.token.issuers.default.client-secret");
        assertTrue(TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.ERROR, secret).isEmpty(),
                "no ERROR record carries the configured value");
    }

    @Test
    @DisplayName("Is reached through the real startup-event path and accepts the effective configuration")
    void acceptsTheEffectiveConfigurationThroughTheRealStartupEventPath() {
        // Act — delivered by the container's event bus, not by a direct call, so a lazy proxy that never
        // ran the observer could not pass.
        assertDoesNotThrow(() -> startupEvent.fire(new StartupEvent()),
                "the shipped configuration carries no sheriff.token.issuers.* key, so the gate accepts it");

        // Assert — the gate's own trace proves it ran, and it refused nothing.
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG, ACCEPTED_TRACE);
        LogAsserts.assertNoLogMessagePresent(TestLogLevel.ERROR, ExtensionIssuerNamespaceGate.class);
    }

    @Test
    @DisplayName("Observes StartupEvent ahead of the extension's initializer and of the scheduler")
    void observesStartupAheadOfTheExtensionAndTheScheduler() {
        // Arrange — the container's resolved StartupEvent observers, with the priority each is notified at.
        Set<ObserverMethod<? super StartupEvent>> observers = beanManager.resolveObserverMethods(new StartupEvent());
        int gatePriority = priorityOf(observers, ExtensionIssuerNamespaceGate.class.getName());
        List<Integer> extensionPriorities = prioritiesOfPackage(observers, "de.cuioss.sheriff.token.quarkus.");
        List<Integer> schedulerPriorities = prioritiesOfPackage(observers, "io.quarkus.scheduler.");

        // Assert — the controls first: both populations the ordering is measured against exist, the
        // extension's initializer among them, so the comparison below cannot pass vacuously.
        assertFalse(extensionPriorities.isEmpty(), "control: the extension observes StartupEvent; observers: "
                + describe(observers));
        assertTrue(hasObserverOf(observers, TokenValidatorStartupInitializer.class.getName()),
                "control: the extension's startup initializer is among the resolved observers");
        assertFalse(schedulerPriorities.isEmpty(), "control: the scheduler that drives the extension's "
                + "metrics collector observes StartupEvent; observers: " + describe(observers));
        assertEquals(ExtensionIssuerNamespaceGate.OBSERVER_PRIORITY, gatePriority,
                "the container notifies the gate at its declared priority");
        assertTrue(extensionPriorities.stream().allMatch(priority -> gatePriority < priority),
                "the gate must be notified before every extension observer; gate=" + gatePriority
                        + ", extension=" + extensionPriorities);
        assertTrue(schedulerPriorities.stream().allMatch(priority -> gatePriority < priority),
                "the gate must be notified before the scheduler starts; gate=" + gatePriority
                        + ", scheduler=" + schedulerPriorities);
    }

    /**
     * Profile-prefixed and default-value keys: the gate must refuse exactly the configurations the
     * extension acts on, including a key the enumeration could only miss through its profile spelling or
     * through the default-value source a build-time-recorded key lands in.
     * <p>
     * The oracle is the extension's own enablement probe, {@link IssuerConfigResolver#hasEnabledIssuers},
     * run over the same hand-built {@link Config} the gate reads. Each configuration carries the default
     * interceptors and an active profile, as the Quarkus runtime configuration does, so a key spelled
     * for that profile resolves exactly as it would at boot. The expected verdicts are stated as well as
     * compared, so a probe that had stopped discovering anything could not make the agreement vacuous.
     */
    @Nested
    @DisplayName("Profile-prefixed and default-value keys")
    class ProfilePrefixedKeys {

        private static final String ACTIVE_PROFILE = "prod";

        private Config profiled(org.eclipse.microprofile.config.spi.ConfigSource source) {
            return new SmallRyeConfigBuilder()
                    .addDefaultInterceptors()
                    .withProfile(ACTIVE_PROFILE)
                    .withSources(source)
                    .build();
        }

        @Test
        @DisplayName("Refuses a property key spelled for the active profile, which the extension acts on")
        void refusesTheActiveProfilePropertySpelling() {
            Config config = profiled(new PropertiesConfigSource(
                    Map.of("%" + ACTIVE_PROFILE + "." + DOTTED_ISSUER_KEY, "true"), "gate-fixture", 300));
            ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(config);

            assertTrue(IssuerConfigResolver.hasEnabledIssuers(config),
                    "control: the extension discovers an issuer spelled for the active profile");
            assertThrows(IllegalStateException.class, gate::refuseConfiguredExtensionIssuers,
                    "a key the extension acts on must refuse the boot, whatever its profile spelling");
        }

        @Test
        @DisplayName("Refuses an environment variable spelled for the active profile, which the extension acts on")
        void refusesTheActiveProfileEnvironmentSpelling() {
            Config config = profiled(new EnvConfigSource(
                    Map.of("_" + ACTIVE_PROFILE.toUpperCase(java.util.Locale.ROOT) + "_" + ENVIRONMENT_ISSUER_KEY,
                            "true"), 300));
            ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(config);

            assertAll(
                    () -> assertTrue(IssuerConfigResolver.hasEnabledIssuers(config),
                            "control: the extension discovers an issuer whose environment variable is spelled "
                                    + "for the active profile"),
                    () -> assertTrue(refuses(gate),
                            "a key the extension acts on must refuse the boot, whatever its profile spelling"));
        }

        @Test
        @DisplayName("Refuses a key carried only as a default value, the shape a build-time-recorded key takes")
        void refusesAKeyCarriedOnlyAsADefaultValue() {
            Config config = new SmallRyeConfigBuilder()
                    .addDefaultInterceptors()
                    .withProfile(ACTIVE_PROFILE)
                    .withDefaultValue(DOTTED_ISSUER_KEY, "true")
                    .build();
            ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(config);

            assertAll(
                    () -> assertTrue(IssuerConfigResolver.hasEnabledIssuers(config),
                            "control: the extension discovers an issuer carried only as a default value"),
                    () -> assertTrue(refuses(gate),
                            "a key the extension acts on must refuse the boot, whichever source carries it"));
        }

        @Test
        @DisplayName("Accepts a key spelled for an inactive profile, which the extension ignores (matched control)")
        void acceptsAnInactiveProfileSpelling() {
            Config config = profiled(new PropertiesConfigSource(
                    Map.of("%dev." + DOTTED_ISSUER_KEY, "true"), "gate-fixture", 300));
            ExtensionIssuerNamespaceGate gate = new ExtensionIssuerNamespaceGate(config);

            assertFalse(IssuerConfigResolver.hasEnabledIssuers(config),
                    "control: a key for an inactive profile enables nothing in the extension");
            assertDoesNotThrow(gate::refuseConfiguredExtensionIssuers,
                    "a key no active profile reads makes the extension build nothing, so the boot proceeds");
        }

        private boolean refuses(ExtensionIssuerNamespaceGate gate) {
            try {
                gate.refuseConfiguredExtensionIssuers();
                return false;
            } catch (IllegalStateException refused) {
                return true;
            }
        }
    }

    /**
     * The namespace predicate on its own: the two spellings it must catch, and the near misses it must
     * leave alone.
     */
    @Nested
    @DisplayName("Namespace predicate")
    class NamespacePredicate {

        @ParameterizedTest
        @ValueSource(strings = {DOTTED_ISSUER_KEY, "sheriff.token.issuers.keycloak.jwks.http.url",
                ENVIRONMENT_ISSUER_KEY, "sheriff_token_issuers_default_enabled",
                "SHERIFF_TOKEN_ISSUERS_KEYCLOAK_DPOP_ENABLED"})
        @DisplayName("Matches each spelling of a namespace key")
        void matchesNamespaceKeys(String key) {
            assertTrue(ExtensionIssuerNamespaceGate.inExtensionIssuerNamespace(key), key);
        }

        @ParameterizedTest
        @ValueSource(strings = {"sheriff.token.issuers", "sheriff.token.issuer.default.enabled",
                "sheriff.token.metrics.collection.interval", "SHERIFF_TOKEN_ISSUER_DEFAULT_ENABLED",
                "gateway.sheriff.token.issuers.default.enabled", "quarkus.http.port"})
        @DisplayName("Leaves near misses and unrelated keys alone (matched control)")
        void leavesOtherKeysAlone(String key) {
            assertFalse(ExtensionIssuerNamespaceGate.inExtensionIssuerNamespace(key), key);
        }
    }

    private static int priorityOf(Set<ObserverMethod<? super StartupEvent>> observers, String beanClassName) {
        return observers.stream()
                .filter(observer -> observer.getBeanClass().getName().equals(beanClassName))
                .mapToInt(ObserverMethod::getPriority)
                .findFirst()
                .orElseThrow(() -> new AssertionError(beanClassName + " observes no StartupEvent; observers: "
                        + describe(observers)));
    }

    private static List<Integer> prioritiesOfPackage(Set<ObserverMethod<? super StartupEvent>> observers,
            String packagePrefix) {
        return observers.stream()
                .filter(observer -> observer.getBeanClass().getName().startsWith(packagePrefix))
                .map(ObserverMethod::getPriority)
                .toList();
    }

    private static boolean hasObserverOf(Set<ObserverMethod<? super StartupEvent>> observers, String beanClassName) {
        return observers.stream().anyMatch(observer -> observer.getBeanClass().getName().equals(beanClassName));
    }

    private static List<String> describe(Set<ObserverMethod<? super StartupEvent>> observers) {
        return observers.stream()
                .map(observer -> observer.getBeanClass().getName() + "@" + observer.getPriority())
                .sorted()
                .toList();
    }
}
