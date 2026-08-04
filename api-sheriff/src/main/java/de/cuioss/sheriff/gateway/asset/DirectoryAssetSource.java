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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The local-directory / volume-mount {@link AssetSource} (decision: ADR-0014).
 * <p>
 * Serves regular files from a configured directory root — no classpath-embedded
 * resources — through the two shared, gateway-owned primitives:
 * {@link PathConfinement} maps the untrusted request sub-path to a target proven to
 * lie inside the root lexically (any encoding-based escape or malformed encoding is a
 * 404), and this class additionally verifies the resolved target's <em>real</em> path
 * (symlinks followed) still lies inside the root's real path before it is touched — so
 * a symlink planted under root cannot serve content that lives outside it. Every
 * response is then governed by {@link AssetResponseEnvelope} (fixed content type,
 * {@code nosniff}, forced {@code no-store} for authenticated access, stripped
 * {@code Set-Cookie}). Only {@code GET} and {@code HEAD} are served; a {@code HEAD}
 * carries the governed headers with an empty body. Files larger than the configured
 * cap are refused rather than streamed.
 * <p>
 * Honouring the {@link AssetSource} ordering contract, the backing filesystem is
 * touched only after confinement has produced an in-root target — no byte is read for
 * a rejected path.
 * <p>
 * <strong>How the bytes are materialized, and what that does and does not close.</strong>
 * Proving a path in-root and then re-walking it to open it is a check-then-act window: the
 * same volume-write capability {@link #realPathWithinRoot} already reasons about can
 * re-point a component of that path between the check and the open. On a platform whose
 * default provider yields a {@link SecureDirectoryStream} (POSIX systems with the
 * {@code *at} syscalls — Linux and macOS in practice), this class closes the window for
 * <em>every</em> component: it descends from the root one confined name at a time with
 * {@link LinkOption#NOFOLLOW_LINKS}, and both the stat and the bounded read are issued
 * against the descriptor that descent produced. No component can be re-pointed after it
 * was proven, because no component is ever looked up by name from the filesystem root a
 * second time.
 * <p>
 * <strong>Where the window remains open.</strong> When the default provider does
 * <em>not</em> yield a {@link SecureDirectoryStream}, the source falls back to the
 * resolved-path form: the stat and the read target the already-resolved real path with
 * {@link LinkOption#NOFOLLOW_LINKS}. That refuses a swapped <em>final</em> component, but
 * {@code NOFOLLOW_LINKS} constrains the last path component only — so on such a platform an
 * <em>ancestor directory</em> of the resolved path can still be replaced by a symlink after
 * the check and be traversed by the read. The ancestor window is therefore NOT closed on
 * that platform, and the fallback is announced once per source with
 * {@link ApiSheriffLogMessages.WARN#ASSET_CONFINED_WALK_UNAVAILABLE} so the residual
 * exposure is visible in the operator log rather than only in this javadoc.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DirectoryAssetSource implements AssetSource {

    private static final CuiLogger LOGGER = new CuiLogger(DirectoryAssetSource.class);

    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int SERVER_ERROR = 500;
    private static final byte[] EMPTY_BODY = new byte[0];

    /** Read-only open, refusing to follow a final component swapped to a symlink. */
    private static final Set<OpenOption> READ_NOFOLLOW =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private final Path root;
    private final AccessLevel access;
    private final PathConfinement confinement;
    private final long maxBytes;
    private final Map<String, String> operatorContentTypes;
    private final Opener opener;

    /**
     * Creates a source rooted at {@code root} for a route of the given access level,
     * using the default {@link PathConfinement} and the shared
     * {@link AssetSource#DEFAULT_MAX_BYTES} size cap.
     *
     * @param root                 the configured directory root (mandatory)
     * @param access               the serving route's effective access level (mandatory)
     * @param operatorContentTypes the boot-resolved add-only content-type additions
     *                             (mandatory; empty when unconfigured)
     */
    public DirectoryAssetSource(Path root, AccessLevel access, Map<String, String> operatorContentTypes) {
        this(root, access, new PathConfinement(), AssetSource.DEFAULT_MAX_BYTES, operatorContentTypes);
    }

    /**
     * Creates a source with an explicit confinement and size cap.
     *
     * @param root                 the configured directory root (mandatory)
     * @param access               the serving route's effective access level (mandatory)
     * @param confinement          the shared path confinement (mandatory)
     * @param maxBytes             the maximum served-file size in bytes
     * @param operatorContentTypes the boot-resolved add-only content-type additions
     *                             (mandatory; empty when unconfigured). Resolved once at
     *                             boot and read-only thereafter — no per-request lookup
     *                             and no shared mutable state.
     */
    public DirectoryAssetSource(Path root, AccessLevel access, PathConfinement confinement, long maxBytes,
            Map<String, String> operatorContentTypes) {
        this(root, access, confinement, maxBytes, operatorContentTypes, new SecureWalkOpener());
    }

    /**
     * Creates a source with an explicit {@link Opener} — the seam that materializes the asset.
     * <p>
     * Package-visible for tests: the production {@link SecureWalkOpener} reports a stat and a read
     * that always agree, which leaves the post-read cap check unreachable. Injecting an opener whose
     * stat sits at or under the cap while its read yields more bytes is what exercises that check.
     *
     * @param root                 the configured directory root (mandatory)
     * @param access               the serving route's effective access level (mandatory)
     * @param confinement          the shared path confinement (mandatory)
     * @param maxBytes             the maximum served-file size in bytes
     * @param operatorContentTypes the boot-resolved add-only content-type additions (mandatory)
     * @param opener               the stat-and-read seam (mandatory)
     */
    DirectoryAssetSource(Path root, AccessLevel access, PathConfinement confinement, long maxBytes,
            Map<String, String> operatorContentTypes, Opener opener) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.access = Objects.requireNonNull(access, "access");
        this.confinement = Objects.requireNonNull(confinement, "confinement");
        this.maxBytes = maxBytes;
        this.operatorContentTypes = Map.copyOf(
                Objects.requireNonNull(operatorContentTypes, "operatorContentTypes"));
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Serves the confined asset addressed by {@code subPath}.
     *
     * @param method  the request verb; only {@code GET} and {@code HEAD} are served
     * @param subPath the untrusted request sub-path relative to the root
     * @return the governed {@link Served} response — {@code 405} for a non-read verb,
     *         {@code 404} for a confinement rejection, a symlink escape, or a missing
     *         file, {@code 413} for an oversized file, {@code 500} on a read error,
     *         otherwise {@code 200}
     */
    @Override
    public Served serve(HttpMethod method, String subPath) {
        Objects.requireNonNull(method, "method");
        if (!AssetResponseEnvelope.isAllowedMethod(method)) {
            return new Served(METHOD_NOT_ALLOWED, Map.of(), EMPTY_BODY);
        }
        Optional<Path> confined = confinement.confine(root, subPath);
        if (confined.isEmpty()) {
            return new Served(NOT_FOUND, Map.of(), EMPTY_BODY);
        }
        Path file = confined.get();
        if (!Files.isRegularFile(file)) {
            return new Served(NOT_FOUND, Map.of(), EMPTY_BODY);
        }
        Optional<InRoot> inRoot = realPathWithinRoot(file);
        if (inRoot.isEmpty()) {
            return new Served(NOT_FOUND, Map.of(), EMPTY_BODY);
        }
        return serveConfined(method, file, inRoot.get());
    }

    /**
     * Materializes the proven-in-root asset through the {@link Opener} seam and governs the response.
     * <p>
     * The size pre-check is deliberately KEPT as a cheap fast-reject — it refuses a known-oversized
     * file without reading a single byte — while the length check on the bytes actually READ is the
     * authoritative guard. That pairing is what survives a file which GROWS between the stat and the
     * read: an unbounded read would materialize it in full, bounding the read to {@code maxBytes + 1}
     * makes the in-memory ceiling hold regardless. It gives the directory source the same mid-flight
     * guarantee {@code UpstreamAssetSource.CappedByteArrayBodySubscriber} gives the upstream source,
     * so neither asset path can be outrun into an unbounded buffer.
     *
     * @param method the request verb, already narrowed to {@code GET} or {@code HEAD}
     * @param file   the confined request target, used for the content-type resolution only
     * @param inRoot the real root and the proven-in-root path relative to it
     * @return the governed response — {@code 413} when either the stat or the bytes read exceed the
     *         cap, {@code 500} when the asset cannot be materialized (including an ancestor or final
     *         component re-pointed to a symlink after the confinement check), otherwise {@code 200}
     */
    private Served serveConfined(HttpMethod method, Path file, InRoot inRoot) {
        try (ConfinedAsset asset = opener.open(inRoot.realRoot(), inRoot.relative())) {
            if (asset.size() > maxBytes) {
                return new Served(PAYLOAD_TOO_LARGE, Map.of(), EMPTY_BODY);
            }
            byte[] body = method == HttpMethod.HEAD ? EMPTY_BODY : asset.read(readLimit());
            if (body.length > maxBytes) {
                return new Served(PAYLOAD_TOO_LARGE, Map.of(), EMPTY_BODY);
            }
            Map<String, String> headers = AssetResponseEnvelope.governedHeaders(
                    file.getFileName().toString(), access, Map.of(), operatorContentTypes);
            return new Served(OK, headers, body);
        } catch (IOException _) {
            return new Served(SERVER_ERROR, Map.of(), EMPTY_BODY);
        }
    }

    /**
     * @return the number of bytes to read — one past the cap, so the caller's length check can
     *         detect a breach without ever materializing an oversized file. Clamped before the
     *         increment so a {@code maxBytes} at or near {@link Long#MAX_VALUE} cannot overflow
     *         into a negative (and then a zero-length) limit.
     */
    private int readLimit() {
        long clamped = Math.min(maxBytes, Integer.MAX_VALUE - 1L);
        return (int) clamped + 1;
    }

    /**
     * Resolves the confined {@code file} to its real path and reports it — as a root-relative
     * path, ready to be walked down from the root — only when it still lies inside the configured
     * root's real path.
     * <p>
     * {@link PathConfinement} closes the encoding/traversal class <em>lexically</em>: it proves
     * the requested path's normalized string starts with the root's normalized string. A symlink
     * entry living under {@code root} — planted by a compromised deploy step, a hostile archive
     * extraction, or any other write access to the mounted volume — can lexically resolve inside
     * root while its followed target lives outside it; that is a different spelling of the same
     * escape class the confinement javadoc claims closes entirely. Resolving both sides through
     * {@link Path#toRealPath(java.nio.file.LinkOption...)} (default: follow symlinks) and
     * comparing the real paths closes that spelling too. Fails closed: any I/O failure resolving
     * either real path (a TOCTOU removal, an unresolvable symlink cycle) is treated as an escape.
     * <p>
     * The result is expressed RELATIVE to the real root rather than as an absolute path, because
     * that is the form the descent in {@link SecureWalkOpener} consumes: the real path is by
     * construction free of symlinks, so re-walking it name by name with
     * {@link LinkOption#NOFOLLOW_LINKS} reaches the same file and refuses any component that was
     * re-pointed in the meantime. An in-root symlinked asset therefore still serves — it is
     * resolved here first — while a post-check swap of any component is refused.
     *
     * @param file the confined, lexically in-root request target
     * @return the real root paired with the resolved path relative to it, or
     *         {@link Optional#empty()} when the target resolves outside the root or cannot be
     *         resolved at all
     */
    private Optional<InRoot> realPathWithinRoot(Path file) {
        try {
            Path realRoot = root.toRealPath();
            Path real = file.toRealPath();
            if (!real.startsWith(realRoot)) {
                return Optional.empty();
            }
            return Optional.of(new InRoot(realRoot, realRoot.relativize(real)));
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    /**
     * A proven-in-root asset location, split into the two halves the descent needs.
     *
     * @param realRoot the configured root resolved through its own symlink chain
     * @param relative the asset's resolved real path, expressed relative to {@code realRoot}
     */
    private record InRoot(Path realRoot, Path relative) {
    }

    /**
     * The stat-and-read seam: ONE handle on an asset, from which both the size and the bounded
     * read are taken.
     * <p>
     * Keeping the pair on a single handle is what lets the production implementation bind both
     * operations to the same descriptor, and what lets a test drive them apart — a stat within the
     * cap against a read that yields more bytes is the shape that reaches the post-read refusal.
     */
    interface ConfinedAsset extends Closeable {

        /**
         * @return the asset's size in bytes, without following a symlink
         * @throws IOException when the asset cannot be stat-ed
         */
        long size() throws IOException;

        /**
         * @param limit the maximum number of bytes to read
         * @return at most {@code limit} bytes of the asset
         * @throws IOException when the asset cannot be read, or a component was re-pointed to a
         *                     symlink after the confinement check
         */
        byte[] read(int limit) throws IOException;

        @Override
        void close() throws IOException;
    }

    /**
     * Opens a {@link ConfinedAsset} on an asset already proven to lie inside the root.
     */
    @FunctionalInterface
    interface Opener {

        /**
         * @param realRoot the configured root, resolved through its own symlink chain
         * @param relative the asset's resolved real path relative to {@code realRoot}
         * @return an open handle on the asset; the caller closes it
         * @throws IOException when the asset cannot be opened
         */
        ConfinedAsset open(Path realRoot, Path relative) throws IOException;
    }

    /**
     * The production opener: descends from the root one confined name at a time, so no path
     * component can be re-pointed between the confinement check and the read.
     * <p>
     * Falls back to the resolved-path form — which leaves the ancestor window open, see the class
     * javadoc — on a platform whose default provider yields no {@link SecureDirectoryStream}. The
     * fallback is warned once per instance rather than once per request: it is a fixed property of
     * the platform, so repeating it per served asset would flood the operator log without adding
     * information.
     */
    static final class SecureWalkOpener implements Opener {

        private final AtomicBoolean fallbackWarned = new AtomicBoolean();

        @Override
        public ConfinedAsset open(Path realRoot, Path relative) throws IOException {
            DirectoryStream<Path> top = Files.newDirectoryStream(realRoot);
            if (top instanceof SecureDirectoryStream<Path> secure) {
                return descend(secure, relative);
            }
            top.close();
            if (fallbackWarned.compareAndSet(false, true)) {
                LOGGER.warn(ApiSheriffLogMessages.WARN.ASSET_CONFINED_WALK_UNAVAILABLE, realRoot);
            }
            return new ResolvedPathAsset(realRoot.resolve(relative));
        }

        /**
         * Walks {@code relative}'s directory components down from {@code top}, refusing to follow a
         * symlink at any of them, and hands the final directory's descriptor to the returned handle.
         * <p>
         * The descent owns exactly ONE stream at a time — the one {@code dir} names — and the
         * {@code finally} closes it on every path that does not hand it off. The child is adopted
         * into {@code dir} <em>before</em> its parent is closed, so a failing parent close cannot
         * strand the descriptor it just produced; ownership passes to the returned handle only once
         * that handle exists. Descending still opens each child from its parent's descriptor and
         * closes the parent immediately afterwards, so the confinement the walk exists to provide is
         * unchanged: no component is ever looked up by name from the filesystem root a second time.
         *
         * @param top      an open, secure stream on the real root
         * @param relative the asset path relative to the real root
         * @return a handle bound to the descriptor of the directory holding the asset
         * @throws IOException when a component does not exist or was re-pointed to a symlink
         */
        private static ConfinedAsset descend(SecureDirectoryStream<Path> top, Path relative) throws IOException {
            SecureDirectoryStream<Path> dir = top;
            boolean owned = true;
            try {
                for (int i = 0; i < relative.getNameCount() - 1; i++) {
                    SecureDirectoryStream<Path> parent = dir;
                    dir = parent.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                    parent.close();
                }
                ConfinedAsset asset = new DescriptorAsset(dir, relative.getFileName());
                owned = false;
                return asset;
            } finally {
                if (owned) {
                    dir.close();
                }
            }
        }
    }

    /**
     * A handle bound to the descriptor of the directory holding the asset: the stat and the read
     * both name the asset relative to that descriptor, never from the filesystem root, so neither
     * can traverse a component swapped after the confinement check.
     */
    private static final class DescriptorAsset implements ConfinedAsset {

        private final SecureDirectoryStream<Path> dir;
        private final Path name;

        DescriptorAsset(SecureDirectoryStream<Path> dir, Path name) {
            this.dir = dir;
            this.name = name;
        }

        @Override
        public long size() throws IOException {
            return dir.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().size();
        }

        @Override
        public byte[] read(int limit) throws IOException {
            try (InputStream in = Channels.newInputStream(dir.newByteChannel(name, READ_NOFOLLOW))) {
                return in.readNBytes(limit);
            }
        }

        @Override
        public void close() throws IOException {
            dir.close();
        }
    }

    /**
     * The platform fallback: stat and read the already-resolved real path with
     * {@link LinkOption#NOFOLLOW_LINKS}.
     * <p>
     * This refuses a <em>final</em> component swapped to a symlink after resolution — the open
     * fails with an {@link IOException}, which the caller renders as a {@code 500}, rather than
     * silently reading out of root. It does NOT refuse a swapped <em>ancestor</em> directory:
     * {@code NOFOLLOW_LINKS} constrains the last component only. Used only where the platform
     * offers no {@link SecureDirectoryStream}; the residual exposure is warned and documented
     * rather than papered over.
     */
    static final class ResolvedPathAsset implements ConfinedAsset {

        private final Path file;

        /**
         * @param file the asset's resolved, proven-in-root real path
         */
        ResolvedPathAsset(Path file) {
            this.file = file;
        }

        @Override
        public long size() throws IOException {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        }

        @Override
        public byte[] read(int limit) throws IOException {
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return in.readNBytes(limit);
            }
        }

        @Override
        public void close() {
            // The resolved-path form holds nothing open between calls.
        }
    }
}
