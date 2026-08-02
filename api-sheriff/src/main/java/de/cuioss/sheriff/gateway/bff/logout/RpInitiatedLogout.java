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
package de.cuioss.sheriff.gateway.bff.logout;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


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
 * concerns: it {@linkplain TokenRevocation revokes} the mediated tokens (RFC 7009, best-effort), mints
 * a session-bound {@code state}, and carries that {@code state} in the short-lived single-use
 * {@value #LOGOUT_STATE_COOKIE_NAME} cookie. The engine-owned {@code post_logout_redirect_uri} is a
 * gateway-owned reserved path (the return leg), so the browser never controls the redirect target.
 * <p>
 * The {@linkplain #completeReturn return leg} verifies the returned {@code state} against the cookie
 * (constant-time, engine-owned), clears the single-use cookie, then redirects to the configured
 * {@code final_redirect}. A missing or mismatched {@code state} is rejected {@code 400} — a forged
 * logout-return cannot land the browser anywhere.
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

    private final EndSessionFlow endSessionFlow;
    private final TokenRevocation revocation;
    private final String endSessionEndpoint;
    private final String postLogoutRedirectUri;
    private final String finalRedirect;
    private final Duration stateCookieTtl;

    /**
     * Assembles the RP-initiated logout with the engine end-session flow and the gateway-side settings.
     *
     * @param endSessionFlow        the engine end-session redirect builder (owns exact-match validation)
     * @param revocation            the RFC 7009 token-revocation seam (best-effort)
     * @param endSessionEndpoint    the IdP {@code end_session_endpoint} (from discovery)
     * @param postLogoutRedirectUri the gateway-owned return-leg URI sent to the IdP (exact-match)
     * @param finalRedirect         the application landing URL after the return leg
     * @param stateCookieTtl        the short lifetime of the single-use logout-state cookie (e.g. 60s)
     */
    public RpInitiatedLogout(EndSessionFlow endSessionFlow, TokenRevocation revocation, String endSessionEndpoint,
            String postLogoutRedirectUri, String finalRedirect, Duration stateCookieTtl) {
        this.endSessionFlow = Objects.requireNonNull(endSessionFlow, "endSessionFlow");
        this.revocation = Objects.requireNonNull(revocation, "revocation");
        this.endSessionEndpoint = requireNonBlank(endSessionEndpoint, "endSessionEndpoint");
        this.postLogoutRedirectUri = requireNonBlank(postLogoutRedirectUri, "postLogoutRedirectUri");
        this.finalRedirect = requireNonBlank(finalRedirect, "finalRedirect");
        this.stateCookieTtl = Objects.requireNonNull(stateCookieTtl, "stateCookieTtl");
    }

    /**
     * The configured application landing after logout. The {@link de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint}
     * edge redirects an <em>already-logged-out</em> browser (a logout request that carries no live
     * session, so there is no {@code id_token_hint} to send) straight here, bypassing the IdP round-trip.
     *
     * @return the {@code final_redirect} application landing URL
     */
    public String finalRedirect() {
        return finalRedirect;
    }

    /**
     * Initiates RP-initiated logout for a live session: revokes the mediated tokens (best-effort),
     * mints the session-bound {@code state}, and builds the engine end-session redirect carrying the
     * {@code id_token_hint}, the exact {@code post_logout_redirect_uri}, and the {@code state}.
     *
     * @param session the live session being logged out (its raw ID token is the {@code id_token_hint})
     * @return the redirect to the IdP {@code end_session_endpoint} carrying the logout-state {@code Set-Cookie}
     */
    public LogoutRedirect initiate(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // Revocation at the IdP is best-effort: any runtime failure of the revocation seam must not
        // strand the browser half-logged-out, so the catch is deliberately broad.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            revocation.revoke(session);
        } catch (RuntimeException revocationFailure) {
            // Revocation at the IdP is best-effort: the authoritative, immediately-effective step is the
            // local session destruction the caller performs. A revocation-endpoint failure must not strand
            // the browser half-logged-out, so it is logged and the logout proceeds.
            LOGGER.debug(revocationFailure, "Token revocation failed during RP-initiated logout — proceeding with local logout");
        }
        String state = newState();
        String location = endSessionFlow.buildLogoutRedirect(endSessionEndpoint, session.idToken(),
                postLogoutRedirectUri, state);
        return new LogoutRedirect(location, List.of(stateSetCookie(state)));
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
     * The RFC 7009 token-revocation seam. The session runtime binds it to the engine's revocation
     * call against the {@code revocation_endpoint}; a test binds it to a no-op or a failing stub.
     * Revocation is best-effort — the caller's local session destruction is the authoritative logout.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface TokenRevocation {

        /**
         * Revokes the mediated tokens held by the given session (RFC 7009).
         *
         * @param session the session whose access/refresh tokens are being revoked
         */
        void revoke(SessionRecord session);
    }

    /**
     * The framework-agnostic result of a logout initiation: a {@code 302} redirect to the IdP
     * {@code end_session_endpoint}, carrying the single-use logout-state {@code Set-Cookie}.
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
