/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.asset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.HttpMethod;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DirectoryAssetSource}: in-root files serve through the shared
 * {@link AssetResponseEnvelope}; the shared {@link PathConfinement} denies every
 * out-of-root escape (lexically, via encoding/traversal); a symlink planted under root
 * that resolves outside it is independently denied by the real-path check; the content
 * type resolves from the fixed gateway map; and an {@link AccessLevel#AUTHENTICATED}
 * route forces {@code Cache-Control: no-store}.
 */
class DirectoryAssetSourceTest {

    private static final byte[] INDEX_BODY = "<html><body>home</body></html>".getBytes(StandardCharsets.UTF_8);
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void arrangeDirectory() throws IOException {
        root = Files.createDirectories(tempDir.resolve("public"));
        Files.write(root.resolve("index.html"), INDEX_BODY);
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/app.css"), "body{color:red}");
        Files.writeString(tempDir.resolve("secret.txt"), "top-secret");
    }

    private DirectoryAssetSource publicSource() {
        return new DirectoryAssetSource(root, AccessLevel.PUBLIC);
    }

    @Test
    @DisplayName("Should serve an in-root file with body and the governed envelope")
    void shouldServeInRootFile() {
        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(OK, served.status(), "an in-root file should serve 200"),
                () -> assertArrayEquals(INDEX_BODY, served.body(), "the file body should be streamed"),
                () -> assertEquals("text/html; charset=utf-8",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                        "the content type resolves from the fixed gateway map"),
                () -> assertEquals(AssetResponseEnvelope.NOSNIFF,
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE_OPTIONS),
                        "nosniff is always set"));
    }

    @Test
    @DisplayName("Should resolve the content type from the fixed map for a nested asset")
    void shouldResolveContentTypeFromMap() {
        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "assets/app.css");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertEquals("text/css; charset=utf-8",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE)));
    }

    @Test
    @DisplayName("Should deny an out-of-root traversal with 404 and never read the sentinel")
    void shouldDenyOutOfRootTraversal() {
        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "../secret.txt");

        assertAll(
                () -> assertEquals(NOT_FOUND, served.status(), "an escape attempt must be denied"),
                () -> assertEquals(0, served.body().length, "no byte of the sentinel is served"));
    }

    @Test
    @DisplayName("Should deny a symlink under root that resolves outside it (real-path escape)")
    void shouldDenySymlinkEscapingRoot() {
        Path outsideTarget = tempDir.resolve("secret.txt");
        Path link = root.resolve("linked-secret.txt");
        try {
            Files.createSymbolicLink(link, outsideTarget);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false,
                    "symbolic links are not supported/permitted in this environment: " + unsupported.getMessage());
        }

        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "linked-secret.txt");

        assertAll(
                () -> assertEquals(NOT_FOUND, served.status(),
                        "a symlink resolving outside the root must be denied even though it lexically "
                                + "sits inside root"),
                () -> assertEquals(0, served.body().length, "no byte of the symlink target is served"));
    }

    @Test
    @DisplayName("Should return 404 for an in-root file that does not exist")
    void shouldReturnNotFoundForMissingFile() {
        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "missing.js");

        assertEquals(NOT_FOUND, served.status(), "a missing in-root file is a 404");
    }

    @Test
    @DisplayName("Should force Cache-Control: no-store for an authenticated route")
    void shouldForceNoStoreForAuthenticatedRoute() {
        DirectoryAssetSource authenticated = new DirectoryAssetSource(root, AccessLevel.AUTHENTICATED);

        AssetSource.Served served = authenticated.serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertEquals(AssetResponseEnvelope.NO_STORE,
                        served.headers().get(AssetResponseEnvelope.CACHE_CONTROL),
                        "an authenticated asset must be no-store"));
    }

    @Test
    @DisplayName("Should not force no-store for a public route")
    void shouldNotForceNoStoreForPublicRoute() {
        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "index.html");

        assertNotEquals(AssetResponseEnvelope.NO_STORE,
                served.headers().get(AssetResponseEnvelope.CACHE_CONTROL),
                "a public asset is not forced to no-store");
    }

    @Test
    @DisplayName("Should serve HEAD with the governed headers and an empty body")
    void shouldServeHeadWithoutBody() {
        AssetSource.Served served = publicSource().serve(HttpMethod.HEAD, "index.html");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertEquals(0, served.body().length, "HEAD carries no body"),
                () -> assertEquals("text/html; charset=utf-8",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                        "HEAD still carries the governed headers"));
    }

    @Test
    @DisplayName("Should reject a write verb with 405")
    void shouldRejectWriteVerb() {
        AssetSource.Served served = publicSource().serve(HttpMethod.POST, "index.html");

        assertEquals(METHOD_NOT_ALLOWED, served.status(), "POST must be rejected 405");
    }
}
