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
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * The login-initiation reserved endpoint ({@code oidc.login.path}) — the sixth reserved gateway
 * path (D12). It is registered exactly like the other reserved endpoints (exact match, on the OIDC
 * host, resolved by the {@link ReservedPathRegistry} <em>before</em> the proxy route table), and
 * gives the browser an explicit, gateway-owned URL to <em>start</em> a login from — the mirror
 * entry point to the {@link CallbackEndpoint} that lands it.
 * <p>
 * <strong>No new subsystem.</strong> The endpoint is a thin edge over the D5 {@link LoginFlow}: it
 * reuses the same-origin-validated post-login return URL and the browser-binding cookie carried by
 * the D2b {@link PendingAuthorizationRecord}, and delegates the whole auth-code initiation (engine
 * authorization, PKCE/{@code state}/{@code nonce}, pending-record persistence, binding-cookie
 * minting) to {@link LoginFlow#initiate}. It adds exactly one gateway-side concern on top: the
 * already-authenticated short-circuit.
 * <p>
 * <strong>Return-URL safety (never an open redirect).</strong> The {@code returnUrl} parameter is
 * same-origin-validated exactly as D2b requires
 * ({@link PendingAuthorizationRecord#sameOrigin(String, String)}). On the unauthenticated path the
 * validation is {@link LoginFlow}'s (a cross-origin or unparseable target falls back to
 * {@link LoginFlow#DEFAULT_RETURN_URL}); on the already-authenticated path this endpoint applies the
 * identical validation before it redirects. Either way an off-origin, schema-relative
 * ({@code //host}), blank, or unparseable target can never become the redirect location — the
 * login-initiation URL is not an open redirect.
 * <p>
 * <strong>Already-authenticated behaviour (explicitly defined).</strong> A caller that already
 * holds a live session MUST NOT be silently re-driven through a fresh auth-code flow: the endpoint
 * short-circuits straight to the same-origin-validated return URL ({@link #initiate} resolves the
 * live session through the {@link SessionCookieCodec}/{@link SessionStore} seam, exactly as
 * {@link UserInfoEndpoint} and {@link LogoutEndpoint} do). No pending record is created and no
 * binding cookie is set on this path — there is no new login transaction to bind.
 * <p>
 * The endpoint is framework-agnostic (a raw {@code returnUrl} parameter and a raw {@code Cookie}
 * header in, a {@link LoginInitiationOutcome} the edge renders out — no JAX-RS/Vert.x coupling), so
 * it is unit-testable without a container; the session runtime wires it to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LoginInitiationEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(LoginInitiationEndpoint.class);

    private static final int FOUND = 302;

    private final LoginFlow loginFlow;
    private final SessionStore sessionStore;
    private final SessionCookieCodec sessionCookieCodec;
    private final String gatewayOrigin;

    /**
     * Assembles the login-initiation endpoint with the D5 login flow and the session-resolution seam.
     *
     * @param loginFlow          the D5 auth-code login initiation reused on the unauthenticated path
     * @param sessionStore       the server-side session store resolving the opaque session id
     * @param sessionCookieCodec the opaque session-cookie codec reading the request cookie
     * @param gatewayOrigin      the gateway's own origin (the {@code redirect_uri} origin) used to
     *                           same-origin-validate the return URL on the already-authenticated path
     */
    public LoginInitiationEndpoint(LoginFlow loginFlow, SessionStore sessionStore,
            SessionCookieCodec sessionCookieCodec, String gatewayOrigin) {
        this.loginFlow = Objects.requireNonNull(loginFlow, "loginFlow");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.sessionCookieCodec = Objects.requireNonNull(sessionCookieCodec, "sessionCookieCodec");
        this.gatewayOrigin = Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
    }

    /**
     * Initiates a login: an already-authenticated caller is redirected straight to the same-origin-
     * validated return URL (no fresh auth-code flow); an unauthenticated caller is driven into the D5
     * auth-code flow, which returns to the same validated return URL after the callback.
     *
     * @param requestedReturnUrl the post-login return target the browser asked for, may be absent
     * @param cookieHeader       the raw request {@code Cookie} header value, may be absent
     * @param now                the reference instant (session-resolution TTL anchor and, on the
     *                           unauthenticated path, the pending record's TTL anchor)
     * @return a {@code 302} redirect — straight to the validated return URL (no cookies) for a live
     *         session, or to the IdP authorization URL carrying the binding {@code Set-Cookie} for a
     *         fresh login
     */
    public LoginInitiationOutcome initiate(@Nullable String requestedReturnUrl, @Nullable String cookieHeader,
            Instant now) {
        Objects.requireNonNull(now, "now");

        Optional<SessionRecord> session = sessionCookieCodec.readSessionId(cookieHeader)
                .flatMap(sessionId -> sessionStore.resolve(sessionId, now));
        if (session.isPresent()) {
            String returnUrl = PendingAuthorizationRecord.sameOrigin(requestedReturnUrl, gatewayOrigin)
                    ? requestedReturnUrl : LoginFlow.DEFAULT_RETURN_URL;
            LOGGER.debug("Login initiation with a live session — short-circuiting to the validated "
                    + "return URL, no fresh auth-code flow");
            return LoginInitiationOutcome.redirect(returnUrl, List.of());
        }

        LoginFlow.LoginRedirect redirect = loginFlow.initiate(requestedReturnUrl, now);
        LOGGER.debug("Login initiation without a live session — starting the OIDC auth-code flow");
        return LoginInitiationOutcome.redirect(redirect.authorizationUrl(), redirect.setCookieHeaders());
    }

    /**
     * The framework-agnostic result of a login initiation: always a {@code 302} redirect — straight
     * to the same-origin-validated return URL (no cookies) on the already-authenticated short-circuit,
     * or to the IdP authorization URL carrying the browser-binding {@code Set-Cookie} on a fresh login.
     * No token material ever appears here — only the opaque binding-cookie header and the redirect
     * location.
     *
     * @param status           the HTTP status the edge returns (always {@code 302})
     * @param location         the redirect target (the validated return URL or the IdP authorization URL)
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit — the binding cookie on a
     *                         fresh login, empty on the already-authenticated short-circuit
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LoginInitiationOutcome(int status, String location, List<String> setCookieHeaders) {

        /**
         * Canonical constructor rejecting an absent location and defensively copying the cookies.
         */
        public LoginInitiationOutcome {
            Objects.requireNonNull(location, "location");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A {@code 302} redirect carrying the {@code Set-Cookie} headers.
         *
         * @param location         the redirect target
         * @param setCookieHeaders the {@code Set-Cookie} header values to emit
         * @return the redirect outcome
         */
        public static LoginInitiationOutcome redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new LoginInitiationOutcome(FOUND, location, setCookieHeaders);
        }

        /**
         * @return {@code true} when this outcome is a redirect (always, by construction)
         */
        public boolean isRedirect() {
            return status == FOUND;
        }
    }
}
