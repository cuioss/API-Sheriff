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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
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
 * that resolves outside it is independently denied by the real-path check — whether it is
 * the requested file itself or an ancestor directory of it; the descriptor-relative walk
 * refuses to traverse a symlinked component; the content type resolves from the fixed
 * gateway map; and an {@link AccessLevel#AUTHENTICATED} route forces
 * {@code Cache-Control: no-store}.
 */
class DirectoryAssetSourceTest {

    private static final byte[] INDEX_BODY = "<html><body>home</body></html>".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "top-secret";
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int SERVER_ERROR = 500;

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void arrangeDirectory() throws IOException {
        root = Files.createDirectories(tempDir.resolve("public"));
        Files.write(root.resolve("index.html"), INDEX_BODY);
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/app.css"), "body{color:red}");
        Files.writeString(tempDir.resolve("secret.txt"), SECRET);
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
                () -> assertEquals(OK, served.status(), "a nested in-root asset serves 200"),
                () -> assertEquals("text/css; charset=utf-8",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                        "the nested asset's extension resolves through the same fixed map"));
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
        assumeSymlink(link, outsideTarget);

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
            assumeSymlink(link, magicLink);
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
                () -> assertEquals(OK, served.status(), "an operator-declared extension still serves"),
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
                () -> assertEquals(OK, served.status(), "an authenticated route still serves the asset"),
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
                () -> assertEquals(OK, served.status(), "HEAD on an in-root file serves 200"),
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

    // --- Ancestor-component confinement ----------------------------------------------------------
    // NOFOLLOW_LINKS constrains the FINAL path component only, so refusing a swapped final component
    // is not the whole window: an ANCESTOR directory of a proven-in-root path can be re-pointed just
    // as easily. The three tests below pin the two halves of the answer — an ancestor that resolves
    // out of root is refused before any byte is touched, the descriptor-relative walk refuses to
    // traverse a symlinked ancestor at all, and a legitimate in-root symlinked ancestor still serves
    // (the control that stops the first two from passing by refusing everything).

    @Test
    @DisplayName("Should deny an asset reached through an ancestor symlink that leaves the root")
    void shouldDenyAssetBehindOutOfRootAncestorSymlink() throws Exception {
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outsideDir.resolve("secret.html"), SECRET);
        assumeSymlink(root.resolve("escape"), outsideDir);

        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "escape/secret.html");

        assertAll(
                () -> assertEquals(NOT_FOUND, served.status(),
                        "an asset whose ANCESTOR directory resolves outside the root must be denied — "
                                + "refusing only a swapped final component would serve this one"),
                () -> assertEquals(0, served.body().length,
                        "no byte of the out-of-root file reaches the caller"),
                () -> assertNotEquals(SECRET, new String(served.body(), StandardCharsets.UTF_8),
                        "the out-of-root sentinel content must never appear in the response body"));
    }

    @Test
    @DisplayName("Should refuse to walk down through a symlinked ancestor even when it stays in root")
    void shouldRefuseDescentThroughSymlinkedAncestor() throws Exception {
        assumeSecureWalk();
        assumeSymlink(root.resolve("alias"), root.resolve("assets"));
        Path realRoot = root.toRealPath();
        DirectoryAssetSource.SecureWalkOpener walker = new DirectoryAssetSource.SecureWalkOpener();

        assertAll(
                () -> assertThrows(IOException.class,
                        () -> walker.open(realRoot, Path.of("alias", "app.css")),
                        "the descent must refuse a symlinked ancestor — following it is exactly the "
                                + "re-pointing NOFOLLOW_LINKS on the final component alone cannot stop"),
                () -> {
                    // THE CONTROL: the same walker reaches the same file through the real directory
                    // name, so the refusal above is the symlink and not a walker that fails always.
                    try (DirectoryAssetSource.ConfinedAsset asset =
                                 walker.open(realRoot, Path.of("assets", "app.css"))) {
                        assertTrue(asset.size() > 0,
                                "the walker must reach an asset through its real ancestor name");
                    }
                });
    }

    @Test
    @DisplayName("Should still serve an asset behind an in-root ancestor symlink")
    void shouldServeAssetBehindInRootAncestorSymlink() throws Exception {
        // THE CONTROL for the two refusals above, at the serve() level: confinement resolves the
        // ancestor symlink BEFORE the walk, so a legitimate in-root symlinked directory keeps
        // working. Without this, an implementation that refused every symlinked ancestor — including
        // the in-root ones operators legitimately deploy — would satisfy both refusal tests.
        assumeSymlink(root.resolve("alias"), root.resolve("assets"));

        AssetSource.Served served = publicSource().serve(HttpMethod.GET, "alias/app.css");

        assertAll(
                () -> assertEquals(OK, served.status(),
                        "an ancestor symlink resolving INSIDE the root is legitimate and must serve"),
                () -> assertArrayEquals("body{color:red}".getBytes(StandardCharsets.UTF_8), served.body(),
                        "the asset behind the in-root ancestor symlink serves its real bytes"));
    }

    // --- Served-asset byte cap -------------------------------------------------------------------
    // The cap had NO coverage before this suite: neither the 413 refusal nor the boundary either
    // side of it was asserted, so the whole bound rested on inspection. The tests below pin the
    // boundary as a matched pair plus a control, so none of them can pass vacuously.

    private DirectoryAssetSource cappedSource(long maxBytes) {
        return new DirectoryAssetSource(root, AccessLevel.PUBLIC, new PathConfinement(), maxBytes, Map.of());
    }

    private DirectoryAssetSource cappedSource(long maxBytes, DirectoryAssetSource.Opener opener) {
        return new DirectoryAssetSource(root, AccessLevel.PUBLIC, new PathConfinement(), maxBytes, Map.of(),
                opener);
    }

    @Test
    @DisplayName("Should serve a file sitting exactly at the cap")
    void shouldServeFileExactlyAtCap() {
        // THE CONTROL for the refusal tests below. Without it, a source that refused
        // unconditionally — or one whose cap was mis-derived to zero — would satisfy all of them
        // while serving nothing at all, and the set would look like a working boundary.
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
    @DisplayName("Should refuse a far-over-cap file at the size fast path without reading it")
    void shouldRefuseFarOverCapFileAtTheSizeFastPath() throws Exception {
        // This asserts the CHEAP leg only: a file whose stat already exceeds the cap is refused
        // before a byte is read. It is deliberately NOT the bounded-read assertion — the stat and
        // the read agree here, so the post-read check is never reached. The test below is the one
        // that drives them apart.
        Files.write(root.resolve("large.html"), new byte[8192]);

        AssetSource.Served served = cappedSource(16L).serve(HttpMethod.GET, "large.html");

        assertAll(
                () -> assertEquals(PAYLOAD_TOO_LARGE, served.status(),
                        "a file far exceeding the cap is refused at the stat"),
                () -> assertEquals(0, served.body().length,
                        "no prefix of an over-cap file reaches the caller"));
    }

    @Test
    @DisplayName("Should refuse on the bytes actually read when the stat under-reports the asset")
    void shouldRefuseOnBytesReadWhenStatUnderReportsTheAsset() {
        // The TOCTOU remediation, on the arm that actually exercises it. A file which GROWS between
        // the stat and the read presents exactly this shape: a size within the cap followed by a
        // read that yields more. The size fast path passes it, so only the check on the bytes really
        // materialized can refuse it — which is why an unbounded readAllBytes would fail here while
        // it passes every stat-driven test above.
        long cap = 16L;

        AssetSource.Served served = cappedSource(cap, understatingOpener(cap, 8192))
                .serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(PAYLOAD_TOO_LARGE, served.status(),
                        "an asset whose read yields more than the cap is refused even though its stat "
                                + "reported a size within the cap"),
                () -> assertEquals(0, served.body().length,
                        "the over-cap refusal serves no bytes — not even the bounded prefix that was "
                                + "read to detect the breach"));
    }

    @Test
    @DisplayName("Should serve when the stat under-reports but the read stays within the cap")
    void shouldServeWhenReadStaysWithinCapDespiteDisagreeingStat() {
        // THE CONTROL for the test above: the same disagreeing-seam construction, but with a read
        // that stays inside the cap. Without it, a source that refused whenever the stat and the
        // read disagreed at all — rather than on the bytes read exceeding the cap — would satisfy
        // the refusal test while breaking every legitimate asset whose size shifted harmlessly.
        long cap = 16L;

        AssetSource.Served served = cappedSource(cap, understatingOpener(1L, 8))
                .serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(OK, served.status(),
                        "a read within the cap serves, whatever the stat reported"),
                () -> assertEquals(8, served.body().length,
                        "the bytes actually read are the bytes served"));
    }

    @Test
    @DisplayName("Should answer 500 when the asset cannot be opened")
    void shouldAnswerServerErrorWhenAssetCannotBeOpened() {
        // The arm a component re-pointed to a symlink AFTER the in-root check lands on: the
        // descriptor walk refuses it with an IOException rather than reading out of root. The race
        // itself cannot be forced from here, so the seam stages the same failure deterministically.
        AssetSource.Served served = cappedSource(1024L, (realRoot, relative) -> {
            throw new IOException("staged asset-open failure");
        }).serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(SERVER_ERROR, served.status(),
                        "an asset that cannot be opened is a 500 — never a 200 carrying whatever "
                                + "bytes were reachable"),
                () -> assertEquals(0, served.body().length, "a failed read serves no bytes"));
    }

    @Test
    @DisplayName("Should answer 500 when the asset opens but cannot be read")
    void shouldAnswerServerErrorWhenAssetCannotBeRead() {
        AssetSource.Served served = cappedSource(1024L, failingReadOpener())
                .serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(SERVER_ERROR, served.status(),
                        "a read failure after a successful open is a 500, not a partial 200"),
                () -> assertEquals(0, served.body().length, "a failed read serves no bytes"));
    }

    @Test
    @DisplayName("Should serve through the resolved-path fallback used where no secure walk exists")
    void shouldServeThroughResolvedPathFallback() {
        // The platform fallback is unreachable on a POSIX default provider, so it is driven through
        // the seam instead of being left to inspection: it must serve the same bytes the descriptor
        // walk does, since the fallback is what a non-POSIX deployment actually runs on.
        AssetSource.Served served = cappedSource(1024L,
                (realRoot, relative) -> new DirectoryAssetSource.ResolvedPathAsset(realRoot.resolve(relative)))
                .serve(HttpMethod.GET, "index.html");

        assertAll(
                () -> assertEquals(OK, served.status(), "the fallback serves an in-root asset"),
                () -> assertArrayEquals(INDEX_BODY, served.body(),
                        "the fallback serves the same bytes as the descriptor-relative walk"));
    }

    @Test
    @DisplayName("Should read the asset byte cap from the single shared seam, not a per-class constant")
    void shouldDeriveAssetCapFromTheSharedSeam() {
        // Fitness function for the ADR-0026 'one derivation seam, not one per stage' rule, phrased
        // positively (the seam declares the cap) plus the negative leg that makes it enforceable
        // (no implementation re-declares it). The cap was previously declared twice, as independent
        // public constants carrying duplicated literals, with nothing tying them together — so a
        // change to one would silently leave the other divergent. Deleting the duplicates fixed the
        // instance; this test is what stops it recurring, since re-adding a per-class
        // DEFAULT_MAX_BYTES is otherwise invisible at the point it is written. The set is derived
        // from the sealed hierarchy rather than listed by hand, so a NEW permitted source is covered
        // the moment it is permitted.
        Class<?>[] permitted = AssetSource.class.getPermittedSubclasses();

        assertAll(
                () -> assertEquals(AssetSource.DEFAULT_MAX_BYTES, 10L * 1024 * 1024,
                        "the seam carries the 10 MiB served-asset cap"),
                () -> assertTrue(permitted.length > 0,
                        "AssetSource must permit at least one source — an empty permitted set would "
                                + "make the per-source leg below pass without checking anything"),
                () -> assertAll(Stream.of(permitted)
                        .map(type -> () -> assertFalse(declaresOwnMaxBytes(type),
                                type.getSimpleName() + " must read AssetSource.DEFAULT_MAX_BYTES "
                                        + "rather than re-declaring the cap"))));
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

    /**
     * An opener whose stat and read deliberately DISAGREE — the shape a file that grows between
     * the two syscalls presents, and the only way to reach the check on the bytes actually read.
     *
     * @param statSize  the size the stat reports
     * @param available the number of bytes the read can supply, before the caller's limit applies
     * @return the staged seam
     */
    private static DirectoryAssetSource.Opener understatingOpener(long statSize, int available) {
        return (realRoot, relative) -> new DirectoryAssetSource.ConfinedAsset() {

            @Override
            public long size() {
                return statSize;
            }

            @Override
            public byte[] read(int limit) {
                return new byte[Math.min(limit, available)];
            }

            @Override
            public void close() {
                // Nothing is held open by the staged seam.
            }
        };
    }

    /**
     * @return an opener that opens cleanly and then fails the read, staging the post-open I/O
     *         failure arm of {@code serve()}
     */
    private static DirectoryAssetSource.Opener failingReadOpener() {
        return (realRoot, relative) -> new DirectoryAssetSource.ConfinedAsset() {

            @Override
            public long size() {
                return 1L;
            }

            @Override
            public byte[] read(int limit) throws IOException {
                throw new IOException("staged asset-read failure");
            }

            @Override
            public void close() {
                // Nothing is held open by the staged seam.
            }
        };
    }

    /**
     * Skips the calling test when the platform refuses symbolic links, which the symlink-based
     * confinement assertions cannot be staged without.
     */
    private static void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false,
                    "symbolic links are not supported/permitted in this environment: " + unsupported.getMessage());
        }
    }

    /**
     * Skips the calling test when the default provider yields no {@link SecureDirectoryStream} —
     * the platforms on which {@code DirectoryAssetSource} deliberately falls back to the
     * resolved-path read and the descriptor-relative descent under test does not run.
     */
    private void assumeSecureWalk() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            assumeTrue(stream instanceof SecureDirectoryStream,
                    "the default provider yields no SecureDirectoryStream on this platform");
        }
    }
}
