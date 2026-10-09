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
import java.util.Objects;
import java.util.Optional;

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
 * transport-free logic — the call to the token-revocation seam, {@code state} minting, the engine
 * end-session redirect, and the return-leg {@code state} verification — lives in
 * {@link RpInitiatedLogout}.
 * <p>
 * <strong>Logout leg.</strong> {@link #logout(String, Instant)} resolves the request's live
 * {@link SessionRecord} through the mode-neutral {@link SessionBinding} seam and
 * <strong>destroys it first</strong> ({@link SessionBinding#destroy}): the local session
 * destruction is the authoritative, immediately-effective logout, and nothing that follows can
 * prevent it. Only then does it drive {@link RpInitiatedLogout#initiate} (which calls its
 * token-revocation seam and builds the {@code end_session_endpoint} redirect carrying the
 * {@code id_token_hint}, the exact {@code post_logout_redirect_uri}, and the single-use logout-state
 * cookie). Every answer clears every cookie the binding sets
 * ({@link SessionBinding#clearingSetCookieHeaders()} — in cookie mode the session cookie and its
 * activity cookie).
 * <p>
 * The answer is always a {@code 302}. When an end-session redirect was built, the browser is sent to
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
 * The endpoint is framework-agnostic (raw {@code Cookie} header in, a {@link LogoutOutcome} the edge
 * renders out — no JAX-RS/Vert.x coupling), so it is unit-testable without a container; the session
 * runtime wires it to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutEndpoint {

    /** The parameter name the null checks of a redirect location report. */
    private static final String LOCATION_PARAMETER = "location";

    private static final CuiLogger LOGGER = new CuiLogger(LogoutEndpoint.class);

    private final RpInitiatedLogout rpInitiatedLogout;
    private final SessionBinding sessionBinding;

    /**
     * Assembles the logout endpoint with the RP-initiated logout logic and the session binding.
     *
     * @param rpInitiatedLogout the transport-free RP-initiated logout orchestration
     * @param sessionBinding    the mode-neutral session binding the session is resolved, destroyed,
     *                          and cleared through
     */
    public LogoutEndpoint(RpInitiatedLogout rpInitiatedLogout, SessionBinding sessionBinding) {
        this.rpInitiatedLogout = Objects.requireNonNull(rpInitiatedLogout, "rpInitiatedLogout");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
    }

    /**
     * Handles the RP-initiated logout leg: resolves the live session, destroys it, drives the engine
     * end-session redirect, and clears the session cookie.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant (the session-resolution TTL anchor)
     * @return a {@code 302} redirect to the IdP {@code end_session_endpoint} (session-clearing and
     *         logout-state {@code Set-Cookie} headers) when an end-session redirect was built,
     *         otherwise a {@code 302} straight to {@code final_redirect} clearing the session cookie
     */
    public LogoutOutcome logout(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");

        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            LOGGER.debug("RP-initiated logout without a live session — already logged out, landing on final_redirect");
            return LogoutOutcome.redirect(rpInitiatedLogout.finalRedirect(),
                    sessionBinding.clearingSetCookieHeaders());
        }
        SessionRecord session = resolved.get();
        // Local logout is the authoritative, immediately-effective step, so it comes first: whatever
        // happens to the end-session redirect below, the session is already gone.
        sessionBinding.destroy(session);

        Optional<RpInitiatedLogout.LogoutRedirect> endSession;
        // The catch is deliberately broad: the end-session redirect is built by the engine over
        // discovered provider metadata, and no failure there may turn a completed local logout into
        // an error answer.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            endSession = rpInitiatedLogout.initiate(session);
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
     * The framework-agnostic result of a logout leg: either a {@code 302} redirect (to the IdP
     * {@code end_session_endpoint}, or to {@code final_redirect} on the return, already-logged-out
     * and local-only paths) carrying the {@code Set-Cookie} headers to emit, or a {@code 4xx} error
     * with no redirect.
     * <p>
     * The redirect to the IdP is the one outcome that holds token material: its location's query
     * carries the {@code id_token_hint}. {@link #carriesIdToken()} marks it, and the record's string
     * form prints neither the location nor a cookie value.
     *
     * @param status           the HTTP status the edge returns
     * @param location         the redirect target, {@code null} on anything but a redirect outcome
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, empty on an error
     * @param carriesIdToken   {@code true} when the location is the end-session redirect, whose
     *                         query carries the {@code id_token_hint}
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record LogoutOutcome(int status, @Nullable String location, List<String> setCookieHeaders,
    boolean carriesIdToken) {

        private static final int FOUND = 302;

        /**
         * Canonical constructor defensively copying the cookies.
         */
        public LogoutOutcome {
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
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
            return new LogoutOutcome(FOUND, location, setCookieHeaders, false);
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
            return new LogoutOutcome(FOUND, location, setCookieHeaders, true);
        }

        /**
         * An error outcome carrying no redirect and no cookies.
         *
         * @param status the {@code 4xx} status
         * @return the error outcome
         */
        public static LogoutOutcome error(int status) {
            return new LogoutOutcome(status, null, List.of(), false);
        }

        /**
         * @return {@code true} when this outcome is a redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }

        /**
         * Overridden so that neither an end-session location — its query carries the
         * {@code id_token_hint} — nor a cookie value reaches a log line or an exception message.
         *
         * @return the status, the location unless it carries the ID token, and the number of cookies
         */
        @Override
        public String toString() {
            return "LogoutOutcome[status=%s, location=%s, setCookieHeaders=%s, carriesIdToken=%s]"
                    .formatted(status, carriesIdToken ? "<redacted>" : location, setCookieHeaders.size(),
                            carriesIdToken);
        }
    }
}
