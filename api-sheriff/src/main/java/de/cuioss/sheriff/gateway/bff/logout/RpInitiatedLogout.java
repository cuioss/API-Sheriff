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
package de.cuioss.sheriff.gateway.bff.logout;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The gateway-side RP-initiated logout logic (D5) — the mirror of the login flow for the logout
 * direction. Framework-agnostic by construction; the {@link de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint}
 * owns the request/response edge and the session store.
 * <p>
 * The gateway re-implements <strong>no</strong> OAuth leg: the engine's {@link EndSessionFlow} owns
 * the {@code end_session_endpoint} redirect construction, the {@code id_token_hint}, and — via its
 * {@code PostLogoutRedirectValidator} — the <strong>exact-match</strong> {@code post_logout_redirect_uri}
 * that is the open-redirect defence. {@link RpInitiatedLogout} only orchestrates the gateway-side
 * concerns: it hands the session to the {@link TokenRevocation} seam it was constructed with
 * (best-effort — a failure of the seam is logged and the logout proceeds), mints
 * a session-bound {@code state}, and carries that {@code state} in the short-lived single-use
 * {@value #LOGOUT_STATE_COOKIE_NAME} cookie. It sends no revocation request itself: whether a token
 * is revoked at the identity provider is decided entirely by the seam implementation it is given. The engine-owned {@code post_logout_redirect_uri} is a
 * gateway-owned reserved path (the return leg), so the browser never controls the redirect target.
 * <p>
 * <strong>The end-session request is a redirect.</strong> {@link #initiate} hands out the URL the
 * engine built on the provider's {@code end_session_endpoint}; its query carries the
 * {@code id_token_hint}, the {@code post_logout_redirect_uri} and the {@code state}. The ID token is
 * therefore part of a URL the browser follows; the access and refresh tokens are in no answer.
 * <p>
 * <strong>The end-session endpoint is resolved per logout, and its absence is not a failure.</strong>
 * The endpoint comes from provider discovery through the {@link EndSessionEndpointSource} seam, read
 * when a logout is initiated rather than when this object is built. When discovery fails, when the
 * provider publishes no usable {@code end_session_endpoint}, or when the session holds no ID token,
 * {@link #initiate} yields no redirect: the caller's local logout stands on its own and the browser
 * lands on {@code final_redirect}. A provider without a usable endpoint is recorded once per instance
 * as {@code ApiSheriff-135}; every repeat is a {@code DEBUG} line.
 * <p>
 * The {@linkplain #completeReturn return leg} verifies the returned {@code state} against the cookie
 * (constant-time, engine-owned), clears the single-use cookie, then redirects to the configured
 * {@code final_redirect}. A missing or mismatched {@code state} is rejected {@code 400} — a forged
 * logout-return cannot land the browser anywhere.
 * <p>
 * Thread-safe: the collaborators are safe for concurrent use and the one piece of mutable state is
 * an atomic latch; one instance serves every request.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RpInitiatedLogout {

    private static final CuiLogger LOGGER = new CuiLogger(RpInitiatedLogout.class);

    /** The {@code __Host-}-prefixed single-use logout-state cookie name (CSRF defence for logout). */
    public static final String LOGOUT_STATE_COOKIE_NAME = "__Host-sheriff-logout";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int STATE_BYTES = 32;
    private static final int BAD_REQUEST = 400;
    private static final int FOUND = 302;
    private static final String SCHEME_HTTPS = "https";
    private static final String SCHEME_HTTP = "http";

    private final EndSessionFlow endSessionFlow;
    private final TokenRevocation revocation;
    private final EndSessionEndpointSource endSessionEndpointSource;
    private final String postLogoutRedirectUri;
    private final String finalRedirect;
    private final Duration stateCookieTtl;
    private final AtomicBoolean missingEndpointReported = new AtomicBoolean();

    /**
     * Assembles the RP-initiated logout with the engine end-session flow and the gateway-side settings.
     *
     * @param endSessionFlow           the engine end-session redirect builder (owns exact-match validation)
     * @param revocation               the token-revocation seam, called once per initiated logout
     *                                 (best-effort)
     * @param endSessionEndpointSource the seam naming the provider's {@code end_session_endpoint},
     *                                 consulted once per initiated logout
     * @param postLogoutRedirectUri    the gateway-owned return-leg URI sent to the IdP (exact-match)
     * @param finalRedirect            the application landing URL after the return leg
     * @param stateCookieTtl           the short lifetime of the single-use logout-state cookie (e.g. 60s)
     */
    public RpInitiatedLogout(EndSessionFlow endSessionFlow, TokenRevocation revocation,
            EndSessionEndpointSource endSessionEndpointSource, String postLogoutRedirectUri, String finalRedirect,
            Duration stateCookieTtl) {
        this.endSessionFlow = Objects.requireNonNull(endSessionFlow, "endSessionFlow");
        this.revocation = Objects.requireNonNull(revocation, "revocation");
        this.endSessionEndpointSource = Objects.requireNonNull(endSessionEndpointSource, "endSessionEndpointSource");
        this.postLogoutRedirectUri = requireNonBlank(postLogoutRedirectUri, "postLogoutRedirectUri");
        this.finalRedirect = requireNonBlank(finalRedirect, "finalRedirect");
        this.stateCookieTtl = Objects.requireNonNull(stateCookieTtl, "stateCookieTtl");
    }

    /**
     * The configured application landing after logout. The {@link de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint}
     * edge redirects the browser straight here whenever it is not sent to the identity provider: an
     * <em>already-logged-out</em> browser (a logout request that carries no live session), and a
     * logout for which {@link #initiate} yielded no redirect.
     *
     * @return the {@code final_redirect} application landing URL
     */
    public String finalRedirect() {
        return finalRedirect;
    }

    /**
     * Initiates RP-initiated logout for a session: hands the session to the token-revocation
     * seam (best-effort — a {@link RuntimeException} it raises is logged and does not stop the logout),
     * resolves the provider's end-session endpoint, mints the session-bound {@code state}, and has the
     * engine build the end-session redirect carrying the {@code id_token_hint}, the exact
     * {@code post_logout_redirect_uri}, and the {@code state}.
     * <p>
     * The result is empty — and the caller completes the logout locally — when the session holds no ID
     * token, when the end-session endpoint cannot be resolved, or when the provider publishes none
     * that is an absolute {@code http} or {@code https} URI. None of these raises.
     *
     * @param session the session being logged out (its raw ID token is the {@code id_token_hint})
     * @return the redirect to the IdP {@code end_session_endpoint} carrying the logout-state
     *         {@code Set-Cookie}; empty when the browser cannot be sent there
     */
    public Optional<LogoutRedirect> initiate(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // The revocation seam is best-effort: any runtime failure of it must not strand the browser
        // half-logged-out, so the catch is deliberately broad.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            revocation.revoke(session);
        } catch (RuntimeException revocationFailure) {
            // The revocation seam is best-effort: the authoritative, immediately-effective step is the
            // local session destruction the caller performs. A failure of the seam must not strand
            // the browser half-logged-out, so it is logged and the logout proceeds.
            LOGGER.debug(revocationFailure, "Token revocation failed during RP-initiated logout — proceeding with local logout");
        }
        if (session.idToken().isBlank()) {
            LOGGER.debug("RP-initiated logout for a session without an ID token — no end-session redirect is sent");
            return Optional.empty();
        }
        Optional<String> resolvedEndpoint = resolveEndSessionEndpoint();
        if (resolvedEndpoint.isEmpty()) {
            return Optional.empty();
        }
        String endpoint = resolvedEndpoint.get();
        if (!isAbsoluteHttpUri(endpoint)) {
            reportMissingEndpoint();
            return Optional.empty();
        }
        String state = newState();
        String location = endSessionFlow.buildLogoutRedirect(endpoint, session.idToken(),
                postLogoutRedirectUri, state);
        return Optional.of(new LogoutRedirect(location, List.of(stateSetCookie(state))));
    }

    /**
     * Reads the end-session endpoint from the seam. A failure of the seam — provider discovery could
     * not be completed — is not a failure of the logout: it is logged and reported as an absent
     * endpoint, and the next logout asks the seam again. The catch is deliberately broad because the
     * seam reaches the confidential-client engine, whose failure types are not part of this contract.
     */
    private Optional<String> resolveEndSessionEndpoint() {
        Optional<String> declared;
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            declared = endSessionEndpointSource.endSessionEndpoint();
        } catch (RuntimeException discoveryFailure) {
            LOGGER.debug(discoveryFailure,
                    "RP-initiated logout — the end-session endpoint could not be resolved; no end-session redirect is sent");
            return Optional.empty();
        }
        Optional<String> usable = declared.filter(endpoint -> !endpoint.isBlank());
        if (usable.isEmpty()) {
            reportMissingEndpoint();
        }
        return usable;
    }

    private void reportMissingEndpoint() {
        if (missingEndpointReported.compareAndSet(false, true)) {
            LOGGER.warn(BffLogMessages.WARN.NO_END_SESSION_ENDPOINT);
        } else {
            LOGGER.debug("RP-initiated logout without a usable end_session_endpoint again — already reported "
                    + "once, stays at DEBUG for the rest of the process");
        }
    }

    /**
     * Whether the candidate is an absolute {@code http} or {@code https} URI with a host. Anything
     * else — a relative reference, another scheme, a URI without a host, or text that is no URI at
     * all — is not an address the browser may be redirected to.
     */
    private static boolean isAbsoluteHttpUri(String candidate) {
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException _) {
            return false;
        }
        String scheme = uri.getScheme();
        return uri.getHost() != null
                && (SCHEME_HTTPS.equalsIgnoreCase(scheme) || SCHEME_HTTP.equalsIgnoreCase(scheme));
    }

    /**
     * Completes the RP-initiated logout return leg: verifies the returned {@code state} against the
     * single-use logout-state cookie, clears the cookie, and redirects to {@code final_redirect}.
     *
     * @param stateParam  the {@code state} returned by the IdP on the post-logout redirect, may be absent
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @return the redirect to {@code final_redirect} on a matching state, or a {@code 400} on mismatch
     */
    public LogoutReturn completeReturn(@Nullable String stateParam, @Nullable String cookieHeader) {
        Optional<String> cookieState = readState(cookieHeader);
        if (cookieState.isEmpty()) {
            LOGGER.debug("Post-logout return without a logout-state cookie — rejected");
            return LogoutReturn.error(BAD_REQUEST);
        }
        try {
            endSessionFlow.verifyPostLogoutState(cookieState.get(), stateParam);
        } catch (IllegalStateException stateMismatch) {
            LOGGER.debug(stateMismatch, "Post-logout return state did not match the logout-state cookie — rejected");
            return LogoutReturn.error(BAD_REQUEST);
        }
        return LogoutReturn.redirect(finalRedirect, List.of(clearingStateCookie()));
    }

    private String stateSetCookie(String state) {
        return "%s=%s; Max-Age=%d; Path=/; Secure; HttpOnly; SameSite=Lax"
                .formatted(LOGOUT_STATE_COOKIE_NAME, state, stateCookieTtl.toSeconds());
    }

    private static String clearingStateCookie() {
        return LOGOUT_STATE_COOKIE_NAME + "=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax";
    }

    private static Optional<String> readState(@Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && LOGOUT_STATE_COOKIE_NAME.equals(trimmed.substring(0, equals))) {
                String value = trimmed.substring(equals + 1);
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String newState() {
        byte[] bytes = new byte[STATE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * The token-revocation seam: the hook through which an RFC 7009 revocation of a session's
     * tokens can be performed during logout. {@link RpInitiatedLogout} calls the implementation it
     * is constructed with once per initiated logout and performs no revocation of its own, so what
     * happens at the identity provider is decided by whoever binds the seam — an implementation
     * that does nothing revokes nothing. The seam is best-effort — the caller's local session
     * destruction is the authoritative logout.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface TokenRevocation {

        /**
         * Receives the session being logged out, so that an implementation can revoke the mediated
         * tokens it holds at the identity provider (RFC 7009).
         *
         * @param session the session being logged out, carrying the access and refresh tokens an
         *                implementation may revoke
         */
        void revoke(SessionRecord session);
    }

    /**
     * The seam naming the identity provider's {@code end_session_endpoint}. It is consulted once per
     * initiated logout, so an implementation backed by provider discovery is asked again after a
     * failed attempt instead of the failure being remembered.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface EndSessionEndpointSource {

        /**
         * Names the provider's end-session endpoint.
         *
         * @return the {@code end_session_endpoint} the provider publishes; empty when it publishes none
         * @throws RuntimeException when the provider's metadata cannot be obtained; the caller treats
         *                          that as an absent endpoint for this one logout
         */
        Optional<String> endSessionEndpoint();
    }

    /**
     * The framework-agnostic result of a logout initiation: a {@code 302} redirect to the IdP
     * {@code end_session_endpoint}, carrying the single-use logout-state {@code Set-Cookie}. The
     * location's query holds the {@code id_token_hint}, so {@link #toString()} prints neither the
     * location nor a cookie value.
     *
     * @param location         the IdP end-session redirect URL to send the browser to
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit (the logout-state cookie)
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LogoutRedirect(String location, List<String> setCookieHeaders) {

        /**
         * Canonical constructor rejecting an absent location and defensively copying the cookies.
         */
        public LogoutRedirect {
            Objects.requireNonNull(location, "location");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * Overridden so that neither the location — its query carries the {@code id_token_hint} — nor
         * a cookie value reaches a log line or an exception message.
         *
         * @return the number of cookies only
         */
        @Override
        public String toString() {
            return "LogoutRedirect[location=<redacted>, setCookieHeaders=%s]".formatted(setCookieHeaders.size());
        }
    }

    /**
     * The framework-agnostic result of the logout return leg: either a {@code 302} redirect to
     * {@code final_redirect} carrying the logout-state-clearing {@code Set-Cookie}, or a {@code 400}
     * on a missing/mismatched state.
     *
     * @param status           the HTTP status the edge returns
     * @param location         the redirect target, {@code null} on anything but a matching state
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, empty on error
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record LogoutReturn(int status, @Nullable String location, List<String> setCookieHeaders) {

        /**
         * Canonical constructor defensively copying the cookies.
         */
        public LogoutReturn {
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A successful-return {@code 302} redirect to {@code final_redirect}.
         *
         * @param location         the application landing URL
         * @param setCookieHeaders the logout-state-clearing {@code Set-Cookie}
         * @return the redirect outcome
         */
        public static LogoutReturn redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new LogoutReturn(FOUND, location, setCookieHeaders);
        }

        /**
         * An error outcome carrying no redirect and no cookies.
         *
         * @param status the {@code 4xx} status
         * @return the error outcome
         */
        public static LogoutReturn error(int status) {
            return new LogoutReturn(status, null, List.of());
        }

        /**
         * @return {@code true} when this outcome is a successful-return redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }
    }
}
