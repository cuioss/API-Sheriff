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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;


import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.QueryParameter;
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
 *   <li>compares the session's active scope set {@code A} ({@link SessionRecord#activeScopes()})
 *       against the route's {@link RouteRuntime#getNeededScopes() neededScopes} and obtains what is
 *       missing before anything is relayed (see <em>Scope enforcement</em> below);</li>
 *   <li>records the mediated access token on the request for automatic upstream injection as
 *       {@code Authorization: Bearer} ({@link PipelineRequest#mediatedBearer(String)} — never an
 *       operator-configured header) — but only when the route's effective {@code auth.token_relay}
 *       is {@code true} (the default). With {@code token_relay: false} the session is still
 *       resolved, refreshed, scope-checked and required, so the unauthenticated
 *       {@code 302}/{@code 401} negotiation and the scope enforcement are unchanged, yet no bearer is
 *       recorded and the upstream receives no {@code Authorization} header. The token material is
 *       never disclosed to the browser up to this point; the forward stage renders the bearer and the
 *       session cookie never crosses.</li>
 * </ol>
 * An <strong>unauthenticated</strong> request is content-negotiated: a <em>navigation</em> request
 * (its {@code Accept} offers {@code text/html}) is redirected {@code 302} into the auth-code flow via
 * the {@link LoginInitiation} seam (short-circuiting the pipeline), requesting the selected route's
 * {@link RouteRuntime#getNeededScopes() neededScopes}; anything else (an XHR / API call)
 * gets {@code 401} {@code application/problem+json} via {@link EventType#TOKEN_MISSING}.
 * <p>
 * <strong>Scope enforcement.</strong> A session route never reaches its upstream with a session
 * that lacks a scope the route needs. On every request — whatever {@code auth.token_relay} says,
 * and before any bearer is recorded — the stage computes {@code missing = neededScopes − A}:
 * <ul>
 *   <li><strong>nothing missing</strong> — the request continues with no additional identity-provider
 *       call;</li>
 *   <li><strong>everything missing lies inside the granted scope set {@code S}</strong>
 *       ({@link SessionRecord#grantedScopes()}) — exactly one call through the {@link ScopeRefresh}
 *       seam, requesting {@code A ∪ missing}. A session that then carries every needed scope is
 *       relayed and the re-bind's cookies are emitted; an ended session or a request left without a
 *       token is answered exactly as on the near-expiry leg; a kept session that still lacks a scope
 *       is treated like a scope outside {@code S}. Navigation and API calls alike take this
 *       path;</li>
 *   <li><strong>a missing scope lies outside {@code S}</strong>, or the refresh did not obtain the
 *       set — a <em>navigation</em> is redirected {@code 302} through the {@link WideningInitiation}
 *       seam, which widens the live session, requesting {@code S ∪ neededScopes} (a silent attempt first)
 *       and returns to the requested URL; anything else gets {@code 403}
 *       {@code application/problem+json} via {@link EventType#SCOPE_MISSING}, carrying the problem
 *       extension members {@value #MISSING_SCOPES_MEMBER} (the missing scope names, sorted) and — only
 *       when {@code oidc.step_up.path} is configured — {@value #STEP_UP_URL_MEMBER}, the same-origin
 *       URL a browser follows to widen the session for the refused request. The request is never
 *       relayed.</li>
 * </ul>
 * Every name in {@value #MISSING_SCOPES_MEMBER} is drawn from the route's boot-configured
 * {@code neededScopes}, never from a token, so the response carries no token material.
 * <p>
 * One case skips the refresh although everything missing lies inside {@code S}: when the near-expiry
 * leg has just re-bound the session with a new cookie, the request's own {@code Cookie} header still
 * names the binding that leg rotated away, so a second exchange started from it would present a
 * refresh token the identity provider has already retired. Such a request takes the widening branch;
 * the next request carries the new cookie and is refreshed normally.
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

    /**
     * The RFC 9457 problem extension member naming the scopes a refused session request lacks: a
     * JSON array of scope names, sorted, every one drawn from the route's boot-configured
     * {@code neededScopes}.
     */
    public static final String MISSING_SCOPES_MEMBER = "missing_scopes";

    /**
     * The RFC 9457 problem extension member naming the same-origin URL that widens the live session
     * for the refused request: the configured {@code oidc.step_up.path} with the refused request's
     * path and query as its percent-encoded {@code returnUrl} parameter. Absent when no step-up path is
     * configured.
     */
    public static final String STEP_UP_URL_MEMBER = "step_up_url";

    private static final String COOKIE_HEADER = "Cookie";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String LOCATION_HEADER = "Location";
    private static final String TEXT_HTML = "text/html";
    private static final String RETURN_URL_QUERY = "?returnUrl=";
    private static final int FOUND = 302;

    private final SessionBinding sessionBinding;
    private final TokenRefresh tokenRefresh;
    private final ScopeRefresh scopeRefresh;
    private final LoginInitiation loginInitiation;
    private final WideningInitiation wideningInitiation;
    private final OnFailure onFailure;
    private final @Nullable String stepUpPath;
    private final Clock clock;

    /**
     * Assembles the stage with the session binding, the engine / edge seams and the refresh-failure
     * policy.
     *
     * @param sessionBinding     the mode-neutral session binding resolving the request's live session
     * @param tokenRefresh       the single-flight near-expiry refresh seam (the D9 hook)
     * @param scopeRefresh       the scope-driven refresh seam obtaining needed scopes that lie inside
     *                           the session's granted scope set
     * @param loginInitiation    the auth-code-flow initiation seam for a navigation redirect
     * @param wideningInitiation the session-widening seam a navigation is redirected through when a
     *                           needed scope cannot be obtained by a refresh
     * @param onFailure          the resolved {@code oidc.session.refresh.on_failure} policy applied when
     *                           a refresh leaves the request without a token to mediate
     * @param stepUpPath         the configured {@code oidc.step_up.path} named as
     *                           {@value #STEP_UP_URL_MEMBER} on a {@code 403}, {@code null} when the path
     *                           is not configured — the member is then omitted
     * @param clock              the reference clock (TTL anchor for session resolution and refresh)
     */
    // Each parameter is one independently bound seam or policy of the stage, assembled once by
    // BffRuntimeProducer; a parameter object would only regroup them without removing one.
    @SuppressWarnings("java:S107")
    public SessionAuthenticationStage(SessionBinding sessionBinding, TokenRefresh tokenRefresh,
            ScopeRefresh scopeRefresh, LoginInitiation loginInitiation, WideningInitiation wideningInitiation,
            OnFailure onFailure, @Nullable String stepUpPath, Clock clock) {
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.tokenRefresh = Objects.requireNonNull(tokenRefresh, "tokenRefresh");
        this.scopeRefresh = Objects.requireNonNull(scopeRefresh, "scopeRefresh");
        this.loginInitiation = Objects.requireNonNull(loginInitiation, "loginInitiation");
        this.wideningInitiation = Objects.requireNonNull(wideningInitiation, "wideningInitiation");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.stepUpPath = stepUpPath;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the session runtime for the selected {@code require: session} route.
     *
     * @param request the in-flight request; its route must be selected (stage 2)
     * @throws GatewayException {@code 401} when an unauthenticated non-navigation request is
     *                          challenged or a refresh failure is rejected under
     *                          {@link OnFailure#REJECT}; {@code 403} when a non-navigation request's
     *                          session lacks a needed scope it cannot obtain by a refresh
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
                enforceScopes(request, route, bound, cookieHeader, now);
            }
            case RefreshResult.SessionEnded() -> endSession(request, route, now);
            case RefreshResult.RequestFailed() ->
                // The identity provider never processed the refresh and the access token has expired: the
                // session is still live, so the cookie is deliberately NOT cleared — the next request can
                // retry the refresh once the back-off has elapsed.
                challengeRefreshFailure(request, route, now);
        }
    }

    /**
     * The scope enforcement every session request passes before anything is relayed: compares the
     * session's active scope set against the route's needed scopes, and obtains what is missing by one
     * refresh inside the granted set or by a widening outside it.
     *
     * @param mediated     the session the near-expiry leg mediates from, plus the cookies its re-bind
     *                     produced (already emitted by the caller)
     * @param cookieHeader the raw request {@code Cookie} header value the session was resolved from
     */
    private void enforceScopes(PipelineRequest request, RouteRuntime route, SessionBinding.BoundSession mediated,
            @Nullable String cookieHeader, Instant now) {
        SessionRecord session = mediated.session();
        Set<String> missing = missingScopes(route, session);
        if (missing.isEmpty()) {
            relay(request, route, session);
            return;
        }
        // A re-bind that produced a cookie rotated the binding the request's own Cookie header names,
        // so that header can no longer start a second exchange (see the class documentation).
        boolean cookieHeaderCurrent = mediated.setCookieHeaders().isEmpty();
        if (!cookieHeaderCurrent || !session.grantedScopes().containsAll(missing)) {
            widenOrRefuse(request, route, session, missing, now);
            return;
        }
        // Everything missing lies inside S: one refresh requesting A ∪ missing.
        Set<String> requested = new TreeSet<>(session.activeScopes());
        requested.addAll(missing);
        RefreshResult scoped = scopeRefresh.refreshForScopes(session, cookieHeader, requested, now);
        switch (scoped) {
            case RefreshResult.Mediate(SessionBinding.BoundSession bound) -> {
                emitSetCookies(request, bound.setCookieHeaders());
                SessionRecord kept = bound.session();
                Set<String> stillMissing = missingScopes(route, kept);
                if (stillMissing.isEmpty()) {
                    relay(request, route, kept);
                } else {
                    // The session is kept but the refresh did not obtain the set — a narrower grant, a
                    // refresh that is backing off, or no refresh path at all. It is never relayed short.
                    widenOrRefuse(request, route, kept, stillMissing, now);
                }
            }
            case RefreshResult.SessionEnded() -> endSession(request, route, now);
            case RefreshResult.RequestFailed() -> challengeRefreshFailure(request, route, now);
        }
    }

    /**
     * Lets a session that carries every needed scope through. {@code token_relay: false} keeps the
     * session fully in force — resolved, refreshed, scope-checked and required — but withholds the
     * access token from the upstream: no {@code Authorization}.
     */
    private static void relay(PipelineRequest request, RouteRuntime route, SessionRecord session) {
        if (route.getEffectiveAuth().effectiveTokenRelay()) {
            request.mediatedBearer(session.accessToken());
        }
    }

    /**
     * Answers a request whose session lacks a needed scope no refresh can obtain. A navigation is
     * redirected into a widening of the live session; anything else is refused {@code 403}. Either way
     * no bearer is recorded and the request never reaches the upstream.
     */
    private void widenOrRefuse(PipelineRequest request, RouteRuntime route, SessionRecord session,
            Set<String> missing, Instant now) {
        if (acceptsHtml(request)) {
            LoginChallenge challenge = wideningInitiation.initiate(session, returnUrl(request),
                    route.getNeededScopes(), now);
            request.responseHeaders().put(LOCATION_HEADER, challenge.location());
            emitSetCookies(request, challenge.setCookieHeaders());
            request.shortCircuit(FOUND);
            LOGGER.debug("Session lacks a needed scope on session route %s — redirecting into a session widening",
                    route.getId());
            return;
        }
        Map<String, Object> problemExtensions = new LinkedHashMap<>();
        // Sorted, and drawn from the route's boot-configured neededScopes — never from the token.
        problemExtensions.put(MISSING_SCOPES_MEMBER, List.copyOf(new TreeSet<>(missing)));
        if (stepUpPath != null) {
            problemExtensions.put(STEP_UP_URL_MEMBER,
                    stepUpPath + RETURN_URL_QUERY + URLEncoder.encode(returnUrl(request), StandardCharsets.UTF_8));
        }
        throw new GatewayException(EventType.SCOPE_MISSING,
                "Session missing a needed scope for session route " + route.getId(), problemExtensions);
    }

    /**
     * The needed scopes the session's active scope set does not carry — a subset of the route's
     * boot-configured {@code neededScopes}, so it never holds a name taken from a token. A satisfied
     * session, the case every ordinary request takes, is answered without allocating.
     */
    private static Set<String> missingScopes(RouteRuntime route, SessionRecord session) {
        Set<String> needed = route.getNeededScopes();
        if (session.activeScopes().containsAll(needed)) {
            return Set.of();
        }
        Set<String> missing = new TreeSet<>(needed);
        missing.removeAll(session.activeScopes());
        return missing;
    }

    /**
     * Answers a request whose session a refresh seam destroyed (the identity provider rejected the
     * refresh token — including a replayed one under strict rotation — or the gateway refused a
     * redeemed response). Mediating the pre-refresh token would keep serving an ended session, so the
     * clearing cookie drops the browser's stale copy first. On the reauthenticate navigation branch the
     * login challenge adds its own binding cookie for a DIFFERENT cookie name, so both must reach the
     * browser on this one response — hence the multi-valued Set-Cookie accumulator rather than a
     * single-valued header slot.
     */
    private void endSession(PipelineRequest request, RouteRuntime route, Instant now) {
        emitSetCookies(request, List.of(sessionBinding.clearingSetCookieHeader()));
        challengeRefreshFailure(request, route, now);
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
            // The login requests exactly what this route needs — the one boot-derived neededScopes the
            // bearer check also reads — so requesting and checking can never drift apart.
            LoginChallenge challenge = loginInitiation.initiate(returnUrl(request), route.getNeededScopes(), now);
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

    /**
     * The post-login return target: the canonical path plus, when the request carried a query, a
     * {@code ?} and the raw query rebuilt from {@link PipelineRequest#queryParameters()}. The pairs
     * are the raw, still-percent-encoded wire bytes in wire order, so the rebuilt query is
     * byte-identical to the inbound one — repeated and interleaved names keep their order, a bare
     * name stays bare (never {@code name=}), and no pair is decoded or re-encoded. No {@code ?} is
     * appended when there is no query.
     */
    private static String returnUrl(PipelineRequest request) {
        String canonicalPath = request.canonicalPath();
        String path = canonicalPath != null ? canonicalPath : request.requestPath();
        List<QueryParameter> query = request.queryParameters();
        if (query.isEmpty()) {
            return path;
        }
        StringJoiner rawQuery = new StringJoiner("&", path + "?", "");
        for (QueryParameter pair : query) {
            rawQuery.add(pair.value() == null ? pair.name() : pair.name() + "=" + pair.value());
        }
        return rawQuery.toString();
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
     * The scope-driven refresh seam: obtains needed scopes that are missing from the session's active
     * scope set but lie inside its granted scope set, by one refresh grant requesting exactly the set
     * it is given. The session runtime binds it to the refresh coordinator's scope-driven leg, which
     * shares the near-expiry leg's single-flight exclusion; with transparent refresh switched off it
     * binds a pass-through that returns the session unchanged.
     * <p>
     * The seam reports only what became of the session. Whether the returned session carries the
     * requested set is decided by the stage, which compares it against the route's needed scopes again
     * — so a binding can never cause an under-scoped session to be relayed.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface ScopeRefresh {

        /**
         * Refreshes the session's mediated token requesting {@code requestedScopes}.
         *
         * @param session         the live session, lacking a member of {@code requestedScopes}
         * @param cookieHeader    the raw request {@code Cookie} header value the session was resolved
         *                        from, so the coordinator can re-resolve it under single-flight
         *                        exclusion; may be absent
         * @param requestedScopes the scope set the grant requests — the session's active scope set
         *                        united with the route's missing scopes
         * @param now             the reference instant
         * @return {@link RefreshResult.Mediate mediate} carrying the session that was kept — refreshed
         *         and carrying the set, or unchanged or narrower when the set was not obtained — plus
         *         any {@code Set-Cookie} the re-bind produced; {@link RefreshResult.SessionEnded session
         *         ended} when the seam destroyed the session; or {@link RefreshResult.RequestFailed
         *         request failed} when the session is kept but this request has no valid token
         */
        RefreshResult refreshForScopes(SessionRecord session, @Nullable String cookieHeader,
                Set<String> requestedScopes, Instant now);
    }

    /**
     * What a refresh seam — {@link TokenRefresh} or {@link ScopeRefresh} — decided for one request.
     * Sealed, so the stage's switches over it are checked for exhaustiveness.
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
         * @param returnUrl the post-login return target (the path the browser was navigating to, plus
         *                  its raw query verbatim when it carried one)
         * @param scopes    the scope set the login requests — the selected route's
         *                  {@link RouteRuntime#getNeededScopes() neededScopes}
         * @param now       the reference instant (the pending record's TTL anchor)
         * @return the redirect target and the browser-binding {@code Set-Cookie}
         */
        LoginChallenge initiate(String returnUrl, Collection<String> scopes, Instant now);
    }

    /**
     * The session-widening seam for a navigation whose live session lacks a needed scope no refresh
     * can obtain. The session runtime binds it to the runtime's widening coordinator, starting with a
     * silent attempt: the identity provider is asked for the session's granted scopes united with
     * {@code neededScopes}, and the callback merges the grant into the live session and returns the
     * browser to {@code returnUrl}. A test binds it to a hand-built challenge.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface WideningInitiation {

        /**
         * Initiates a widening of the live session.
         *
         * @param live         the live session to widen
         * @param returnUrl    the target the browser returns to after the widening (the path it was
         *                     navigating to, plus its raw query verbatim when it carried one)
         * @param neededScopes the selected route's {@link RouteRuntime#getNeededScopes() neededScopes}
         * @param now          the reference instant (the pending record's TTL anchor)
         * @return the redirect target and the browser-binding {@code Set-Cookie}
         */
        LoginChallenge initiate(SessionRecord live, String returnUrl, Set<String> neededScopes, Instant now);
    }

    /**
     * The framework-agnostic result of a login or widening initiation: the {@code 302} redirect target
     * and the browser-binding {@code Set-Cookie} header(s) to emit. Token material never appears here.
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
