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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
import de.cuioss.sheriff.gateway.testsupport.ConfigPropertyInjectionPoints;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.arc.Unremovable;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.common.http.TestHTTPResource;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
 * {@link TestCredentialsProvider} so the recorder can resolve it. The boot-wide test properties
 * disable the management interface; this profile enables it again, on a randomly assigned test port
 * so it cannot collide with a concurrently forked boot. The recorder then reads the provider for a
 * keystore password, builds no key options and starts the port on plain HTTP — and the audit must say
 * so. An audit that counted the password source as a certificate stayed silent on exactly this port.
 * {@link #managementInterfaceAnswersOnPlainHttp()} observes the listener itself: a cleartext request
 * to its liveness probe is served, so the warning is asserted against a port that really exists and
 * really speaks plain HTTP, not only against the audit's verdict.
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

    /**
     * The liveness probe, relative to the management root path the injected {@link #managementUrl}
     * carries. It is deliberately not absolute: the root is a build-time choice
     * ({@code quarkus.management.root-path}), so a hard-coded root here would miss the listener of a
     * build that moved it. {@link #livenessUri} appends it beneath that root.
     */
    private static final String LIVENESS_PATH = "health/live";

    /** The bound on connecting to, and being answered by, the booted management interface. */
    private static final Duration MANAGEMENT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    Event<StartupEvent> startupEvent;

    /** The base URL of the management interface this boot started, on its randomly assigned test port. */
    @TestHTTPResource(management = true)
    URL managementUrl;

    /**
     * Declares a management credentials provider and no key-bearing management setting, so the
     * management port resolves to plain HTTP with the password source configured.
     */
    public static final class CredentialsProviderOnlyProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.management.enabled", "true",
                    "quarkus.management.test-port", "0",
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
    @DisplayName("The management interface this boot started really answers on plain HTTP")
    void managementInterfaceAnswersOnPlainHttp() throws Exception {
        URI liveness = livenessUri(managementUrl);
        HttpRequest request = HttpRequest.newBuilder(liveness).timeout(MANAGEMENT_REQUEST_TIMEOUT).GET().build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(MANAGEMENT_REQUEST_TIMEOUT).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertAll("the audit's verdict must describe a listener that exists and speaks cleartext",
                () -> assertEquals("http", liveness.getScheme(), "the request is sent without TLS"),
                () -> assertEquals(200, response.statusCode(),
                        () -> "the management port must serve its liveness probe over plain HTTP: "
                                + response.body()),
                () -> assertTrue(response.body().contains("UP"),
                        () -> "the liveness document must report UP: " + response.body()));
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
     * <p>
     * The spelling cases are generated from
     * {@link DeclaredKeyMaterialKeys#MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS}: each key is driven into
     * the constructor parameter whose {@code @ConfigProperty} names it, so a spelling added to the
     * vocabulary gets its case without an edit here, and a spelling with no injection point fails.
     */
    @Nested
    @DisplayName("Declared certificate spellings — the false positive removed")
    class DeclaredCertificateSpellings {

        static Stream<String> keyBearingSpellings() {
            return DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("keyBearingSpellings")
        @DisplayName("Each key-bearing spelling alone means HTTPS, so none of them warns")
        void eachSpellingAloneMeansHttps(String key) throws Exception {
            Constructor<?> constructor =
                    ConfigPropertyInjectionPoints.injectionConstructor(ManagementPlainHttpAudit.class);
            OptionalInt index = ConfigPropertyInjectionPoints.parameterIndexOf(constructor, key);
            assertTrue(index.isPresent(), () -> "'" + key + "' is a key-bearing management spelling, but "
                    + "no audit constructor parameter is injected with it — the audit cannot see a "
                    + "certificate declared that way and would emit ApiSheriff-115 against an HTTPS port");

            Object[] arguments = {defaultBucketRegistry(keyLessConfiguration()), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), MANAGEMENT_PORT};
            arguments[index.getAsInt()] =
                    ConfigPropertyInjectionPoints.declaringValueFor(constructor.getParameters()[index.getAsInt()]);
            ManagementPlainHttpAudit audit = (ManagementPlainHttpAudit) constructor.newInstance(arguments);

            assertFalse(audit.auditManagementTls(), () -> "'" + key + "' alone puts a certificate on the "
                    + "management port, so reporting plain HTTP is the false positive this audit used to "
                    + "carry");
        }

        @Test
        @DisplayName("One spelling case per key-bearing key, each reaching its own injection point")
        void spellingCasesCoverTheWholeVocabulary() {
            Constructor<?> constructor =
                    ConfigPropertyInjectionPoints.injectionConstructor(ManagementPlainHttpAudit.class);
            Set<Integer> reached = keyBearingSpellings()
                    .map(key -> ConfigPropertyInjectionPoints.parameterIndexOf(constructor, key))
                    .filter(OptionalInt::isPresent).map(OptionalInt::getAsInt)
                    .collect(Collectors.toSet());

            assertAll("the generated cases must be as many as the vocabulary, and never none",
                    () -> assertFalse(DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS.isEmpty(),
                            "an empty vocabulary would generate no case at all"),
                    () -> assertEquals(DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS.size(),
                            keyBearingSpellings().count(), "one generated case per key-bearing key"),
                    () -> assertEquals(DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS.size(),
                            reached.size(), "each key must reach a distinct audit parameter: " + reached));
        }

        @Test
        @DisplayName("With no spelling declared the port really is plain HTTP — the positive control")
        void noSpellingDeclaredMeansPlainHttp() {
            assertTrue(auditWith(Optional.empty(), Optional.empty(), Optional.empty())
                            .auditManagementTls(),
                    "without this control the spelling cases above would pass equally against an "
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

    /**
     * The liveness URI beneath the injected management root, joined on exactly one {@code /}.
     * <p>
     * {@link URI#resolve(String)} with the relative {@link #LIVENESS_PATH} alone is not enough: RFC
     * 3986 resolution drops the last segment of a base path that does not end in {@code /}, so a root
     * of {@code http://host:port/q} would resolve to {@code /health/live} and miss the listener. The
     * root path is therefore given exactly one trailing {@code /} first, so the composition is correct
     * whether or not the injected URL carries one. (This boot injects {@code http://localhost:{port}/q/},
     * with the slash; dropping the root from the composition turns the liveness test red.)
     *
     * @param managementRoot the management interface's base URL, root path included
     * @return the liveness probe's absolute URI
     * @throws URISyntaxException if the injected URL is not a valid URI
     */
    private static URI livenessUri(URL managementRoot) throws URISyntaxException {
        URI root = managementRoot.toURI();
        String rootPath = root.getPath();
        String rootDirectory = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        return root.resolve(rootDirectory + LIVENESS_PATH);
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
