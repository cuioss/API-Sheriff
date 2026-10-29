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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed one-off descriptor gives
 * {@link ThreatClassificationIT} the configuration it is sized against — the wiring whose absence
 * would leave the IT green while proving nothing.
 * <p>
 * <strong>The declared values.</strong> {@code sheriff-config-threat/gateway.yaml} must declare the
 * {@code threat_classification} threshold, window and {@code weights.input_validation} the IT's bounds
 * are computed from. An absent member silently resolves to its default, and the IT is sized so that
 * every default turns it red — this test is the descriptor half of that: it asserts the declared
 * values, and that the IT's bounds keep each declared value apart from its default.
 * <p>
 * <strong>The isolation.</strong> The descriptor declares no {@code enumeration_detection} block, so
 * the recon ledger runs its default and cannot feed the score with a recon trip during the IT's short
 * run, and no compose service mounts it: the whole suite reaches the compose gateways from one source
 * address, so a threshold this low there would raise ALERTs for every suite's deliberate rejections.
 * <p>
 * It parses the committed descriptor only (YAML text) — it starts no container and reaches no network.
 * Modelled on {@link EnumerationDetectionActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("the one-off descriptor gives ThreatClassificationIT the threshold, window and weight it is sized against")
class ThreatClassificationActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final String THREAT_DIRECTORY = "sheriff-config-threat";
    private static final Path THREAT_GATEWAY = MODULE.resolve("src/main/docker/" + THREAT_DIRECTORY + "/gateway.yaml");
    private static final Path BASE_GATEWAY = MODULE.resolve("src/main/docker/sheriff-config/gateway.yaml");
    private static final String BLOCK_KEY = "threat_classification";

    @Test
    @DisplayName("the one-off descriptor declares the threshold, window and input_validation weight the IT is sized against")
    void oneOffDescriptorDeclaresTheValuesTheItReliesOn() throws Exception {
        Map<String, Object> document = loadYaml(THREAT_GATEWAY);
        Map<String, Object> block = mapping(document.get(BLOCK_KEY), BLOCK_KEY);
        Map<String, Object> weights = mapping(block.get("weights"), BLOCK_KEY + ".weights");

        assertAll("the declared threat_classification block",
                () -> assertEquals(ThreatClassificationIT.THRESHOLD, block.get("threshold"),
                        "threshold must be the value the alert bound is sized against"),
                () -> assertEquals(ThreatClassificationIT.WINDOW_SECONDS, block.get("window_seconds"),
                        "window_seconds must be the value the field-shape leg reads back"),
                () -> assertEquals(ThreatClassificationIT.INPUT_VALIDATION_WEIGHT, weights.get("input_validation"),
                        "weights.input_validation must be the weight the alert bound is sized against"),
                () -> assertEquals(1, weights.size(), () -> "only input_validation is declared, so no other weight "
                        + "can carry the scan: " + weights),
                () -> assertNotEquals(Boolean.FALSE, block.get("enabled"), "classification must not be switched off"),
                () -> assertFalse(document.containsKey("enumeration_detection"), "the recon ledger must run its default, "
                        + "which the IT's short run cannot trip, so no recon trip feeds the score"));
    }

    @Test
    @DisplayName("the IT's alert bound lies between the declared and the default values, so a deleted key turns it red")
    void alertBoundSeparatesTheDeclaredValuesFromTheDefaults() {
        int bound = ThreatClassificationIT.ALERT_BOUND;
        int declaredWeight = ThreatClassificationIT.INPUT_VALIDATION_WEIGHT;
        int declaredThreshold = ThreatClassificationIT.THRESHOLD;
        assertAll("the IT's sizing",
                () -> assertTrue(declaredWeight < declaredThreshold,
                        "one malformed request must stay below the declared threshold, or the benign leg alerts"),
                () -> assertTrue((bound - 1) * declaredWeight >= declaredThreshold,
                        "one request fewer than the bound must already reach the declared threshold, so the drain "
                                + "cannot hold the scan below it at the bound"),
                () -> assertTrue(bound * declaredWeight < ThreatClassificationConfig.DEFAULT_THRESHOLD,
                        "the bound must not reach the default threshold, or a deleted threshold would stay green"),
                () -> assertTrue(bound * ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION < declaredThreshold,
                        "the bound must not reach the declared threshold at the default weight, or a deleted "
                                + "weights.input_validation would stay green"),
                () -> assertNotEquals(ThreatClassificationConfig.DEFAULT_WINDOW_SECONDS,
                        ThreatClassificationIT.WINDOW_SECONDS,
                        "the declared window must differ from the default, or a deleted window_seconds would stay green"),
                () -> assertTrue(ThreatClassificationIT.SCAN_REQUESTS * declaredWeight >= 5 * declaredThreshold,
                        "the scan must go on to several times the threshold, to show one ALERT per episode"),
                () -> assertTrue(ThreatClassificationIT.SCAN_REQUESTS < EnumerationDetectionConfig.DEFAULT_THRESHOLD / 2,
                        "the scan must stay far below the default recon threshold, so no recon trip feeds the score"),
                () -> assertTrue(ThreatClassificationIT.SHARED_STACK_SCAN
                        * ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION
                        > ThreatClassificationConfig.DEFAULT_THRESHOLD,
                        "the shared-stack leg's scan must go past the default threshold at the default weight"));
    }

    @Test
    @DisplayName("the shared base descriptor declares no threat_classification block, so the compose stack runs the defaults")
    void sharedBaseDescriptorRunsTheDefaults() throws Exception {
        Map<String, Object> document = loadYaml(BASE_GATEWAY);

        assertAll("the shared stack runs the shipped defaults",
                () -> assertFalse(document.isEmpty(), "the base descriptor must parse to a document"),
                () -> assertFalse(document.containsKey(BLOCK_KEY), "the primary instance must run the shipped defaults, "
                        + "so ThreatClassificationIT's shared-stack leg proves the defaults and not a declared value"));
    }

    @Test
    @DisplayName("no compose service mounts the one-off descriptor")
    void noComposeServiceMountsTheOneOffDescriptor() throws Exception {
        String compose = Files.readString(MODULE.resolve("docker-compose.yml"));

        assertAll("the one-off descriptor stays one-off",
                () -> assertTrue(Files.isRegularFile(THREAT_GATEWAY), "the descriptor must exist: " + THREAT_GATEWAY),
                () -> assertFalse(compose.contains(THREAT_DIRECTORY), "docker-compose.yml must not mount "
                        + THREAT_DIRECTORY + ": its low threshold belongs to the one-off gateway"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value, String name) {
        assertInstanceOf(Map.class, value, "the one-off descriptor must declare a " + name + " mapping");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> document = new Yaml().loadAs(in, Map.class);
            return document != null ? document : Map.of();
        }
    }
}
