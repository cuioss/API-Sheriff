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
package de.cuioss.sheriff.gateway.bff.csrf;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The fixed CSRF defence for {@code require: session} routes (D7). It is <strong>fixed
 * behaviour with no disable knob</strong>: every unsafe-method request on a session route must
 * prove same-origin provenance or it is rejected {@code 403} with a {@link EventType#CSRF_REJECTED}
 * event. There is no configuration flag that turns the defence off.
 * <p>
 * "Unsafe" means any method outside the CSRF-safe set {@code GET} / {@code HEAD} / {@code OPTIONS}
 * (the methods a browser issues without a body-mutating intent). For an unsafe method the defence
 * is fail-closed and accepts only two proofs of same-origin provenance:
 * <ol>
 *   <li>an {@code Origin} header whose exact full-origin value (scheme + host + port) is a member of
 *       the trusted-origin set (configured via {@code session.csrf.trusted_origins}, defaulting to the
 *       origin of {@code redirect_uri}); or</li>
 *   <li>when {@code Origin} is absent, a {@code Sec-Fetch-Site: same-origin} fetch-metadata header.</li>
 * </ol>
 * Everything else — an untrusted {@code Origin}, an absent {@code Origin} without a
 * {@code same-origin} {@code Sec-Fetch-Site}, or both signals absent — is rejected. The comparison is
 * case-insensitive (the trusted origins are lower-cased once at construction and the inbound values
 * are lower-cased on each check), matching the exact-match, fail-closed model of the WebSocket-upgrade
 * Origin gate.
 * <p>
 * The defence is framework-agnostic — it consumes the {@link PipelineRequest} carrier and carries no
 * Vert.x / Quarkus types — and immutable, so a single instance is safely shared across threads.
 * Security-relevant rejections are logged with the rejection disposition only, never the raw offending
 * {@code Origin} value; the {@link EventType#CSRF_REJECTED} counter is the authoritative observability
 * signal for a rejection.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CsrfDefence {

    private static final CuiLogger LOGGER = new CuiLogger(CsrfDefence.class);

    private static final String ORIGIN_HEADER = "Origin";
    private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    private static final Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private static final String DISPOSITION_UNTRUSTED_ORIGIN = "untrusted-origin";
    private static final String DISPOSITION_NO_ORIGIN_PROOF = "no-origin-proof";

    private final Set<String> trustedOrigins;

    /**
     * Assembles the defence with the effective trusted-origin allowlist.
     *
     * @param trustedOrigins the browser origins trusted on unsafe methods — full origin strings
     *                       (scheme + host + port), already resolved to the configured
     *                       {@code session.csrf.trusted_origins} or the {@code redirect_uri} default;
     *                       lower-cased and defensively copied here. An empty set is the fail-closed
     *                       extreme where only {@code Sec-Fetch-Site: same-origin} can pass.
     */
    public CsrfDefence(Set<String> trustedOrigins) {
        Objects.requireNonNull(trustedOrigins, "trustedOrigins");
        this.trustedOrigins = trustedOrigins.stream()
                .map(origin -> origin.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Enforces the fixed CSRF defence for a request selected onto a {@code require: session} route.
     * A CSRF-safe method returns immediately; an unsafe method must present a trusted {@code Origin}
     * or a {@code Sec-Fetch-Site: same-origin} header.
     *
     * @param request the in-flight request carrier
     * @throws GatewayException carrying {@link EventType#CSRF_REJECTED} ({@code 403}) when an
     *                          unsafe-method request presents neither a trusted {@code Origin} nor a
     *                          {@code same-origin} {@code Sec-Fetch-Site}
     */
    public void enforce(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        if (SAFE_METHODS.contains(request.method())) {
            return;
        }
        Optional<String> origin = request.firstHeader(ORIGIN_HEADER);
        if (origin.isPresent()) {
            if (trustedOrigins.contains(origin.get().toLowerCase(Locale.ROOT))) {
                return;
            }
            throw rejected(DISPOSITION_UNTRUSTED_ORIGIN);
        }
        if (isSameOriginFetch(request)) {
            return;
        }
        throw rejected(DISPOSITION_NO_ORIGIN_PROOF);
    }

    private static boolean isSameOriginFetch(PipelineRequest request) {
        return request.firstHeader(SEC_FETCH_SITE_HEADER)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(SAME_ORIGIN::equals)
                .isPresent();
    }

    private static GatewayException rejected(String disposition) {
        LOGGER.debug("CSRF defence rejected an unsafe-method session request: %s", disposition);
        return new GatewayException(EventType.CSRF_REJECTED,
                "CSRF defence rejected an unsafe-method session request: " + disposition);
    }
}
