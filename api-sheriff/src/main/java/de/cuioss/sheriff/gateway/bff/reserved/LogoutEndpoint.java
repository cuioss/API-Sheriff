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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The RP-initiated logout endpoint — the request/response edge over {@link RpInitiatedLogout}, the
 * mirror of {@link CallbackEndpoint} for the logout direction (D5). It owns the two reserved logout
 * legs ({@link ReservedPathRegistry.ReservedEndpoint#LOGOUT} and
 * {@link ReservedPathRegistry.ReservedEndpoint#LOGOUT_RETURN}) and the session binding; the
 * transport-free logic — the revocation of the session's refresh token, {@code state} minting, the
 * engine end-session redirect, and the return-leg {@code state} verification — lives in
 * {@link RpInitiatedLogout}.
 * <p>
 * <strong>Provenance comes first.</strong> The logout path is matched whatever the method, so a page
 * could otherwise end a user's session with a plain link or an image — a cross-site page, and equally a
 * same-origin one carrying markup an attacker placed in content the gateway serves. Before any session
 * is resolved, {@link #logout(String, String, CsrfDefence.FetchMetadata, Instant)} asks the logout
 * leg's {@link CsrfDefence} whether the request is a top-level document navigation from a trusted
 * origin ({@link CsrfDefence#provesTrustedDocumentNavigation(String, CsrfDefence.FetchMetadata)}: no
 * {@code Sec-Purpose}, {@code Sec-Fetch-Mode: navigate} and {@code Sec-Fetch-Dest: document} when
 * present, and a trusted {@code Origin} or, with {@code Origin} absent,
 * {@code Sec-Fetch-Site: same-origin}). A same-origin image, script, {@code fetch()}, frame, prefetch or
 * prerender, a typed URL or a link opened from another application ({@code Sec-Fetch-Site: none}), and
 * a request from a browser without Fetch Metadata and without {@code Origin} therefore lack the proof.
 * Without it the answer is a {@code 200} confirmation page: a fixed HTML body that reflects nothing of the
 * request, with one form whose button {@code POST}s to the configured logout path. The browser marks
 * that {@code POST} with the gateway's own {@code Origin}, which the logout leg's defence trusts
 * whatever {@code session.csrf.trusted_origins} lists, so it re-enters this leg with the proof. The
 * page is {@code no-store}, forbids framing ({@code Content-Security-Policy} {@code frame-ancestors
 * 'none'}, {@code X-Frame-Options: DENY}), carries no {@code form-action} so the {@code POST} can follow
 * the identity provider's end-session redirect, and leaves the session and its cookies untouched;
 * {@link RpInitiatedLogout} is not reached, so no identity-provider call is made.
 * <p>
 * <strong>Logout leg.</strong> With the proof, {@link #logout(String, String, CsrfDefence.FetchMetadata, Instant)}
 * resolves the request's live {@link SessionRecord} through the mode-neutral {@link SessionBinding}
 * seam and
 * <strong>destroys it first</strong> ({@link SessionBinding#destroy}): the local session
 * destruction is the authoritative, immediately-effective logout, and nothing that follows can
 * prevent it. Only then does it drive {@link RpInitiatedLogout#initiate}, which first revokes the
 * session's refresh token at the identity provider (RFC 7009, synchronously) and then builds the
 * {@code end_session_endpoint} redirect carrying the {@code id_token_hint}, the exact
 * {@code post_logout_redirect_uri}, and the single-use logout-state cookie. A failed revocation is
 * reported by {@link RpInitiatedLogout} and never turns the logout into an error; the access token is
 * not revoked.
 * <p>
 * <strong>Which refresh token is revoked.</strong> The one the session held <em>when it was
 * destroyed</em>, not the one it held when it was resolved: {@link SessionBinding#destroy} hands back
 * the session as it stood at its destruction, and that record is what {@link RpInitiatedLogout#initiate}
 * receives. In server mode it is the record the store removed, so a refresh that rotated the token
 * between the resolve and the destruction is honoured; in cookie mode it is the record the request
 * presented. When the session was already gone at the destruction — another path ended it after the
 * resolve: a concurrent login evicting the subject's oldest session, the periodic expiry sweep, or a
 * concurrent resolve evicting it as expired — the destruction hands back nothing, and the endpoint
 * hands {@link RpInitiatedLogout#initiate} the session it resolved instead. None of those paths
 * revokes anything, so the resolved record's refresh token is revoked here; if it was already spent
 * by a rotation, revoking it is harmless (RFC 7009, section 2.2).
 * <p>
 * Every answer of a proven logout clears every cookie the binding sets
 * ({@link SessionBinding#clearingSetCookieHeaders()} — in cookie mode the session cookie and its
 * activity cookie).
 * <p>
 * A proven logout is always answered with a {@code 302}. When an end-session redirect was built, the browser is sent to
 * the identity provider; that redirect is marked {@linkplain LogoutOutcome#carriesIdToken() as
 * carrying the ID token} so the edge answers it with {@code Referrer-Policy: no-referrer}. In every
 * other case the browser is redirected to {@link RpInitiatedLogout#finalRedirect()}: a logout
 * request that carries <em>no</em> live session (already logged out, and there is no
 * {@code id_token_hint} to send), a session without an ID token, an identity provider that
 * publishes no usable end-session endpoint, provider metadata that cannot be obtained, and any
 * failure while the redirect is built. A logout request is never answered with an error.
 * <p>
 * <strong>Return leg.</strong> {@link #completeReturn(String, String)} delegates to
 * {@link RpInitiatedLogout#completeReturn} — the returned {@code state} is verified (constant-time,
 * engine-owned) against the single-use logout-state cookie, the cookie is cleared, and the browser is
 * redirected to {@code final_redirect}; a missing/mismatched {@code state} is rejected {@code 400}, so
 * a forged logout-return cannot land the browser anywhere.
 * <p>
 * The endpoint is framework-agnostic (raw {@code Cookie}, {@code Origin} and Fetch Metadata header
 * values in, a {@link LogoutOutcome} the edge renders out — no JAX-RS/Vert.x coupling), so it is
 * unit-testable without a container; the session runtime wires it to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutEndpoint {

    /** The parameter name the null checks of a redirect location report. */
    private static final String LOCATION_PARAMETER = "location";

    private static final CuiLogger LOGGER = new CuiLogger(LogoutEndpoint.class);

    /**
     * The fixed confirmation page. Its one placeholder is the HTML-escaped configured logout path; no
     * request input is ever written into it.
     */
    private static final String CONFIRMATION_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Sign out</title>
            </head>
            <body>
            <h1>Sign out</h1>
            <p>Do you want to sign out?</p>
            <form method="POST" action="%s">
            <button type="submit">Sign out</button>
            </form>
            </body>
            </html>
            """;

    private final RpInitiatedLogout rpInitiatedLogout;
    private final SessionBinding sessionBinding;
    private final CsrfDefence csrfDefence;
    /** The rendered confirmation page, {@code null} when no logout path is reserved. */
    private final @Nullable String confirmationPage;

    /**
     * Assembles the logout endpoint with the RP-initiated logout logic, the session binding, the
     * session CSRF defence the provenance is judged by, and the logout path the confirmation form
     * posts to.
     *
     * @param rpInitiatedLogout the transport-free RP-initiated logout orchestration
     * @param sessionBinding    the mode-neutral session binding the session is resolved, destroyed,
     *                          and cleared through
     * @param csrfDefence       the logout leg's defence — the session CSRF defence the edge enforces on
     *                          unsafe-method session requests,
     *                          {@linkplain CsrfDefence#withGatewayOrigin(String) also trusting the
     *                          gateway's own origin} — whose trusted origins prove a logout
     * @param logoutPath        the reserved {@code oidc.logout.path} the confirmation form posts to;
     *                          {@code null} when no logout path is reserved, in which case only the
     *                          return leg can be dispatched here
     */
    public LogoutEndpoint(RpInitiatedLogout rpInitiatedLogout, SessionBinding sessionBinding,
            CsrfDefence csrfDefence, @Nullable String logoutPath) {
        this.rpInitiatedLogout = Objects.requireNonNull(rpInitiatedLogout, "rpInitiatedLogout");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.csrfDefence = Objects.requireNonNull(csrfDefence, "csrfDefence");
        this.confirmationPage = logoutPath == null ? null : CONFIRMATION_PAGE.formatted(escapeHtml(logoutPath));
    }

    /**
     * Handles the RP-initiated logout leg. Without the proof of a top-level document navigation from a
     * trusted origin it answers the confirmation page and touches nothing else. With it, it resolves the live session, destroys it, revokes the
     * refresh token the destroyed session held — or the resolved session's, when another path had
     * already ended it — drives the engine end-session redirect, and clears the session cookie.
     * <p>
     * Thread-safe: the endpoint holds no mutable state and its collaborators are safe for concurrent
     * use.
     *
     * @param cookieHeader  the raw request {@code Cookie} header value, may be absent
     * @param origin        the request's first {@code Origin} header value, may be absent
     * @param fetchMetadata the request's Fetch Metadata headers, each may be absent
     * @param now           the reference instant (the session-resolution TTL anchor)
     * @return without the proof, a {@code 200} confirmation page that sets no cookie;
     *         otherwise a {@code 302} redirect to the IdP {@code end_session_endpoint}
     *         (session-clearing and logout-state {@code Set-Cookie} headers) when an end-session
     *         redirect was built, or a {@code 302} straight to {@code final_redirect} clearing the
     *         session cookie
     * @throws IllegalStateException when the request lacks the proof and no logout path is reserved —
     *                               the logout leg is dispatched only for a reserved logout path
     */
    public LogoutOutcome logout(@Nullable String cookieHeader, @Nullable String origin,
            CsrfDefence.FetchMetadata fetchMetadata, Instant now) {
        Objects.requireNonNull(fetchMetadata, "fetchMetadata");
        Objects.requireNonNull(now, "now");

        // Judged before any session is resolved: a request without the proof learns nothing about the
        // session, and no identity-provider call is made for it.
        if (!csrfDefence.provesTrustedDocumentNavigation(origin, fetchMetadata)) {
            if (confirmationPage == null) {
                throw new IllegalStateException("logout leg dispatched without a reserved logout path");
            }
            LOGGER.debug("RP-initiated logout without a trusted document navigation — answering the confirmation page, session untouched");
            return LogoutOutcome.confirmation(confirmationPage);
        }

        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            LOGGER.debug("RP-initiated logout without a live session — already logged out, landing on final_redirect");
            return LogoutOutcome.redirect(rpInitiatedLogout.finalRedirect(),
                    sessionBinding.clearingSetCookieHeaders());
        }
        SessionRecord session = resolved.get();
        // Local logout is the authoritative, immediately-effective step, so it comes first: whatever
        // happens to the end-session redirect below, the session is already gone. The destruction hands
        // back the session as it stood at that moment: a refresh that rotated the refresh token after
        // the resolve above is reflected there, and only there.
        Optional<SessionRecord> destroyed = sessionBinding.destroy(session);
        // Revoke the token the session held when it was destroyed, not the resolve-time snapshot, which
        // a rotation may already have spent. When another path ended the session between the resolve and
        // the destruction, nothing came back, and none of those paths revokes anything — so the
        // resolve-time record is the only one left that names the token, and it is revoked instead.
        // Revoking a token that is already spent is harmless (RFC 7009, section 2.2).
        SessionRecord toRevoke = destroyed.orElse(session);
        if (destroyed.isEmpty()) {
            LOGGER.debug("RP-initiated logout — session already gone at destruction, revoking the resolved session's token");
        }

        Optional<RpInitiatedLogout.LogoutRedirect> endSession;
        // The catch is deliberately broad: the end-session redirect is built by the engine over
        // discovered provider metadata, and no failure there may turn a completed local logout into
        // an error answer.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            endSession = rpInitiatedLogout.initiate(toRevoke);
        } catch (RuntimeException initiationFailure) {
            LOGGER.debug(initiationFailure,
                    "RP-initiated logout — end-session redirect construction failed; local session destroyed, landing on final_redirect");
            endSession = Optional.empty();
        }
        if (endSession.isEmpty()) {
            LOGGER.debug("RP-initiated logout — session destroyed, no end-session redirect; landing on final_redirect");
            return LogoutOutcome.redirect(rpInitiatedLogout.finalRedirect(),
                    sessionBinding.clearingSetCookieHeaders());
        }
        RpInitiatedLogout.LogoutRedirect redirect = endSession.get();
        List<String> setCookies = new ArrayList<>(redirect.setCookieHeaders());
        setCookies.addAll(sessionBinding.clearingSetCookieHeaders());
        LOGGER.debug("RP-initiated logout — session destroyed, redirecting to the IdP end_session_endpoint");
        return LogoutOutcome.endSessionRedirect(redirect.location(), setCookies);
    }

    /**
     * Handles the RP-initiated logout return leg: verifies the returned {@code state} against the
     * single-use logout-state cookie, clears the cookie, and redirects to {@code final_redirect}.
     *
     * @param stateParam   the {@code state} returned by the IdP on the post-logout redirect, may be absent
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @return a {@code 302} redirect to {@code final_redirect} on a matching state, or a {@code 400}
     *         on a missing/mismatched state
     */
    public LogoutOutcome completeReturn(@Nullable String stateParam, @Nullable String cookieHeader) {
        RpInitiatedLogout.LogoutReturn result = rpInitiatedLogout.completeReturn(stateParam, cookieHeader);
        if (!result.isRedirect()) {
            return LogoutOutcome.error(result.status());
        }
        return LogoutOutcome.redirect(Objects.requireNonNull(result.location(), LOCATION_PARAMETER),
                result.setCookieHeaders());
    }

    /**
     * HTML-escapes the configured logout path for the confirmation form's {@code action} attribute.
     */
    private static String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * The framework-agnostic result of a logout leg: a {@code 302} redirect (to the IdP
     * {@code end_session_endpoint}, or to {@code final_redirect} on the return, already-logged-out
     * and local-only paths) carrying the {@code Set-Cookie} headers to emit, a {@code 200}
     * confirmation page carrying its HTML body and fixed headers, or a {@code 4xx} error with no
     * redirect.
     * <p>
     * The redirect to the IdP is the one outcome that holds token material: its location's query
     * carries the {@code id_token_hint}. {@link #carriesIdToken()} marks it, and the record's string
     * form prints neither the location, the body nor a cookie value.
     *
     * @param status           the HTTP status the edge returns
     * @param location         the redirect target, {@code null} on anything but a redirect outcome
     * @param body             the HTML body, present only on the confirmation page
     * @param headers          the fixed response headers to emit, present only on the confirmation
     *                         page
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, empty on an error and on
     *                         the confirmation page
     * @param carriesIdToken   {@code true} when the location is the end-session redirect, whose
     *                         query carries the {@code id_token_hint}
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record LogoutOutcome(int status, @Nullable String location, @Nullable String body,
    Map<String, String> headers, List<String> setCookieHeaders, boolean carriesIdToken) {

        private static final int OK = 200;
        private static final int FOUND = 302;

        /**
         * The fixed headers of the confirmation page: an HTML media type, no caching, and a policy that
         * loads nothing and forbids framing (the {@code X-Frame-Options} line for user agents that
         * predate {@code frame-ancestors}).
         * <p>
         * The policy deliberately carries no {@code form-action}. A browser enforces that directive on
         * every redirect of a form submission, so {@code form-action 'self'} would block the
         * confirmation {@code POST}'s {@code 302} to the identity provider's cross-origin
         * {@code end_session_endpoint}: the local session would end while the provider's single sign-on
         * session survived. {@code form-action} does not fall back to {@code default-src}, so leaving it
         * out allows the post and its redirect; the page's one form is fixed and reflects no request
         * input, so the directive would protect nothing here.
         */
        private static final Map<String, String> CONFIRMATION_HEADERS = Map.of(
                "Content-Type", "text/html; charset=utf-8",
                "Cache-Control", "no-store",
                "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'",
                "X-Frame-Options", "DENY");

        /**
         * Canonical constructor defensively copying the headers and the cookies.
         */
        public LogoutOutcome {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A {@code 200} confirmation page that sets no cookie and carries the fixed confirmation
         * headers.
         *
         * @param body the rendered confirmation page
         * @return the confirmation outcome
         */
        public static LogoutOutcome confirmation(String body) {
            Objects.requireNonNull(body, "body");
            return new LogoutOutcome(OK, null, body, CONFIRMATION_HEADERS, List.of(), false);
        }

        /**
         * A {@code 302} redirect to a gateway-configured landing, carrying the {@code Set-Cookie}
         * headers. The location holds no token material.
         *
         * @param location         the redirect target
         * @param setCookieHeaders the {@code Set-Cookie} header values to emit
         * @return the redirect outcome
         */
        public static LogoutOutcome redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, LOCATION_PARAMETER);
            return new LogoutOutcome(FOUND, location, null, Map.of(), setCookieHeaders, false);
        }

        /**
         * A {@code 302} redirect to the IdP {@code end_session_endpoint}, whose query carries the
         * {@code id_token_hint}.
         *
         * @param location         the end-session redirect URL
         * @param setCookieHeaders the {@code Set-Cookie} header values to emit
         * @return the end-session redirect outcome
         */
        public static LogoutOutcome endSessionRedirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, LOCATION_PARAMETER);
            return new LogoutOutcome(FOUND, location, null, Map.of(), setCookieHeaders, true);
        }

        /**
         * An error outcome carrying no redirect and no cookies.
         *
         * @param status the {@code 4xx} status
         * @return the error outcome
         */
        public static LogoutOutcome error(int status) {
            return new LogoutOutcome(status, null, null, Map.of(), List.of(), false);
        }

        /**
         * @return {@code true} when this outcome is a redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }

        /**
         * Overridden so that neither an end-session location — its query carries the
         * {@code id_token_hint} — nor a body or a cookie value reaches a log line or an exception
         * message.
         *
         * @return the status, the location unless it carries the ID token, whether a body is present,
         *         the header names and the number of cookies
         */
        @Override
        public String toString() {
            return "LogoutOutcome[status=%s, location=%s, body=%s, headerNames=%s, setCookieHeaders=%s, carriesIdToken=%s]"
                    .formatted(status, carriesIdToken ? "<redacted>" : location, body == null ? "absent" : "present",
                            headers.keySet(), setCookieHeaders.size(), carriesIdToken);
        }
    }
}
