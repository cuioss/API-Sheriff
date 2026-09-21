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
 *       unwired binding returns the session unchanged) and acts on the {@link RefreshResult} it
 *       returns:
 *       <ul>
 *         <li>{@link RefreshResult.Mediate mediate} — emits any {@code Set-Cookie} the seam returns, so
 *             a binding that re-binds on refresh reaches the browser on the same response, and
 *             continues with the session;</li>
 *         <li>{@link RefreshResult.SessionEnded session ended} — the seam destroyed the session, so the
 *             stage clears the browser's copy and then applies the refresh-failure response;</li>
 *         <li>{@link RefreshResult.RequestFailed request failed} — the session is still live but this
 *             request has no valid token to mediate, so the stage applies the refresh-failure response
 *             <em>without</em> clearing the cookie: the next request can still use the session.</li>
 *       </ul>
 *       The refresh-failure response is the {@link OnFailure} policy
 *       ({@code oidc.session.refresh.on_failure}): {@link OnFailure#REAUTHENTICATE} re-drives the same
 *       negotiation as a missing session, {@link OnFailure#REJECT} answers {@code 401}
 *       {@code application/problem+json} for every request, navigation included;</li>
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
 * The stage runs <strong>no scope check</strong>: no session-route path answers
 * {@link EventType#SCOPE_MISSING}. The scopes a session route needs
 * ({@link RouteRuntime#getNeededScopes()}) are <em>requested</em> when the session is established,
 * not enforced against the session's token on every request; the {@code 403 insufficient_scope}
 * check belongs to the bearer route alone.
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
    private final LoginInitiation loginInitiation;
    private final OnFailure onFailure;
    private final Clock clock;

    /**
     * Assembles the stage with the session binding, the engine / edge seams and the refresh-failure
     * policy.
     *
     * @param sessionBinding  the mode-neutral session binding resolving the request's live session
     * @param tokenRefresh    the single-flight near-expiry refresh seam (the D9 hook)
     * @param loginInitiation the auth-code-flow initiation seam for a navigation redirect
     * @param onFailure       the resolved {@code oidc.session.refresh.on_failure} policy applied when a
     *                        refresh leaves the request without a token to mediate
     * @param clock           the reference clock (TTL anchor for session resolution and refresh)
     */
    public SessionAuthenticationStage(SessionBinding sessionBinding, TokenRefresh tokenRefresh,
            LoginInitiation loginInitiation, OnFailure onFailure, Clock clock) {
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.tokenRefresh = Objects.requireNonNull(tokenRefresh, "tokenRefresh");
        this.loginInitiation = Objects.requireNonNull(loginInitiation, "loginInitiation");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the session runtime for the selected {@code require: session} route.
     *
     * @param request the in-flight request; its route must be selected (stage 2)
     * @throws GatewayException {@code 401} when an unauthenticated non-navigation request is
     *                          challenged or a refresh failure is rejected under
     *                          {@link OnFailure#REJECT}
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

        RefreshResult refreshed = tokenRefresh.refreshIfNeeded(resolved.get(), cookieHeader, now);
        // A pattern switch over the sealed result: javac rejects it the moment a fourth disposition appears.
        switch (refreshed) {
            case RefreshResult.Mediate(SessionBinding.BoundSession bound) -> {
                emitSetCookies(request, bound.setCookieHeaders());
                request.mediatedBearer(bound.session().accessToken());
            }
            case RefreshResult.SessionEnded() -> {
                // The seam destroyed the session (the identity provider rejected the refresh token —
                // including a replayed one under strict rotation — or the gateway refused a redeemed
                // response). Mediating the pre-refresh token would keep serving an ended session, so the
                // clearing cookie drops the browser's stale copy first. On the reauthenticate navigation
                // branch the login challenge adds its own binding cookie for a DIFFERENT cookie name, so
                // both must reach the browser on this one response — hence the multi-valued Set-Cookie
                // accumulator rather than a single-valued header slot.
                emitSetCookies(request, List.of(sessionBinding.clearingSetCookieHeader()));
                challengeRefreshFailure(request, route, now);
            }
            case RefreshResult.RequestFailed() ->
                // The identity provider never processed the refresh and the access token has expired: the
                // session is still live, so the cookie is deliberately NOT cleared — the next request can
                // retry the refresh once the back-off has elapsed.
                challengeRefreshFailure(request, route, now);
        }
    }

    /**
     * Appends every supplied {@code Set-Cookie} value to the request's multi-valued Set-Cookie
     * accumulator. Appending — never a single-valued put, never a {@code findFirst()} truncation —
     * is what keeps BOTH the clearing cookie and the login-challenge cookie alive on the
     * ended-session navigation path: the clearing cookie is what drops the browser's copy of a
     * session the gateway just destroyed, so losing it would leave an ended session cookie in place.
     */
    private static void emitSetCookies(PipelineRequest request, List<String> setCookieHeaders) {
        setCookieHeaders.forEach(request::addResponseSetCookie);
    }

    /**
     * Applies the {@link OnFailure} policy to a request a refresh left without a token to mediate.
     */
    private void challengeRefreshFailure(PipelineRequest request, RouteRuntime route, Instant now) {
        switch (onFailure) {
            case REAUTHENTICATE -> challengeUnauthenticated(request, route, now);
            case REJECT -> throw new GatewayException(EventType.TOKEN_MISSING,
                    "Token refresh failed for require:session route " + route.getId() + " (on_failure: reject)");
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
         * @return {@link RefreshResult.Mediate mediate} carrying the session to mediate from — the
         *         same one, or a refreshed copy carrying the rotated token material — plus any
         *         {@code Set-Cookie} the re-bind produced; {@link RefreshResult.SessionEnded session
         *         ended} when the seam destroyed the session; or {@link RefreshResult.RequestFailed
         *         request failed} when the session is kept but this request has no valid token
         */
        RefreshResult refreshIfNeeded(SessionRecord session, @Nullable String cookieHeader, Instant now);
    }

    /**
     * What the {@link TokenRefresh} seam decided for one request. Sealed, so the stage's switch over it
     * is checked for exhaustiveness.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public sealed interface RefreshResult {

        /**
         * @param boundSession the session to mediate from plus the re-bind's {@code Set-Cookie} values
         * @return the mediate result
         */
        static RefreshResult mediate(SessionBinding.BoundSession boundSession) {
            return new Mediate(boundSession);
        }

        /**
         * @return the result for a session the seam destroyed
         */
        static RefreshResult sessionEnded() {
            return new SessionEnded();
        }

        /**
         * @return the result for a kept session whose request has no valid token to mediate
         */
        static RefreshResult requestFailed() {
            return new RequestFailed();
        }

        /**
         * The session is usable: mediate its token and emit the re-bind's cookies.
         *
         * @param boundSession the session to mediate from plus the re-bind's {@code Set-Cookie} values
         * @author API Sheriff Team
         * @since 1.0
         */
        record Mediate(SessionBinding.BoundSession boundSession) implements RefreshResult {

            /**
             * Canonical constructor rejecting an absent bound session.
             */
            public Mediate {
                Objects.requireNonNull(boundSession, "boundSession");
            }
        }

        /**
         * The session was destroyed: clear the browser's session cookie, then apply the
         * {@link OnFailure} policy.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        record SessionEnded() implements RefreshResult {
        }

        /**
         * The session is kept but this request has no valid token: apply the {@link OnFailure} policy
         * without clearing the session cookie.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        record RequestFailed() implements RefreshResult {
        }
    }

    /**
     * The resolved {@code oidc.session.refresh.on_failure} policy: how a request is answered when a
     * refresh leaves it without a token to mediate.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum OnFailure {

        /**
         * The default ({@code reauthenticate}, also when the key is omitted): the same negotiation as a
         * missing session — a navigation is redirected into login, anything else gets {@code 401}.
         */
        REAUTHENTICATE,

        /** {@code reject}: {@code 401} {@code application/problem+json} for every request, navigation included. */
        REJECT
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
