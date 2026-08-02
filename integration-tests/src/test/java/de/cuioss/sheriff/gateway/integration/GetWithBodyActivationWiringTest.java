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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed base descriptor actually
 * <strong>activates</strong> the {@code GET}-body opt-in, and that no descriptor declares it with a
 * non-boolean value the schema would refuse.
 * <p>
 * This closes a unit-green / integration-red blind spot. {@code FramingGateTest} proves
 * {@code config -> behaviour} — with the opt-in on, a {@code Content-Length}-framed {@code GET} body
 * is admitted while {@code Transfer-Encoding} on {@code GET} and any {@code HEAD} body stay
 * rejected. What it cannot prove is {@code committed-descriptor -> activated}: that the shipped
 * {@code gateway.yaml} declares the key at all. Without this assertion the opt-in could ship fully
 * unit-covered and entirely inert, with the containerised suite still meeting a {@code 400} on the
 * very request the feature exists to admit.
 * <p>
 * The descriptors are discovered by glob rather than hard-coded, so a new {@code sheriff-config-*}
 * directory comes under the type assertion automatically — and a glob matching fewer than the
 * descriptors present today fails rather than passing vacuously. Only the base descriptor is
 * required to declare the opt-in: the sibling instances (mtls, cookie, ws-admission) deliberately
 * keep the strict default, which is itself worth pinning — an accidental gateway-wide relaxation
 * across every instance would go unnoticed otherwise.
 * <p>
 * It parses the committed descriptors only (YAML text) and starts no container and reaches no
 * network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class GetWithBodyActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** The base descriptor — the one instance the suite expects the opt-in activated on. */
    private static final Path BASE_DESCRIPTOR = DOCKER.resolve("sheriff-config/gateway.yaml");

    private static final String SECURITY_DEFAULTS_KEY = "security_defaults";
    private static final String OPT_IN_KEY = "allow_get_with_content_length_body";

    /**
     * The descriptor count committed today. The glob must match at least this many, so an empty or
     * mis-rooted glob fails loudly instead of satisfying the per-descriptor loop vacuously.
     */
    private static final int COMMITTED_DESCRIPTOR_COUNT = 4;

    @Test
    @DisplayName("the base descriptor activates the GET-body opt-in")
    void baseDescriptorActivatesTheOptIn() throws Exception {
        // Arrange
        Object declared = declaredOptIn(loadYaml(BASE_DESCRIPTOR));

        // Assert — an absent key means the framing gate keeps the strict default, so the feature
        // would be inert in the shipped deployment while every unit test stayed green.
        assertNotNull(declared, BASE_DESCRIPTOR + " must declare " + SECURITY_DEFAULTS_KEY + "."
                + OPT_IN_KEY + ", otherwise the shipped gateway never exercises the GET-body path");
        assertEquals(Boolean.TRUE, declared,
                BASE_DESCRIPTOR + " must declare " + OPT_IN_KEY + ": true to activate the opt-in");
    }

    @Test
    @DisplayName("every committed descriptor declares the opt-in as a boolean, or not at all")
    void everyCommittedDescriptorDeclaresABooleanOrNothing() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();

        // Act + Assert — the glob must actually find the committed descriptors before the loop below
        // can mean anything.
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);

        // The schema types this key as a boolean; a string "true" would fail binding at boot.
        for (Path descriptor : descriptors) {
            Object declared = declaredOptIn(loadYaml(descriptor));
            if (declared != null) {
                assertInstanceOf(Boolean.class, declared,
                        descriptor + " declares " + OPT_IN_KEY + " as " + declared.getClass().getSimpleName()
                                + "; the schema types it as a boolean and that instance would fail its boot");
            }
        }
    }

    @Test
    @DisplayName("the sibling descriptors keep the strict default rather than relaxing gateway-wide")
    void siblingDescriptorsKeepTheStrictDefault() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();

        // Assert — pinning the siblings is what makes an accidental blanket relaxation visible.
        for (Path descriptor : descriptors) {
            if (descriptor.equals(BASE_DESCRIPTOR)) {
                continue;
            }
            Object declared = declaredOptIn(loadYaml(descriptor));
            assertTrue(declared == null || Boolean.FALSE.equals(declared),
                    descriptor + " declares " + OPT_IN_KEY + "=" + declared
                            + "; only the base descriptor activates the opt-in, so a relaxation here is"
                            + " an accidental gateway-wide widening of the framing gate");
        }
    }

    /**
     * The declared {@code security_defaults.allow_get_with_content_length_body} value.
     *
     * @param document the parsed gateway descriptor
     * @return the declared value, or {@code null} when the block or key is absent
     */
    private static Object declaredOptIn(Map<String, Object> document) {
        Object securityDefaults = document.get(SECURITY_DEFAULTS_KEY);
        if (securityDefaults == null) {
            return null;
        }
        assertInstanceOf(Map.class, securityDefaults, SECURITY_DEFAULTS_KEY + " must be a mapping");
        return ((Map<?, ?>) securityDefaults).get(OPT_IN_KEY);
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
