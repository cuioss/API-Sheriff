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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
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
 * concerns: it hands the session to the {@link TokenRevocation} seam it was constructed with, mints
 * a session-bound {@code state}, and carries that {@code state} in the short-lived single-use
 * {@value #LOGOUT_STATE_COOKIE_NAME} cookie. The engine-owned {@code post_logout_redirect_uri} is a
 * gateway-owned reserved path (the return leg), so the browser never controls the redirect target.
 * <p>
 * <strong>The logout revokes the session's refresh token.</strong> The runtime binds the
 * {@link TokenRevocation} seam to an RFC 7009 revocation of the session's refresh token at the
 * identity provider ({@code BffRuntimeProducer.revokeOnLogout}). {@link #initiate} calls it once,
 * synchronously, before the end-session redirect is built — and the caller has already destroyed the
 * local session by then. The caller hands {@link #initiate} the record its destruction removed, so the
 * token revoked is the one the session held when it was destroyed. When something else had already
 * ended the session before the caller's destruction — so the destruction removed nothing — the caller
 * hands over the record it resolved instead, and that record's refresh token is revoked: no other path
 * that ends a session revokes anything, and revoking a token that is already spent or invalid is
 * harmless (RFC 7009, section 2.2). A session without a refresh token sends nothing. The access token
 * is not revoked: it stays valid at a resource server until it expires.
 * <p>
 * <strong>A failed revocation is a latched {@code WARN}.</strong> A {@link RuntimeException} the seam
 * raises — a transport failure, a non-success answer, provider metadata that cannot be obtained — is
 * recorded once per instance as {@code ApiSheriff-147}, which carries no value and no throwable; every
 * failure, the first included, also leaves a {@code DEBUG} line that names the exception types of the
 * failure's cause chain and nothing else — neither the throwable nor any exception message is logged,
 * because a message may carry the identity provider's unsanitised answer or the refresh token itself.
 * The logout proceeds in every case: the local session destruction is the authoritative logout.
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
 * Thread-safe: the collaborators are safe for concurrent use and the only mutable state is two
 * atomic latches; one instance serves every request.
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
    private static final String CAUSE_SEPARATOR = " caused by ";

    private final EndSessionFlow endSessionFlow;
    private final TokenRevocation revocation;
    private final EndSessionEndpointSource endSessionEndpointSource;
    private final String postLogoutRedirectUri;
    private final String finalRedirect;
    private final Duration stateCookieTtl;
    private final AtomicBoolean missingEndpointReported = new AtomicBoolean();
    private final AtomicBoolean revocationFailureReported = new AtomicBoolean();

    /**
     * Assembles the RP-initiated logout with the engine end-session flow and the gateway-side settings.
     *
     * @param endSessionFlow           the engine end-session redirect builder (owns exact-match validation)
     * @param revocation               the token-revocation seam, called once per initiated logout; a
     *                                 failure it raises is reported and never stops the logout
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
     * seam — which the runtime binds to the RFC 7009 revocation of the session's refresh token — then
     * resolves the provider's end-session endpoint, mints the session-bound {@code state}, and has the
     * engine build the end-session redirect carrying the {@code id_token_hint}, the exact
     * {@code post_logout_redirect_uri}, and the {@code state}.
     * <p>
     * The seam is called first and in every case, also when no redirect can be built. A
     * {@link RuntimeException} it raises does not stop the logout: the first one per instance is
     * recorded as {@code ApiSheriff-147} and every one leaves a {@code DEBUG} line naming the
     * exception types of its cause chain — never the throwable or an exception message.
     * <p>
     * The result is empty — and the caller completes the logout locally — when the session holds no ID
     * token, when the end-session endpoint cannot be resolved, or when the provider publishes none
     * that is an absolute {@code https} URI — the engine refuses every other scheme because the request
     * carries the ID token, so the gateway judges the same rule up front and reports the endpoint as
     * unusable ({@code ApiSheriff-135}) instead of letting the engine's refusal surface as a bare
     * {@code DEBUG} line in the caller. None of these raises.
     * <p>
     * {@code session} is the record whose refresh token is revoked, so the caller passes the record its
     * local destruction removed — the session as it stood when it was destroyed — rather than one it
     * resolved earlier, which a concurrent refresh may since have rotated away from. When the
     * destruction removed nothing, because another path had already ended the session, the caller
     * passes the record it resolved: that is the only record left that names the token, and revoking
     * one that was already spent is harmless.
     *
     * @param session the session being logged out — as it stood at its destruction, or as the caller
     *                resolved it when the destruction removed nothing (its refresh token is the one
     *                revoked; its raw ID token is the {@code id_token_hint})
     * @return the redirect to the IdP {@code end_session_endpoint} carrying the logout-state
     *         {@code Set-Cookie}; empty when the browser cannot be sent there
     */
    public Optional<LogoutRedirect> initiate(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // The revocation seam reaches the identity provider, whose failure types are not part of this
        // contract, and no failure of it may strand the browser half-logged-out — so the catch is
        // deliberately broad.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            revocation.revoke(session);
        } catch (RuntimeException revocationFailure) {
            // The authoritative, immediately-effective step is the local session destruction the caller
            // has already performed, so the logout proceeds. The failure is still a failed security
            // control and is reported, latched.
            reportRevocationFailure(revocationFailure);
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
        if (!isAbsoluteHttpsUri(endpoint)) {
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

    /**
     * Reports a failed refresh-token revocation. The first failure per instance is the
     * {@code ApiSheriff-147} {@code WARN}, which carries no substitution and no throwable, because the
     * throwable's message may carry the identity provider's unsanitised answer. Every failure, the
     * first included, leaves a {@code DEBUG} line that names the exception types of the failure's cause
     * chain and nothing more: the throwable is not attached and no exception message is printed, because
     * a seam may put the refresh token itself into a message or a cause, and a {@code DEBUG} line is
     * still a log line. The latch keeps the caller, who can repeat a logout at will, from setting the
     * volume of {@code WARN} lines (ADR-0051).
     */
    private void reportRevocationFailure(RuntimeException revocationFailure) {
        String failureTypes = exceptionTypes(revocationFailure);
        if (revocationFailureReported.compareAndSet(false, true)) {
            LOGGER.warn(BffLogMessages.WARN.LOGOUT_REVOCATION_FAILED);
            LOGGER.debug("Refresh-token revocation failed during RP-initiated logout (%s) — proceeding with the logout",
                    failureTypes);
        } else {
            LOGGER.debug("Refresh-token revocation failed during RP-initiated logout again (%s) — already "
                    + "reported once, stays at DEBUG for the rest of the process", failureTypes);
        }
    }

    /**
     * Names the exception types of a failure's cause chain, outermost first, separated by
     * {@code " caused by "}. Only class names are read — never a message — and a cause chain that
     * loops back on itself is cut at the first repeat.
     */
    private static String exceptionTypes(Throwable failure) {
        StringJoiner types = new StringJoiner(CAUSE_SEPARATOR);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            types.add(current.getClass().getName());
            current = current.getCause();
        }
        return types.toString();
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
     * Whether the candidate is an absolute {@code https} URI with a host. Anything else — a relative
     * reference, {@code http} or another scheme, a URI without a host, or text that is no URI at all —
     * is not an address the browser may be redirected to with the ID token in the query. This mirrors
     * the engine's own TLS-only rule for the {@code end_session_endpoint} (token-sheriff 0.9.9), which
     * the gateway never relaxes: the gateway's flow is built without the cleartext allowance.
     */
    private static boolean isAbsoluteHttpsUri(String candidate) {
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException _) {
            return false;
        }
        String scheme = uri.getScheme();
        return uri.getHost() != null && SCHEME_HTTPS.equalsIgnoreCase(scheme);
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
     * The token-revocation seam: the hook through which a session's token is revoked at the identity
     * provider during logout. {@link RpInitiatedLogout} calls it once per initiated logout, after the
     * caller has destroyed the local session and before the end-session redirect is built. The record it
     * receives is the one the caller's destruction removed, so its refresh token is the one the session
     * held at that moment; when another path had already ended the session, so the destruction removed
     * nothing, it receives the record the caller resolved.
     * <p>
     * The runtime binds it to {@code BffRuntimeProducer.revokeOnLogout}, an RFC 7009 revocation of the
     * session's refresh token ({@code token_type_hint=refresh_token}). That binding sends nothing when
     * the session holds no refresh token or the provider declares no {@code revocation_endpoint}, and
     * it never revokes the access token, which stays valid at a resource server until it expires.
     * Whether the provider also revokes the rest of the refresh-token family is the provider's
     * decision. A failure of the seam is reported and never stops the logout — the caller's local
     * session destruction is the authoritative logout.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface TokenRevocation {

        /**
         * Revokes the logged-out session's token at the identity provider.
         *
         * @param session the session being logged out — as it stood when the caller destroyed it, or as
         *                the caller resolved it when another path had already ended it
         * @throws RuntimeException when the revocation cannot be completed — metadata that cannot be
         *                          obtained, a transport failure or a non-success answer; the caller
         *                          reports it and completes the logout
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
