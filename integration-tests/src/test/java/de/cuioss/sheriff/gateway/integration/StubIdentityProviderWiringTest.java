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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard over the one container image the integration tests start
 * that the compose stack does not: the stub identity provider of {@link StubIdentityProviderRig}.
 * <p>
 * It holds two properties of {@link StubIdentityProviderRig#IMAGE}:
 * <ul>
 *   <li><strong>The reference is pinned.</strong> It has the shape
 *       {@code name:tag@sha256:<64 hex>} — an exact release tag <em>and</em> the digest. A tag alone
 *       can be moved to another image, so a reference that loses its digest fails here.</li>
 *   <li><strong>The image stays a test fixture.</strong> Its name appears in neither
 *       {@code integration-tests/docker-compose.yml}, nor {@code api-sheriff/pom.xml}, nor any file
 *       below {@code api-sheriff/src/main/docker/} — so it can reach neither the stack every other
 *       suite runs against, nor the {@code api-sheriff} classpath, nor the production image, without
 *       this guard failing.</li>
 * </ul>
 * <p>
 * <strong>Anti-vacuity.</strong> The shape pattern is shown to refuse a reference without a digest,
 * with a shortened digest and without a tag, so a pattern loosened to accept anything fails. The
 * absence scan is shown to find the name in the rig's own source with the very predicate it applies
 * to the production inputs, and each production input must exist and be non-empty, so a scan that
 * reads nothing fails instead of reporting absence.
 * <p>
 * It reads committed files only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class StubIdentityProviderWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** An image name, an exact tag and the sha256 digest of the image, in that order. */
    private static final Pattern PINNED_REFERENCE = Pattern.compile(
            "[a-z0-9]+(?:[._/-][a-z0-9]+)*:[A-Za-z0-9_][A-Za-z0-9_.-]*@sha256:[0-9a-f]{64}");

    private static final String DIGEST_SEPARATOR = "@sha256:";

    /** The rig's own source, relative to the module: the one file that has to name the image. */
    private static final Path RIG_SOURCE = Path.of("src", "test", "java", "de", "cuioss", "sheriff", "gateway",
            "integration", "StubIdentityProviderRig.java");

    private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");

    /** Relative to the repository root. */
    private static final Path PRODUCTION_POM = Path.of("api-sheriff", "pom.xml");

    /** Relative to the repository root: the build inputs of the production image. */
    private static final Path PRODUCTION_DOCKER = Path.of("api-sheriff", "src", "main", "docker");

    @Test
    @DisplayName("the stub image reference is an exact tag together with a sha256 digest")
    void imageReferenceIsPinnedByTagAndDigest() {
        String reference = StubIdentityProviderRig.IMAGE;

        boolean pinned = PINNED_REFERENCE.matcher(reference).matches();

        assertTrue(pinned, () -> "StubIdentityProviderRig.IMAGE must have the shape name:tag@sha256:<64 hex>, "
                + "so a moved tag cannot change what the suite runs; found " + reference);
    }

    @Test
    @DisplayName("control: a reference without its digest, with a shortened digest or without a tag is refused")
    void unpinnedReferencesAreRefused() {
        String reference = StubIdentityProviderRig.IMAGE;
        int digestAt = reference.indexOf(DIGEST_SEPARATOR);
        assertTrue(digestAt > 0, () -> "the reference under test carries no digest: " + reference);
        String withoutDigest = reference.substring(0, digestAt);
        String shortenedDigest = reference.substring(0, reference.length() - 1);
        String withoutTag = imageName() + reference.substring(digestAt);

        assertAll("references that are not pinned by tag and digest",
                () -> assertFalse(PINNED_REFERENCE.matcher(withoutDigest).matches(),
                        "a reference without its digest must be refused: " + withoutDigest),
                () -> assertFalse(PINNED_REFERENCE.matcher(shortenedDigest).matches(),
                        "a digest shorter than 64 hex digits must be refused: " + shortenedDigest),
                () -> assertFalse(PINNED_REFERENCE.matcher(withoutTag).matches(),
                        "a reference without a tag must be refused: " + withoutTag));
    }

    @Test
    @DisplayName("the stub image is named by no compose file and no production build input")
    void imageIsNamedByNoComposeFileAndNoProductionBuildInput() throws IOException {
        List<Path> productionInputs = productionInputs();

        List<Path> naming = new ArrayList<>();
        for (Path input : productionInputs) {
            if (namesTheImage(input)) {
                naming.add(input);
            }
        }

        assertEquals(List.of(), naming, () -> "the stub identity provider is a test-only image that "
                + "StubIdentityProviderRig alone starts; it must not be named by the compose stack, the "
                + "api-sheriff build or the production image inputs. Named in " + naming);
    }

    @Test
    @DisplayName("control: the absence scan finds the image name in the rig's own source")
    void absenceScanFindsTheNameWhereItIsDeclared() throws IOException {
        Path rigSource = MODULE.resolve(RIG_SOURCE);
        assertTrue(Files.isRegularFile(rigSource), () -> "the rig source was not found at " + rigSource
                + "; point this guard at its new location rather than leaving the control vacuous");

        boolean named = namesTheImage(rigSource);

        assertTrue(named, () -> "the scan applied to the production inputs must find '" + imageToken()
                + "' in " + rigSource + ", where StubIdentityProviderRig.IMAGE declares it; a scan that "
                + "cannot find it there proves nothing by not finding it elsewhere");
    }

    /**
     * The files the image must not be named in: the compose file, the {@code api-sheriff} POM and
     * every regular file below the production docker directory. Each must exist and hold content.
     */
    private static List<Path> productionInputs() throws IOException {
        Path repositoryRoot = MODULE.getParent();
        assertNotNull(repositoryRoot, () -> "the module directory " + MODULE + " has no parent, so the "
                + "repository root cannot be resolved");
        Path productionDocker = repositoryRoot.resolve(PRODUCTION_DOCKER);
        assertTrue(Files.isDirectory(productionDocker),
                () -> "the production docker directory was not found at " + productionDocker);

        List<Path> inputs = new ArrayList<>();
        inputs.add(MODULE.resolve(COMPOSE_FILE));
        inputs.add(repositoryRoot.resolve(PRODUCTION_POM));
        int fixedInputs = inputs.size();
        try (Stream<Path> files = Files.walk(productionDocker)) {
            files.filter(Files::isRegularFile).sorted().forEach(inputs::add);
        }
        assertTrue(inputs.size() > fixedInputs,
                () -> productionDocker + " holds no file, so the scan of the production image inputs is empty");
        for (Path input : inputs) {
            assertTrue(Files.isRegularFile(input) && Files.size(input) > 0,
                    () -> "the scanned input " + input + " is missing or empty");
        }
        return inputs;
    }

    /**
     * Whether a file names the stub image. The match is on the last segment of the image name, without
     * regard to letter case, so the same image under another registry or namespace is found too. The
     * file is decoded as ISO-8859-1, which maps every byte, so a binary file cannot fail the read.
     */
    private static boolean namesTheImage(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        return content.toLowerCase(Locale.ROOT).contains(imageToken());
    }

    /** The image name: the reference up to its tag. */
    private static String imageName() {
        String reference = StubIdentityProviderRig.IMAGE;
        int tagAt = reference.indexOf(':');
        assertTrue(tagAt > 0, () -> "the reference carries no image name before a tag: " + reference);
        return reference.substring(0, tagAt);
    }

    /** The last path segment of the image name, lower-cased. */
    private static String imageToken() {
        String name = imageName();
        String token = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        assertFalse(token.isBlank(), () -> "the image name " + name + " has no last segment to search for");
        return token;
    }
}
