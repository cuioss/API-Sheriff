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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.arc.Unremovable;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ManagementPlainHttpAudit}.
 * <p>
 * The load-bearing test here is {@link #credentialsProviderAloneWarnsThroughTheRealStartupEventPath()}:
 * it fires a {@link StartupEvent} through the container's own event bus and asserts the warning
 * appears, rather than calling the observer method directly. Lesson 2026-07-20-18-002 is precisely
 * that a normal-scoped observer bean can get a lazy CDI proxy that is never touched — the check
 * silently never runs in production while a test that invokes the method directly stays green. Only
 * delivery through the real event bus proves the observer is registered and reachable.
 * <p>
 * <strong>The boot declares a management credentials provider and nothing else.</strong> The test
 * configuration supplies the main certificate through {@code quarkus.http.ssl.certificate.*}, so the
 * default TLS registry bucket carries no key material, and no key-bearing management certificate is
 * configured. {@link CredentialsProviderOnlyProfile} adds
 * {@code quarkus.management.ssl.certificate.credentials-provider}, backed by
 * {@link TestCredentialsProvider} so the recorder can resolve it. The recorder then reads the
 * provider for a keystore password, builds no key options and starts the port on plain HTTP — and the
 * audit must say so. An audit that counted the password source as a certificate stayed silent on
 * exactly this port.
 * <p>
 * <strong>The default-bucket regression.</strong> {@link #defaultBucketWithKeyMaterialIsNotPlainHttp()}
 * pins the false positive this audit used to carry: it was built on {@code TlsConfiguration.from},
 * which never consults the registry's default bucket, while the recorder does — so a populated
 * default {@code quarkus.tls.key-store.*} bucket produced a management listener on HTTPS that this
 * audit reported as plain HTTP. The audit is constructed directly there rather than booted, because
 * the regression is about which registry lookups the audit performs and a stub registry is the only
 * way to present a populated default bucket without adding key material to the shipped test boot —
 * which {@code application.properties} forbids by name. The startup-event test above remains the
 * proof that the observer path itself works.
 */
@QuarkusTest
@TestProfile(ManagementPlainHttpAuditTest.CredentialsProviderOnlyProfile.class)
@EnableTestLogger
@DisplayName("Management plain-HTTP audit")
@Tag("quarkus-fork")
class ManagementPlainHttpAuditTest {

    private static final int MANAGEMENT_PORT = 9000;

    /** The bean name the profile points the management credentials provider at. */
    static final String TEST_CREDENTIALS_PROVIDER = "sheriff-test-credentials";

    @Inject
    Event<StartupEvent> startupEvent;

    /**
     * Declares a management credentials provider and no key-bearing management setting, so the
     * management port resolves to plain HTTP with the password source configured.
     */
    public static final class CredentialsProviderOnlyProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_CREDENTIALS_PROVIDER, "management-keystore",
                    DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_PREFIX + "credentials-provider-name",
                    TEST_CREDENTIALS_PROVIDER);
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TestCredentialsProvider.class);
        }
    }

    /**
     * The credentials provider the profile names. It answers with no credentials at all: the
     * recorder only reads a keystore password from it, and there is no keystore to unlock. Being an
     * alternative, it exists only in the boot whose profile enables it.
     */
    @Alternative
    @Unremovable
    @ApplicationScoped
    @Named(TEST_CREDENTIALS_PROVIDER)
    public static class TestCredentialsProvider implements CredentialsProvider {

        @Override
        public Map<String, String> getCredentials(String credentialsProviderName) {
            return Map.of();
        }
    }

    @Test
    @DisplayName("The credentials provider alone warns, through the real startup-event path")
    void credentialsProviderAloneWarnsThroughTheRealStartupEventPath() {
        startupEvent.fire(new StartupEvent());

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                "Management interface is serving PLAIN HTTP on port");
    }

    @Test
    @DisplayName("A default bucket carrying key material is not reported as plain HTTP")
    void defaultBucketWithKeyMaterialIsNotPlainHttp() {
        ManagementPlainHttpAudit audit = auditOver(defaultBucketRegistry(keyBearingConfiguration()),
                Optional.empty());

        assertFalse(audit.auditManagementTls(),
                "the recorder takes the default registry bucket whenever it carries key material, so "
                        + "the listener is on HTTPS and reporting plain HTTP is a false positive");
    }

    @Test
    @DisplayName("A key-less default bucket with no management certificate still means plain HTTP")
    void keyLessDefaultBucketStillMeansPlainHttp() {
        ManagementPlainHttpAudit audit = auditOver(defaultBucketRegistry(keyLessConfiguration()),
                Optional.empty());

        assertTrue(audit.auditManagementTls(),
                "the positive control for the case above — a default bucket without key material is "
                        + "not a fallback the recorder takes, so nothing terminates the port");
    }

    @Test
    @DisplayName("A key-less default bucket with a management certificate means HTTPS")
    void keyLessDefaultBucketWithCertificateMeansHttps() {
        ManagementPlainHttpAudit audit = auditOver(defaultBucketRegistry(keyLessConfiguration()),
                Optional.of(List.of("management.crt")));

        assertFalse(audit.auditManagementTls(),
                "quarkus.management.ssl.certificate.* alone still produces HTTPS — the shipped "
                        + "posture for a TLS-terminated management port, which must not warn");
    }

    /**
     * The management leg of the certificate-spelling false positive.
     * <p>
     * The audit injected {@code quarkus.management.ssl.certificate.files} alone, while the
     * management recorder falls through to the same legacy block as the main listener and builds key
     * options from two further spellings. A management interface whose certificate arrives as a
     * keystore file, or as a PEM key without a chain file, was therefore reported as plain HTTP —
     * {@code ApiSheriff-115} against a properly terminated port. The password-only credentials
     * provider is not among them; the boot-level test above is its case.
     */
    @Nested
    @DisplayName("Declared certificate spellings — the false positive removed")
    class DeclaredCertificateSpellings {

        @Test
        @DisplayName("Each of the three key-bearing spellings alone means HTTPS, so none of them warns")
        void eachSpellingAloneMeansHttps() {
            assertAll("the management block bears key material through the main listener's spellings",
                    () -> assertFalse(auditWith(Optional.of(List.of("management.crt")),
                                    Optional.empty(), Optional.empty())
                                    .auditManagementTls(),
                            "quarkus.management.ssl.certificate.files — the spelling that already "
                                    + "worked"),
                    () -> assertFalse(auditWith(Optional.empty(),
                                    Optional.of(List.of("management.key")), Optional.empty())
                                    .auditManagementTls(),
                            "quarkus.management.ssl.certificate.key-files alone"),
                    () -> assertFalse(auditWith(Optional.empty(), Optional.empty(),
                                    Optional.of("/etc/certs/management.p12"))
                                    .auditManagementTls(),
                            "quarkus.management.ssl.certificate.key-store-file: a keystore carries "
                                    + "chain AND key, so such a deployment declares no .files at all "
                                    + "— this is the reported false positive, and the matched control "
                                    + "for the credentials-provider boot above"));
        }

        @Test
        @DisplayName("With no spelling declared the port really is plain HTTP — the positive control")
        void noSpellingDeclaredMeansPlainHttp() {
            assertTrue(auditWith(Optional.empty(), Optional.empty(), Optional.empty())
                            .auditManagementTls(),
                    "without this control the three assertions above would pass equally against an "
                            + "audit that had simply stopped warning altogether");
        }

        @Test
        @DisplayName("A blank or empty value is no declaration, so it still means plain HTTP")
        void blankValuesAreNoDeclaration() {
            assertTrue(auditWith(Optional.of(List.of()), Optional.of(List.of()), Optional.of(""))
                            .auditManagementTls(),
                    "an empty list and a key cleared to the empty string are both the shape a "
                            + "compose file's bare VAR= produces, and neither supplies material");
        }

        private ManagementPlainHttpAudit auditWith(Optional<List<String>> certificateFiles,
                Optional<List<String>> certificateKeyFiles,
                Optional<String> certificateKeyStoreFile) {
            return new ManagementPlainHttpAudit(defaultBucketRegistry(keyLessConfiguration()),
                    Optional.empty(), certificateFiles, certificateKeyFiles, certificateKeyStoreFile,
                    MANAGEMENT_PORT);
        }
    }

    private static ManagementPlainHttpAudit auditOver(TlsConfigurationRegistry registry,
            Optional<List<String>> certificateFiles) {
        return new ManagementPlainHttpAudit(registry, Optional.empty(), certificateFiles,
                Optional.empty(), Optional.empty(), MANAGEMENT_PORT);
    }

    /**
     * A registry holding only a default bucket — the shape the F1 regression turns on, since the
     * audit selects no configuration name.
     *
     * @param fallback the configuration returned as the default bucket
     * @return a registry over that single answer
     */
    private static TlsConfigurationRegistry defaultBucketRegistry(TlsConfiguration fallback) {
        return new TlsConfigurationRegistry() {

            @Override
            public Optional<TlsConfiguration> get(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<TlsConfiguration> getDefault() {
                return Optional.of(fallback);
            }

            @Override
            public void register(String name, TlsConfiguration configuration) {
                throw new UnsupportedOperationException("the audit never registers");
            }
        };
    }

    private static TlsConfiguration keyLessConfiguration() {
        return new BaseTlsConfiguration() {
        };
    }

    private static TlsConfiguration keyBearingConfiguration() {
        return new BaseTlsConfiguration() {
            @Override
            public KeyCertOptions getKeyStoreOptions() {
                return new PemKeyCertOptions();
            }
        };
    }
}
