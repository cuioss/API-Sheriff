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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;

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
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DirectoryAssetSource implements AssetSource {

    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int SERVER_ERROR = 500;
    private static final byte[] EMPTY_BODY = new byte[0];

    private final Path root;
    private final AccessLevel access;
    private final PathConfinement confinement;
    private final long maxBytes;
    private final Map<String, String> operatorContentTypes;

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
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.access = Objects.requireNonNull(access, "access");
        this.confinement = Objects.requireNonNull(confinement, "confinement");
        this.maxBytes = maxBytes;
        this.operatorContentTypes = Map.copyOf(
                Objects.requireNonNull(operatorContentTypes, "operatorContentTypes"));
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
        if (!realPathWithinRoot(file)) {
            return new Served(NOT_FOUND, Map.of(), EMPTY_BODY);
        }
        try {
            if (Files.size(file) > maxBytes) {
                return new Served(PAYLOAD_TOO_LARGE, Map.of(), EMPTY_BODY);
            }
            byte[] body = method == HttpMethod.HEAD ? EMPTY_BODY : readWithinCap(file);
            // The authoritative cap check, on the bytes actually READ rather than on the size
            // sampled beforehand. Same shape as UpstreamAssetSource's post-fetch check, so both
            // sources refuse an over-cap asset on the bytes they really materialized.
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
     * Reads at most {@code maxBytes + 1} bytes of {@code file} — enough for the caller's length
     * check to detect a breach, never enough to materialize an oversized file.
     * <p>
     * <strong>Why the {@link Files#size(Path)} pre-check is not sufficient on its own.</strong>
     * That check samples the size and the read happens afterwards, so a file that GROWS in between
     * would be materialized in full by an unbounded {@code readAllBytes}: the in-memory bound would
     * be the file's size at READ time rather than {@code maxBytes}. Winning that race needs write
     * access to the mounted asset volume — which is exactly the attacker capability
     * {@link #realPathWithinRoot} already reasons about (a compromised deploy step, a hostile
     * archive extraction), so it is in-model here rather than out of scope. Bounding the read
     * closes it: the ceiling now holds regardless of what the file does between the two calls.
     * <p>
     * The pre-check is deliberately KEPT as a cheap fast-reject — it refuses a known-oversized file
     * without reading a single byte — while this bounded read is the authoritative guard. That
     * pairing gives the directory source the same mid-flight guarantee
     * {@code UpstreamAssetSource.CappedByteArrayBodySubscriber} gives the upstream source, so
     * neither asset path can be outrun into an unbounded buffer.
     *
     * @param file the confined, in-root regular file to read
     * @return the file's bytes, truncated to one byte past the cap when it exceeds it
     * @throws IOException on a read failure
     */
    private byte[] readWithinCap(Path file) throws IOException {
        // Clamped before the increment so a maxBytes at or near Long.MAX_VALUE cannot overflow into
        // a negative (and then a zero-length) limit.
        long clamped = Math.min(maxBytes, (long) Integer.MAX_VALUE - 1L);
        int limit = (int) clamped + 1;
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(limit);
        }
    }

    /**
     * Verifies the confined {@code file}'s symlink-resolved real path still lies inside the
     * configured root's real path.
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
     */
    private boolean realPathWithinRoot(Path file) {
        try {
            return file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException _) {
            return false;
        }
    }
}
