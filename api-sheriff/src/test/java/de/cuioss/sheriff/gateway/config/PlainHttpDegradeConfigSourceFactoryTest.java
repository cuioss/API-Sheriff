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
package de.cuioss.sheriff.gateway.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PlainHttpDegradeConfigSourceFactory}: the declared-key-material predicate over all
 * three supply routes, the ordinal placement that lets the degrade beat the shipped default while
 * still losing to the deployment, and the boot the seam exists for — a gateway with no certificate at
 * all coming up on plain HTTP instead of refusing to start.
 * <p>
 * <strong>Every predicate assertion is made through the real config-resolution path.</strong> The
 * question that matters is not what the factory returns in isolation but what an assembled
 * {@link Config} resolves for {@code quarkus.http.insecure-requests} after ordinal arbitration — that
 * is the only question Quarkus ever asks this source. Each case therefore builds a
 * {@code SmallRyeConfig} carrying the factory and reads the value back through the {@link Config}
 * API, exactly as {@code NeutralTlsConfigSourceTest} does for its own source.
 * <p>
 * <strong>The shipped default is a matched control, not scenery.</strong> A "contributes nothing"
 * assertion made against an otherwise-empty configuration would prove nothing: the key would be
 * absent whether or not the source declined to project it. Every no-degrade case therefore runs
 * against {@code quarkus.http.insecure-requests=redirect} supplied at the ordinal
 * {@code application.properties} really has, so "the shipped default survives" is a positive
 * observation of the surviving value <em>and</em> its source name, rather than the absence of any
 * observation at all.
 * <p>
 * <strong>Why this class is itself a {@code @QuarkusTest}.</strong> The unit-level cases above settle
 * what the source projects; they cannot settle that projecting it is enough to boot. The profile
 * clears the certificate keys the test {@code application.properties} supplies, so the whole class
 * boots in exactly the state that throws
 * {@code IllegalStateException("Cannot set quarkus.http.insecure-requests without enabling SSL.")}
 * without this seam. The boot happening at all is therefore the load-bearing measurement, and
 * {@link #degradeSuppliedTheExposureStrategyInTheRealBoot()} pins <em>which</em> source supplied the
 * exposure so a future deployment-supplied override could not pass as the degrade.
 */
@QuarkusTest
@TestProfile(PlainHttpDegradeConfigSourceFactoryTest.NoCertificateProfile.class)
@EnableTestLogger
@DisplayName("No-certificate plain-HTTP degrade")
class PlainHttpDegradeConfigSourceFactoryTest {

    private static final String INSECURE_REQUESTS = PlainHttpDegradeConfigSourceFactory.INSECURE_REQUESTS;
    private static final String SOURCE_NAME = PlainHttpDegradeConfigSourceFactory.SOURCE_NAME;
    private static final String DEGRADED_VALUE = PlainHttpDegradeConfigSourceFactory.DEGRADED_VALUE;

    /** The value {@code application.properties} ships, and the value that must survive untouched. */
    private static final String SHIPPED_DEFAULT = "redirect";

    /** The ordinal SmallRye gives {@code application.properties}. */
    private static final int APPLICATION_PROPERTIES_ORDINAL = 250;

    /** The ordinal SmallRye gives the environment-variable source — the precedence being contested. */
    private static final int ENVIRONMENT_ORDINAL = 300;

    /** Route 1 — the deployment selects a named TLS registry bucket for the main listener. */
    private static final String TLS_CONFIGURATION_NAME = "quarkus.http.tls-configuration-name";

    /** Route 2 — a key of the DEFAULT TLS registry bucket, in property spelling. */
    private static final String DEFAULT_BUCKET_KEY = "quarkus.tls.key-store.p12.path";

    /** Route 3 — the legacy HTTP-SSL certificate route, the one this gateway ships with. */
    private static final String CERTIFICATE_FILES = "quarkus.http.ssl.certificate.files";

    private static final String CERTIFICATE_PATH = "/etc/certs/localhost.crt";

    @Inject
    Event<StartupEvent> startupEvent;

    @Inject
    Config bootConfig;

    @TestHTTPResource("/")
    URL rootUrl;

    /**
     * Clears every certificate key the test {@code application.properties} supplies, so the boot runs
     * in genuine no-certificate mode.
     * <p>
     * A blank value rather than a removal is deliberate and is the shape a deployment actually
     * produces — {@code QUARKUS_HTTP_SSL_CERTIFICATE_FILES=} in a compose file, or a test profile
     * override. It is also the shape {@code PlainHttpDegradeConfigSourceFactory} must read as "no
     * material": treating a blank as a declaration would leave the boot refusing for a certificate
     * nobody configured.
     */
    public static final class NoCertificateProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.ssl.certificate.files", "",
                    "quarkus.http.ssl.certificate.key-files", "");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The declared-key-material predicate, resolved through an assembled Config
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("With no route declaring material the degrade supplies the exposure strategy")
    void noDeclaredMaterialEntersTheDegrade() {
        ConfigValue resolved = resolveAgainstShippedDefault(Map.of());

        assertAll("no-certificate mode",
                () -> assertEquals(DEGRADED_VALUE, resolved.getValue(),
                        "with nothing to terminate with, the boot refuses under any other exposure "
                                + "strategy — projecting enabled is what turns the refusal into a "
                                + "running gateway"),
                () -> assertEquals(SOURCE_NAME, resolved.getSourceName(),
                        "the audit distinguishes an entered degrade from an operator-chosen plain "
                                + "HTTP by source name, so the name is part of the contract"));
    }

    @ParameterizedTest
    @DisplayName("Any single route declaring material silences the degrade entirely")
    @ValueSource(strings = {
            TLS_CONFIGURATION_NAME,
            DEFAULT_BUCKET_KEY,
            "quarkus.http.ssl.certificate.files",
            "quarkus.http.ssl.certificate.key-files",
            "quarkus.http.ssl.certificate.key-store-file",
            "quarkus.http.ssl.certificate.credentials-provider"
    })
    void anySingleDeclaredRouteLeavesTheShippedDefaultStanding(String declaredKey) {
        ConfigValue resolved = resolveAgainstShippedDefault(Map.of(declaredKey, CERTIFICATE_PATH));

        assertShippedDefaultSurvived(resolved, declaredKey
                + " declares server key material, so the listener has something to terminate with and "
                + "the shipped redirect posture must stand untouched");
    }

    @Test
    @DisplayName("All three routes declared at once still contributes nothing — the predicate is a disjunction")
    void allThreeRoutesDeclaredTogetherLeaveTheShippedDefaultStanding() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put(TLS_CONFIGURATION_NAME, "named-server-tls");
        declared.put(DEFAULT_BUCKET_KEY, "/etc/certs/keystore.p12");
        declared.put(CERTIFICATE_FILES, CERTIFICATE_PATH);

        assertShippedDefaultSurvived(resolveAgainstShippedDefault(declared),
                "the predicate is a disjunction over the three routes: declaring several is still "
                        + "'material is declared', never a state that re-enables the degrade");
    }

    @Test
    @DisplayName("Two routes declared together behave as either one alone")
    void twoRoutesDeclaredTogetherLeaveTheShippedDefaultStanding() {
        assertAll("pairwise combinations",
                () -> assertShippedDefaultSurvived(
                        resolveAgainstShippedDefault(Map.of(
                                TLS_CONFIGURATION_NAME, "named-server-tls",
                                DEFAULT_BUCKET_KEY, "/etc/certs/keystore.p12")),
                        "route 1 + route 2"),
                () -> assertShippedDefaultSurvived(
                        resolveAgainstShippedDefault(Map.of(
                                TLS_CONFIGURATION_NAME, "named-server-tls",
                                CERTIFICATE_FILES, CERTIFICATE_PATH)),
                        "route 1 + route 3"),
                () -> assertShippedDefaultSurvived(
                        resolveAgainstShippedDefault(Map.of(
                                DEFAULT_BUCKET_KEY, "/etc/certs/keystore.p12",
                                CERTIFICATE_FILES, CERTIFICATE_PATH)),
                        "route 2 + route 3"));
    }

    @ParameterizedTest
    @DisplayName("A key cleared to blank is no declaration, so the degrade still enters")
    @ValueSource(strings = {TLS_CONFIGURATION_NAME, DEFAULT_BUCKET_KEY, CERTIFICATE_FILES})
    void aBlankValueIsNoDeclaration(String declaredKey) {
        ConfigValue resolved = resolveAgainstShippedDefault(Map.of(declaredKey, ""));

        assertEquals(DEGRADED_VALUE, resolved.getValue(),
                declaredKey + " cleared to the empty string supplies no key material — the shape a "
                        + "compose file's QUARKUS_HTTP_SSL_CERTIFICATE_FILES= produces. Reading it as "
                        + "a declaration would leave the boot refusing for a certificate nobody "
                        + "configured");
    }

    @Test
    @DisplayName("The default bucket is seen through its environment-variable spelling too")
    void defaultBucketIsSeenThroughItsEnvironmentSpelling() {
        ConfigValue resolved = resolveAgainstShippedDefault(
                Map.of("QUARKUS_TLS_KEY__STORE_P12_PATH", "/etc/certs/keystore.p12"));

        assertShippedDefaultSurvived(resolved,
                "route 2 is detected by enumerating names rather than by direct lookup, so the "
                        + "canonicalisation is the only thing that lets a bucket declared through the "
                        + "environment door be seen at all — without it the degrade would fire over a "
                        + "deployment that really does carry key material");
    }

    @Test
    @DisplayName("A PEM entry of the default bucket counts, whatever arbitrary name it is filed under")
    void pemEntryOfTheDefaultBucketCounts() {
        assertAll("the PEM sub-bucket is a map, so its material can only be found by enumeration",
                () -> assertShippedDefaultSurvived(resolveAgainstShippedDefault(
                        Map.of("quarkus.tls.key-store.pem.a.cert", "/etc/certs/localhost.crt")),
                        "the certificate leaf"),
                () -> assertShippedDefaultSurvived(resolveAgainstShippedDefault(
                        Map.of("quarkus.tls.key-store.pem.a.key", "/etc/certs/localhost.key")),
                        "the private-key leaf"));
    }

    @ParameterizedTest
    @DisplayName("A non-material leaf of the default bucket is not key material, defaults included")
    @ValueSource(strings = {
            "quarkus.tls.key-store.sni",
            "quarkus.tls.key-store.credentials-provider.password-key",
            "quarkus.tls.key-store.credentials-provider.alias-password-key"
    })
    void nonMaterialLeavesOfTheDefaultBucketAreNotKeyMaterial(String nonMaterialLeaf) {
        ConfigValue resolved = resolveAgainstShippedDefault(Map.of(nonMaterialLeaf, "false"));

        assertAll("the regression guard for the defect the boot test surfaced",
                () -> assertEquals(DEGRADED_VALUE, resolved.getValue(),
                        "Quarkus contributes exactly these three @WithDefault leaves of the default "
                                + "bucket to EVERY boot, whether or not a bucket is declared. A "
                                + "route-2 test that matched the bucket prefix alone therefore "
                                + "reported 'material is declared' in every deployment, silencing the "
                                + "degrade permanently and handing the no-certificate boot back the "
                                + "IllegalStateException this seam exists to remove — and it did so "
                                + "invisibly, because a hand-assembled SmallRyeConfig carries no such "
                                + "defaults. Only a material-bearing leaf may count"),
                () -> assertEquals(SOURCE_NAME, resolved.getSourceName(),
                        "and it must be the degrade that supplied it"));
    }

    @Test
    @DisplayName("A NAMED bucket is not the default bucket, so it does not count as declared material")
    void namedBucketIsNotTheDefaultBucket() {
        ConfigValue resolved = resolveAgainstShippedDefault(
                Map.of("quarkus.tls.plain-management.key-store.p12.path", "/etc/certs/keystore.p12"));

        assertAll("the negative control for the route-2 prefix test",
                () -> assertEquals(DEGRADED_VALUE, resolved.getValue(),
                        "HttpServerOptionsUtils consults the DEFAULT bucket only when no configuration "
                                + "name is selected, so a named bucket nobody selected supplies the "
                                + "main listener with nothing — a prefix test loose enough to match it "
                                + "would suppress the degrade and hand the boot back its refusal"),
                () -> assertEquals(SOURCE_NAME, resolved.getSourceName(),
                        "and it must be the degrade that supplied it"));
    }

    // ---------------------------------------------------------------------------------------------
    // The ordinal contract
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The contributed source carries ordinal 275 and projects exactly one key")
    void contributedSourceCarriesOrdinal275AndOneKey() {
        ConfigSource contributed = degradeSourceOf(configuration(Map.of()));

        assertAll("the projected source",
                () -> assertEquals(275, PlainHttpDegradeConfigSourceFactory.ORDINAL,
                        "275 is pinned by two neighbours at once: application.properties at 250, which "
                                + "it must beat, and the environment source at 300, which must beat it"),
                () -> assertEquals(PlainHttpDegradeConfigSourceFactory.ORDINAL, contributed.getOrdinal(),
                        "the declared constant is only a contract if the contributed source reports it"),
                () -> assertEquals(Set.of(INSECURE_REQUESTS), contributed.getPropertyNames(),
                        "the cardinality is pinned deliberately: this source outranks the shipped "
                                + "configuration file, so a second projected key must be a visible diff "
                                + "rather than an unnoticed widening"),
                () -> assertEquals(Map.of(INSECURE_REQUESTS, DEGRADED_VALUE), contributed.getProperties(),
                        "getProperties must agree with getPropertyNames"));
    }

    @Test
    @DisplayName("An environment-sourced quarkus.http.insecure-requests still outranks the degrade")
    void environmentSuppliedExposureStrategyStillWins() {
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PlainHttpDegradeConfigSourceFactory())
                .withSources(new StandInSource("StandInApplicationProperties",
                        APPLICATION_PROPERTIES_ORDINAL, Map.of(INSECURE_REQUESTS, SHIPPED_DEFAULT)))
                .withSources(new StandInSource("StandInEnvironmentSource", ENVIRONMENT_ORDINAL,
                        Map.of(INSECURE_REQUESTS, SHIPPED_DEFAULT)))
                .build();

        ConfigValue resolved = config.getConfigValue(INSECURE_REQUESTS);

        assertAll("the deployment keeps the last word on a deployment-bound knob",
                () -> assertNotNull(degradeSourceOf(config),
                        "the control is only meaningful while the degrade is actually contributing — "
                                + "no certificate is declared here, so it is"),
                () -> assertEquals(SHIPPED_DEFAULT, resolved.getValue(),
                        "quarkus.http.insecure-requests is classified deployment-bound exposure, not "
                                + "TLS policy, so outranking the environment on it would be exactly the "
                                + "operator surprise the ordinal rule exists to prevent"),
                () -> assertEquals("StandInEnvironmentSource", resolved.getSourceName(),
                        "and the surviving value must come from the environment source rather than "
                                + "coincidentally matching the shipped default"));
    }

    // ---------------------------------------------------------------------------------------------
    // The boot the seam exists for
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The gateway boots with no certificate at all, where it would previously refuse")
    void bootsWithNoCertificateConfigured() {
        assertNotNull(bootConfig,
                "reaching this assertion is the measurement: without the degrade,"
                        + " VertxHttpRecorder.initializeMainHttpServer throws IllegalStateException"
                        + " during startup and no test in this class ever runs");
    }

    @Test
    @DisplayName("The degrade — not the deployment and not the shipped default — supplied the exposure")
    void degradeSuppliedTheExposureStrategyInTheRealBoot() {
        ConfigValue resolved = bootConfig.getConfigValue(INSECURE_REQUESTS);

        assertAll("the live boot's exposure strategy",
                () -> assertEquals(DEGRADED_VALUE, resolved.getValue(),
                        "the shipped application.properties declares redirect; only the degrade turns "
                                + "it into enabled for this boot"),
                () -> assertEquals(SOURCE_NAME, resolved.getSourceName(),
                        "asserting the value alone would pass just as happily if the test harness had "
                                + "supplied enabled itself — the source name is what makes this a "
                                + "measurement of the seam"),
                () -> assertEquals(PlainHttpDegradeConfigSourceFactory.ORDINAL, resolved.getSourceOrdinal(),
                        "the source really was registered at 275 in the assembled runtime config, not "
                                + "merely at the ordinal the unit-level cases build by hand"));
    }

    @Test
    @DisplayName("The booted listener serves plain HTTP rather than redirecting to a port that is not there")
    void servesPlainHttp() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(HttpRequest.newBuilder(rootUrl.toURI()).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

        assertAll("a plain-HTTP request completed against the main listener",
                () -> assertTrue(response.statusCode() > 0,
                        "the request was spoken in cleartext against the main listener and answered; "
                                + "against a TLS listener it would not have completed at all"),
                () -> assertTrue(response.headers().firstValue("location")
                                .filter(location -> location.startsWith("https")).isEmpty(),
                        "enabled serves the request; redirect would answer it with a 30x pointing at "
                                + "an HTTPS port that does not exist in no-certificate mode"));
    }

    @Test
    @DisplayName("The degrade reports itself as ApiSheriff-123 on the startup path, since a source cannot log")
    void degradeReportsItselfThroughTheStartupAudit() {
        startupEvent.fire(new StartupEvent());

        assertAll("both records fire, and they answer different questions",
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        "No-certificate mode: no server key material is DECLARED"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        "Terminated main listener is serving PLAIN HTTP on port"));
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolves {@link #INSECURE_REQUESTS} the way Quarkus does — through an assembled {@link Config}
     * carrying the factory — with the shipped {@code redirect} default present at the ordinal
     * {@code application.properties} really has.
     *
     * @param declared the configuration a deployment declares, key material or otherwise
     * @return the resolved value together with the source that supplied it
     */
    private static ConfigValue resolveAgainstShippedDefault(Map<String, String> declared) {
        return configuration(declared).getConfigValue(INSECURE_REQUESTS);
    }

    private static Config configuration(Map<String, String> declared) {
        Map<String, String> deployment = new HashMap<>(declared);
        return new SmallRyeConfigBuilder()
                .withSources(new PlainHttpDegradeConfigSourceFactory())
                .withSources(new StandInSource("StandInApplicationProperties",
                        APPLICATION_PROPERTIES_ORDINAL, Map.of(INSECURE_REQUESTS, SHIPPED_DEFAULT)))
                .withSources(new StandInSource("StandInEnvironmentSource", ENVIRONMENT_ORDINAL, deployment))
                .build();
    }

    /**
     * @param config an assembled configuration
     * @return the source the factory contributed, or {@code null} when it contributed none
     */
    private static @Nullable ConfigSource degradeSourceOf(Config config) {
        return StreamSupport.stream(config.getConfigSources().spliterator(), false)
                .filter(source -> SOURCE_NAME.equals(source.getName()))
                .findFirst()
                .orElse(null);
    }

    private static void assertShippedDefaultSurvived(ConfigValue resolved, String reason) {
        assertAll(reason,
                () -> assertEquals(SHIPPED_DEFAULT, resolved.getValue(),
                        "the secure posture is expressed by silence: with material declared the source "
                                + "contributes nothing and the shipped redirect stands"),
                () -> assertEquals("StandInApplicationProperties", resolved.getSourceName(),
                        "asserting the value alone could not tell 'the degrade declined' from 'the "
                                + "degrade projected redirect', so the surviving source is named"));
    }

    /**
     * Stands in for a real configuration source at a real ordinal, so precedence is genuinely
     * contested rather than resolved by default against an empty configuration.
     */
    private record StandInSource(String name, int ordinal, Map<String, String> properties)
            implements ConfigSource {

        @Override
        public int getOrdinal() {
            return ordinal;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Set<String> getPropertyNames() {
            return properties.keySet();
        }

        @Override
        public @Nullable String getValue(String propertyName) {
            return properties.get(propertyName);
        }

        @Override
        public Map<String, String> getProperties() {
            return properties;
        }
    }
}
