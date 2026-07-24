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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * The RP-initiated logout endpoint — the request/response edge over {@link RpInitiatedLogout}, the
 * mirror of {@link CallbackEndpoint} for the logout direction (D5). It owns the two reserved logout
 * legs ({@link ReservedPathRegistry.ReservedEndpoint#LOGOUT} and
 * {@link ReservedPathRegistry.ReservedEndpoint#LOGOUT_RETURN}) and the session store; the
 * transport-free logic — token revocation, {@code state} minting, the engine end-session redirect,
 * and the return-leg {@code state} verification — lives in {@link RpInitiatedLogout}.
 * <p>
 * <strong>Logout leg.</strong> {@link #logout(String, Instant)} resolves the opaque {@code __Host-}
 * session cookie to a live {@link SessionRecord}, drives {@link RpInitiatedLogout#initiate} (which
 * revokes the mediated tokens and builds the {@code end_session_endpoint} redirect carrying the
 * {@code id_token_hint}, the exact {@code post_logout_redirect_uri}, and the single-use logout-state
 * cookie), then destroys the server-side session ({@link SessionStore#destroyById}) and clears the
 * session cookie. The local session destruction is the authoritative, immediately-effective logout;
 * the IdP round-trip is layered on top. A logout request that carries <em>no</em> live session is
 * already logged out — the endpoint clears any stale session cookie and lands the browser on
 * {@link RpInitiatedLogout#finalRedirect()} directly, bypassing the IdP round-trip (there is no
 * {@code id_token_hint} to send).
 * <p>
 * <strong>Return leg.</strong> {@link #completeReturn(String, String)} delegates to
 * {@link RpInitiatedLogout#completeReturn} — the returned {@code state} is verified (constant-time,
 * engine-owned) against the single-use logout-state cookie, the cookie is cleared, and the browser is
 * redirected to {@code final_redirect}; a missing/mismatched {@code state} is rejected {@code 400}, so
 * a forged logout-return cannot land the browser anywhere.
 * <p>
 * The endpoint is framework-agnostic (raw {@code Cookie} header in, a {@link LogoutOutcome} the edge
 * renders out — no JAX-RS/Vert.x coupling), so it is unit-testable without a container; the session
 * runtime wires it to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(LogoutEndpoint.class);

    private final RpInitiatedLogout rpInitiatedLogout;
    private final SessionStore sessionStore;
    private final SessionCookieCodec sessionCookieCodec;

    /**
     * Assembles the logout endpoint with the RP-initiated logout logic and the gateway-side stores.
     *
     * @param rpInitiatedLogout  the transport-free RP-initiated logout orchestration
     * @param sessionStore       the server-side session store the session is destroyed in
     * @param sessionCookieCodec the opaque session-cookie codec reading and clearing the session cookie
     */
    public LogoutEndpoint(RpInitiatedLogout rpInitiatedLogout, SessionStore sessionStore,
            SessionCookieCodec sessionCookieCodec) {
        this.rpInitiatedLogout = Objects.requireNonNull(rpInitiatedLogout, "rpInitiatedLogout");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.sessionCookieCodec = Objects.requireNonNull(sessionCookieCodec, "sessionCookieCodec");
    }

    /**
     * Handles the RP-initiated logout leg: resolves the live session, drives the engine end-session
     * redirect, destroys the server-side session, and clears the session cookie.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant (the session-resolution TTL anchor)
     * @return a {@code 302} redirect to the IdP {@code end_session_endpoint} (session-cleared and
     *         logout-state {@code Set-Cookie} headers), or — when no live session — a {@code 302}
     *         straight to {@code final_redirect} clearing the session cookie
     */
    public LogoutOutcome logout(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");

        Optional<String> sessionId = sessionCookieCodec.readSessionId(cookieHeader);
        Optional<SessionRecord> session = sessionId.flatMap(id -> sessionStore.resolve(id, now));
        if (session.isEmpty()) {
            LOGGER.debug("RP-initiated logout without a live session — already logged out, landing on final_redirect");
            return LogoutOutcome.redirect(rpInitiatedLogout.finalRedirect(),
                    List.of(sessionCookieCodec.toClearingSetCookieHeader()));
        }

        RpInitiatedLogout.LogoutRedirect redirect = rpInitiatedLogout.initiate(session.get());
        sessionStore.destroyById(sessionId.get());
        List<String> setCookies = new ArrayList<>(redirect.setCookieHeaders());
        setCookies.add(sessionCookieCodec.toClearingSetCookieHeader());
        LOGGER.debug("RP-initiated logout — session destroyed, redirecting to the IdP end_session_endpoint");
        return LogoutOutcome.redirect(redirect.location(), setCookies);
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
        return LogoutOutcome.redirect(result.location().orElseThrow(), result.setCookieHeaders());
    }

    /**
     * The framework-agnostic result of a logout leg: either a {@code 302} redirect (to the IdP
     * {@code end_session_endpoint}, or to {@code final_redirect} on the return / already-logged-out
     * paths) carrying the {@code Set-Cookie} headers to emit, or a {@code 4xx} error with no redirect.
     * Token material never appears here — only the opaque cookie headers and the redirect location.
     *
     * @param status           the HTTP status the edge returns
     * @param location         the redirect target, present only on a redirect outcome
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, empty on an error
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LogoutOutcome(int status, Optional<String> location, List<String> setCookieHeaders) {

        private static final int FOUND = 302;

        /**
         * Canonical constructor normalizing an absent location and defensively copying the cookies.
         */
        public LogoutOutcome {
            location = Objects.requireNonNullElse(location, Optional.empty());
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A {@code 302} redirect carrying the {@code Set-Cookie} headers.
         *
         * @param location         the redirect target
         * @param setCookieHeaders the {@code Set-Cookie} header values to emit
         * @return the redirect outcome
         */
        public static LogoutOutcome redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new LogoutOutcome(FOUND, Optional.of(location), setCookieHeaders);
        }

        /**
         * An error outcome carrying no redirect and no cookies.
         *
         * @param status the {@code 4xx} status
         * @return the error outcome
         */
        public static LogoutOutcome error(int status) {
            return new LogoutOutcome(status, Optional.empty(), List.of());
        }

        /**
         * @return {@code true} when this outcome is a redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }
    }
}
