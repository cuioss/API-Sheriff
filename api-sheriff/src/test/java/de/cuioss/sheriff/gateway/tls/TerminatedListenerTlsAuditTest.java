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

import java.util.Map;
import java.util.Optional;


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
import org.junit.jupiter.api.Test;

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
 * leaves the listener with nothing to terminate with. Deliberately, it does <em>not</em> clear the
 * certificate keys: leaving real certificate paths in the configuration is what keeps
 * {@link #emitsNoStorePath()} non-vacuous, since a record that leaked a store path would have a path
 * available to leak. The profile must also set {@code quarkus.http.insecure-requests=enabled},
 * because {@code VertxHttpRecorder.initializeMainHttpServer} refuses to start a key-material-less
 * listener under any other exposure strategy; that boot refusal is a separate deliverable's subject,
 * and this test steps around it rather than depending on it.
 */
@QuarkusTest
@TestProfile(TerminatedListenerTlsAuditTest.KeyLessMainListenerProfile.class)
@EnableTestLogger
@DisplayName("Terminated main-listener TLS audit")
class TerminatedListenerTlsAuditTest {

    private static final String NAMED_BUCKET = "named-server-tls";

    @Inject
    Event<StartupEvent> startupEvent;

    /**
     * Points the main listener at the shipped key-less TLS bucket so it really resolves to plain
     * HTTP, while leaving the shipped certificate keys in place.
     */
    public static final class KeyLessMainListenerProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.tls-configuration-name", "plain-management",
                    "quarkus.http.insecure-requests", "enabled");
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

    @Test
    @DisplayName("Names the port and the topology only — never the configured store path")
    void emitsNoStorePath() {
        startupEvent.fire(new StartupEvent());

        assertAll("the boot has real certificate paths configured, so this absence is a measurement",
                () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "localhost.crt"),
                () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "localhost.key"));
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
