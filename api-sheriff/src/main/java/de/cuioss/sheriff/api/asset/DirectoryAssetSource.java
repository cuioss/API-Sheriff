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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.HttpMethod;

/**
 * The local-directory / volume-mount {@link AssetSource} (decision: ADR-0014).
 * <p>
 * Serves regular files from a configured directory root — no classpath-embedded
 * resources — through the two shared, gateway-owned primitives:
 * {@link PathConfinement} maps the untrusted request sub-path to a target proven to
 * lie inside the root (any escape or malformed encoding is a 404), and
 * {@link AssetResponseEnvelope} governs every served response (fixed content type,
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

    /** The default served-file size cap (10 MiB). */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

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

    /**
     * Creates a source rooted at {@code root} for a route of the given access level,
     * using the default {@link PathConfinement} and {@value #DEFAULT_MAX_BYTES}-byte
     * size cap.
     *
     * @param root   the configured directory root (mandatory)
     * @param access the serving route's effective access level (mandatory)
     */
    public DirectoryAssetSource(Path root, AccessLevel access) {
        this(root, access, new PathConfinement(), DEFAULT_MAX_BYTES);
    }

    /**
     * Creates a source with an explicit confinement and size cap.
     *
     * @param root        the configured directory root (mandatory)
     * @param access      the serving route's effective access level (mandatory)
     * @param confinement the shared path confinement (mandatory)
     * @param maxBytes    the maximum served-file size in bytes
     */
    public DirectoryAssetSource(Path root, AccessLevel access, PathConfinement confinement, long maxBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.access = Objects.requireNonNull(access, "access");
        this.confinement = Objects.requireNonNull(confinement, "confinement");
        this.maxBytes = maxBytes;
    }

    /**
     * Serves the confined asset addressed by {@code subPath}.
     *
     * @param method  the request verb; only {@code GET} and {@code HEAD} are served
     * @param subPath the untrusted request sub-path relative to the root
     * @return the governed {@link Served} response — {@code 405} for a non-read verb,
     *         {@code 404} for a confinement rejection or a missing file, {@code 413}
     *         for an oversized file, {@code 500} on a read error, otherwise {@code 200}
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
        try {
            if (Files.size(file) > maxBytes) {
                return new Served(PAYLOAD_TOO_LARGE, Map.of(), EMPTY_BODY);
            }
            Map<String, String> headers = AssetResponseEnvelope.governedHeaders(
                    file.getFileName().toString(), access, Map.of());
            byte[] body = method == HttpMethod.HEAD ? EMPTY_BODY : Files.readAllBytes(file);
            return new Served(OK, headers, body);
        } catch (IOException readFailure) {
            return new Served(SERVER_ERROR, Map.of(), EMPTY_BODY);
        }
    }
}
