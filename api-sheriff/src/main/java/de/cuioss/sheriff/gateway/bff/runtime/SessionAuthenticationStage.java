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
package de.cuioss.sheriff.gateway.bff.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * Stage 4 — the {@code require: session} runtime (D4), the server-session counterpart of the
 * offline bearer validation in {@code AuthenticationStage}. It replaces the boot-time rejection the
 * {@code RouteRuntimeAssembler} used to raise for session routes.
 * <p>
 * For a request selected onto a {@code require: session} route the stage:
 * <ol>
 *   <li>resolves the request's live {@link SessionRecord} through the mode-neutral
 *       {@link SessionBinding} seam (an expired / unknown / unreadable session is treated as
 *       unauthenticated) — no opaque session id appears in this stage's contract, so the stage is
 *       identical for a server-side store and for a stateless binding;</li>
 *   <li>on a live session, offers it to the single-flight {@link TokenRefresh} refresh seam (the D9
 *       hook — the seam owns the near-expiry decision, single-flight coalescing, and rotation; the
 *       unwired binding returns the session unchanged) and emits any {@code Set-Cookie} the seam
 *       returns, so a binding that re-binds on refresh reaches the browser on the same response. A
 *       <em>failed</em> refresh destroyed the session, so the stage clears the browser's copy and
 *       re-drives the same unauthenticated negotiation rather than mediating a revoked token;</li>
 *   <li>enforces the route's {@code required_scopes} against the <em>mediated</em> token's granted
 *       scopes through the {@link GrantedScopes} seam — a shortfall is {@code 403}
 *       {@link EventType#SCOPE_MISSING} (the D2c residual);</li>
 *   <li>records the mediated access token on the request for automatic upstream injection as
 *       {@code Authorization: Bearer} ({@link PipelineRequest#mediatedBearer(String)} — never an
 *       operator-configured header). The token material is never disclosed to the browser up to
 *       this point; the forward stage renders the bearer and the session cookie never crosses.</li>
 * </ol>
 * An <strong>unauthenticated</strong> request is content-negotiated: a <em>navigation</em> request
 * (its {@code Accept} offers {@code text/html}) is redirected {@code 302} into the auth-code flow via
 * the {@link LoginInitiation} seam (short-circuiting the pipeline); anything else (an XHR / API call)
 * gets {@code 401} {@code application/problem+json} via {@link EventType#TOKEN_MISSING}.
 * <p>
 * The stage is framework-agnostic and driven entirely through its collaborators and seams, so it is
 * unit-testable without a container or a live IdP. The engine-side and edge-side wiring (the refresh
 * coordinator, the login initiation binding, and the reserved-endpoint plumbing) is supplied by the
 * session runtime; the seams keep this stage decoupled from that wiring.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SessionAuthenticationStage {

    private static final CuiLogger LOGGER = new CuiLogger(SessionAuthenticationStage.class);

    private static final String COOKIE_HEADER = "Cookie";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String LOCATION_HEADER = "Location";
    private static final String TEXT_HTML = "text/html";
    private static final int FOUND = 302;

    private final SessionBinding sessionBinding;
    private final TokenRefresh tokenRefresh;
    private final GrantedScopes grantedScopes;
    private final LoginInitiation loginInitiation;
    private final Clock clock;

    /**
     * Assembles the stage with the session binding and the engine / edge seams.
     *
     * @param sessionBinding  the mode-neutral session binding resolving the request's live session
     * @param tokenRefresh    the single-flight near-expiry refresh seam (the D9 hook)
     * @param grantedScopes   the mediated-token scope-membership seam backing {@code required_scopes}
     * @param loginInitiation the auth-code-flow initiation seam for a navigation redirect
     * @param clock           the reference clock (TTL anchor for session resolution and refresh)
     */
    public SessionAuthenticationStage(SessionBinding sessionBinding, TokenRefresh tokenRefresh,
            GrantedScopes grantedScopes, LoginInitiation loginInitiation, Clock clock) {
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.tokenRefresh = Objects.requireNonNull(tokenRefresh, "tokenRefresh");
        this.grantedScopes = Objects.requireNonNull(grantedScopes, "grantedScopes");
        this.loginInitiation = Objects.requireNonNull(loginInitiation, "loginInitiation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the session runtime for the selected {@code require: session} route.
     *
     * @param request the in-flight request; its route must be selected (stage 2)
     * @throws GatewayException {@code 401} when an unauthenticated non-navigation request is
     *                          challenged, or {@code 403} when the mediated token lacks a required scope
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        RouteRuntime route = requireSelectedRoute(request);
        Instant now = clock.instant();
        String cookieHeader = request.firstHeader(COOKIE_HEADER).orElse(null);

        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            challengeUnauthenticated(request, route, now);
            return;
        }

        Optional<SessionBinding.BoundSession> refreshed =
                tokenRefresh.refreshIfNeeded(resolved.get(), cookieHeader, now);
        if (refreshed.isEmpty()) {
            // The refresh failed and the seam already destroyed the session (an IdP rejection, or
            // engine-detected refresh-token reuse that revoked the whole family). Mediating the
            // pre-refresh token here would keep serving a session the gateway just revoked, so the
            // request re-drives the SAME unauthenticated negotiation as a missing session. The
            // clearing cookie drops the browser's stale copy of the revoked session; on the
            // navigation branch the login challenge adds its own binding cookie for a DIFFERENT
            // cookie name, so both must reach the browser on this one response — hence the
            // multi-valued Set-Cookie accumulator rather than a single-valued header slot.
            emitSetCookies(request, List.of(sessionBinding.clearingSetCookieHeader()));
            challengeUnauthenticated(request, route, now);
            return;
        }
        emitSetCookies(request, refreshed.get().setCookieHeaders());
        SessionRecord session = refreshed.get().session();
        enforceScopes(route, session);
        request.mediatedBearer(session.accessToken());
    }

    /**
     * Appends every supplied {@code Set-Cookie} value to the request's multi-valued Set-Cookie
     * accumulator. Appending — never a single-valued put, never a {@code findFirst()} truncation —
     * is what keeps BOTH the clearing cookie and the login-challenge cookie alive on the
     * failed-refresh navigation path: the clearing cookie is what drops the browser's copy of a
     * session the gateway just revoked, so losing it would leave a revoked session cookie in place.
     */
    private static void emitSetCookies(PipelineRequest request, List<String> setCookieHeaders) {
        setCookieHeaders.forEach(request::addResponseSetCookie);
    }

    private void enforceScopes(RouteRuntime route, SessionRecord session) {
        List<String> requiredScopes = route.getEffectiveAuth().requiredScopes();
        if (!requiredScopes.isEmpty() && !grantedScopes.provides(session.accessToken(), requiredScopes)) {
            throw new GatewayException(EventType.SCOPE_MISSING,
                    "Mediated token missing a required scope for route " + route.getId());
        }
    }

    private void challengeUnauthenticated(PipelineRequest request, RouteRuntime route, Instant now) {
        if (acceptsHtml(request)) {
            LoginChallenge challenge = loginInitiation.initiate(returnUrl(request), now);
            request.responseHeaders().put(LOCATION_HEADER, challenge.location());
            emitSetCookies(request, challenge.setCookieHeaders());
            request.shortCircuit(FOUND);
            LOGGER.debug("Unauthenticated navigation on require:session route %s — redirecting into login",
                    route.getId());
            return;
        }
        throw new GatewayException(EventType.TOKEN_MISSING,
                "No live session for require:session route " + route.getId());
    }

    private static boolean acceptsHtml(PipelineRequest request) {
        return request.headerValues(ACCEPT_HEADER).stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(TEXT_HTML));
    }

    private static String returnUrl(PipelineRequest request) {
        String canonicalPath = request.canonicalPath();
        return canonicalPath != null ? canonicalPath : request.requestPath();
    }

    private static RouteRuntime requireSelectedRoute(PipelineRequest request) {
        RouteRuntime route = request.selectedRoute();
        if (route == null) {
            throw new IllegalStateException("Session authentication requires the route selected at stage 2");
        }
        return route;
    }

    /**
     * The single-flight near-expiry refresh seam (the D9 hook). The session runtime binds it to the
     * refresh coordinator, which owns the near-expiry decision, single-flight coalescing per session,
     * and refresh-token rotation. The unwired binding returns the session unchanged with no cookies,
     * so a gateway without the refresh coordinator injects the current mediated token verbatim.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface TokenRefresh {

        /**
         * Returns the session to mediate from, refreshing its mediated token when near expiry.
         *
         * @param session      the resolved live session
         * @param cookieHeader the raw request {@code Cookie} header value the session was resolved
         *                     from, so the coordinator can re-resolve it under single-flight
         *                     exclusion; may be absent
         * @param now          the reference instant
         * @return the session to mediate from — the same one, or a refreshed copy carrying the
         *         rotated token material — plus any {@code Set-Cookie} the re-bind produced; or
         *         {@link Optional#empty()} when the refresh failed and the seam destroyed the
         *         session, which the stage treats as unauthenticated
         */
        Optional<SessionBinding.BoundSession> refreshIfNeeded(SessionRecord session, @Nullable String cookieHeader,
                Instant now);
    }

    /**
     * The mediated-token scope-membership seam. The session runtime binds it to the engine token
     * parsing so {@code required_scopes} is enforced against the mediated access token's granted
     * scopes; a test binds it to a fixed predicate. Keeping the token parsing behind the seam
     * decouples this stage from the engine and keeps it unit-testable.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface GrantedScopes {

        /**
         * @param accessToken    the mediated access token
         * @param requiredScopes the route's non-empty {@code required_scopes}
         * @return {@code true} when the token grants every required scope
         */
        boolean provides(String accessToken, List<String> requiredScopes);
    }

    /**
     * The auth-code-flow initiation seam for a navigation redirect. The session runtime binds it to
     * the login flow (which drives the engine authorization, persists the pending record, and mints
     * the browser-binding cookie); a test binds it to a hand-built challenge.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface LoginInitiation {

        /**
         * Initiates a fresh login for an unauthenticated navigation request.
         *
         * @param returnUrl the post-login return target (the path the browser was navigating to)
         * @param now       the reference instant (the pending record's TTL anchor)
         * @return the redirect target and the browser-binding {@code Set-Cookie}
         */
        LoginChallenge initiate(String returnUrl, Instant now);
    }

    /**
     * The framework-agnostic result of a login initiation: the {@code 302} redirect target and the
     * browser-binding {@code Set-Cookie} header(s) to emit. Token material never appears here.
     *
     * @param location         the IdP authorization URL to redirect the browser to
     * @param setCookieHeaders the browser-binding {@code Set-Cookie} header values (the single binding cookie)
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LoginChallenge(String location, List<String> setCookieHeaders) {

        /**
         * Canonical constructor rejecting an absent location and defensively copying the cookies.
         */
        public LoginChallenge {
            Objects.requireNonNull(location, "location");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }
    }
}
