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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.testsupport.ConfigPropertyInjectionPoints;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link TerminatedListenerTlsAudit} and the {@link ResolvedServerTlsMaterial}
 * discriminator it shares with {@link ManagementPlainHttpAudit}.
 * <p>
 * The load-bearing test here is {@link #warnsThroughTheRealStartupEventPath()}: it fires a
 * {@link StartupEvent} through the container's own event bus and asserts the warning appears, rather
 * than calling the observer method directly. Lesson 2026-07-20-18-002 is precisely that a
 * normal-scoped observer bean can get a lazy CDI proxy that is never touched — the check silently
 * never runs in production while a test that invokes the method directly stays green. Only delivery
 * through the real event bus proves the observer is registered and reachable.
 * <p>
 * <strong>How the profile reaches the plain-HTTP state, and why that shape was chosen.</strong> The
 * shipped test boot supplies a main certificate through {@code quarkus.http.ssl.certificate.*}, so
 * the terminated listener resolves to HTTPS and the audit is correctly silent. The profile selects
 * the key-less {@code plain-management} bucket for the main listener instead — the one bucket this
 * project declares with no key material by construction — which drives the resolution down leg 1 and
 * leaves the listener with nothing to terminate with. The profile must also set
 * {@code quarkus.http.insecure-requests=enabled}: that is the explicit plain-HTTP opt-in
 * ({@link ServerTlsDeclarationGate}), and without it both this boot's key-less bucket selection and
 * {@code VertxHttpRecorder.initializeMainHttpServer} refuse to start the listener at all. Declaring
 * it is this test saying, in the one key that means it, that plain HTTP is the state it wants to
 * observe.
 * <p>
 * <strong>Why the profile CLEARS the inherited certificate keys.</strong> The opt-in combined with
 * declared server key material is one of the three incoherent combinations
 * {@link ServerTlsDeclarationGate} refuses at boot, and the boot-wide
 * {@code src/test/resources/application.properties} declares exactly those keys. Inheriting them
 * here would therefore fail this container's start on a contradiction that has nothing to do with
 * what these cases observe, so the profile clears both to the blank value the gate reads as absent.
 * The record-content case that needed real paths in the configuration no longer runs against the
 * boot at all — see {@link EmittedRecordContent}, which supplies those paths to the audit directly
 * and is strictly the stronger measurement for it.
 */
@QuarkusTest
@TestProfile(TerminatedListenerTlsAuditTest.KeyLessMainListenerProfile.class)
@EnableTestLogger
@DisplayName("Terminated main-listener TLS audit")
@Tag("quarkus-fork")
class TerminatedListenerTlsAuditTest {

    private static final String NAMED_BUCKET = "named-server-tls";
    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;

    /**
     * The certificate paths the boot-wide test properties declare. {@link EmittedRecordContent}
     * hands them to the audit itself, so the record it emits really does have a path available to
     * leak.
     */
    private static final String CERTIFICATE_PATH =
            "../integration-tests/src/main/docker/certificates/localhost.crt";
    private static final String CERTIFICATE_KEY_PATH =
            "../integration-tests/src/main/docker/certificates/localhost.key";

    @Inject
    Event<StartupEvent> startupEvent;

    /**
     * The bound gateway document, taken from the live boot rather than hand-built: the audit reads
     * it only for the ADR-0017 topology fragment, which is not what these cases are about.
     */
    @Inject
    GatewayConfig gatewayConfig;

    /**
     * Points the main listener at the shipped key-less TLS bucket so it really resolves to plain
     * HTTP, and clears the certificate keys the boot-wide test properties declare so the plain-HTTP
     * opt-in is the only server-TLS posture this container declares.
     */
    public static final class KeyLessMainListenerProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.tls-configuration-name", "plain-management",
                    "quarkus.http.insecure-requests", "enabled",
                    "quarkus.http.ssl.certificate.files", "",
                    "quarkus.http.ssl.certificate.key-files", "");
        }
    }

    @Test
    @DisplayName("Warns through the real startup-event path, not by direct invocation")
    void warnsThroughTheRealStartupEventPath() {
        startupEvent.fire(new StartupEvent());

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                "Terminated main listener is serving PLAIN HTTP on port");
    }

    @Test
    @DisplayName("Reports the live ADR-0017 topology in the same record, never in a second one")
    void reportsTheLiveTopologyInTheSameRecord() {
        startupEvent.fire(new StartupEvent());

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                "Live edge topology: single terminated listener (ADR-0017 default topology)");
    }

    /**
     * What the emitted record carries, asserted against an audit that really is holding a store
     * path.
     * <p>
     * <strong>Why this case cannot be driven from the boot any more.</strong> Its whole value is
     * that the absence of a store path in the record is a MEASUREMENT rather than a tautology, which
     * needs a path to be available to leak in the first place. That used to come from the booted
     * container's own certificate keys — but declared key material alongside the plain-HTTP opt-in
     * is now one of the combinations {@link ServerTlsDeclarationGate} refuses at boot, so no
     * container can hold both the paths and the plain-HTTP state at once. Constructing the audit
     * directly restores the premise and tightens it: the paths are handed to the very object under
     * test, so what the record omits is demonstrably something that object had.
     */
    @Nested
    @DisplayName("The emitted record's content")
    class EmittedRecordContent {

        @Test
        @DisplayName("Names the port and the topology only — never the configured store path")
        void emitsNoStorePath() {
            TerminatedListenerTlsAudit audit = new TerminatedListenerTlsAudit(
                    registry(keyLessConfiguration(), null), gatewayConfig, Optional.of(NAMED_BUCKET),
                    Optional.of(List.of(CERTIFICATE_PATH)), Optional.of(List.of(CERTIFICATE_KEY_PATH)),
                    Optional.empty(), HTTP_PORT, HTTPS_PORT);

            boolean plain = audit.auditTerminatedListenerTls();

            assertAll("the audit holds real certificate paths, so this absence is a measurement",
                    () -> assertTrue(plain,
                            "the vacuity guard: a selected key-less bucket REPLACES the certificate, "
                                    + "so this audit resolves to plain HTTP and really does emit the "
                                    + "record. Without it the two absences below would pass equally "
                                    + "against an audit that emitted nothing at all"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            "Terminated main listener is serving PLAIN HTTP on port"),
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "localhost.crt"),
                    () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "localhost.key"));
        }
    }

    /**
     * The three legs of the shared discriminator, exercised directly.
     * <p>
     * Each leg gets both a positive and a negative case — a discriminator tested on one side only
     * cannot distinguish "decided correctly" from "always answers the same way".
     */
    @Nested
    @DisplayName("Resolved server TLS material — the shared discriminator")
    class Discriminator {

        @Test
        @DisplayName("Leg 1: a named bucket carrying key material means HTTPS")
        void namedBucketWithKeyMaterialMeansHttps() {
            TlsConfigurationRegistry registry = registry(keyBearingConfiguration(), null);

            assertFalse(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                            NAMED_BUCKET, false),
                    "a named bucket that carries key material terminates TLS, so the legacy "
                            + "certificate keys are never consulted");
        }

        @Test
        @DisplayName("Leg 1: a named key-less bucket means plain HTTP even with a certificate configured")
        void namedKeyLessBucketMeansPlainHttp() {
            TlsConfigurationRegistry registry = registry(keyLessConfiguration(), null);

            assertTrue(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                            NAMED_BUCKET, true),
                    "selecting a key-less bucket REPLACES the deployment's certificate rather than "
                            + "adding to it, so the listener ends up with no key material");
        }

        @Test
        @DisplayName("Leg 1: an unresolvable name reports plain HTTP rather than throwing")
        void unresolvableNameReportsPlainHttp() {
            TlsConfigurationRegistry registry = registry(null, null);

            assertTrue(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                            NAMED_BUCKET, true),
                    "the recorder's own ConfigurationException has already failed the boot before any "
                            + "startup observer runs, so the audit reports rather than throwing again");
        }

        @Test
        @DisplayName("Leg 2: a default bucket carrying key material means HTTPS with no certificate configured")
        void defaultBucketWithKeyMaterialMeansHttps() {
            TlsConfigurationRegistry registry = registry(null, keyBearingConfiguration());

            assertFalse(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry, null, false),
                    "the recorder falls back to the DEFAULT registry bucket when it carries key "
                            + "material — the leg TlsConfiguration.from omits, and the false positive "
                            + "this discriminator was extracted to remove");
        }

        @Test
        @DisplayName("Leg 2: a key-less default bucket falls through to the legacy certificate leg")
        void keyLessDefaultBucketFallsThroughToTheLegacyLeg() {
            TlsConfigurationRegistry registry = registry(null, keyLessConfiguration());

            assertAll("a default bucket without key material is not a fallback the recorder takes",
                    () -> assertFalse(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                                    null, true),
                            "the legacy certificate still terminates TLS"),
                    () -> assertTrue(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                                    null, false),
                            "with no legacy certificate either, nothing is left to terminate with"));
        }

        @Test
        @DisplayName("Leg 3: with no bucket at all the legacy certificate decides")
        void withoutAnyBucketTheCertificateDecides() {
            TlsConfigurationRegistry registry = registry(null, null);

            assertAll("the shipped posture supplies its certificate through the legacy keys",
                    () -> assertFalse(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                                    null, true),
                            "quarkus.http.ssl.certificate.* alone produces HTTPS — the shipped default "
                                    + "posture, which must not warn"),
                    () -> assertTrue(ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                                    null, false),
                            "no bucket and no certificate leaves nothing to terminate with"));
        }
    }

    /**
     * The legacy certificate leg, exercised through the audit's own injection points.
     * <p>
     * These cases pin the false positive the audit carried: it injected
     * {@code quarkus.http.ssl.certificate.files} alone, while
     * {@code TlsUtils.computeKeyStoreOptions} builds key options from two further spellings. A
     * deployment supplying a keystore file, or a PEM key without a chain file, therefore terminated
     * TLS perfectly well and was reported as a plain-HTTP downgrade — {@code ApiSheriff-121} against
     * a gateway that was doing exactly the right thing.
     * <p>
     * The password-only {@code credentials-provider} is deliberately not an injection point: on its
     * own it puts no certificate on the listener, so it must not silence {@code ApiSheriff-121}.
     * {@code DeclaredKeyMaterialKeysDriftTest} pins that the constructor no longer reads it.
     * <p>
     * The spelling cases are generated from
     * {@link DeclaredKeyMaterialKeys#HTTP_KEY_BEARING_CERTIFICATE_KEYS}, not listed here: each key is
     * driven into the constructor parameter whose {@code @ConfigProperty} names it, found the way
     * {@code DeclaredKeyMaterialKeysDriftTest} finds it, so a spelling added to the vocabulary gets
     * its case without an edit here, and a spelling with no injection point fails rather than being
     * skipped.
     * <p>
     * The audit is constructed directly here rather than booted, because the subject is which
     * <em>injected values</em> the constructor folds into its verdict; a profile could only present
     * one such deployment per boot, and three boots to assert three spellings would be paying a
     * container start for a constructor question. {@link #warnsThroughTheRealStartupEventPath()}
     * above remains the proof that the observer path itself works.
     */
    @Nested
    @DisplayName("Declared certificate spellings — the false positive removed")
    class DeclaredCertificateSpellings {

        static Stream<String> keyBearingSpellings() {
            return DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("keyBearingSpellings")
        @DisplayName("Each key-bearing spelling alone means HTTPS, so none of them warns")
        void eachSpellingAloneMeansHttps(String key) throws Exception {
            Constructor<?> constructor =
                    ConfigPropertyInjectionPoints.injectionConstructor(TerminatedListenerTlsAudit.class);
            OptionalInt index = ConfigPropertyInjectionPoints.parameterIndexOf(constructor, key);
            assertTrue(index.isPresent(), () -> "'" + key + "' is a key-bearing spelling the gate "
                    + "reads, but no audit constructor parameter is injected with it — the audit "
                    + "cannot see a certificate declared that way and would report plain HTTP");

            Object[] arguments = {registry(null, null), gatewayConfig, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), HTTP_PORT, HTTPS_PORT};
            arguments[index.getAsInt()] =
                    ConfigPropertyInjectionPoints.declaringValueFor(constructor.getParameters()[index.getAsInt()]);
            TerminatedListenerTlsAudit audit = (TerminatedListenerTlsAudit) constructor.newInstance(arguments);

            assertFalse(audit.auditTerminatedListenerTls(), () -> "'" + key + "' alone puts a "
                    + "certificate on the listener, so reporting plain HTTP is the false positive this "
                    + "audit used to carry");
        }

        @Test
        @DisplayName("One spelling case per key-bearing key, each reaching its own injection point")
        void spellingCasesCoverTheWholeVocabulary() {
            Constructor<?> constructor =
                    ConfigPropertyInjectionPoints.injectionConstructor(TerminatedListenerTlsAudit.class);
            Set<Integer> reached = keyBearingSpellings()
                    .map(key -> ConfigPropertyInjectionPoints.parameterIndexOf(constructor, key))
                    .filter(OptionalInt::isPresent).map(OptionalInt::getAsInt)
                    .collect(Collectors.toSet());

            assertAll("the generated cases must be as many as the vocabulary, and never none",
                    () -> assertFalse(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS.isEmpty(),
                            "an empty vocabulary would generate no case at all"),
                    () -> assertEquals(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS.size(),
                            keyBearingSpellings().count(), "one generated case per key-bearing key"),
                    () -> assertEquals(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS.size(),
                            reached.size(), "each key must reach a distinct audit parameter: " + reached));
        }

        @Test
        @DisplayName("With no spelling declared the listener really is plain HTTP — the positive control")
        void noSpellingDeclaredMeansPlainHttp() {
            assertTrue(auditWith(Optional.empty(), Optional.empty(), Optional.empty())
                            .auditTerminatedListenerTls(),
                    "without this control the spelling cases above would pass equally against an "
                            + "audit that had simply stopped warning altogether");
        }

        @Test
        @DisplayName("A blank or empty value is no declaration, so it still means plain HTTP")
        void blankValuesAreNoDeclaration() {
            assertTrue(auditWith(Optional.of(List.of()), Optional.of(List.of()), Optional.of(""))
                            .auditTerminatedListenerTls(),
                    "an empty list and a key cleared to the empty string are both the shape a "
                            + "compose file's bare VAR= produces; neither supplies material, and "
                            + "reading them as declarations would silence the warning for a gateway "
                            + "that really is serving cleartext");
        }

        private TerminatedListenerTlsAudit auditWith(Optional<List<String>> certificateFiles,
                Optional<List<String>> certificateKeyFiles,
                Optional<String> certificateKeyStoreFile) {
            return new TerminatedListenerTlsAudit(registry(null, null), gatewayConfig,
                    Optional.empty(), certificateFiles, certificateKeyFiles, certificateKeyStoreFile,
                    HTTP_PORT, HTTPS_PORT);
        }
    }

    /**
     * A registry stub answering exactly the two lookups the discriminator performs.
     *
     * @param named    the configuration returned for any name, {@code null} when the registry holds
     *                 no such bucket
     * @param fallback the configuration returned as the default bucket, {@code null} when none is
     *                 registered
     * @return a registry over those two answers
     */
    static TlsConfigurationRegistry registry(TlsConfiguration named, TlsConfiguration fallback) {
        return new TlsConfigurationRegistry() {

            @Override
            public Optional<TlsConfiguration> get(String name) {
                return Optional.ofNullable(named);
            }

            @Override
            public Optional<TlsConfiguration> getDefault() {
                return Optional.ofNullable(fallback);
            }

            @Override
            public void register(String name, TlsConfiguration configuration) {
                throw new UnsupportedOperationException("the discriminator never registers");
            }
        };
    }

    /**
     * @return a TLS configuration carrying no key material, the shape a bucket declared without a
     *         key store resolves to
     */
    static TlsConfiguration keyLessConfiguration() {
        return new BaseTlsConfiguration() {
        };
    }

    /**
     * @return a TLS configuration carrying key material, the shape a populated bucket resolves to
     */
    static TlsConfiguration keyBearingConfiguration() {
        return new BaseTlsConfiguration() {
            @Override
            public KeyCertOptions getKeyStoreOptions() {
                return new PemKeyCertOptions();
            }
        };
    }
}
