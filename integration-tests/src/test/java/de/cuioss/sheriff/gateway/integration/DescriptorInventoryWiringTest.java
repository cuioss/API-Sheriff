/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that makes the descriptor inventory claimed in prose by
 * {@code doc/development/declared-limit-assertion-coverage.adoc} machine-checked.
 * <p>
 * That note opens by enumerating the declared surface as <em>twelve files</em> — four
 * {@code gateway.yaml} documents, one per {@code sheriff-config*} directory, seven
 * {@code sheriff-config/endpoints/*.yaml} files and one
 * {@code sheriff-config/topology.properties} — and then states that a limit-shaped key sweep over
 * those twelve returns hits only in the four gateway documents and in five of the seven endpoint
 * files. Both are load-bearing claims: every status row in the note is scoped to that surface, so a
 * descriptor the note never enumerated is not merely undocumented, it is <em>invisible</em> to the
 * whole coverage index.
 * <p>
 * Nothing machine-checked either claim before this guard. The sibling wiring guards glob the
 * {@code sheriff-config*} directories and resolve one {@code gateway.yaml} inside each — they never
 * reach {@code endpoints/} or {@code topology.properties} — and their descriptor assertion is an
 * <em>at-least</em> floor
 * ({@code size() >= COMMITTED_DESCRIPTOR_COUNT}), which a newly added file satisfies without
 * complaint. So an added overlay directory, an added endpoint document, or a new limit-shaped key
 * in a file the note records as declaring none would all leave the checked-in note quietly wrong
 * with a green build.
 * <p>
 * This guard asserts <strong>exact set equality</strong> rather than a floor, in both directions:
 * an addition and a removal each fail loudly, and the failure message names the note so the fix is
 * obvious — update the note in the same change that touches the surface.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network. It is
 * deliberately <em>additive</em>: it does not widen the sibling guards' globs, whose narrower reach
 * is a separate, report-only finding recorded in the note.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class DescriptorInventoryWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** The prose claim this guard machine-checks; named in every failure message. */
    private static final String NOTE = "doc/development/declared-limit-assertion-coverage.adoc";

    /** The four gateway documents the note's "Enumerated Surface" section names. */
    private static final Set<String> DECLARED_GATEWAY_DESCRIPTORS = Set.of(
            "sheriff-config/gateway.yaml",
            "sheriff-config-cookie/gateway.yaml",
            "sheriff-config-mtls/gateway.yaml",
            "sheriff-config-ws-admission/gateway.yaml");

    /** The seven endpoint documents the note's "Enumerated Surface" section names. */
    private static final Set<String> DECLARED_ENDPOINT_DESCRIPTORS = Set.of(
            "sheriff-config/endpoints/assets.yaml",
            "sheriff-config/endpoints/assets-secure.yaml",
            "sheriff-config/endpoints/bff-session.yaml",
            "sheriff-config/endpoints/grpc.yaml",
            "sheriff-config/endpoints/httpbin.yaml",
            "sheriff-config/endpoints/secure.yaml",
            "sheriff-config/endpoints/websocket.yaml");

    /** The single alias file; declares no limit-shaped key, which the sweep below re-proves. */
    private static final String DECLARED_TOPOLOGY = "sheriff-config/topology.properties";

    /**
     * The note's limit-shaped key sweep, transcribed verbatim from its "Enumerated Surface"
     * section. It is applied to declared KEY NAMES only — never to raw file text — so the
     * descriptors' extensive prose comments cannot manufacture a hit.
     */
    private static final Pattern LIMIT_SHAPED_KEY = Pattern.compile(
            "max|min|limit|cap|_bytes|_seconds|timeout|ttl|leeway|length|allow|deny|threshold"
                    + "|budget|quota|window|depth");

    /**
     * The nine files the note records as declaring at least one limit-shaped key. Its complement
     * within the inventory — {@code assets.yaml}, {@code assets-secure.yaml} and
     * {@code topology.properties} — is the "declare none" half of the same claim, so asserting this
     * set exactly asserts both halves at once.
     */
    private static final Set<String> DECLARED_LIMIT_BEARING_FILES = Set.of(
            "sheriff-config/gateway.yaml",
            "sheriff-config-cookie/gateway.yaml",
            "sheriff-config-mtls/gateway.yaml",
            "sheriff-config-ws-admission/gateway.yaml",
            "sheriff-config/endpoints/bff-session.yaml",
            "sheriff-config/endpoints/grpc.yaml",
            "sheriff-config/endpoints/httpbin.yaml",
            "sheriff-config/endpoints/secure.yaml",
            "sheriff-config/endpoints/websocket.yaml");

    @Test
    @DisplayName("the committed descriptor inventory is exactly the twelve files the note enumerates")
    void descriptorInventoryIsExactlyTheEnumeratedSurface() throws Exception {
        // Arrange — the note's claim, assembled from the three surfaces it lists separately
        Set<String> declared = new TreeSet<>(DECLARED_GATEWAY_DESCRIPTORS);
        declared.addAll(DECLARED_ENDPOINT_DESCRIPTORS);
        declared.add(DECLARED_TOPOLOGY);

        // Act
        Set<String> committed = committedInventory();

        // Assert — exact equality, so an ADDED descriptor fails just as loudly as a removed one.
        // A floor would let a new overlay directory or a new endpoints/*.yaml slip in unrecorded.
        assertEquals(declared, committed, "the committed descriptor inventory under " + DOCKER
                + " no longer matches the twelve files enumerated in " + NOTE
                + ". Every status row in that note is scoped to this surface, so a descriptor it does"
                + " not enumerate is invisible to the whole coverage index — add or remove the row in"
                + " the same change that adds or removes the file.");
    }

    @Test
    @DisplayName("no limit-shaped key lives outside the surfaces the note records as declaring one")
    void limitShapedKeysLiveOnlyInTheRecordedSurfaces() throws Exception {
        // Arrange
        Set<String> committed = committedInventory();

        // Act — partition the inventory on "declares at least one limit-shaped key"
        Set<String> limitBearing = new TreeSet<>();
        for (String relative : committed) {
            if (declaresLimitShapedKey(relative)) {
                limitBearing.add(relative);
            }
        }

        // Assert — exact equality asserts both halves of the note's claim in one step: the files it
        // says declare limits still do, and the files it says declare none still declare none.
        assertEquals(DECLARED_LIMIT_BEARING_FILES, limitBearing,
                "the set of descriptors declaring a limit-shaped key no longer matches " + NOTE
                        + ". A newly limit-bearing file has no status row and is therefore an unasserted"
                        + " declared limit; a file that lost its keys leaves a stale row behind. Update"
                        + " the note's Coverage Matrix in the same change.");
    }

    /**
     * Every committed file under a {@code sheriff-config*} directory, as slash-separated paths
     * relative to the docker directory. The walk is recursive and unfiltered on purpose — filtering
     * by extension would hide exactly the kind of unrecorded companion file this guard exists to
     * catch.
     *
     * @return the committed inventory, sorted for a readable assertion diff
     * @throws IOException when the docker tree cannot be listed
     */
    private static Set<String> committedInventory() throws IOException {
        Set<String> inventory = new TreeSet<>();
        List<Path> roots = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, "sheriff-config*")) {
            for (Path directory : directories) {
                roots.add(directory);
            }
        }
        assertFalse(roots.isEmpty(), "no sheriff-config* directory was found under " + DOCKER
                + " — this guard would compare against an empty inventory; check the module working"
                + " directory and the glob");
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .map(path -> DOCKER.relativize(path).toString().replace('\\', '/'))
                        .forEach(inventory::add);
            }
        }
        return inventory;
    }

    /**
     * Whether one inventory file declares at least one limit-shaped key. YAML documents are parsed
     * and every declared key name — at every nesting depth, including inside sequences — is matched;
     * {@code .properties} files contribute their property names. Any other file kind declares no
     * keys and therefore no limit.
     *
     * @param relative the file's docker-relative path
     * @return {@code true} when at least one declared key name matches the note's sweep
     * @throws IOException when the file cannot be read
     */
    private static boolean declaresLimitShapedKey(String relative) throws IOException {
        Set<String> keys = new TreeSet<>();
        Path file = DOCKER.resolve(relative);
        if (relative.endsWith(".yaml")) {
            collectKeys(loadYaml(file), keys);
        } else if (relative.endsWith(".properties")) {
            keys.addAll(loadProperties(file).stringPropertyNames());
        }
        return keys.stream()
                .anyMatch(key -> LIMIT_SHAPED_KEY.matcher(key.toLowerCase(Locale.ROOT)).find());
    }

    /**
     * Collects every declared key name of a parsed YAML node, recursing through nested maps and
     * sequences.
     *
     * @param node the parsed node; anything that is neither a map nor a sequence declares no key
     * @param keys the accumulator to add the discovered key names to
     */
    private static void collectKeys(Object node, Set<String> keys) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                keys.add(String.valueOf(entry.getKey()));
                collectKeys(entry.getValue(), keys);
            }
        } else if (node instanceof List<?> sequence) {
            for (Object item : sequence) {
                collectKeys(item, keys);
            }
        }
    }

    private static Object loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }
}
