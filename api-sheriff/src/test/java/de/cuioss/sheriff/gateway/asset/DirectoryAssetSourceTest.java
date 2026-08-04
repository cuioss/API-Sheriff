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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;

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
    private static final int PAYLOAD_TOO_LARGE = 413;

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
        return new DirectoryAssetSource(root, AccessLevel.PUBLIC, Map.of());
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
    @DisplayName("Should deny an in-root entry whose real path cannot be resolved (fails closed)")
    void shouldDenyUnresolvableRealPath() throws Exception {
        Path procFd = Path.of("/proc/self/fd");
        assumeTrue(Files.isDirectory(procFd), "requires the Linux /proc filesystem to stage the entry");
        Path vanishing = Files.write(tempDir.resolve("vanishing.bin"), INDEX_BODY);
        try (var _ = Files.newInputStream(vanishing)) {
            // A held descriptor of a deleted file still stats as a regular file through its /proc magic
            // link, but its real path no longer resolves — the deterministic stand-in for the TOCTOU
            // removal the real-path check must survive.
            Files.delete(vanishing);
            Path magicLink = deletedDescriptorLink(procFd, vanishing);
            assumeTrue(magicLink != null, "no /proc/self/fd entry resolved to the deleted file");
            Path link = root.resolve("unresolvable.html");
            try {
                Files.createSymbolicLink(link, magicLink);
            } catch (IOException | UnsupportedOperationException unsupported) {
                assumeTrue(false, "symbolic links are not supported/permitted in this environment: "
                        + unsupported.getMessage());
            }
            assumeTrue(Files.isRegularFile(link), "the staged entry must still stat as a regular file");
            assertThrows(IOException.class, link::toRealPath,
                    "the staged entry must fail real-path resolution for this test to exercise the "
                            + "fail-closed branch");

            AssetSource.Served served = publicSource().serve(HttpMethod.GET, "unresolvable.html");

            assertAll(
                    () -> assertEquals(NOT_FOUND, served.status(),
                            "an entry whose real path cannot be resolved must be denied"),
                    () -> assertEquals(0, served.body().length, "no byte is served for an unresolvable entry"));
        }
    }

    /**
     * Locates the {@code /proc/self/fd} magic link that still refers to the just-deleted
     * {@code deleted} file (the kernel renders such a target as {@code <path> (deleted)}).
     */
    private static Path deletedDescriptorLink(Path procFd, Path deleted) throws IOException {
        try (Stream<Path> entries = Files.list(procFd)) {
            return entries.filter(entry -> {
                try {
                    return Files.readSymbolicLink(entry).toString().startsWith(deleted.toString());
                } catch (IOException | UnsupportedOperationException _) {
                    return false;
                }
            }).findFirst().orElse(null);
        }
    }

    @Test
    @DisplayName("Should return 404 for an in-root file that does not exist")
    void shouldReturnNotFoundForMissingFile() {
        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "missing.js");

        assertEquals(NOT_FOUND, served.status(), "a missing in-root file is a 404");
    }

    @Test
    @DisplayName("Should serve an operator-declared extension with the operator's content type")
    void shouldServeOperatorDeclaredExtension() throws Exception {
        Files.writeString(root.resolve("site.webmanifest"), "{}");
        DirectoryAssetSource source = new DirectoryAssetSource(root, AccessLevel.PUBLIC,
                Map.of("webmanifest", "application/manifest+json"));

        AssetSource.Served served = source.serve(HttpMethod.GET, "site.webmanifest");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertEquals("application/manifest+json",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                        "an extension the gateway does not map resolves to the operator's value "
                                + "instead of application/octet-stream"));
    }

    @Test
    @DisplayName("Should keep the built-in content type when an operator entry names a built-in extension")
    void shouldKeepBuiltInContentTypeAgainstOperatorOverride() {
        DirectoryAssetSource hostile = new DirectoryAssetSource(root, AccessLevel.PUBLIC,
                Map.of("html", "text/plain; charset=utf-8"));

        AssetSource.Served served = hostile.serve(HttpMethod.GET, "index.html");

        assertEquals("text/html; charset=utf-8",
                served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                "the built-in mapping is immutable — the add-only rule holds at the source too "
                        + "(and such an entry is refused at boot)");
    }

    @Test
    @DisplayName("Should force Cache-Control: no-store for an authenticated route")
    void shouldForceNoStoreForAuthenticatedRoute() {
        DirectoryAssetSource authenticated = new DirectoryAssetSource(root, AccessLevel.AUTHENTICATED, Map.of());

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

    // --- Served-asset byte cap -------------------------------------------------------------------
    // The cap had NO coverage before this suite: neither the 413 refusal nor the boundary either
    // side of it was asserted, so the whole bound rested on inspection. The three tests below pin
    // the boundary as a matched pair plus a control, so none of them can pass vacuously.

    private DirectoryAssetSource cappedSource(long maxBytes) {
        return new DirectoryAssetSource(root, AccessLevel.PUBLIC, new PathConfinement(), maxBytes, Map.of());
    }

    @Test
    @DisplayName("Should serve a file sitting exactly at the cap")
    void shouldServeFileExactlyAtCap() {
        // THE CONTROL for the two refusal tests below. Without it, a source that refused
        // unconditionally — or one whose cap was mis-derived to zero — would satisfy both of them
        // while serving nothing at all, and the pair would look like a working boundary.
        AssetSource.Served served = cappedSource(INDEX_BODY.length).serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(OK, served.status(), "a file exactly at the cap is within it and serves"),
                () -> assertArrayEquals(INDEX_BODY, served.body(),
                        "the at-cap file serves its complete body, not a truncated one"));
    }

    @Test
    @DisplayName("Should refuse a file one byte over the cap with 413 and no body")
    void shouldRefuseFileOverCap() {
        AssetSource.Served served = cappedSource(INDEX_BODY.length - 1L).serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(PAYLOAD_TOO_LARGE, served.status(),
                        "a file over the cap is refused 413"),
                () -> assertEquals(0, served.body().length,
                        "a refused asset serves no bytes at all — never a truncated prefix, which a "
                                + "caller would otherwise receive as a valid 200-shaped body"));
    }

    @Test
    @DisplayName("Should refuse an over-cap file on HEAD too, without reading it")
    void shouldRefuseOverCapFileOnHead() {
        // HEAD never reads a body, so the size pre-check alone decides it. Asserted so the cap is
        // known to gate the metadata-only verb as well, rather than only the body-carrying one.
        AssetSource.Served served = cappedSource(INDEX_BODY.length - 1L).serve(HttpMethod.HEAD, "index.html");

        assertEquals(PAYLOAD_TOO_LARGE, served.status(), "HEAD on an over-cap file is refused 413");
    }

    @Test
    @DisplayName("Should bound the read so a file larger than the cap is never materialized whole")
    void shouldBoundTheReadRatherThanTheSampledSize() throws Exception {
        // The TOCTOU remediation, asserted on its observable consequence. serve() reads through a
        // cap-bounded read (readNBytes of maxBytes+1) and then re-checks the length of what it
        // ACTUALLY read, rather than trusting the size it sampled beforehand. A file that is larger
        // than the cap therefore cannot be served whatever the earlier stat reported.
        //
        // The race itself cannot be forced deterministically from here — winning it needs the file
        // to grow between the two syscalls — so this asserts the invariant the fix establishes: the
        // refusal is driven by the bytes read. A very small cap against a much larger file makes the
        // over-cap margin unambiguous.
        byte[] large = new byte[8192];
        Files.write(root.resolve("large.html"), large);

        AssetSource.Served served = cappedSource(16L).serve(HttpMethod.GET, "large.html");

        assertAll(
                () -> assertEquals(PAYLOAD_TOO_LARGE, served.status(),
                        "a file far exceeding the cap is refused"),
                () -> assertEquals(0, served.body().length,
                        "no prefix of an over-cap file reaches the caller"));
    }

    @Test
    @DisplayName("Should read the asset byte cap from the single shared seam, not a per-class constant")
    void shouldDeriveAssetCapFromTheSharedSeam() {
        // Fitness function for the ADR-0026 'one derivation seam, not one per stage' rule, phrased
        // positively (the seam declares the cap) plus the negative leg that makes it enforceable
        // (neither implementation re-declares it). The cap was previously declared twice, as
        // independent public constants carrying duplicated literals, with nothing tying them
        // together — so a change to one would silently leave the other divergent. Deleting the
        // duplicates fixed the instance; this test is what stops it recurring, since re-adding a
        // per-class DEFAULT_MAX_BYTES is otherwise invisible at the point it is written.
        assertAll(
                () -> assertEquals(AssetSource.DEFAULT_MAX_BYTES, 10L * 1024 * 1024,
                        "the seam carries the 10 MiB served-asset cap"),
                () -> assertFalse(declaresOwnMaxBytes(DirectoryAssetSource.class),
                        "DirectoryAssetSource must read AssetSource.DEFAULT_MAX_BYTES rather than "
                                + "re-declaring the cap"),
                () -> assertFalse(declaresOwnMaxBytes(UpstreamAssetSource.class),
                        "UpstreamAssetSource must read AssetSource.DEFAULT_MAX_BYTES rather than "
                                + "re-declaring the cap"));
    }

    /**
     * @param type the asset-source implementation to inspect
     * @return {@code true} when {@code type} declares its own {@code DEFAULT_MAX_BYTES} field —
     *         the duplicated-literal shape the shared seam exists to prevent. Declared fields only,
     *         so the constant inherited from {@link AssetSource} is deliberately not counted.
     */
    private static boolean declaresOwnMaxBytes(Class<?> type) {
        return Stream.of(type.getDeclaredFields())
                .anyMatch(field -> "DEFAULT_MAX_BYTES".equals(field.getName()));
    }
}
