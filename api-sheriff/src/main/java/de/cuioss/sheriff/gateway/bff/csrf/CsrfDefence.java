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
import org.jspecify.annotations.Nullable;

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
 * A rejection is recorded by the {@link EventType#CSRF_REJECTED} event counter and a {@code DEBUG}
 * diagnostic only — no {@code INFO} / {@code WARN} log record is written. The diagnostic carries the
 * rejection disposition ({@code untrusted-origin} / {@code no-origin-proof}) only, never the raw
 * offending {@code Origin} value; the counter is the authoritative observability signal for a
 * rejection.
 * <p>
 * <strong>The logout navigation.</strong> {@link #provesTrustedDocumentNavigation(String, FetchMetadata)}
 * asks a stricter proof of a request whatever its method, for the RP-initiated logout leg, which ends a
 * session on a {@code GET} as well. Same-origin provenance alone is not enough there: any same-origin
 * subresource, frame or speculative load carries it, so the request must also be a top-level document
 * navigation. It throws nothing; the caller decides what a missing proof answers. The logout leg's
 * instance is built with {@link #withGatewayOrigin(String)}, so the gateway's own origin — the origin of
 * the confirmation page whose form posts back to the logout path — is trusted there even when the
 * configured {@code session.csrf.trusted_origins} leaves it out.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CsrfDefence {

    private static final CuiLogger LOGGER = new CuiLogger(CsrfDefence.class);

    private static final String ORIGIN_HEADER = "Origin";
    private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    /** The {@code Sec-Fetch-Mode} value of a navigation, top-level or nested. */
    private static final String NAVIGATE_MODE = "navigate";
    /** The {@code Sec-Fetch-Dest} value of a top-level navigation; a frame sends {@code iframe} or {@code frame}. */
    private static final String DOCUMENT_DEST = "document";
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
     * The gateway's own origin, normalised, trusted by the logout navigation proof alone; {@code null}
     * unless the instance was built through {@link #withGatewayOrigin(String)}.
     */
    private final @Nullable String gatewayOrigin;

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
        this.gatewayOrigin = null;
    }

    private CsrfDefence(Set<String> trustedOrigins, boolean trustsReceivedOrigin, @Nullable String gatewayOrigin) {
        this.trustedOrigins = trustedOrigins;
        this.trustsReceivedOrigin = trustsReceivedOrigin;
        this.gatewayOrigin = gatewayOrigin;
    }

    /**
     * Derives the logout leg's instance: the same trusted origins, plus the gateway's own origin for
     * {@link #provesTrustedDocumentNavigation(String, FetchMetadata)} alone. The confirmation page the
     * logout leg answers is served under the gateway's origin, so its form posts back with that
     * {@code Origin}; without this, a {@code session.csrf.trusted_origins} list that names only other
     * origins (a separately hosted SPA, say) would refuse that post and the session could never be ended
     * through the logout path. {@link #enforce} does not consult the added origin.
     *
     * @param gatewayOrigin the gateway's own origin — the origin of {@code redirect_uri}, the one origin
     *                      a session cookie is set on; lower-cased and default-port normalised here
     * @return a new instance trusting the gateway's origin on the logout navigation
     */
    public CsrfDefence withGatewayOrigin(String gatewayOrigin) {
        Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
        return new CsrfDefence(trustedOrigins, trustsReceivedOrigin, normaliseOrigin(gatewayOrigin));
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
        return new CsrfDefence(normalised, true, null);
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
        if (fetchSite(request.firstHeader(SEC_FETCH_SITE_HEADER).orElse(null)).filter(SAME_ORIGIN::equals)
                .isPresent()) {
            return;
        }
        throw rejected(DISPOSITION_NO_ORIGIN_PROOF);
    }

    /**
     * Whether a request is a top-level document navigation from a trusted origin — the proof the
     * RP-initiated logout leg asks before it ends a session, whatever the method. All of the following
     * must hold:
     * <ol>
     *   <li>No speculative-load marker ({@link FetchMetadata#purpose()}). A browser marks a speculative
     *       load (a prefetch or a prerender), which no user asked for, even when that load is a
     *       navigation: with a {@code Sec-Purpose} header, or, in a browser that predates it, with the
     *       legacy {@code Purpose: prefetch} or {@code X-Moz: prefetch}. The edge fills the marker from
     *       the first {@code Sec-Purpose} value of any content, or, when there is none, from a
     *       {@code Purpose} and then an {@code X-Moz} value equal to {@code prefetch} ignoring case; any
     *       marker refuses the proof.</li>
     *   <li>{@code Sec-Fetch-Mode}, when present, is {@code navigate}, and {@code Sec-Fetch-Dest}, when
     *       present, is {@code document}. A same-origin image, script, {@code fetch()}, link prefetch or
     *       frame carries a different mode or destination and is refused.</li>
     *   <li>A present {@code Origin} is trusted — a configured origin, or the
     *       {@linkplain #withGatewayOrigin(String) gateway's own origin} — and decides the provenance
     *       alone; with {@code Origin} absent, {@code Sec-Fetch-Site} must be {@code same-origin}.</li>
     * </ol>
     * Mode and destination are judged only when present because the threat is a request a browser
     * sends on the victim's behalf, and a browser that sends {@code Sec-Fetch-Site} sends
     * {@code Sec-Fetch-Mode} with it (and {@code Sec-Fetch-Dest} in every current version); a client
     * that omits them is not a browser forged into the request and holds no victim's cookie.
     * <p>
     * {@code Sec-Fetch-Site: none} is not a proof. A browser sends it for a typed URL and a bookmark, but
     * also for a link opened from another application — a mail client or a chat — so a delivered link
     * carries it too. Such a request is answered the confirmation page by the caller, as is a request
     * from a browser without Fetch Metadata and without {@code Origin}. A cookie-forwarding instance
     * has no received origin to compare against here, so only its additional origins are trusted.
     * <p>
     * Thread-safe: the defence is immutable.
     *
     * @param origin        the request's first {@code Origin} header value, {@code null} when absent
     * @param fetchMetadata the request's Fetch Metadata headers, each {@code null} when absent
     * @return {@code true} when the request is a non-speculative top-level document navigation with a
     *         trusted {@code Origin}, or with no {@code Origin} and a {@code same-origin}
     *         {@code Sec-Fetch-Site}; {@code false} otherwise
     */
    public boolean provesTrustedDocumentNavigation(@Nullable String origin, FetchMetadata fetchMetadata) {
        Objects.requireNonNull(fetchMetadata, "fetchMetadata");
        if (fetchMetadata.purpose() != null
                || !absentOrEqual(fetchMetadata.mode(), NAVIGATE_MODE)
                || !absentOrEqual(fetchMetadata.dest(), DOCUMENT_DEST)) {
            return false;
        }
        if (origin != null) {
            return isTrusted(origin, null)
                    || (gatewayOrigin != null && gatewayOrigin.equals(normaliseOrigin(origin)));
        }
        return fetchSite(fetchMetadata.site()).filter(SAME_ORIGIN::equals).isPresent();
    }

    /** Whether a Fetch Metadata value is absent or, compared case-insensitively, the expected one. */
    private static boolean absentOrEqual(@Nullable String value, String expected) {
        return value == null || expected.equals(value.toLowerCase(Locale.ROOT));
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

    /**
     * The one origin-trust rule of both entry points. {@code request} supplies the received origin a
     * cookie-forwarding instance also trusts; {@code null} when the caller has no request to read it
     * from, in which case only the configured origins are trusted.
     */
    private boolean isTrusted(String origin, @Nullable PipelineRequest request) {
        if (!trustsReceivedOrigin) {
            return trustedOrigins.contains(origin.toLowerCase(Locale.ROOT));
        }
        String candidate = normaliseOrigin(origin);
        return trustedOrigins.contains(candidate)
                || (request != null && receivedOrigin(request).filter(candidate::equals).isPresent());
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

    /**
     * The one {@code Sec-Fetch-Site} parse of both entry points: the value lower-cased, empty when the
     * header is absent.
     */
    private static Optional<String> fetchSite(@Nullable String value) {
        return Optional.ofNullable(value).map(site -> site.toLowerCase(Locale.ROOT));
    }

    private static GatewayException rejected(String disposition) {
        LOGGER.debug(REJECTION_TEXT + "%s", disposition);
        return new GatewayException(EventType.CSRF_REJECTED, REJECTION_TEXT + disposition);
    }

    /**
     * The Fetch Metadata request headers the logout navigation proof reads, each the request's first
     * value as received, {@code null} when the header is absent.
     *
     * @param site    {@code Sec-Fetch-Site}
     * @param mode    {@code Sec-Fetch-Mode}
     * @param dest    {@code Sec-Fetch-Dest}
     * @param purpose the speculative-load marker: {@code Sec-Purpose}, or, when the request carries none, a
     *                {@code prefetch} value of the legacy {@code Purpose} or {@code X-Moz} header
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record FetchMetadata(@Nullable String site, @Nullable String mode, @Nullable String dest,
    @Nullable String purpose) {

        /** A request that carries none of the headers — a browser without Fetch Metadata, or no browser. */
        public static final FetchMetadata ABSENT = new FetchMetadata(null, null, null, null);
    }
}
