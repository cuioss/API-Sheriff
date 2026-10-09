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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link NeutralTlsConfigSource}: the management TLS projection, the ordinal contract that
 * lets a policy named in {@code gateway.yaml} outrank the same knob supplied as a deployment
 * environment variable, the silent-degradation contract for an absent or malformed document, and the
 * negative constraints that bound what the source is allowed to project.
 * <p>
 * <strong>The projection is asserted through the real config-resolution path.</strong> The
 * behaviour that matters is not what {@link NeutralTlsConfigSource#getValue(String)} returns in
 * isolation but what the assembled {@link Config} resolves after ordinal arbitration — that is the
 * question Quarkus actually asks the source. Calling the source's own accessor would leave a
 * mechanism green while it was never exercised through the seam that uses it, which is the failure
 * mode this whole plan exists to eliminate. Every projection assertion therefore builds a
 * {@code SmallRyeConfig} and reads the value back through the {@code Config} API.
 * <p>
 * <strong>The lower-ordinal stand-in is a matched control, not scenery.</strong> A no-projection
 * assertion made against an otherwise-empty configuration proves nothing: the value would be absent
 * whether or not the source declined to project it. Each no-projection case therefore runs against a
 * competing value supplied at the environment source's ordinal, so "the deployment value survives" is
 * a positive observation rather than the absence of any observation at all — and the disabled case
 * runs against the same competitor, so "the projection wins" is likewise observed rather than assumed.
 */
class NeutralTlsConfigSourceTest {

    private static final String MANAGEMENT_TLS_CONFIGURATION_NAME =
            NeutralTlsConfigSource.MANAGEMENT_TLS_CONFIGURATION_NAME;
    private static final String PLAIN_MANAGEMENT = NeutralTlsConfigSource.PLAIN_MANAGEMENT_BUCKET;

    /** A value only a deployment could have supplied, so its survival is unambiguous. */
    private static final String DEPLOYMENT_SUPPLIED = "deployment-supplied-bucket";

    /** The ordinal SmallRye gives the environment-variable source — the precedence being contested. */
    private static final int ENVIRONMENT_ORDINAL = 300;

    private static final String MANAGEMENT_TLS_DISABLED = """
            version: 1
            tls:
              min_version: "1.3"
            management:
              tls:
                enabled: false
            """;

    private static final String MANAGEMENT_TLS_ENABLED = """
            version: 1
            tls:
              min_version: "1.3"
            management:
              tls:
                enabled: true
            """;

    private static final String NO_MANAGEMENT_BLOCK = """
            version: 1
            tls:
              min_version: "1.3"
            """;

    @TempDir
    Path configDir;

    private void writeGateway(String content) throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), content);
    }

    /**
     * Resolves {@link #MANAGEMENT_TLS_CONFIGURATION_NAME} the way Quarkus does — through an assembled
     * {@link Config} — with a competing deployment value present at the environment source's ordinal.
     */
    private @Nullable String resolveAgainstDeploymentValue() {
        Config config = new SmallRyeConfigBuilder()
                .withSources(new NeutralTlsConfigSource(configDir))
                .withSources(new StandInEnvironmentSource(
                        Map.of(MANAGEMENT_TLS_CONFIGURATION_NAME, DEPLOYMENT_SUPPLIED)))
                .build();
        return config.getOptionalValue(MANAGEMENT_TLS_CONFIGURATION_NAME, String.class).orElse(null);
    }

    @Test
    @DisplayName("The ordinal outranks the environment-variable source")
    void ordinalOutranksTheEnvironmentVariableSource() {
        assertTrue(NeutralTlsConfigSource.ORDINAL > ENVIRONMENT_ORDINAL,
                "an ordinal at or below the environment source's 300 would let a deployment variable "
                        + "silently override a policy named in gateway.yaml");
    }

    @Test
    @DisplayName("management.tls.enabled=false selects the key-less bucket through the resolved Config")
    void disabledManagementTlsSelectsThePlainBucketThroughTheResolvedConfig() throws Exception {
        writeGateway(MANAGEMENT_TLS_DISABLED);

        assertEquals(PLAIN_MANAGEMENT, resolveAgainstDeploymentValue(),
                "the neutral opt-out must reach the management listener through the config-resolution "
                        + "path and must outrank the deployment value — that precedence is the entire "
                        + "reason the ordinal sits above 300");
    }

    @Test
    @DisplayName("management.tls.enabled=true projects nothing, so the deployment value stands")
    void enabledManagementTlsLeavesTheDeploymentValueStanding() throws Exception {
        writeGateway(MANAGEMENT_TLS_ENABLED);

        assertEquals(DEPLOYMENT_SUPPLIED, resolveAgainstDeploymentValue(),
                "HTTPS is the secure default and needs no key to express it; projecting one here "
                        + "would outrank QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME and turn the two "
                        + "doors onto this lever into two competing mechanisms");
    }

    @Test
    @DisplayName("An absent management block projects nothing, so the deployment value stands")
    void absentManagementBlockLeavesTheDeploymentValueStanding() throws Exception {
        writeGateway(NO_MANAGEMENT_BLOCK);

        assertEquals(DEPLOYMENT_SUPPLIED, resolveAgainstDeploymentValue(),
                "a document that says nothing about management TLS must leave the deployment's own "
                        + "route onto the same Quarkus key working unchanged");
    }

    @Test
    @DisplayName("The projected key set is exactly one key")
    void projectedKeySetIsExactlyOneKey() throws Exception {
        writeGateway(MANAGEMENT_TLS_DISABLED);

        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertAll("projection",
                () -> assertEquals(Set.of(MANAGEMENT_TLS_CONFIGURATION_NAME), source.getPropertyNames(),
                        "the cardinality is pinned deliberately: this source outranks every environment "
                                + "variable, so a second projected key must be a visible diff rather "
                                + "than an unnoticed widening of the policy-keys-only constraint"),
                () -> assertEquals(Map.of(MANAGEMENT_TLS_CONFIGURATION_NAME, PLAIN_MANAGEMENT),
                        source.getProperties(), "getProperties must agree with getPropertyNames"));
    }

    @ParameterizedTest
    @DisplayName("A shape the schema will reject projects nothing rather than guessing")
    @ValueSource(strings = {
            "version: 1\nmanagement: not-a-map\n",
            "version: 1\nmanagement:\n  tls: not-a-map\n",
            "version: 1\nmanagement:\n  tls:\n    enabled: \"false\"\n",
            "version: 1\nmanagement:\n  tls:\n    enabled: 0\n"
    })
    void malformedManagementShapeLeavesTheDeploymentValueStanding(String document) throws Exception {
        writeGateway(document);

        assertEquals(DEPLOYMENT_SUPPLIED, resolveAgainstDeploymentValue(),
                "this parse runs before the schema validates the document, so an unexpected shape must "
                        + "read as 'not explicitly disabled' — keeping TLS on is the fail-safe direction, "
                        + "and ConfigLoader reports the real error moments later");
    }

    @Test
    @DisplayName("An absent document degrades silently rather than failing the boot early")
    void absentDocumentProjectsNoKey() {
        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertTrue(source.getProperties().isEmpty(),
                "ConfigLoader reports a missing gateway.yaml with full context moments later; failing "
                        + "here would replace that diagnostic with a context-free config-system error");
    }

    @Test
    @DisplayName("A malformed document degrades silently rather than failing the boot early")
    void malformedDocumentProjectsNoKey() throws Exception {
        writeGateway("this: [is: not: valid: yaml");

        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertTrue(source.getProperties().isEmpty(), "a parse failure here is re-reported by ConfigLoader");
    }

    @ParameterizedTest
    @DisplayName("No deployment-bound or key-material key is ever projected")
    @ValueSource(strings = {
            "quarkus.management.port",
            "quarkus.http.port",
            "quarkus.http.ssl-port",
            "quarkus.tls.key-store.pem.0.cert",
            "quarkus.tls.key-store.p12.path",
            "quarkus.tls.trust-store.p12.path",
            "quarkus.tls." + PLAIN_MANAGEMENT + ".key-store.p12.path"
    })
    void neverProjectsADeploymentBoundOrKeyMaterialKey(String forbiddenKey) throws Exception {
        // the actively-projecting document, so the constraint is asserted while the source is live
        writeGateway(MANAGEMENT_TLS_DISABLED);

        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertNull(source.getValue(forbiddenKey),
                () -> forbiddenKey + " must never be projected: this source outranks every environment "
                        + "variable, so projecting a port would override the deployment's own knob and "
                        + "projecting key material would populate the default TLS registry bucket");
    }

    @Test
    @DisplayName("The source names itself after the document it reads")
    void nameIdentifiesTheDocumentItReads() {
        assertEquals("NeutralTlsConfigSource[gateway.yaml]", new NeutralTlsConfigSource(configDir).getName());
    }

    @Test
    @DisplayName("getOrdinal reports the declared ordinal")
    void getOrdinalReportsTheDeclaredOrdinal() {
        assertEquals(NeutralTlsConfigSource.ORDINAL, new NeutralTlsConfigSource(configDir).getOrdinal());
    }

    @Test
    @DisplayName("The registered provider yields exactly one source")
    void providerYieldsExactlyOneSource() {
        var sources = new NeutralTlsConfigSource.Provider().getConfigSources(getClass().getClassLoader());

        assertEquals(1, StreamSupport.stream(sources.spliterator(), false).count(),
                "the ServiceLoader entry must contribute the single neutral source, no more");
    }

    /**
     * Stands in for the deployment's environment-variable source at its real ordinal, so precedence is
     * genuinely contested rather than resolved by default against an empty configuration.
     */
    private record StandInEnvironmentSource(Map<String, String> properties) implements ConfigSource {

        @Override
        public int getOrdinal() {
            return ENVIRONMENT_ORDINAL;
        }

        @Override
        public String getName() {
            return "StandInEnvironmentSource";
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
