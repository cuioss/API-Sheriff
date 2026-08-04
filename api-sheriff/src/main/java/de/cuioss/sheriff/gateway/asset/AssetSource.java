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

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;

/**
 * The sealed source seam for the asset terminal action (decision: ADR-0014).
 * <p>
 * An asset route resolves its bytes through exactly one {@code AssetSource}: a
 * {@link DirectoryAssetSource local directory} or an {@link UpstreamAssetSource
 * secondary origin}. Sealing the hierarchy lets the terminal-action dispatcher match
 * the two cases exhaustively while keeping every other module closed to new source
 * kinds.
 * <p>
 * <strong>Auth-before-source-resolution ordering contract.</strong> Every
 * implementation MUST be invoked only after the request pipeline has (1) authenticated
 * and authorized the request against the route's effective auth posture and (2)
 * confined the request sub-path through {@link PathConfinement}. An implementation
 * MUST NOT touch its backing store — open a file, issue an upstream call — until a
 * confined target has been produced; the confinement result is the only path an
 * implementation may act on. This ordering is what makes an {@code access:
 * authenticated} asset fail closed: no byte is read before authorization succeeds.
 * <p>
 * The gateway — not the source — governs the response headers: every implementation
 * routes its proposed headers through
 * {@link AssetResponseEnvelope#governedHeaders(String, AccessLevel, Map, Map)} and honours
 * {@link AssetResponseEnvelope#isAllowedMethod(HttpMethod)} before serving.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public sealed interface AssetSource permits DirectoryAssetSource, UpstreamAssetSource {

    /**
     * The served-asset byte cap (10 MiB) — <strong>the single derivation seam both implementations
     * read</strong>, never re-declared per class (ADR-0026, "one derivation seam, not one per
     * stage").
     * <p>
     * It lives on the sealed seam rather than on either implementation because the permitted set is
     * exactly the two sources that enforce it: declaring it twice let the two literals drift
     * independently, with nothing structurally tying them together, which is the seam-erosion shape
     * ADR-0026 names. Reading one constant makes "both sources cap at the same value" true by
     * construction instead of by coincidence.
     * <p>
     * <strong>This is NOT the per-route request-body cap.</strong> The two bounds are routinely
     * conflated because both are byte ceilings on the request path, so the distinction is stated
     * here once:
     * <ul>
     *   <li><strong>This cap</strong> bounds the <em>served asset</em> — the response bytes a
     *       {@code directory} or {@code upstream} asset route returns. It is a fixed product
     *       constant, deliberately <em>not</em> operator-configurable ({@code asset_defaults}
     *       carries content types only), and it is enforced inside each source.</li>
     *   <li><strong>{@code security_filter.max_body_bytes}</strong> bounds the <em>inbound request
     *       body</em>. It is per-route and operator-configurable, it is enforced by
     *       {@code ThoroughChecksStage} and streamed by {@code DispatchStage.ByteCappedBodyStream},
     *       and it answers a breach with {@code 413 CONTENT_TOO_LARGE} (ADR-0023).</li>
     * </ul>
     * An asset route's terminal action applies no request-body cap of its own, and this cap never
     * bounds a proxied request body — they govern opposite directions over different inputs.
     */
    long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    /**
     * Serves the confined asset addressed by {@code subPath}.
     *
     * @param method  the request verb; only {@code GET} and {@code HEAD} are served
     * @param subPath the untrusted request sub-path relative to the source's root
     * @return the governed {@link Served} response
     */
    Served serve(HttpMethod method, String subPath);

    /**
     * A gateway-governed asset response — the shared shape both {@link AssetSource}
     * implementations and the edge dispatcher use, from the raw source read through
     * the buffered write-back.
     *
     * @param status  the HTTP status code
     * @param headers the governed response headers (never source-dictated)
     * @param body    the response body; empty for {@code HEAD} and every non-200
     *                outcome
     * @author API Sheriff Team
     * @since 1.0
     */
    record Served(int status, Map<String, String> headers, byte[] body) {

        /**
         * Canonical constructor defensively copying the headers and body.
         */
        public Served {
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Objects.requireNonNull(body, "body").clone();
        }

        /**
         * @return a defensive copy of the response body
         */
        @Override
        public byte[] body() {
            return body.clone();
        }

        /**
         * Value equality over the status, headers, and body <em>content</em> — the
         * generated accessor would compare the {@code body} array by identity, so it is
         * overridden to use {@link Arrays#equals(byte[], byte[])}.
         *
         * @param other the object to compare against
         * @return {@code true} when {@code other} is a {@code Served} with the same status,
         *         headers, and body bytes
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Served(var otherStatus, var otherHeaders, var otherBody)
                    && status == otherStatus
                    && headers.equals(otherHeaders)
                    && Arrays.equals(body, otherBody);
        }

        /**
         * Content-based hash consistent with {@link #equals(Object)} — the {@code body}
         * array contributes via {@link Arrays#hashCode(byte[])} rather than identity.
         *
         * @return the content hash
         */
        @Override
        public int hashCode() {
            return Objects.hash(status, headers, Arrays.hashCode(body));
        }

        /**
         * Renders the status and headers with only the body <em>length</em> — the body
         * bytes are never dumped, since an asset response may carry sensitive content on
         * this security-focused gateway.
         *
         * @return a body-content-free description of this response
         */
        @Override
        public String toString() {
            return "Served[status=%d, headers=%s, body.length=%d]".formatted(status, headers, body.length);
        }
    }
}
