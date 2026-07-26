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


import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.tools.logging.CuiLogger;

/**
 * Stage 4 — the {@code require: session} runtime (D4), the server-session counterpart of the
 * offline bearer validation in {@code AuthenticationStage}. It replaces the boot-time rejection the
 * {@code RouteRuntimeAssembler} used to raise for session routes.
 * <p>
 * For a request selected onto a {@code require: session} route the stage:
 * <ol>
 *   <li>resolves the opaque {@code __Host-} session cookie to a live {@link SessionRecord} through
 *       the {@link SessionStore} (an expired / unknown session is treated as unauthenticated);</li>
 *   <li>on a live session, offers it to the single-flight {@link TokenRefresh} refresh seam (the D9
 *       hook — the seam owns the near-expiry decision, single-flight coalescing, and rotation; the
 *       unwired binding returns the session unchanged);</li>
 *   <li>enforces the route's {@code required_scopes} against the <em>mediated</em> token's granted
 *       scopes through the {@link GrantedScopes} seam — a shortfall is {@code 403}
 *       {@link EventType#SCOPE_MISSING} (the D2c residual);</li>
 *   <li>records the mediated access token on the request for automatic upstream injection as
 *       {@code Authorization: Bearer} ({@link PipelineRequest#mediatedBearer(String)} — never an
 *       operator-configured header). The token material never leaves the server up to this point;
 *       the forward stage renders the bearer and the opaque session cookie never crosses.</li>
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
    private static final String SET_COOKIE_HEADER = "Set-Cookie";
    private static final String TEXT_HTML = "text/html";
    private static final int FOUND = 302;

    private final SessionStore sessionStore;
    private final SessionCookieCodec sessionCookieCodec;
    private final TokenRefresh tokenRefresh;
    private final GrantedScopes grantedScopes;
    private final LoginInitiation loginInitiation;
    private final Clock clock;

    /**
     * Assembles the stage with the session stores and the engine / edge seams.
     *
     * @param sessionStore       the server-side session store resolving the opaque session id
     * @param sessionCookieCodec the opaque session-cookie codec reading the request cookie
     * @param tokenRefresh       the single-flight near-expiry refresh seam (the D9 hook)
     * @param grantedScopes      the mediated-token scope-membership seam backing {@code required_scopes}
     * @param loginInitiation    the auth-code-flow initiation seam for a navigation redirect
     * @param clock              the reference clock (TTL anchor for session resolution and refresh)
     */
    public SessionAuthenticationStage(SessionStore sessionStore, SessionCookieCodec sessionCookieCodec,
            TokenRefresh tokenRefresh, GrantedScopes grantedScopes, LoginInitiation loginInitiation, Clock clock) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.sessionCookieCodec = Objects.requireNonNull(sessionCookieCodec, "sessionCookieCodec");
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

        Optional<SessionRecord> resolved = resolveSession(request, now);
        if (resolved.isEmpty()) {
            challengeUnauthenticated(request, route, now);
            return;
        }

        SessionRecord session = tokenRefresh.refreshIfNeeded(resolved.get(), now);
        enforceScopes(route, session);
        request.mediatedBearer(session.accessToken());
    }

    private Optional<SessionRecord> resolveSession(PipelineRequest request, Instant now) {
        return sessionCookieCodec.readSessionId(request.firstHeader(COOKIE_HEADER).orElse(null))
                .flatMap(sessionId -> sessionStore.resolve(sessionId, now));
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
            challenge.setCookieHeaders().stream().findFirst()
                    .ifPresent(cookie -> request.responseHeaders().put(SET_COOKIE_HEADER, cookie));
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
     * and refresh-token rotation. The unwired binding returns the session unchanged, so a gateway
     * without the refresh coordinator injects the current mediated token verbatim.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface TokenRefresh {

        /**
         * Returns the session to mediate from, refreshing its mediated token when near expiry.
         *
         * @param session the resolved live session
         * @param now     the reference instant
         * @return the same session, or a refreshed copy carrying the rotated token material
         */
        SessionRecord refreshIfNeeded(SessionRecord session, Instant now);
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
