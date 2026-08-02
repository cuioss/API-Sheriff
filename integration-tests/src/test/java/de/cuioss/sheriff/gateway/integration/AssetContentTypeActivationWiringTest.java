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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.asset.AssetResponseEnvelope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed deployment descriptors actually
 * <strong>activate</strong> the add-only asset content-type block, and that none of them declares an
 * entry the gateway would refuse at boot.
 * <p>
 * This closes a unit-green / integration-red blind spot. The unit tests prove
 * {@code config -> behaviour}: an operator entry for an unmapped extension resolves to the operator's
 * value, and an entry naming a built-in extension is refused. What they cannot prove is
 * {@code committed-descriptor -> activated} — that the shipped {@code gateway.yaml} actually declares
 * the block at all. Without that assertion the feature could ship fully unit-covered and entirely
 * inert, with every served asset still falling back to {@code application/octet-stream}.
 * <p>
 * The second assertion is the deployment-side mirror of the boot refusal. Because
 * {@code ConfigValidator} refuses an {@code asset_defaults.content_types} entry naming a built-in
 * extension, such an entry in <em>any</em> committed descriptor would abort that instance's boot —
 * turning a container-suite run red long after the cheap signal was available here. The built-in set
 * is read from {@link AssetResponseEnvelope#builtInExtensions()} rather than restated, so this guard
 * cannot drift from the map it guards.
 * <p>
 * The coverage is deliberately <strong>all committed descriptors, not just the base one</strong>, and
 * they are discovered by glob rather than hard-coded, so a new {@code sheriff-config-*} directory
 * comes under the collision assertion automatically — and a glob matching fewer than the descriptors
 * present today fails rather than passing vacuously.
 * <p>
 * It parses the committed descriptors only (YAML text) and starts no container and reaches no
 * network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class AssetContentTypeActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** The base descriptor — the one instance whose asset surface the feature is activated on. */
    private static final Path BASE_DESCRIPTOR = DOCKER.resolve("sheriff-config/gateway.yaml");

    private static final String ASSET_DEFAULTS_KEY = "asset_defaults";
    private static final String CONTENT_TYPES_KEY = "content_types";

    /**
     * The descriptor count committed today. The glob must match at least this many, so an empty or
     * mis-rooted glob fails loudly instead of satisfying the per-descriptor loop vacuously.
     */
    private static final int COMMITTED_DESCRIPTOR_COUNT = 4;

    @Test
    @DisplayName("the base descriptor activates asset_defaults.content_types with at least one entry")
    void baseDescriptorActivatesTheBlock() throws Exception {
        // Arrange
        Map<String, String> declared = declaredContentTypes(loadYaml(BASE_DESCRIPTOR));

        // Assert — an absent or empty block is the inert-feature defect this guard exists to catch:
        // every asset would keep falling back to application/octet-stream while the unit suite stayed
        // green.
        assertFalse(declared.isEmpty(),
                BASE_DESCRIPTOR + " must declare a non-empty " + ASSET_DEFAULTS_KEY + "."
                        + CONTENT_TYPES_KEY + " block, otherwise the shipped gateway never exercises the"
                        + " operator content-type path at all");
    }

    @Test
    @DisplayName("the base descriptor's declared extensions are ones the gateway does not already map")
    void baseDescriptorDeclaresOnlyUnmappedExtensions() throws Exception {
        // Arrange
        Map<String, String> declared = declaredContentTypes(loadYaml(BASE_DESCRIPTOR));

        // Assert — a built-in entry would both abort the boot and make the activation meaningless,
        // since the built-in mapping wins at runtime regardless.
        Set<String> collisions = collisions(declared.keySet());
        assertTrue(collisions.isEmpty(),
                BASE_DESCRIPTOR + " declares built-in extension(s) " + collisions
                        + "; the block is add-only and such an entry is refused at boot");
    }

    @Test
    @DisplayName("no committed descriptor declares a content type for a built-in extension")
    void noCommittedDescriptorCollidesWithABuiltInExtension() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();

        // Act + Assert — the glob must actually find the committed descriptors before the loop below
        // can mean anything.
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);

        // The same add-only rule ConfigValidator enforces per booted instance — caught here, before a
        // native image is ever built.
        for (Path descriptor : descriptors) {
            Set<String> collisions = collisions(declaredContentTypes(loadYaml(descriptor)).keySet());
            assertTrue(collisions.isEmpty(),
                    descriptor + " declares " + ASSET_DEFAULTS_KEY + "." + CONTENT_TYPES_KEY
                            + " entries for built-in extension(s) " + collisions
                            + ". That instance would abort its boot on the fail-closed add-only refusal —"
                            + " the built-in mappings are immutable because remapping one (svg away from"
                            + " image/svg+xml, or any built-in to text/html) is a stored-XSS lever.");
        }
    }

    /**
     * The declared extensions that the gateway already maps itself, read from the production map so
     * this guard cannot drift from it.
     *
     * @param declaredExtensions the extensions a descriptor declares
     * @return the offending extensions, sorted for a stable failure message; empty when none collide
     */
    private static Set<String> collisions(Set<String> declaredExtensions) {
        Set<String> builtIn = AssetResponseEnvelope.builtInExtensions();
        Set<String> collisions = new TreeSet<>();
        for (String extension : declaredExtensions) {
            if (builtIn.contains(extension.toLowerCase(Locale.ROOT))) {
                collisions.add(extension);
            }
        }
        return collisions;
    }

    /**
     * The {@code asset_defaults.content_types} entries a parsed descriptor declares.
     *
     * @param document the parsed gateway descriptor
     * @return the declared extension-to-content-type entries, empty when the block is absent
     */
    private static Map<String, String> declaredContentTypes(Map<String, Object> document) {
        Object assetDefaults = document.get(ASSET_DEFAULTS_KEY);
        if (assetDefaults == null) {
            return Map.of();
        }
        assertInstanceOf(Map.class, assetDefaults, ASSET_DEFAULTS_KEY + " must be a mapping");
        Object contentTypes = ((Map<?, ?>) assetDefaults).get(CONTENT_TYPES_KEY);
        if (contentTypes == null) {
            return Map.of();
        }
        assertInstanceOf(Map.class, contentTypes,
                ASSET_DEFAULTS_KEY + "." + CONTENT_TYPES_KEY + " must be a mapping");
        Map<String, String> declared = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) contentTypes).entrySet()) {
            declared.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return declared;
    }

    /**
     * Every committed gateway descriptor under a {@code sheriff-config} directory, discovered by glob
     * so a new instance directory is covered automatically.
     *
     * @return the descriptor paths, in directory-stream order
     * @throws IOException when the docker directory cannot be listed
     */
    private static List<Path> committedGatewayDescriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, "sheriff-config*")) {
            for (Path directory : directories) {
                Path descriptor = directory.resolve("gateway.yaml");
                if (Files.isRegularFile(descriptor)) {
                    descriptors.add(descriptor);
                }
            }
        }
        return descriptors;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
