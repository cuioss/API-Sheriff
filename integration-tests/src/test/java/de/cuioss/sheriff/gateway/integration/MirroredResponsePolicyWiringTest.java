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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that every integration-test overlay descriptor which
 * <em>mirrors</em> a response-policy block of the base descriptor keeps it identical to the base.
 * <p>
 * Several {@code sheriff-config-*} overlays restate the base document's top-level
 * {@code security_headers} block — the response security headers together with the global
 * {@code cors} policy — or an anchor's {@code security_headers} block, so that an IT running against
 * the overlay instance observes the same response policy as one running against the primary. Nothing
 * else ties the copies together: an edit to the base block that is not repeated in the overlay leaves
 * that instance answering under a different policy, and an IT that compares the two instances then
 * measures the drift instead of the property it was written for.
 * <p>
 * The guard therefore discovers every {@code sheriff-config-*}{@code /gateway.yaml} by directory glob
 * (the glob must match at least one overlay, so a moved tree fails loudly instead of passing with
 * nothing to compare) and asserts two things over the <em>parsed</em> YAML structures, never the text:
 * <ul>
 *   <li>an overlay that declares a top-level {@code security_headers} block declares exactly the
 *       base block;</li>
 *   <li>an overlay anchor that declares {@code security_headers} and has a same-named anchor in the
 *       base declares exactly that anchor's block.</li>
 * </ul>
 * An overlay that omits a block mirrors nothing and is not judged. An overlay that diverges on
 * purpose is named, with its justification, in {@link #DELIBERATE_DIVERGENCE}; that list is empty,
 * and an entry that no longer diverges fails the guard so the list cannot go stale.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class MirroredResponsePolicyWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** The shared base descriptor every overlay mirrors its response policy from. */
    private static final Path BASE_DESCRIPTOR = DOCKER.resolve("sheriff-config/gateway.yaml");

    /** The overlay directories, as a directory-name glob below {@link #DOCKER}. */
    private static final String OVERLAY_GLOB = "sheriff-config-*";

    private static final String DESCRIPTOR = "gateway.yaml";
    private static final String SECURITY_HEADERS = "security_headers";
    private static final String ANCHORS = "anchors";

    /**
     * Overlays allowed to diverge from the base, keyed by overlay directory name, with the reason.
     * Each entry names an overlay whose top-level or anchor {@code security_headers} block differs
     * from the base on purpose. Empty: no committed overlay diverges.
     */
    private static final Map<String, String> DELIBERATE_DIVERGENCE = Map.of();

    @Test
    @DisplayName("an overlay declaring a top-level security_headers block declares exactly the base block")
    void mirroredGlobalBlockEqualsTheBase() throws Exception {
        // Arrange
        Object baseBlock = globalBlock(load(BASE_DESCRIPTOR));
        List<String> drifted = new ArrayList<>();

        // Act
        for (Map.Entry<String, Map<?, ?>> overlay : overlays().entrySet()) {
            Object overlayBlock = overlay.getValue().get(SECURITY_HEADERS);
            if (overlayBlock != null && !overlayBlock.equals(baseBlock)
                    && !DELIBERATE_DIVERGENCE.containsKey(overlay.getKey())) {
                drifted.add(describe(overlay.getKey(), SECURITY_HEADERS, overlayBlock, baseBlock));
            }
        }

        // Assert
        assertTrue(drifted.isEmpty(), "overlay descriptors mirror the top-level " + SECURITY_HEADERS
                + " block of " + BASE_DESCRIPTOR + " but no longer equal it — repeat the base edit in"
                + " the overlay, or name the overlay with its reason in DELIBERATE_DIVERGENCE:\n"
                + String.join("\n", drifted));
    }

    @Test
    @DisplayName("an overlay anchor declaring security_headers declares exactly the same-named base anchor's block")
    void mirroredAnchorBlockEqualsTheBase() throws Exception {
        // Arrange
        Map<?, ?> baseAnchors = anchors(load(BASE_DESCRIPTOR));
        List<String> drifted = new ArrayList<>();

        // Act
        for (Map.Entry<String, Map<?, ?>> overlay : overlays().entrySet()) {
            for (Map.Entry<?, ?> anchor : anchors(overlay.getValue()).entrySet()) {
                Object overlayBlock = securityHeadersOf(anchor.getValue());
                Object baseAnchor = baseAnchors.get(anchor.getKey());
                if (overlayBlock == null || baseAnchor == null
                        || DELIBERATE_DIVERGENCE.containsKey(overlay.getKey())) {
                    continue;
                }
                Object baseBlock = securityHeadersOf(baseAnchor);
                if (!overlayBlock.equals(baseBlock)) {
                    drifted.add(describe(overlay.getKey(), "anchors." + anchor.getKey() + "."
                            + SECURITY_HEADERS, overlayBlock, baseBlock));
                }
            }
        }

        // Assert
        assertTrue(drifted.isEmpty(), "overlay anchors mirror an anchor " + SECURITY_HEADERS
                + " block of " + BASE_DESCRIPTOR + " but no longer equal it — repeat the base edit in"
                + " the overlay, or name the overlay with its reason in DELIBERATE_DIVERGENCE:\n"
                + String.join("\n", drifted));
    }

    @Test
    @DisplayName("the guard compares something: the base carries the blocks and at least one overlay mirrors each kind")
    void theComparisonIsNotVacuous() throws Exception {
        // Arrange
        Map<?, ?> base = load(BASE_DESCRIPTOR);
        Map<?, ?> baseAnchors = anchors(base);
        Map<String, Map<?, ?>> overlays = overlays();

        // Act
        long globalMirrors = overlays.values().stream()
                .filter(overlay -> overlay.get(SECURITY_HEADERS) != null)
                .count();
        long anchorMirrors = overlays.values().stream()
                .flatMap(overlay -> anchors(overlay).entrySet().stream())
                .filter(anchor -> securityHeadersOf(anchor.getValue()) != null
                        && baseAnchors.containsKey(anchor.getKey()))
                .count();

        // Assert — a guard whose comparison set is empty passes against anything
        assertNotNull(globalBlock(base), BASE_DESCRIPTOR + " declares no top-level " + SECURITY_HEADERS
                + " block, so there is nothing for an overlay to mirror");
        assertTrue(globalMirrors > 0, "no overlay under " + DOCKER + " matching '" + OVERLAY_GLOB
                + "' declares a top-level " + SECURITY_HEADERS + " block, so the global comparison"
                + " compares nothing; if the mirrors were removed on purpose, remove this guard with them");
        assertTrue(anchorMirrors > 0, "no overlay anchor under " + DOCKER + " matching '" + OVERLAY_GLOB
                + "' declares a " + SECURITY_HEADERS + " block for an anchor the base also declares, so"
                + " the anchor comparison compares nothing; if the mirrors were removed on purpose,"
                + " remove this guard with them");
    }

    @Test
    @DisplayName("every DELIBERATE_DIVERGENCE entry names an existing overlay that still diverges")
    void theExemptionListIsNotStale() throws Exception {
        // Arrange
        Map<?, ?> base = load(BASE_DESCRIPTOR);
        Map<String, Map<?, ?>> overlays = overlays();

        // Act + Assert
        for (Map.Entry<String, String> exemption : DELIBERATE_DIVERGENCE.entrySet()) {
            assertFalse(exemption.getValue().isBlank(), "the DELIBERATE_DIVERGENCE entry for '"
                    + exemption.getKey() + "' carries no justification");
            Map<?, ?> overlay = overlays.get(exemption.getKey());
            assertNotNull(overlay, "DELIBERATE_DIVERGENCE names '" + exemption.getKey()
                    + "', which is no overlay under " + DOCKER + "; remove the entry");
            assertTrue(diverges(overlay, base), "DELIBERATE_DIVERGENCE names '" + exemption.getKey()
                    + "', whose " + SECURITY_HEADERS + " blocks now equal the base " + BASE_DESCRIPTOR
                    + "; remove the entry so the overlay is guarded again");
        }
    }

    /**
     * Whether an overlay's top-level or any same-named anchor {@code security_headers} block differs
     * from the base.
     *
     * @param overlay the parsed overlay document
     * @param base the parsed base document
     * @return {@code true} when at least one mirrored block differs from the base
     */
    private static boolean diverges(Map<?, ?> overlay, Map<?, ?> base) {
        Object overlayGlobal = overlay.get(SECURITY_HEADERS);
        if (overlayGlobal != null && !overlayGlobal.equals(globalBlock(base))) {
            return true;
        }
        Map<?, ?> baseAnchors = anchors(base);
        for (Map.Entry<?, ?> anchor : anchors(overlay).entrySet()) {
            Object overlayBlock = securityHeadersOf(anchor.getValue());
            Object baseAnchor = baseAnchors.get(anchor.getKey());
            if (overlayBlock != null && baseAnchor != null
                    && !overlayBlock.equals(securityHeadersOf(baseAnchor))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every overlay descriptor, keyed by overlay directory name. An overlay directory without a
     * {@code gateway.yaml} declares no gateway document and is skipped.
     *
     * @return the parsed overlays, sorted by directory name, guaranteed non-empty
     * @throws IOException when the docker tree or a descriptor cannot be read
     */
    private static Map<String, Map<?, ?>> overlays() throws IOException {
        Map<String, Map<?, ?>> overlays = new TreeMap<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, OVERLAY_GLOB)) {
            for (Path directory : directories) {
                Path descriptor = directory.resolve(DESCRIPTOR);
                if (Files.isRegularFile(descriptor)) {
                    overlays.put(String.valueOf(directory.getFileName()), load(descriptor));
                }
            }
        }
        assertFalse(overlays.isEmpty(), "no '" + OVERLAY_GLOB + "/" + DESCRIPTOR + "' was found under "
                + DOCKER + ", so this guard would compare nothing; check the module working directory");
        return overlays;
    }

    private static Map<?, ?> load(Path descriptor) throws IOException {
        try (InputStream in = Files.newInputStream(descriptor)) {
            Object document = new Yaml().load(in);
            if (document instanceof Map<?, ?> mapping) {
                return mapping;
            }
            return fail(descriptor + " is not a YAML mapping");
        }
    }

    private static Object globalBlock(Map<?, ?> document) {
        return document.get(SECURITY_HEADERS);
    }

    private static Map<?, ?> anchors(Map<?, ?> document) {
        return document.get(ANCHORS) instanceof Map<?, ?> anchors ? anchors : Map.of();
    }

    private static Object securityHeadersOf(Object anchor) {
        return anchor instanceof Map<?, ?> declaration ? declaration.get(SECURITY_HEADERS) : null;
    }

    private static String describe(String overlay, String block, Object overlayBlock, Object baseBlock) {
        return "  " + DOCKER.resolve(overlay).resolve(DESCRIPTOR) + " -> " + block + " is " + overlayBlock
                + ", the base " + BASE_DESCRIPTOR + " declares " + baseBlock;
    }
}
