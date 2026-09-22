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
package de.cuioss.sheriff.gateway.portal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.bff.runtime.SessionIdentity;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import org.jspecify.annotations.Nullable;

/**
 * The application portal's own reserved path: the framework-agnostic handler the gateway edge
 * consults immediately after the OIDC reserved paths and <em>before</em> route selection, so a prefix
 * route covering {@code portal.path} never sees a request for it.
 * <p>
 * <strong>A reservation seam of its own.</strong> The portal path is deliberately <em>not</em> a member
 * of the OIDC-built {@code ReservedPathRegistry} closed set: that registry matches only on the OIDC
 * host, while the portal answers its path on <em>any</em> host. {@link #matches(String)} is exact
 * string equality with the canonical request path — a trailing-slash or prefix variant never matches
 * — and the boot-time configuration validator has already refused a {@code portal.path} that would
 * collide with a reserved OIDC path or an enabled endpoint's exact route.
 * <p>
 * <strong>What it answers.</strong> {@code GET} renders the overview page through the
 * {@link PortalRenderer} over the fixed {@link PortalPageModel}; {@code HEAD} answers the same status
 * and headers with an empty body; every other method answers {@code 405} with
 * {@code Allow: GET, HEAD}. The page's cache headers come from the {@link PortalResponseEnvelope}: a
 * page rendered for an authenticated session ({@link SessionIdentity#authenticated()}) is
 * {@code no-store} whether or not it carries a username, a session-free page is cacheable for
 * {@code cache_seconds} and, with an active BFF runtime, announces {@code Vary: Cookie}. The model's
 * links follow the portal's login/logout contract: with an active BFF runtime,
 * {@code links.login} is {@code oidc.login.path} carrying the URL-encoded portal path as
 * {@code returnUrl} and {@code links.logout} is {@code oidc.logout.path}, each only when declared;
 * without an active BFF runtime both are absent and every request renders anonymously.
 * <p>
 * <strong>HTML error pages.</strong> With {@code portal.error_pages} declared {@code true},
 * {@link #renderError(int)} renders the same template as an error page for a gateway-originated
 * error: the status is preserved, the title comes from the fixed per-status table, the session is
 * always anonymous and the page is {@code no-store}. Whether a given exit answers with it is decided
 * by the edge through the {@link ErrorPageClassifier}.
 * <p>
 * The {@linkplain #inert() inert} endpoint stands in when {@code gateway.yaml} declares no
 * {@code portal} block: it never matches, so the edge behaves exactly as before.
 * <p>
 * Immutable after construction and framework-agnostic; thread-safe — the renderer and the session
 * resolver are thread-safe, and every call builds its own model.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PortalEndpoint {

    /** The {@code Allow} response-header name of the {@code 405} answer. */
    public static final String ALLOW_HEADER = "Allow";

    /** The methods the portal path answers, as announced by {@code Allow} on a {@code 405}. */
    public static final String ALLOWED_METHODS = "GET, HEAD";

    /** The query parameter selecting a {@linkplain PortalNotice notice}. */
    public static final String NOTICE_PARAM = "notice";

    private static final int OK = 200;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final String RETURN_URL_QUERY = "?returnUrl=";

    private final @Nullable String path;
    private final @Nullable Active active;

    private PortalEndpoint(@Nullable String path, @Nullable Active active) {
        this.path = path;
        this.active = active;
    }

    /**
     * Assembles the endpoint for a declared {@code portal} block.
     *
     * @param portal          the declared {@code portal} block
     * @param catalog         the catalog resolved from the enabled endpoints
     * @param renderer        the renderer over the built-in or the operator template, already
     *                        accepted by the boot-time template checks
     * @param sessionResolver resolves the display identity of a request's session; consulted only when
     *                        {@code bffActive}
     * @param oidc            the global {@code oidc} block the login/logout links derive from,
     *                        {@code null} when none is declared
     * @param bffActive       whether the BFF runtime is active, i.e. whether a browser can hold a
     *                        session the page would reflect
     * @param contextPath     the normalised application context path ({@code /} by default)
     * @return the active portal endpoint
     */
    public static PortalEndpoint of(PortalConfig portal, PortalCatalog catalog, PortalRenderer renderer,
            SessionResolver sessionResolver, @Nullable OidcConfig oidc, boolean bffActive, String contextPath) {
        Objects.requireNonNull(portal, "portal");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(sessionResolver, "sessionResolver");
        Objects.requireNonNull(contextPath, "contextPath");
        Active active = new Active(portal.title(), catalog, renderer, sessionResolver, bffActive,
                bffActive ? loginLink(oidc, portal.path()) : null,
                bffActive ? logoutLink(oidc) : null,
                contextPath,
                new PortalResponseEnvelope(portal.effectiveCacheSeconds(), bffActive),
                portal.effectiveErrorPages());
        return new PortalEndpoint(portal.path(), active);
    }

    /**
     * @return the endpoint of a gateway without a {@code portal} block — it never matches
     */
    public static PortalEndpoint inert() {
        return new PortalEndpoint(null, null);
    }

    /**
     * @return {@code true} when a {@code portal} block is declared and the endpoint answers its path
     */
    public boolean isActive() {
        return active != null;
    }

    /**
     * Whether a request addresses the portal path. The comparison is exact string equality with the
     * canonical request path, on any host: a trailing-slash or prefix variant never matches.
     *
     * @param canonicalPath the single canonical request path, {@code null} before canonicalisation
     * @return {@code true} when the endpoint is active and {@code canonicalPath} is exactly its path
     */
    public boolean matches(@Nullable String canonicalPath) {
        return path != null && path.equals(canonicalPath);
    }

    /**
     * Answers one request for the portal path.
     *
     * @param method         the request method
     * @param rawNoticeParam the raw, still-encoded {@code notice} query-parameter value, {@code null}
     *                       when absent; mapped onto the fixed {@link PortalNotice} vocabulary and
     *                       never echoed
     * @param cookieHeader   the raw request {@code Cookie} header value, {@code null} when absent
     * @param now            the reference instant for session resolution
     * @return the rendered page for {@code GET}, the same status and headers with an empty body for
     *         {@code HEAD}, or a {@code 405} carrying {@code Allow: GET, HEAD} for any other method
     * @throws IllegalStateException when this endpoint is {@linkplain #inert() inert}
     */
    public PortalResponse handle(HttpMethod method, @Nullable String rawNoticeParam, @Nullable String cookieHeader,
            Instant now) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(now, "now");
        Active portal = requireActive();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            Map<String, String> headers = new LinkedHashMap<>(
                    portal.envelope().headers(PortalResponseEnvelope.Cacheability.ERROR_PAGE));
            headers.put(ALLOW_HEADER, ALLOWED_METHODS);
            return new PortalResponse(METHOD_NOT_ALLOWED, headers, "");
        }
        SessionIdentity identity = portal.bffActive()
                ? portal.sessionResolver().resolve(cookieHeader, now)
                : SessionIdentity.anonymous();
        PortalResponseEnvelope.Cacheability cacheability = identity.authenticated()
                ? PortalResponseEnvelope.Cacheability.SESSION_BEARING
                : PortalResponseEnvelope.Cacheability.SESSION_FREE;
        Map<String, String> headers = portal.envelope().headers(cacheability);
        if (method == HttpMethod.HEAD) {
            return new PortalResponse(OK, headers, "");
        }
        PortalPageModel model = PortalPageModel.builder()
                .title(portal.title())
                .catalog(portal.catalog())
                .authenticated(identity.authenticated())
                .username(identity.username())
                .loginLink(portal.loginLink())
                .logoutLink(portal.logoutLink())
                .notice(PortalNotice.parse(rawNoticeParam).orElse(null))
                .contextPath(portal.contextPath())
                .build();
        return new PortalResponse(OK, headers, portal.renderer().render(model.toMap()));
    }

    /**
     * Whether gateway-originated errors may answer with an HTML error page — {@code portal.error_pages}
     * is declared {@code true} on an active endpoint. The edge additionally requires the exit to be
     * {@linkplain ErrorPageClassifier#classify(de.cuioss.sheriff.gateway.events.EventType) HTML-eligible}
     * and the request to {@linkplain ErrorPageClassifier#offersHtml(String) explicitly accept}
     * {@code text/html}.
     *
     * @return {@code true} when the endpoint is active and its error pages are enabled; always
     *         {@code false} for the {@linkplain #inert() inert} endpoint
     */
    public boolean errorPagesEnabled() {
        return active != null && active.errorPages();
    }

    /**
     * Renders the HTML error page of a gateway-originated error. The page carries the unchanged
     * {@code status}, the fixed, generic {@linkplain ErrorPageClassifier#titleFor(int) per-status title},
     * the catalog and the login/logout links, and always renders anonymously: no session is resolved on
     * a failure path. It never carries a problem detail, an exception message or an upstream address —
     * the status is the only request-derived input. The envelope headers are
     * {@link PortalResponseEnvelope.Cacheability#ERROR_PAGE no-store}.
     *
     * @param status the HTTP status the error answers with, preserved verbatim
     * @return the rendered error page
     * @throws IllegalStateException when error pages are not {@linkplain #errorPagesEnabled() enabled}
     */
    public PortalResponse renderError(int status) {
        Active portal = requireActive();
        if (!portal.errorPages()) {
            throw new IllegalStateException("portal error pages are not enabled");
        }
        PortalPageModel model = PortalPageModel.builder()
                .title(portal.title())
                .catalog(portal.catalog())
                .authenticated(false)
                .loginLink(portal.loginLink())
                .logoutLink(portal.logoutLink())
                .contextPath(portal.contextPath())
                .errorStatus(status)
                .errorTitle(ErrorPageClassifier.titleFor(status))
                .build();
        return new PortalResponse(status,
                portal.envelope().headers(PortalResponseEnvelope.Cacheability.ERROR_PAGE),
                portal.renderer().render(model.toMap()));
    }

    private Active requireActive() {
        if (active == null) {
            throw new IllegalStateException("inert portal endpoint answers no request");
        }
        return active;
    }

    /**
     * {@code oidc.login.path} carrying the portal path as the URL-encoded {@code returnUrl}, so a
     * sign-in started from the portal lands back on it; absent when no login path is declared.
     */
    private static @Nullable String loginLink(@Nullable OidcConfig oidc, String portalPath) {
        OidcConfig.Login login = oidc == null ? null : oidc.login();
        String loginPath = login == null ? null : login.path();
        if (isBlank(loginPath)) {
            return null;
        }
        return loginPath + RETURN_URL_QUERY + URLEncoder.encode(portalPath, StandardCharsets.UTF_8);
    }

    /**
     * {@code oidc.logout.path}; absent when no logout path is declared.
     */
    private static @Nullable String logoutLink(@Nullable OidcConfig oidc) {
        OidcConfig.Logout logout = oidc == null ? null : oidc.logout();
        String logoutPath = logout == null ? null : logout.path();
        return isBlank(logoutPath) ? null : logoutPath;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * Resolves the display identity of a request's browser session — bound to
     * {@code BffRuntime.sessionIdentity} in production and to a fixed identity in a test.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface SessionResolver {

        /**
         * @param cookieHeader the raw request {@code Cookie} header value, {@code null} when absent
         * @param now          the reference instant for session resolution
         * @return the session's display identity, anonymous when no live session exists
         */
        SessionIdentity resolve(@Nullable String cookieHeader, Instant now);
    }

    /**
     * The framework-agnostic answer the edge writes for a portal request: the status, the envelope
     * headers (content type, {@code nosniff}, cache policy, and {@code Allow} on a {@code 405}) and
     * the rendered body, empty for {@code HEAD} and {@code 405}. The portal
     * {@code Content-Security-Policy} is not part of it — the edge composes that through
     * {@code SecurityHeadersStage.applyPortalHeaders}.
     *
     * @param status  the HTTP status
     * @param headers the envelope response headers, in emission order
     * @param body    the rendered HTML, empty when the response carries no body
     * @author API Sheriff Team
     * @since 1.0
     */
    public record PortalResponse(int status, Map<String, String> headers, String body) {

        /**
         * Canonical constructor defensively copying the headers into an unmodifiable,
         * insertion-ordered map.
         */
        public PortalResponse {
            headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(headers, "headers")));
            Objects.requireNonNull(body, "body");
        }
    }

    /**
     * The boot-resolved state of an active endpoint, held as one value so the inert endpoint is a
     * single {@code null} rather than ten.
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @SuppressWarnings("java:S107") // one immutable holder of the boot-resolved portal wiring
    private record Active(
    String title,
    PortalCatalog catalog,
    PortalRenderer renderer,
    SessionResolver sessionResolver,
    boolean bffActive,
    @Nullable String loginLink,
    @Nullable String logoutLink,
    String contextPath,
    PortalResponseEnvelope envelope,
    boolean errorPages) {
    }
}
