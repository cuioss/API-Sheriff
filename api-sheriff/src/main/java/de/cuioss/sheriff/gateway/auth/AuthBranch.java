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
package de.cuioss.sheriff.gateway.auth;

import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;

/**
 * The authentication branch a request is dispatched to at stage 4, resolved once per request
 * from the selected route's effective {@code auth} block.
 * <p>
 * For the three plain postures the branch is the posture itself: {@code require: none} &rarr;
 * {@link #NONE}, {@code require: bearer} &rarr; {@link #BEARER}, {@code require: session} &rarr;
 * {@link #SESSION}. A {@code require: bearer} route that declares {@code session_fallback: true}
 * serves two branches, chosen by one input only — whether the request carries an
 * {@code Authorization} header:
 * <ul>
 *   <li>{@code Authorization} present — any scheme, any value, an empty value included &rarr;
 *       {@link #BEARER};</li>
 *   <li>{@code Authorization} absent &rarr; {@link #SESSION}.</li>
 * </ul>
 * Header <em>presence</em> is the only input, so a malformed, non-{@code Bearer} or empty
 * {@code Authorization} always selects {@link #BEARER} and is rejected there with a 401 — it can
 * never fall through to the session path. The edge's CSRF gate and the authentication dispatch
 * both call {@link #resolve(AuthConfig, PipelineRequest)}, so they cannot disagree on the branch.
 * <p>
 * Each member carries a bounded lower-case {@link #label() label} for metric tagging.
 * <p>
 * <strong>Thread safety.</strong> This enum is immutable and {@link #resolve} is a pure function
 * of its arguments; both are safe to use from any number of request threads concurrently.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum AuthBranch {

    /** Anonymous surface ({@code require: none}): nothing is authenticated. */
    NONE("none"),

    /** Offline bearer-token validation of the inbound {@code Authorization} header. */
    BEARER("bearer"),

    /** The server-session runtime: session cookie, login redirect or 401, mediated bearer. */
    SESSION("session");

    private static final String AUTHORIZATION = "Authorization";

    private final String label;

    AuthBranch(String label) {
        this.label = label;
    }

    /**
     * @return the bounded lower-case label of this branch ({@code none}, {@code bearer} or
     *         {@code session}), suitable as a metric tag value
     */
    public String label() {
        return label;
    }

    /**
     * Resolves the authentication branch for one request.
     *
     * @param effectiveAuth the selected route's effective {@code auth} block
     * @param request       the in-flight request; only the presence of its {@code Authorization}
     *                      header is consulted, and only on a {@code session_fallback} route
     * @return {@link #NONE} for {@code require: none}, {@link #SESSION} for
     *         {@code require: session}, {@link #BEARER} for {@code require: bearer} without
     *         {@code session_fallback}; on a {@code session_fallback} route {@link #BEARER} when an
     *         {@code Authorization} header is present and {@link #SESSION} otherwise
     */
    public static AuthBranch resolve(AuthConfig effectiveAuth, PipelineRequest request) {
        Objects.requireNonNull(effectiveAuth, "effectiveAuth");
        Objects.requireNonNull(request, "request");
        return switch (effectiveAuth.require()) {
            case NONE -> NONE;
            case SESSION -> SESSION;
            case BEARER -> !effectiveAuth.effectiveSessionFallback() || request.hasHeader(AUTHORIZATION)
                    ? BEARER
                    : SESSION;
        };
    }
}
