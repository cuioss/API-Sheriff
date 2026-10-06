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
package de.cuioss.sheriff.gateway.config.boot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.DefaultedPlaceholder;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigBootPipeline}: one case per stage outcome — a valid set assembles, and a
 * loader, topology, validator, body-limit or not-checked outcome is reported as the boot would
 * reach it — plus the injected placeholder engine and the stage-ordered collection of applied
 * in-file defaults.
 */
class ConfigBootPipelineTest {

    private static final long FRAMEWORK_LIMIT_BYTES = 1024L;

    private static final String VALID_GATEWAY = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            """;

    private static final String PROXY_ENDPOINT = """
            endpoint:
              id: %s
              base_url: %s
              auth:
                require: none
              routes:
                - id: %s-proxied
                  match:
                    path_prefix: /%s
            """;

    @TempDir
    Path configDir;

    private static ConfigBootPipeline pipelineWith(Map<String, String> environment) {
        return new ConfigBootPipeline(new EnvSecretResolver(environment::get));
    }

    private void writeGateway(String yaml) throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), yaml);
    }

    private void writeTopology(String content) throws IOException {
        Files.writeString(configDir.resolve("topology.properties"), content);
    }

    private void writeProxyEndpoint(String id, String alias) throws IOException {
        Files.createDirectories(configDir.resolve("endpoints"));
        Files.writeString(configDir.resolve("endpoints/" + id + ".yaml"), PROXY_ENDPOINT.formatted(id, alias, id, id));
    }

    private static String gatewayDeclaringBodyCap(int maxBodyBytes) {
        return """
                version: 1
                anchors:
                  uploads:
                    path_prefix: /uploads
                    type: proxy
                    access: public
                    security_filter:
                      max_body_bytes: %d
                """.formatted(maxBodyBytes);
    }

    @Test
    @DisplayName("A valid set assembles with no violation and nothing left unchecked")
    void validSetAssembles() throws Exception {
        writeGateway(VALID_GATEWAY);
        writeProxyEndpoint("web", "WEB_BACKEND");
        writeTopology("WEB_BACKEND=https://web.internal:8443\n");

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        ConfigBootPipeline.Assembly assembly = outcome.assembly();
        assertNotNull(assembly, "a valid set must assemble");
        assertAll("valid outcome",
                () -> assertTrue(outcome.violations().isEmpty(), () -> "unexpected " + outcome.violations()),
                () -> assertTrue(outcome.notChecked().isEmpty(), () -> "unexpected " + outcome.notChecked()),
                () -> assertTrue(outcome.defaulted().isEmpty(), () -> "unexpected " + outcome.defaulted()),
                () -> assertEquals(1, assembly.enabledEndpoints().size()),
                () -> assertEquals(1, assembly.routeTable().routes().size()),
                () -> assertTrue(assembly.topology().lookup("WEB_BACKEND").isPresent()));
    }

    @ParameterizedTest(name = "loader defect: {0}")
    @ValueSource(strings = {"bogus_unknown_key: x", "oidc:\n  redirect_uri: \"https://${SHERIFF_TEST_ABSENT_HOST}/cb\""})
    @DisplayName("A schema or placeholder defect is reported as loader violations only, with no assembly")
    void loaderDefectStopsTheRun(String defect) throws Exception {
        writeGateway("version: 1\n" + defect + "\n");
        writeProxyEndpoint("web", "MISSING_ALIAS");

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertAll("loader stage stops the run",
                () -> assertNull(outcome.assembly()),
                () -> assertFalse(outcome.violations().isEmpty()),
                () -> assertTrue(outcome.violations().stream().allMatch(v -> "gateway.yaml".equals(v.file())),
                        () -> "only loader violations are reported, never the later topology stage: "
                                + outcome.violations()));
    }

    @Test
    @DisplayName("An unresolved enabled alias and an unresolvable topology placeholder are both reported")
    void topologyFailuresAreReportedTogether() throws Exception {
        writeGateway(VALID_GATEWAY);
        writeProxyEndpoint("alpha", "ALPHA");
        writeProxyEndpoint("beta", "BETA");
        writeTopology("ALPHA=${SHERIFF_TEST_ABSENT_ALPHA_URL}\n");

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertNull(outcome.assembly());
        assertEquals(List.of(
                        new ConfigError("topology.properties", "ALPHA", "Cannot resolve placeholder in topology alias 'ALPHA': "
                                + "Unresolved environment variable: SHERIFF_TEST_ABSENT_ALPHA_URL"),
                        new ConfigError("topology.properties", "BETA",
                                "Unresolved topology alias 'BETA' referenced by enabled endpoint 'beta'")),
                outcome.violations());
    }

    @Test
    @DisplayName("A validator rule violation is reported as a validator entry, with no assembly")
    void validatorViolationIsReported() throws Exception {
        writeGateway("""
                version: 1
                anchors:
                  portal:
                    path_prefix: /portal
                    type: bff
                    access: public
                """);

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertNull(outcome.assembly());
        assertTrue(outcome.violations().stream()
                        .anyMatch(v -> v.message().contains("is type 'bff' and must declare access: authenticated")),
                () -> "expected the access/auth matrix violation, got " + outcome.violations());
    }

    @Test
    @DisplayName("A declared body cap above the supplied framework limit yields the body-limit violation")
    void bodyCapAboveTheFrameworkLimitIsAViolation() throws Exception {
        writeGateway(gatewayDeclaringBodyCap(2048));

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertNull(outcome.assembly());
        assertEquals(List.of(new ConfigError("application.properties", "quarkus.http.limits.max-body-size",
                        "2048 exceeds framework limit 1024; raise quarkus.http.limits.max-body-size to at least 2048")),
                outcome.violations());
    }

    @Test
    @DisplayName("A declared body cap equal to the framework limit is reachable and assembles")
    void bodyCapAtTheFrameworkLimitAssembles() throws Exception {
        writeGateway(gatewayDeclaringBodyCap(1024));

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertNotNull(outcome.assembly(), () -> "unexpected " + outcome.violations());
    }

    @Test
    @DisplayName("Without a framework limit the body-limit check is named as not checked, never passed or failed")
    void nullLimitIsReportedAsNotChecked() throws Exception {
        writeGateway(gatewayDeclaringBodyCap(2048));

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, null);

        assertAll("not checked, not violated",
                () -> assertTrue(outcome.violations().isEmpty(), () -> "unexpected " + outcome.violations()),
                () -> assertEquals(List.of(ConfigBootPipeline.FRAMEWORK_BODY_LIMIT_KEY), outcome.notChecked()),
                () -> assertNotNull(outcome.assembly()));
    }

    @Test
    @DisplayName("Placeholders resolve through the injected engine, not the process environment")
    void usesTheInjectedResolver() throws Exception {
        writeGateway(VALID_GATEWAY);
        writeProxyEndpoint("web", "WEB_BACKEND");
        writeTopology("WEB_BACKEND=${SHERIFF_TEST_INJECTED_WEB_URL}\n");

        ConfigBootPipeline.Outcome outcome = pipelineWith(
                Map.of("SHERIFF_TEST_INJECTED_WEB_URL", "https://fixed.internal:9443")).run(configDir,
                FRAMEWORK_LIMIT_BYTES);

        ConfigBootPipeline.Assembly assembly = outcome.assembly();
        assertNotNull(assembly, () -> "unexpected " + outcome.violations());
        ResolvedUpstream upstream = assembly.topology().lookup("WEB_BACKEND").orElseThrow();
        assertEquals("fixed.internal", upstream.host());
        assertEquals(9443, upstream.port());
    }

    @Test
    @DisplayName("Applied defaults are collected in stage order: loader, then topology")
    void collectsDefaultsInStageOrder() throws Exception {
        writeGateway("""
                version: 1
                metadata:
                  config_version: "${SHERIFF_TEST_CONFIG_VERSION:-v1}"
                """);
        writeProxyEndpoint("web", "WEB_BACKEND");
        writeTopology("WEB_BACKEND=${SHERIFF_TEST_WEB_URL:-https://web.internal:8443}\n");

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertAll("defaults reported, outcome otherwise valid",
                () -> assertEquals(List.of(
                                new DefaultedPlaceholder("gateway.yaml", "/metadata/config_version",
                                        "SHERIFF_TEST_CONFIG_VERSION"),
                                new DefaultedPlaceholder("topology.properties", "WEB_BACKEND", "SHERIFF_TEST_WEB_URL")),
                        outcome.defaulted()),
                () -> assertTrue(outcome.violations().isEmpty(), () -> "unexpected " + outcome.violations()),
                () -> assertNotNull(outcome.assembly()));
    }

    @Test
    @DisplayName("A loader-stage default is still reported when the topology stage fails")
    void keepsLoaderDefaultsWhenTopologyFails() throws Exception {
        writeGateway("""
                version: 1
                metadata:
                  config_version: "${SHERIFF_TEST_CONFIG_VERSION:-v1}"
                """);
        writeProxyEndpoint("web", "MISSING_ALIAS");

        ConfigBootPipeline.Outcome outcome = pipelineWith(Map.of()).run(configDir, FRAMEWORK_LIMIT_BYTES);

        assertAll("topology failed, loader default kept",
                () -> assertNull(outcome.assembly()),
                () -> assertEquals(List.of("topology.properties"),
                        outcome.violations().stream().map(ConfigError::file).toList()),
                () -> assertEquals(List.of(new DefaultedPlaceholder("gateway.yaml", "/metadata/config_version",
                        "SHERIFF_TEST_CONFIG_VERSION")), outcome.defaulted()));
    }

    @Test
    @DisplayName("An outcome refuses an assembly beside violations, and a missing one without them")
    void outcomeEnforcesAssemblyInvariant() {
        List<ConfigError> violations = List.of(new ConfigError("gateway.yaml", "", "broken"));
        List<DefaultedPlaceholder> none = List.of();
        List<String> nothing = List.of();

        assertThrows(IllegalArgumentException.class,
                () -> new ConfigBootPipeline.Outcome(List.of(), none, nothing, null),
                "a valid outcome must carry its assembly");
        ConfigBootPipeline.Outcome failed = new ConfigBootPipeline.Outcome(violations, none, nothing, null);
        assertEquals(violations, failed.violations());
    }
}
