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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the repository runs exactly one Keycloak, pinned once,
 * and that Dependabot moves every copy of that pin together.
 * <p>
 * <strong>The contract.</strong> The integration-test stack and the compose sample both import a realm
 * into Keycloak, so they must run the same release: every compose file that pins
 * {@value #KEYCLOAK_IMAGE} pins it by tag <em>and</em> digest, and all of them carry the identical tag
 * and the identical digest. {@code .github/dependabot.yml} carries exactly one {@code docker-compose}
 * update, covering exactly the directories that hold such a pin, allowing only Keycloak, grouping it in
 * one {@value #GROUP} group so a bump moves every pin in a single pull request, and declaring no
 * cooldown key other than {@code default-days} — a {@code semver-*-days} key is rejected for the docker
 * ecosystems and invalidates the whole file.
 * <p>
 * <strong>Why a descriptor assertion.</strong> A tag-only pin, a pin that drifted in one file, or a
 * Dependabot entry that lost a directory is otherwise noticed only when a bump half-lands or a release
 * cannot name the Keycloak it was verified against. This guard turns each of them into a red surefire
 * run.
 * <p>
 * <strong>Every compose file is covered, none is listed.</strong> The compose files are enumerated from
 * the directory listing of {@value #INTEGRATION_TESTS_DIRECTORY} and {@value #COMPOSE_SAMPLE_DIRECTORY},
 * and the set of directories Dependabot must cover is derived from where a pin was actually found, so
 * a new compose file pinning Keycloak is held to the contract the moment it is committed. The
 * non-vacuity floor requires a pin in each of the two directories, so an empty or mis-rooted
 * enumeration cannot pass by finding nothing to compare.
 * <p>
 * The test asserts no Keycloak version: a Dependabot bump that moves every pin keeps it green. It
 * parses the committed files only — it starts no container and reaches no network. Modelled on
 * {@code BackChannelOriginActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class KeycloakImagePinWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path REPOSITORY = MODULE.resolve("..").normalize();
    private static final Path DEPENDABOT = REPOSITORY.resolve(".github/dependabot.yml");

    private static final String INTEGRATION_TESTS_DIRECTORY = "integration-tests";
    private static final String COMPOSE_SAMPLE_DIRECTORY = "deployment/compose-sample";

    /** The directories whose compose files are enumerated, relative to the repository root. */
    private static final List<String> SCANNED_DIRECTORIES = List.of(INTEGRATION_TESTS_DIRECTORY,
            COMPOSE_SAMPLE_DIRECTORY);

    private static final String COMPOSE_FILE_GLOB = "docker-compose*.yml";
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak";
    private static final String GROUP = "keycloak";
    private static final String ECOSYSTEM = "docker-compose";
    private static final String COOLDOWN_KEY = "default-days";

    /** A full pin: tag and a 64-hex-digit sha256 digest. A tag-only reference does not match. */
    private static final Pattern PINNED_IMAGE = Pattern
            .compile(Pattern.quote(KEYCLOAK_IMAGE) + ":([^@\\s]+)@sha256:([0-9a-f]{64})");

    @Test
    @DisplayName("every compose file pins Keycloak by tag and digest, and every pin is identical")
    void everyKeycloakPinCarriesTheSameTagAndDigest() throws Exception {
        List<KeycloakPin> pins = keycloakPins();

        Set<String> tags = new TreeSet<>();
        Set<String> digests = new TreeSet<>();
        for (KeycloakPin pin : pins) {
            Matcher matcher = PINNED_IMAGE.matcher(pin.image());
            assertTrue(matcher.matches(), pin.describe() + " pins " + pin.image() + ", expected "
                    + KEYCLOAK_IMAGE + ":<tag>@sha256:<64 hex digits> — a tag alone is mutable, so every"
                    + " Keycloak pin carries its digest");
            tags.add(matcher.group(1));
            digests.add(matcher.group(2));
        }

        assertEquals(1, tags.size(), "every Keycloak pin must carry the same tag, found " + tags + " across "
                + describe(pins) + " — the stacks import realms into one Keycloak and must agree on it");
        assertEquals(1, digests.size(), "every Keycloak pin must carry the same digest, found " + digests
                + " across " + describe(pins) + " — a tag that agrees over two different digests is two images");
    }

    @Test
    @DisplayName("Dependabot carries one docker-compose update over exactly the directories that pin Keycloak")
    void dependabotCoversExactlyTheDirectoriesThatPinKeycloak() throws Exception {
        Set<String> pinningDirectories = keycloakPins().stream()
                .map(KeycloakPin::dependabotDirectory)
                .collect(Collectors.toCollection(TreeSet::new));

        Map<?, ?> update = theDockerComposeUpdate();

        List<?> declared = assertInstanceOf(List.class, update.get("directories"), DEPENDABOT
                + " declares a " + ECOSYSTEM + " update without a 'directories' list; expected "
                + pinningDirectories);
        Set<String> declaredDirectories = declared.stream().map(String::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(pinningDirectories, declaredDirectories, DEPENDABOT + " " + ECOSYSTEM + " update covers "
                + declaredDirectories + ", expected exactly the directories holding a Keycloak pin: "
                + pinningDirectories + ". A pin outside the update is never bumped and drifts from the rest.");
    }

    @Test
    @DisplayName("the docker-compose update allows and groups only Keycloak, with a default-days cooldown alone")
    void theDockerComposeUpdateAllowsAndGroupsKeycloakWithADefaultDaysCooldown() throws Exception {
        Map<?, ?> update = theDockerComposeUpdate();

        List<?> allow = assertInstanceOf(List.class, update.get("allow"), DEPENDABOT + " " + ECOSYSTEM
                + " update must declare an 'allow' list naming " + KEYCLOAK_IMAGE);
        Set<String> allowed = allow.stream()
                .map(entry -> assertInstanceOf(Map.class, entry, DEPENDABOT + " declares a non-map allow entry"))
                .map(entry -> String.valueOf(entry.get("dependency-name")))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(KEYCLOAK_IMAGE), allowed, DEPENDABOT + " " + ECOSYSTEM + " update allows " + allowed
                + ", expected only " + KEYCLOAK_IMAGE + " — the other compose images stay manually pinned");

        Map<?, ?> groups = assertInstanceOf(Map.class, update.get("groups"), DEPENDABOT + " " + ECOSYSTEM
                + " update must declare groups, expected the '" + GROUP + "' group");
        Map<?, ?> group = assertInstanceOf(Map.class, groups.get(GROUP), DEPENDABOT + " " + ECOSYSTEM
                + " update must declare the '" + GROUP + "' group so one pull request moves every pin");
        List<?> patterns = assertInstanceOf(List.class, group.get("patterns"), DEPENDABOT + " group '" + GROUP
                + "' must declare patterns");
        assertTrue(patterns.contains(KEYCLOAK_IMAGE), DEPENDABOT + " group '" + GROUP + "' patterns " + patterns
                + " must contain " + KEYCLOAK_IMAGE);

        Map<?, ?> cooldown = assertInstanceOf(Map.class, update.get("cooldown"), DEPENDABOT + " " + ECOSYSTEM
                + " update must declare a cooldown with " + COOLDOWN_KEY);
        assertEquals(Set.of(COOLDOWN_KEY), keyNames(cooldown), DEPENDABOT + " " + ECOSYSTEM
                + " update cooldown declares " + keyNames(cooldown) + ", expected only " + COOLDOWN_KEY
                + " — a semver-*-days key is rejected for the docker ecosystems and invalidates the whole file");
    }

    /**
     * Every service, in every top-level compose file of the scanned directories, whose image is
     * {@value #KEYCLOAK_IMAGE}. Enumerated from the directory listing, with the non-vacuity floor applied:
     * at least one pin in each scanned directory.
     *
     * @return the Keycloak pins found, in directory-stream order
     * @throws IOException when a directory cannot be listed or a compose file cannot be read
     */
    private static List<KeycloakPin> keycloakPins() throws IOException {
        List<KeycloakPin> pins = new ArrayList<>();
        for (String directory : SCANNED_DIRECTORIES) {
            Path root = REPOSITORY.resolve(directory);
            int before = pins.size();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root, COMPOSE_FILE_GLOB)) {
                for (Path composeFile : files) {
                    collectKeycloakPins(directory, composeFile, pins);
                }
            }
            assertTrue(pins.size() > before, "no compose file under " + root + " pins " + KEYCLOAK_IMAGE
                    + " — the pin contract would pass vacuously; check the directory enumeration");
        }
        return pins;
    }

    private static void collectKeycloakPins(String directory, Path composeFile, List<KeycloakPin> pins)
            throws IOException {
        if (!(loadYaml(composeFile).get("services") instanceof Map<?, ?> services)) {
            return;
        }
        services.forEach((name, definition) -> {
            if (definition instanceof Map<?, ?> service && service.get("image") instanceof String image
                    && image.startsWith(KEYCLOAK_IMAGE + ":")) {
                pins.add(new KeycloakPin(directory, composeFile, String.valueOf(name), image));
            }
        });
    }

    /**
     * The one {@value #ECOSYSTEM} entry of {@code .github/dependabot.yml}.
     *
     * @return the update entry
     * @throws IOException when the Dependabot file cannot be read
     */
    private static Map<?, ?> theDockerComposeUpdate() throws IOException {
        List<?> updates = assertInstanceOf(List.class, loadYaml(DEPENDABOT).get("updates"),
                DEPENDABOT + " must declare an 'updates' list");
        List<Map<?, ?>> matching = new ArrayList<>();
        for (Object entry : updates) {
            Map<?, ?> update = assertInstanceOf(Map.class, entry, DEPENDABOT + " declares a non-map update");
            if (ECOSYSTEM.equals(update.get("package-ecosystem"))) {
                matching.add(update);
            }
        }
        assertEquals(1, matching.size(), DEPENDABOT + " must declare exactly one update with package-ecosystem: "
                + ECOSYSTEM + ", found " + matching.size() + " — without it no Keycloak pin is ever bumped");
        return matching.getFirst();
    }

    private static Set<String> keyNames(Map<?, ?> mapping) {
        return mapping.keySet().stream().map(String::valueOf).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String describe(List<KeycloakPin> pins) {
        return pins.stream().map(pin -> pin.describe() + " -> " + pin.image())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static Map<?, ?> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Object document = new Yaml(new ComposeMergeTagConstructor()).load(in);
            assertNotNull(document, path + " is empty");
            return assertInstanceOf(Map.class, document, path + " is not a mapping at its root");
        }
    }

    /**
     * One Keycloak image reference found in a compose file.
     *
     * @param directory   the scanned directory, relative to the repository root
     * @param composeFile the compose file declaring the service
     * @param service     the service name
     * @param image       the declared image reference
     */
    private record KeycloakPin(String directory, Path composeFile, String service, String image) {

        /** @return the directory in the leading-slash form {@code .github/dependabot.yml} lists it in */
        String dependabotDirectory() {
            return "/" + directory;
        }

        String describe() {
            return composeFile + " service '" + service + "'";
        }
    }

    /**
     * A {@link SafeConstructor} that understands the two Compose-Spec merge tags, so an override file
     * such as the compose sample's {@code docker-compose.plain-http.yml} loads rather than being
     * rejected as carrying an unknown tag. {@code !reset} constructs as {@code null}; {@code !override}
     * constructs as its untagged equivalent. This test reads only image references, so no merge
     * semantics are modelled.
     * <p>
     * {@code ComposeSampleForwardedTrustWiringTest} and {@code EnvironmentKeySpellingGuardTest} register
     * the same two tags; the registrations stay local to each test so no test's parser can be changed out
     * from under it by an edit made for another.
     */
    private static final class ComposeMergeTagConstructor extends SafeConstructor {

        private ComposeMergeTagConstructor() {
            super(new LoaderOptions());
            yamlConstructors.put(new Tag("!reset"), new ConstructReset());
            yamlConstructors.put(new Tag("!override"), new ConstructUntagged());
        }

        /** Constructs a {@code !reset} node as {@code null}. */
        private static final class ConstructReset extends AbstractConstruct {

            @Override
            public Object construct(Node node) {
                return null;
            }
        }

        /**
         * Constructs an {@code !override} node as its untagged equivalent. Children are resolved
         * through the enclosing constructor, never the node itself — re-entering on the same node
         * would trip SnakeYAML's recursion guard.
         */
        private final class ConstructUntagged extends AbstractConstruct {

            @Override
            public Object construct(Node node) {
                return switch (node) {
                    case ScalarNode scalar -> scalar.getValue();
                    case SequenceNode sequence -> {
                        List<Object> values = new ArrayList<>();
                        for (Node child : sequence.getValue()) {
                            values.add(constructObject(child));
                        }
                        yield values;
                    }
                    case MappingNode mapping -> {
                        Map<Object, Object> values = new LinkedHashMap<>();
                        for (NodeTuple tuple : mapping.getValue()) {
                            values.put(constructObject(tuple.getKeyNode()), constructObject(tuple.getValueNode()));
                        }
                        yield values;
                    }
                    default -> null;
                };
            }
        }
    }
}
