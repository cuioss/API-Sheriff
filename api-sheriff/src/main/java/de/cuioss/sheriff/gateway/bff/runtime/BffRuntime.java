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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ClientJwksEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.StepUpEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The assembled BFF runtime (D16 edge wiring) — the single collaborator the gateway edge
 * consults to serve the {@code require: session} stage-4 runtime and to dispatch a matched reserved
 * OIDC path to its handler.
 * <p>
 * The runtime is built once at boot by {@code BffRuntimeProducer} <strong>only when a global
 * {@code oidc} block with a {@code redirect_uri} and a recognised {@code session.mode} —
 * {@code server} or {@code cookie} — is configured</strong>; a gateway without such
 * a block gets the {@linkplain #inert() inert} instance, which reports {@link #isActive()}
 * {@code false} and dispatches nothing (the bearer-only proxy path is unchanged, and the
 * {@code ReservedPathRegistry} carries no reserved paths to dispatch anyway).
 * <p>
 * It carries three edge-facing capabilities:
 * <ol>
 *   <li>the {@link SessionAuthenticationStage} the edge injects into the session-aware
 *       {@code AuthenticationStage} constructor so a {@code require: session} route is served rather
 *       than rejected, plus the fixed {@link CsrfDefence} the edge enforces on unsafe-method session
 *       requests;</li>
 *   <li>{@link #dispatch(ReservedEndpoint, ReservedHttpRequest, Instant)} — the framework-agnostic
 *       fan-out that routes each matched reserved kind (callback, logout, logout-return, back-channel
 *       logout, user-info, login, step-up, client JWKS) to its already-wired handler and normalizes
 *       the heterogeneous handler outcomes into one {@link ReservedHttpResponse} the edge renders
 *       verbatim. The back-channel arm stays wired in both session modes — the endpoint's own
 *       capability gate answers {@code 404} where IdP-driven destruction is unsupported, so the
 *       reserved path never falls through to the proxy route table. The client JWKS arm stays wired
 *       in both client-authentication modes for the same reason: with a client secret configured the
 *       endpoint yields {@code 404} itself. That outcome the edge does not render verbatim: it
 *       answers it with the response of an unrouted path.</li>
 *   <li>{@link #sessionIdentity(String, Instant)} — the display identity (signed in or not, and the
 *       {@code preferred_username}) the application portal renders for the request's session.</li>
 * </ol>
 * The runtime is framework-agnostic (raw request pieces in, a {@link ReservedHttpResponse} out — no
 * JAX-RS / Vert.x coupling), so it is unit-testable without a container. The engine-dependent
 * collaborators are supplied by construction (the producer binds the {@code token-sheriff-client}
 * engine seams); the {@link #logoutEndpoint} is a {@link Supplier} assembled on first logout. Its
 * discovery-dependent {@code end_session_endpoint} is not part of that assembly: the handler asks
 * for it on each logout, after the local session has been ended, so a logout never waits on, or
 * fails with, provider discovery.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BffRuntime {

    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String NO_REFERRER = "no-referrer";

    private final boolean active;
    private final @Nullable SessionAuthenticationStage sessionStage;
    private final @Nullable CsrfDefence csrfDefence;
    private final @Nullable StepUpCoordinator stepUpCoordinator;
    private final @Nullable CallbackEndpoint callbackEndpoint;
    private final @Nullable Supplier<LogoutEndpoint> logoutEndpoint;
    private final @Nullable BackchannelLogoutEndpoint backchannelLogoutEndpoint;
    private final @Nullable UserInfoEndpoint userInfoEndpoint;
    private final @Nullable LoginInitiationEndpoint loginInitiationEndpoint;
    private final @Nullable StepUpEndpoint stepUpEndpoint;
    private final @Nullable ClientJwksEndpoint clientJwksEndpoint;
    private final @Nullable GatewayJson gatewayJson;
    private final Set<String> gatewayCookieNames;
    private final @Nullable SessionRelayRegistry sessionRelays;
    private final @Nullable Predicate<String> sessionHeld;

    @SuppressWarnings("java:S107") // wiring holder assembled once by BffRuntimeProducer
    private BffRuntime(boolean active, @Nullable SessionAuthenticationStage sessionStage,
            @Nullable CsrfDefence csrfDefence, @Nullable StepUpCoordinator stepUpCoordinator,
            @Nullable CallbackEndpoint callbackEndpoint,
            @Nullable Supplier<LogoutEndpoint> logoutEndpoint,
            @Nullable BackchannelLogoutEndpoint backchannelLogoutEndpoint,
            @Nullable UserInfoEndpoint userInfoEndpoint, @Nullable LoginInitiationEndpoint loginInitiationEndpoint,
            @Nullable StepUpEndpoint stepUpEndpoint, @Nullable ClientJwksEndpoint clientJwksEndpoint,
            @Nullable GatewayJson gatewayJson, Set<String> gatewayCookieNames,
            @Nullable SessionRelayRegistry sessionRelays, @Nullable Predicate<String> sessionHeld) {
        this.sessionRelays = sessionRelays;
        this.sessionHeld = sessionHeld;
        this.gatewayCookieNames = Set.copyOf(gatewayCookieNames);
        this.active = active;
        this.sessionStage = sessionStage;
        this.csrfDefence = csrfDefence;
        this.stepUpCoordinator = stepUpCoordinator;
        this.callbackEndpoint = callbackEndpoint;
        this.logoutEndpoint = logoutEndpoint;
        this.backchannelLogoutEndpoint = backchannelLogoutEndpoint;
        this.userInfoEndpoint = userInfoEndpoint;
        this.loginInitiationEndpoint = loginInitiationEndpoint;
        this.stepUpEndpoint = stepUpEndpoint;
        this.clientJwksEndpoint = clientJwksEndpoint;
        this.gatewayJson = gatewayJson;
    }

    /**
     * Assembles an active runtime with every reserved-endpoint handler and the session
     * stage-4 runtime wired. The same wiring serves both session modes — {@code server} and
     * {@code cookie} — which differ only in the {@code SessionBinding} the producer supplies.
     *
     * @param sessionStage              the {@code require: session} stage-4 runtime
     * @param csrfDefence               the fixed CSRF defence for unsafe-method session requests
     * @param stepUpCoordinator         the RFC 9470 step-up coordinator (D7) for upstream challenges
     * @param callbackEndpoint          the OIDC auth-code callback handler
     * @param logoutEndpoint            the lazy RP-initiated logout handler; obtaining it never
     *                                  reaches provider discovery
     * @param backchannelLogoutEndpoint the OIDC back-channel logout receiver
     * @param userInfoEndpoint          the session/user-info fold handler (D11)
     * @param loginInitiationEndpoint   the login-initiation fold handler (D12)
     * @param stepUpEndpoint            the step-up handler ({@code oidc.step_up.path})
     * @param clientJwksEndpoint        the client JWKS handler, in its publishing form for
     *                                  {@code private_key_jwt} client authentication and in its
     *                                  withheld form for client-secret authentication
     * @param gatewayJson               the serializer the user-info body, the step-up {@code 401}
     *                                  problem body and the client JWKS document are rendered through
     * @param gatewayCookieNames        the name of every cookie this runtime sets: the session cookie,
     *                                  any further cookie of the session binding, the login-binding
     *                                  cookie and the logout-state cookie
     * @param sessionRelays             the registry of the long-lived relays opened with a session;
     *                                  in server mode it is also the session store's end listener
     * @param sessionHeld               whether the session binding still holds the session of a given
     *                                  identity server-side; always {@code true} for a binding that
     *                                  holds none and so cannot observe an end
     */
    @SuppressWarnings("java:S107") // wiring holder assembled once by BffRuntimeProducer
    public BffRuntime(SessionAuthenticationStage sessionStage, CsrfDefence csrfDefence,
            StepUpCoordinator stepUpCoordinator, CallbackEndpoint callbackEndpoint,
            Supplier<LogoutEndpoint> logoutEndpoint, BackchannelLogoutEndpoint backchannelLogoutEndpoint,
            UserInfoEndpoint userInfoEndpoint, LoginInitiationEndpoint loginInitiationEndpoint,
            StepUpEndpoint stepUpEndpoint, ClientJwksEndpoint clientJwksEndpoint, GatewayJson gatewayJson,
            Set<String> gatewayCookieNames, SessionRelayRegistry sessionRelays, Predicate<String> sessionHeld) {
        this(true,
                Objects.requireNonNull(sessionStage, "sessionStage"),
                Objects.requireNonNull(csrfDefence, "csrfDefence"),
                Objects.requireNonNull(stepUpCoordinator, "stepUpCoordinator"),
                Objects.requireNonNull(callbackEndpoint, "callbackEndpoint"),
                Objects.requireNonNull(logoutEndpoint, "logoutEndpoint"),
                Objects.requireNonNull(backchannelLogoutEndpoint, "backchannelLogoutEndpoint"),
                Objects.requireNonNull(userInfoEndpoint, "userInfoEndpoint"),
                Objects.requireNonNull(loginInitiationEndpoint, "loginInitiationEndpoint"),
                Objects.requireNonNull(stepUpEndpoint, "stepUpEndpoint"),
                Objects.requireNonNull(clientJwksEndpoint, "clientJwksEndpoint"),
                Objects.requireNonNull(gatewayJson, "gatewayJson"),
                Objects.requireNonNull(gatewayCookieNames, "gatewayCookieNames"),
                Objects.requireNonNull(sessionRelays, "sessionRelays"),
                Objects.requireNonNull(sessionHeld, "sessionHeld"));
    }

    /**
     * Starts tracking a long-lived relay that is about to be opened for a request a session was let
     * through with, so the relay is closed when that session ends.
     * <p>
     * The session was resolved a moment ago and may have been destroyed since. The relay is therefore
     * tracked first and the session looked up again afterwards: a destruction that came before the
     * tracking is found by the lookup, one that comes after it is reported to the registry. Either way
     * the returned handle is told to close.
     * <p>
     * Call it off the event loop: in server mode the lookup takes the session store's monitor.
     *
     * @param sessionId the stable identity of the session the request was let through with
     * @param expiresAt the session's absolute expiry
     * @return the relay's handle; empty when the registry is at its capacity, in which case the relay
     *         must not be opened
     * @throws IllegalStateException when this runtime is inert (no BFF variant is configured)
     */
    public Optional<SessionRelayRegistry.Tracked> trackSessionRelay(String sessionId, Instant expiresAt) {
        if (sessionRelays == null || sessionHeld == null) {
            throw new IllegalStateException("inert BFF runtime tracks no session relay");
        }
        Optional<SessionRelayRegistry.Tracked> tracked = sessionRelays.track(sessionId, expiresAt);
        if (tracked.isPresent() && !sessionHeld.test(sessionId)) {
            sessionRelays.sessionEnded(sessionId);
        }
        return tracked;
    }

    /**
     * The names of the cookies this runtime sets and no other party may set: the session cookie, any
     * further cookie of the session binding (the activity cookie in cookie mode), the login-binding
     * cookie and the logout-state cookie. The edge uses them to keep an upstream response from
     * setting one of them.
     *
     * @return the immutable cookie names; empty for the inert runtime, which sets no cookie
     */
    public Set<String> gatewayCookieNames() {
        return gatewayCookieNames;
    }

    /**
     * @return the inert runtime — a gateway serving no BFF variant in either session mode. It reports
     *         {@link #isActive()} {@code false}, exposes no session stage, and dispatches nothing.
     */
    public static BffRuntime inert() {
        return new BffRuntime(false, null, null, null, null, null, null, null, null, null, null, null, Set.of(),
                null, null);
    }

    /**
     * @return the RFC 9470 step-up coordinator (D7) that handles an upstream
     *         {@code insufficient_user_authentication} challenge for a {@code require: session} route
     * @throws IllegalStateException when this runtime is inert (no BFF variant is configured)
     */
    public StepUpCoordinator stepUpCoordinator() {
        if (stepUpCoordinator == null) {
            throw new IllegalStateException("inert BFF runtime exposes no step-up coordinator");
        }
        return stepUpCoordinator;
    }

    /**
     * @return {@code true} when the gateway serves a BFF variant in either session mode (an
     *         {@code oidc} block with a {@code redirect_uri} and a recognised {@code session.mode} —
     *         {@code server} or {@code cookie} — was configured); {@code false} for a bearer-only
     *         gateway
     */
    public boolean isActive() {
        return active;
    }

    /**
     * @return the {@code require: session} stage-4 runtime the edge wires into the session-aware
     *         {@code AuthenticationStage}
     * @throws IllegalStateException when this runtime is inert (no BFF variant is configured)
     */
    public SessionAuthenticationStage sessionStage() {
        if (sessionStage == null) {
            throw new IllegalStateException("inert BFF runtime exposes no session stage");
        }
        return sessionStage;
    }

    /**
     * @return the fixed CSRF defence the edge enforces on unsafe-method {@code require: session}
     *         requests
     * @throws IllegalStateException when this runtime is inert (no BFF variant is configured)
     */
    public CsrfDefence csrfDefence() {
        if (csrfDefence == null) {
            throw new IllegalStateException("inert BFF runtime exposes no CSRF defence");
        }
        return csrfDefence;
    }

    /**
     * Resolves the display identity of the request's browser session for a gateway-rendered page —
     * the application portal — by delegating to the user-info fold, which reads
     * {@code preferred_username} from the validated ID-token claims of the live session.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant (the session-resolution TTL anchor)
     * @return the identity of the live session, or {@link SessionIdentity#anonymous()} when no live
     *         session exists — always anonymous for the inert runtime, which holds no sessions
     */
    public SessionIdentity sessionIdentity(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!active) {
            return SessionIdentity.anonymous();
        }
        return requireNonNull(userInfoEndpoint).sessionIdentity(cookieHeader, now);
    }

    /**
     * Dispatches a matched reserved OIDC path to its handler and normalizes the outcome for the edge.
     *
     * @param kind the reserved endpoint the {@code ReservedPathRegistry} resolved for the request
     * @param req  the framework-agnostic request pieces the handlers consume
     * @param now  the reference instant (TTL anchor for session / pending resolution)
     * @return the normalized response the edge renders; one that sets a cookie always carries
     *         {@code Cache-Control: no-store}
     * @throws IllegalStateException when this runtime is inert (no reserved handler is wired)
     */
    public ReservedHttpResponse dispatch(ReservedEndpoint kind, ReservedHttpRequest req, Instant now) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(req, "req");
        Objects.requireNonNull(now, "now");
        if (!active) {
            throw new IllegalStateException("inert BFF runtime cannot dispatch a reserved path");
        }
        return uncacheableWhenSettingCookies(dispatchToHandler(kind, req, now));
    }

    /**
     * Marks a reserved-path answer that sets a cookie as uncacheable: {@code Cache-Control: no-store}
     * replaces any value the handler supplied. A cookie the gateway sets belongs to one browser, so
     * the answer carrying it must not be stored and replayed to another.
     */
    private static ReservedHttpResponse uncacheableWhenSettingCookies(ReservedHttpResponse response) {
        if (response.setCookieHeaders().isEmpty()) {
            return response;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().forEach((name, value) -> {
            if (!CACHE_CONTROL.equalsIgnoreCase(name)) {
                headers.put(name, value);
            }
        });
        headers.put(CACHE_CONTROL, NO_STORE);
        return new ReservedHttpResponse(response.status(), response.location(), response.jsonBody(), headers,
                response.setCookieHeaders());
    }

    private ReservedHttpResponse dispatchToHandler(ReservedEndpoint kind, ReservedHttpRequest req, Instant now) {
        return switch (kind) {
            case CALLBACK ->
                render(requireNonNull(callbackEndpoint).handle(callbackParameters(req), req.cookieHeader(), now));
            case LOGOUT -> render(requireNonNull(logoutEndpoint).get().logout(req.cookieHeader(), now));
            case LOGOUT_RETURN ->
                render(requireNonNull(logoutEndpoint).get().completeReturn(req.stateParam(), req.cookieHeader()));
            case BACKCHANNEL_LOGOUT ->
                render(requireNonNull(backchannelLogoutEndpoint).receive(req.rawFormBody(), now));
            case USER_INFO -> render(requireNonNull(userInfoEndpoint).handle(req.cookieHeader(), req.claimsParam(), now));
            case LOGIN ->
                render(requireNonNull(loginInitiationEndpoint).initiate(req.returnUrlParam(), req.cookieHeader(), now));
            case STEP_UP -> render(requireNonNull(stepUpEndpoint).handle(req.returnUrlParam(), req.cookieHeader(), now));
            case CLIENT_JWKS -> render(requireNonNull(clientJwksEndpoint).handle(req.httpMethod()));
        };
    }

    /**
     * Selects the raw parameter string the callback parses. The gateway drives the OIDC auth-code
     * flow with {@code response_mode=query}, so the live callback is a {@code 302}-driven top-level
     * GET whose {@code code}/{@code state} arrive in the query string — the only shape on which the
     * browser sends the {@code SameSite=Lax} binding cookie the callback requires. The GET branch
     * hands the callback the raw query verbatim, never a map-collapsed projection, so the BFF-13
     * duplicate-parameter rejection holds (the collapsed {@code of(Map)} form is never taken).
     * <p>
     * The POST branch is retained as a <em>fail-closed</em> path only. The edge no longer reads a
     * body for the callback (only back-channel logout keeps that eager reserved-body read), so a
     * stray {@code POST} to the callback path finds {@code rawFormBody} absent, normalizes to the
     * empty string here, and is rejected {@code 400} for a missing {@code state} — an honest
     * rejection, never a {@code 500}.
     */
    private static String callbackParameters(ReservedHttpRequest req) {
        final String raw = req.isFormPost() ? req.rawFormBody() : req.rawQuery();
        if (raw == null) {
            return "";
        }
        return raw;
    }

    private static ReservedHttpResponse render(CallbackEndpoint.CallbackOutcome outcome) {
        return new ReservedHttpResponse(outcome.status(), outcome.location(), null, Map.of(),
                outcome.setCookieHeaders());
    }

    private static ReservedHttpResponse render(LogoutEndpoint.LogoutOutcome outcome) {
        // The end-session redirect carries the ID token in its query. The policy governs the request
        // the browser makes when it follows the redirect; what the provider's own pages send on as a
        // Referer afterwards is the provider's policy to set.
        Map<String, String> headers = outcome.carriesIdToken()
                ? Map.of(REFERRER_POLICY, NO_REFERRER)
                : Map.of();
        return new ReservedHttpResponse(outcome.status(), outcome.location(), null, headers,
                outcome.setCookieHeaders());
    }

    private static ReservedHttpResponse render(BackchannelLogoutEndpoint.BackchannelLogoutOutcome outcome) {
        // The OIDC back-channel logout response is served uncacheable whatever its status: the 200
        // accepted and 400 rejected outcomes per the spec, and the 404 the endpoint's capability gate
        // returns for a session binding that cannot honour IdP-driven destruction.
        return new ReservedHttpResponse(outcome.status(), null, null, Map.of(CACHE_CONTROL, NO_STORE), List.of());
    }

    private ReservedHttpResponse render(UserInfoEndpoint.UserInfoOutcome outcome) {
        return new ReservedHttpResponse(outcome.status(), null, requireNonNull(gatewayJson).toJson(outcome.body()),
                outcome.headers(), List.of());
    }

    private static ReservedHttpResponse render(LoginInitiationEndpoint.LoginInitiationOutcome outcome) {
        return new ReservedHttpResponse(outcome.status(), outcome.location(), null, Map.of(),
                outcome.setCookieHeaders());
    }

    private ReservedHttpResponse render(StepUpEndpoint.StepUpOutcome outcome) {
        // A redirect carries no body; the 401 no-session answer carries the fixed problem body.
        String jsonBody = outcome.body().isEmpty() ? null : requireNonNull(gatewayJson).toJson(outcome.body());
        return new ReservedHttpResponse(outcome.status(), outcome.location(), jsonBody, outcome.headers(),
                outcome.setCookieHeaders());
    }

    private ReservedHttpResponse render(ClientJwksEndpoint.JwksOutcome outcome) {
        // Only the publishing form's GET carries a document; every other outcome is body-less, and an
        // absent body must stay absent rather than be serialized as the JSON literal null.
        Map<String, Object> document = outcome.document();
        return new ReservedHttpResponse(outcome.status(), null,
                document == null ? null : requireNonNull(gatewayJson).toJson(document), outcome.headers(), List.of());
    }

    private static <T> T requireNonNull(@Nullable T value) {
        return Objects.requireNonNull(value, "active BFF runtime handler must be wired");
    }

    /**
     * The framework-agnostic request pieces a reserved endpoint consumes, extracted by the edge from
     * the raw request. Every field is optional at this level — each handler reads only the pieces it
     * needs (the callback reads {@link #rawQuery} and {@link #cookieHeader}, the back-channel receiver
     * reads {@link #rawFormBody}, the user-info fold reads {@link #claimsParam}, and so on).
     *
     * @param rawQuery       the raw query string (without the leading {@code ?}), never map-collapsed
     *                       — the {@code response_mode=query} GET callback re-parses it to re-detect a
     *                       duplicated {@code code}/{@code state} (BFF-13)
     * @param cookieHeader   the raw request {@code Cookie} header value, may be absent
     * @param claimsParam    the raw {@code claims} selector for the user-info fold, may be absent
     * @param returnUrlParam the raw {@code returnUrl} target for the login fold and the step-up endpoint,
     *                       may be absent
     * @param stateParam     the {@code state} the IdP returned on a logout-return leg, may be absent
     * @param rawFormBody    the raw {@code application/x-www-form-urlencoded} body — carried for
     *                       back-channel logout, the one reserved path that still consumes a body. The
     *                       {@code response_mode=query} callback carries its {@code code}/{@code state}
     *                       in {@link #rawQuery} and no body is read for it, so this is absent there
     * @param httpMethod     the request HTTP method. Absent normalizes to {@code GET} (the CSRF-safe
     *                       default), which is also the method of the live query-mode callback
     * @author API Sheriff Team
     * @since 1.0
     */
    // The marker below suppresses AnnotationNewlineFormat for this declaration. Without it that
    // recipe puts every @Nullable on its own line, splitting each annotation from the component it
    // qualifies and leaving the header unreadable. The header shape follows the project rule: render
    // it on one line when that fits 120 characters, otherwise one component per line at the
    // declaration indent. The flat indent is not a choice — org.openrewrite.java.format.AutoFormat
    // runs ahead of AnnotationNewlineFormat and reindents record components to the declaration's own
    // column, so a deeper continuation indent cannot survive. ReservedHttpResponse below carries the
    // identical note.
    // cui-rewrite:disable AnnotationNewlineFormat
    public record ReservedHttpRequest(
    String rawQuery,
    @Nullable String cookieHeader,
    @Nullable String claimsParam,
    @Nullable String returnUrlParam,
    @Nullable String stateParam,
    @Nullable String rawFormBody,
    String httpMethod) {

        /**
         * Canonical constructor normalizing an absent raw query to the empty string and an absent HTTP
         * method to {@code GET} (the CSRF-safe default).
         */
        public ReservedHttpRequest {
            rawQuery = Objects.requireNonNullElse(rawQuery, "");
            httpMethod = Objects.requireNonNullElse(httpMethod, "GET");
        }

        /**
         * @return {@code true} when this is a {@code POST}. The gateway drives
         *         {@code response_mode=query}, so the live callback is never a POST; this selects the
         *         fail-closed body branch for a stray POST to a reserved path instead
         */
        public boolean isFormPost() {
            return "POST".equalsIgnoreCase(httpMethod);
        }
    }

    /**
     * The normalized response the edge renders for a dispatched reserved path: the HTTP status, an
     * optional redirect {@code Location}, an optional already-serialized JSON body (the user-info
     * fold, the step-up endpoint's no-session problem and the published client key set), the fixed
     * response headers, and the {@code Set-Cookie} header values to emit.
     * <p>
     * One location carries token material: the end-session redirect of an RP-initiated logout,
     * whose query holds the {@code id_token_hint} for the identity provider. Access and refresh
     * tokens never appear here. Everything else is opaque cookie headers, a gateway-configured
     * redirect location, allowlisted disclosure, or the client-authentication public key. The
     * record's string form therefore prints neither the location nor a cookie value.
     *
     * @param status           the HTTP status the edge returns
     * @param location         the redirect target, present only for a redirect outcome
     * @param jsonBody         the already-serialized JSON body, present only for the user-info fold,
     *                         the step-up endpoint's {@code 401} problem, and the client key set a
     *                         {@code GET} on the client JWKS path publishes
     * @param headers          the fixed response headers the edge emits verbatim
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit
     * @author API Sheriff Team
     * @since 1.0
     */
    // The marker below suppresses AnnotationNewlineFormat for this declaration. Without it that
    // recipe puts every @Nullable on its own line, splitting each annotation from the component it
    // qualifies and leaving the header unreadable. The header shape follows the project rule: render
    // it on one line when that fits 120 characters, otherwise one component per line at the
    // declaration indent. The flat indent is not a choice — org.openrewrite.java.format.AutoFormat
    // runs ahead of AnnotationNewlineFormat and reindents record components to the declaration's own
    // column, so a deeper continuation indent cannot survive. ReservedHttpRequest above carries the
    // identical note.
    // cui-rewrite:disable AnnotationNewlineFormat
    public record ReservedHttpResponse(
    int status,
    @Nullable String location,
    @Nullable String jsonBody,
    Map<String, String> headers,
    List<String> setCookieHeaders) {

        /**
         * Canonical constructor defensively copying the header and cookie collections.
         */
        public ReservedHttpResponse {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * @return the optional redirect {@code Location}
         */
        public Optional<String> locationOptional() {
            return Optional.ofNullable(location);
        }

        /**
         * @return the optional serialized JSON body
         */
        public Optional<String> jsonBodyOptional() {
            return Optional.ofNullable(jsonBody);
        }

        /**
         * Overridden to omit the location, the body and the cookie values: the end-session
         * redirect's location carries the {@code id_token_hint}, and a {@code Set-Cookie} value can
         * be a session credential.
         *
         * @return the status, whether a location and a body are present, the header names and the
         *         number of cookies
         */
        @Override
        public String toString() {
            return "ReservedHttpResponse[status=%s, location=%s, jsonBody=%s, headerNames=%s, setCookieHeaders=%s]"
                    .formatted(status, presence(location), presence(jsonBody), headers.keySet(),
                            setCookieHeaders.size());
        }

        private static String presence(@Nullable String value) {
            return value == null ? "absent" : "present";
        }
    }
}
