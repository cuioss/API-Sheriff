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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed descriptors give
 * {@link EnumerationDetectionIT} the configuration each of its legs relies on — the wiring whose
 * absence would leave a leg green while proving nothing.
 * <p>
 * <strong>The block leg.</strong> The one-off gateway's descriptor must declare {@code action: block},
 * and the threshold and block duration the scan and the expiry wait are sized against. An absent
 * member silently resolves to its default, and the IT is sized so that every default makes it red —
 * this test is the descriptor half of that: it asserts the declared values, and that the IT's own
 * bounds keep the declared and the default value apart.
 * <p>
 * <strong>The observe leg.</strong> The shared base document must declare no
 * {@code enumeration_detection} block, so the leg against the primary instance runs the shipped default
 * and not a declared value. No other compose descriptor may declare one either: the whole suite reaches
 * the compose gateways from one source address and runs rejection corpora against them, so a declared
 * {@code block} there would refuse every suite after the first scan. The one-off descriptor must
 * therefore never be mounted by a compose service.
 * <p>
 * It parses the committed descriptors only (YAML text) — it starts no container and reaches no
 * network. Modelled on {@code WsAdmissionActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("the descriptors give EnumerationDetectionIT a declared block on the one-off gateway and the default elsewhere")
class EnumerationDetectionActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final String ENUMERATION_DIRECTORY = "sheriff-config-enumeration";
    private static final Path ENUMERATION_GATEWAY = DOCKER.resolve(ENUMERATION_DIRECTORY + "/gateway.yaml");
    private static final Path BASE_GATEWAY = DOCKER.resolve("sheriff-config/gateway.yaml");
    private static final String BLOCK_KEY = "enumeration_detection";

    @Test
    @DisplayName("the one-off descriptor declares action block and the threshold and block the IT is sized against")
    void oneOffDescriptorDeclaresTheBlockTheItReliesOn() throws Exception {
        Map<String, Object> block = enumerationDetectionBlock();

        assertAll("the declared enumeration_detection block",
                () -> assertEquals("block", block.get("action"),
                        "action must be declared block: the shipped default observe refuses nothing"),
                () -> assertEquals(EnumerationDetectionIT.THRESHOLD, block.get("threshold"),
                        "threshold must be the value the scan is sized against"),
                () -> assertEquals(EnumerationDetectionIT.BLOCK_SECONDS, block.get("block_seconds"),
                        "block_seconds must be the value the expiry wait is sized against"),
                () -> assertNull(block.get("window_seconds"), "window_seconds must stay at its default: a "
                        + "shorter window drains the scan away between its requests"),
                () -> assertNotEquals(Boolean.FALSE, block.get("enabled"), "detection must not be switched off"));
    }

    @Test
    @DisplayName("the IT's bounds lie between the declared values and the defaults, so a deleted key turns a leg red")
    void itBoundsSeparateTheDeclaredValuesFromTheDefaults() {
        assertAll("the IT's sizing",
                () -> assertTrue(EnumerationDetectionIT.THRESHOLD < EnumerationDetectionIT.SCAN_CAP,
                        "the scan must be able to reach the declared threshold"),
                () -> assertTrue(EnumerationDetectionIT.SCAN_CAP < EnumerationDetectionConfig.DEFAULT_THRESHOLD,
                        "the scan must not reach the default threshold, or a deleted threshold would stay green"),
                () -> assertTrue(EnumerationDetectionIT.HONEST_STATIC_MISSES > EnumerationDetectionIT.THRESHOLD,
                        "the browser-ambient misses must be enough to trip if they were counted"),
                () -> assertTrue(EnumerationDetectionIT.HONEST_TOKENLESS_ASSET_FETCHES >= EnumerationDetectionIT.THRESHOLD,
                        "the tokenless asset fetches must be enough to trip if they were counted"),
                () -> assertTrue(EnumerationDetectionIT.BLOCK_SECONDS < EnumerationDetectionIT.BLOCK_EXPIRY_BOUND_SECONDS,
                        "the expiry wait must outlast the declared block"),
                () -> assertTrue(EnumerationDetectionIT.BLOCK_EXPIRY_BOUND_SECONDS
                        < EnumerationDetectionConfig.DEFAULT_BLOCK_SECONDS,
                        "the expiry wait must end before the default block would, or a deleted block_seconds"
                                + " would stay green"),
                () -> assertTrue(EnumerationDetectionIT.OBSERVE_SCAN > EnumerationDetectionConfig.DEFAULT_THRESHOLD,
                        "the observe leg's scan must go past the default threshold"));
    }

    @Test
    @DisplayName("the shared base descriptor declares no enumeration_detection block")
    void sharedBaseDescriptorRunsTheDefault() throws Exception {
        Map<String, Object> document = loadYaml(BASE_GATEWAY);

        assertFalse(document.isEmpty(), "the base descriptor must parse to a document");
        assertFalse(document.containsKey(BLOCK_KEY), "the primary instance must run the shipped default, so the "
                + "observe leg proves the default and not a declared value");
    }

    @Test
    @DisplayName("no compose descriptor declares a trip response, and no compose service mounts the one-off one")
    void noComposeGatewayCarriesATripResponse() throws Exception {
        List<Path> composeDescriptors;
        try (Stream<Path> directories = Files.list(DOCKER)) {
            composeDescriptors = directories
                    .filter(Files::isDirectory)
                    .filter(directory -> directory.getFileName().toString().startsWith("sheriff-config"))
                    .filter(directory -> !ENUMERATION_DIRECTORY.equals(directory.getFileName().toString()))
                    .map(directory -> directory.resolve("gateway.yaml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
        String compose = Files.readString(MODULE.resolve("docker-compose.yml"));

        assertTrue(composeDescriptors.contains(BASE_GATEWAY),
                () -> "the sweep must find the base descriptor, or it asserts over nothing: " + composeDescriptors);
        for (Path descriptor : composeDescriptors) {
            assertFalse(loadYaml(descriptor).containsKey(BLOCK_KEY), () -> descriptor + " declares " + BLOCK_KEY
                    + ": the suite reaches every gateway from one source address, so a trip response there refuses"
                    + " or slows the suites that run after a scan");
        }
        assertFalse(compose.contains(ENUMERATION_DIRECTORY),
                "docker-compose.yml must not mount " + ENUMERATION_DIRECTORY + ": its block belongs to the one-off gateway");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> enumerationDetectionBlock() throws IOException {
        Object block = loadYaml(ENUMERATION_GATEWAY).get(BLOCK_KEY);
        assertInstanceOf(Map.class, block, "the one-off descriptor must declare an " + BLOCK_KEY + " mapping");
        return (Map<String, Object>) block;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> document = new Yaml().loadAs(in, Map.class);
            return document != null ? document : Map.of();
        }
    }
}
