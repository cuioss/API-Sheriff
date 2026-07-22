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
package de.cuioss.sheriff.gateway.asset;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link PathConfinement}: the canonicalize-and-confine boundary that must
 * let no request sub-path escape its configured root.
 * <p>
 * The adversarial corpus is an <em>explicit, auditable</em> set of representative
 * vectors per attack class — encoded traversal, encoded slash, single/double/mixed
 * percent-encoding, {@code %00} null bytes, overlong UTF-8, backslash separators,
 * the {@code ..;/} path-parameter trick, and dot/trailing-slash variants. It is
 * enumerated deterministically rather than sampled from a random generator on
 * purpose: the security contract is to close the whole <em>class</em>, not one
 * spelling (the Gravitee double-patch lesson), so every vector must be exercised on
 * every run. The confinement is the sole gate shared by both asset sources, so a
 * single escaping spelling is a traversal.
 */
class PathConfinementTest {

    private final PathConfinement confinement = new PathConfinement();

    @TempDir
    Path tempDir;

    private Path root;
    private Path outsideSentinel;

    private Path root() throws IOException {
        if (root == null) {
            root = Files.createDirectories(tempDir.resolve("public"));
            outsideSentinel = Files.writeString(tempDir.resolve("secret.txt"), "top-secret");
        }
        return root;
    }

    static Stream<String> traversalAttackCorpus() {
        return Stream.of(
                // plain multi-level traversal escaping the root
                "../secret.txt",
                "../../secret.txt",
                "../../../../../../etc/passwd",
                "public/../../secret.txt",
                // encoded traversal and encoded slash
                "..%2f..%2fsecret.txt",
                "%2e%2e%2f%2e%2e%2fsecret.txt",
                "%2e%2e/%2e%2e/secret.txt",
                "..%2F..%2Fsecret.txt",
                // single / double / mixed percent-encoding
                "%252e%252e%252f%252e%252e%252fsecret.txt",
                "..%252f..%252fsecret.txt",
                "%2e%2e%252fsecret.txt",
                // null-byte injection
                "../secret.txt%00.html",
                "%00../secret.txt",
                "index.html%00",
                // overlong UTF-8 traversal
                "%c0%ae%c0%ae%c0%afsecret.txt",
                "%e0%80%ae%e0%80%ae/secret.txt",
                // backslash separators (raw and encoded)
                "..\\..\\secret.txt",
                "..%5c..%5csecret.txt",
                // ..;/ path-parameter trick
                "..;/..;/secret.txt",
                "public/..;/..;/secret.txt",
                // dot and trailing-slash variants
                "....//....//secret.txt",
                ".../.../secret.txt",
                "../",
                "..%2f");
    }

    @ParameterizedTest
    @MethodSource("traversalAttackCorpus")
    @DisplayName("No adversarial traversal/encoding vector escapes the confined root")
    void shouldNeverEscapeConfinedRoot(String attack) throws Exception {
        Path confinedRoot = root().toAbsolutePath().normalize();

        Optional<Path> confined = confinement.confine(root(), attack);

        confined.ifPresent(resolved -> {
            assertTrue(resolved.startsWith(confinedRoot),
                    () -> "attack '" + attack + "' escaped confinement to: " + resolved);
            assertNotEquals(resolved, outsideSentinel.toAbsolutePath().normalize(), () -> "attack '" + attack + "' reached the out-of-root sentinel");
        });
    }

    @Test
    @DisplayName("A leading traversal that targets a sibling of the root is rejected outright")
    void shouldRejectLeadingTraversalOutright() throws Exception {
        Optional<Path> confined = confinement.confine(root(), "../secret.txt");

        assertTrue(confined.isEmpty(),
                () -> "a leading '../secret.txt' must be rejected, got: " + confined);
    }

    @Test
    @DisplayName("A null sub-path is rejected")
    void shouldRejectNullSubPath() throws Exception {
        assertTrue(confinement.confine(root(), null).isEmpty(), "a null sub-path must be rejected");
    }

    @Test
    @DisplayName("An in-root file confines to a path under the root")
    void shouldConfineInRootFile() throws Exception {
        Optional<Path> confined = confinement.confine(root(), "index.html");

        assertTrue(confined.isPresent(), "a plain in-root file must confine");
        assertTrue(confined.get().startsWith(root().toAbsolutePath().normalize()),
                () -> "the confined path must stay under the root, got: " + confined.get());
        assertTrue(confined.get().endsWith("index.html"), "the confined path must resolve the requested file");
    }

    @Test
    @DisplayName("An in-root nested sub-path confines under the root")
    void shouldConfineInRootNestedSubPath() throws Exception {
        Optional<Path> confined = confinement.confine(root(), "assets/css/app.css");

        assertTrue(confined.isPresent(), "a nested in-root sub-path must confine");
        assertTrue(confined.get().startsWith(root().toAbsolutePath().normalize()),
                () -> "the confined path must stay under the root, got: " + confined.get());
    }

    @Test
    @DisplayName("A leading-slash sub-path is treated as root-relative, not absolute")
    void shouldTreatLeadingSlashAsRootRelative() throws Exception {
        Optional<Path> confined = confinement.confine(root(), "/index.html");

        assertTrue(confined.isPresent(), "a leading-slash sub-path must confine root-relatively");
        assertTrue(confined.get().startsWith(root().toAbsolutePath().normalize()),
                () -> "a leading slash must not escape to the filesystem root, got: " + confined.get());
    }
}
