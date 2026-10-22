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
package de.cuioss.sheriff.gateway.bff.csrf;

import java.util.Collection;
import java.util.EnumSet;
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
 * The same-origin proof the gateway demands of an unsafe-method browser request, rejecting a request
 * without it {@code 403} with a {@link EventType#CSRF_REJECTED} event. One class serves two uses,
 * which differ only in the origins they trust:
 * <ul>
 *   <li><strong>The fixed CSRF defence of {@code require: session} routes</strong> (D7), built through
 *       {@link #CsrfDefence(Set)}. It trusts exactly the configured {@code session.csrf.trusted_origins},
 *       defaulting to the origin of {@code redirect_uri}, and has <strong>no disable knob</strong>: the
 *       edge asks for the proof on every unsafe-method session request.</li>
 *   <li><strong>The same-origin check of a cookie-forwarding route</strong>, built through
 *       {@link #forCookieForwardingRoute(Collection)} once per route at route-table assembly. It trusts
 *       the origin the request itself was received on (the inbound scheme, authority host and authority
 *       port) plus the route's exact {@code cookies.additional_origins}. Whether the proof is asked for
 *       is decided by the edge, not here: it asks only for an unsafe request that carries a cookie the
 *       route would forward, and the route's {@code cookies.same_origin_check: false} removes the
 *       instance altogether.</li>
 * </ul>
 * <p>
 * "Unsafe" means any method outside the CSRF-safe set {@code GET} / {@code HEAD} / {@code OPTIONS}
 * (the methods a browser issues without a body-mutating intent); a safe method always passes. For an
 * unsafe method the proof is fail-closed and accepts exactly two forms of same-origin provenance:
 * <ol>
 *   <li>an {@code Origin} header whose full-origin value (scheme + host + port) is trusted; or</li>
 *   <li>when {@code Origin} is absent, a {@code Sec-Fetch-Site: same-origin} fetch-metadata header.</li>
 * </ol>
 * Everything else — an untrusted {@code Origin}, an absent {@code Origin} without a
 * {@code same-origin} {@code Sec-Fetch-Site}, or both signals absent — is rejected. The comparison is
 * case-insensitive (trusted origins are lower-cased once at construction, inbound values on each
 * check), matching the exact-match, fail-closed model of the WebSocket-upgrade Origin gate. The
 * cookie-forwarding instance additionally drops a default port ({@code :443} for {@code https},
 * {@code :80} for {@code http}) on both sides of the comparison, so {@code https://app.example} and
 * {@code https://app.example:443} name the same origin; the session instance compares its configured
 * values exactly as before.
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
    private static final Set<HttpMethod> SAFE_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private static final String DISPOSITION_UNTRUSTED_ORIGIN = "untrusted-origin";
    private static final String DISPOSITION_NO_ORIGIN_PROOF = "no-origin-proof";
    private static final String REJECTION_TEXT = "CSRF defence rejected an unsafe-method request: ";

    private static final String HTTPS_DEFAULT_PORT_SUFFIX = ":443";
    private static final String HTTP_DEFAULT_PORT_SUFFIX = ":80";
    private static final String HTTPS_PREFIX = "https://";
    private static final String HTTP_PREFIX = "http://";

    private final Set<String> trustedOrigins;
    private final boolean trustsReceivedOrigin;

    /**
     * Assembles the fixed session-route defence with the effective trusted-origin allowlist.
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
        this.trustsReceivedOrigin = false;
    }

    private CsrfDefence(Set<String> trustedOrigins, boolean trustsReceivedOrigin) {
        this.trustedOrigins = trustedOrigins;
        this.trustsReceivedOrigin = trustsReceivedOrigin;
    }

    /**
     * Builds the same-origin check of one cookie-forwarding route. The instance trusts the origin each
     * request was received on — its {@linkplain PipelineRequest#scheme() scheme},
     * {@linkplain PipelineRequest#host() authority host} and
     * {@linkplain PipelineRequest#authorityPort() authority port} — plus exactly the given additional
     * origins. A request whose scheme or host is unknown has no received origin, so only an additional
     * origin or {@code Sec-Fetch-Site: same-origin} can pass it.
     *
     * @param additionalOrigins the route's {@code cookies.additional_origins} — full origin strings
     *                          trusted beside the received origin; lower-cased, default-port normalised
     *                          and defensively copied here. Empty when the route declares none
     * @return the route's same-origin check
     */
    public static CsrfDefence forCookieForwardingRoute(Collection<String> additionalOrigins) {
        Objects.requireNonNull(additionalOrigins, "additionalOrigins");
        Set<String> normalised = additionalOrigins.stream()
                .map(CsrfDefence::normaliseOrigin)
                .collect(Collectors.toUnmodifiableSet());
        return new CsrfDefence(normalised, true);
    }

    /**
     * Enforces the same-origin proof on a request. A CSRF-safe method returns immediately; an unsafe
     * method must present a trusted {@code Origin} or a {@code Sec-Fetch-Site: same-origin} header.
     *
     * @param request the in-flight request carrier
     * @throws GatewayException carrying {@link EventType#CSRF_REJECTED} ({@code 403}) when an
     *                          unsafe-method request presents neither a trusted {@code Origin} nor a
     *                          {@code same-origin} {@code Sec-Fetch-Site}
     */
    public void enforce(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        if (!isUnsafeMethod(request.method())) {
            return;
        }
        Optional<String> origin = request.firstHeader(ORIGIN_HEADER);
        if (origin.isPresent()) {
            if (isTrusted(origin.get(), request)) {
                return;
            }
            throw rejected(DISPOSITION_UNTRUSTED_ORIGIN);
        }
        if (isSameOriginFetch(request)) {
            return;
        }
        throw rejected(DISPOSITION_NO_ORIGIN_PROOF);
    }

    /**
     * Whether a method is unsafe — outside the CSRF-safe set {@code GET} / {@code HEAD} /
     * {@code OPTIONS} — and therefore one {@link #enforce(PipelineRequest)} asks the same-origin proof
     * of. A caller deciding whether to ask for the proof at all reads the method through here, so the
     * two cannot disagree about which methods are safe.
     *
     * @param method the request method
     * @return {@code true} for every method outside the CSRF-safe set
     */
    public static boolean isUnsafeMethod(HttpMethod method) {
        return !SAFE_METHODS.contains(method);
    }

    private boolean isTrusted(String origin, PipelineRequest request) {
        if (!trustsReceivedOrigin) {
            return trustedOrigins.contains(origin.toLowerCase(Locale.ROOT));
        }
        String candidate = normaliseOrigin(origin);
        return trustedOrigins.contains(candidate)
                || receivedOrigin(request).filter(candidate::equals).isPresent();
    }

    /**
     * The origin the request was received on, rendered and normalised like a trusted origin, or empty
     * when the request carries no scheme or no authority host.
     */
    private static Optional<String> receivedOrigin(PipelineRequest request) {
        String scheme = request.scheme();
        String host = request.host();
        if (scheme == null || host == null) {
            return Optional.empty();
        }
        String authority = request.authorityPort() < 0 ? host : host + ":" + request.authorityPort();
        return Optional.of(normaliseOrigin(scheme + "://" + authority));
    }

    /**
     * Lower-cases an origin and drops a default port, so {@code HTTPS://App.Example:443} and
     * {@code https://app.example} compare equal.
     */
    private static String normaliseOrigin(String origin) {
        String lowered = origin.toLowerCase(Locale.ROOT);
        if (lowered.startsWith(HTTPS_PREFIX) && lowered.endsWith(HTTPS_DEFAULT_PORT_SUFFIX)) {
            return lowered.substring(0, lowered.length() - HTTPS_DEFAULT_PORT_SUFFIX.length());
        }
        if (lowered.startsWith(HTTP_PREFIX) && lowered.endsWith(HTTP_DEFAULT_PORT_SUFFIX)) {
            return lowered.substring(0, lowered.length() - HTTP_DEFAULT_PORT_SUFFIX.length());
        }
        return lowered;
    }

    private static boolean isSameOriginFetch(PipelineRequest request) {
        return request.firstHeader(SEC_FETCH_SITE_HEADER)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(SAME_ORIGIN::equals)
                .isPresent();
    }

    private static GatewayException rejected(String disposition) {
        LOGGER.debug(REJECTION_TEXT + "%s", disposition);
        return new GatewayException(EventType.CSRF_REJECTED, REJECTION_TEXT + disposition);
    }
}
