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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;

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
 * It carries two edge-facing capabilities:
 * <ol>
 *   <li>the {@link SessionAuthenticationStage} the edge injects into the session-aware
 *       {@code AuthenticationStage} constructor so a {@code require: session} route is served rather
 *       than rejected, plus the fixed {@link CsrfDefence} the edge enforces on unsafe-method session
 *       requests;</li>
 *   <li>{@link #dispatch(ReservedEndpoint, ReservedHttpRequest, Instant)} — the framework-agnostic
 *       fan-out that routes each matched reserved kind (callback, logout, logout-return, back-channel
 *       logout, user-info, login) to its already-wired handler and normalizes the heterogeneous
 *       handler outcomes into one {@link ReservedHttpResponse} the edge renders verbatim. The
 *       back-channel arm stays wired in both session modes — the endpoint's own capability gate
 *       answers {@code 404} where IdP-driven destruction is unsupported, so the reserved path never
 *       falls through to the proxy route table.</li>
 * </ol>
 * The runtime is framework-agnostic (raw request pieces in, a {@link ReservedHttpResponse} out — no
 * JAX-RS / Vert.x coupling), so it is unit-testable without a container. The engine-dependent
 * collaborators are supplied by construction (the producer binds the {@code token-sheriff-client}
 * engine seams); the {@link #logoutEndpoint} is a {@link Supplier} so its discovery-dependent
 * {@code end_session_endpoint} is resolved lazily on first logout rather than at boot.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BffRuntime {

    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";

    private final boolean active;
    private final @Nullable SessionAuthenticationStage sessionStage;
    private final @Nullable CsrfDefence csrfDefence;
    private final @Nullable StepUpCoordinator stepUpCoordinator;
    private final @Nullable CallbackEndpoint callbackEndpoint;
    private final @Nullable Supplier<LogoutEndpoint> logoutEndpoint;
    private final @Nullable BackchannelLogoutEndpoint backchannelLogoutEndpoint;
    private final @Nullable UserInfoEndpoint userInfoEndpoint;
    private final @Nullable LoginInitiationEndpoint loginInitiationEndpoint;

    @SuppressWarnings("java:S107") // wiring holder assembled once by BffRuntimeProducer
    private BffRuntime(boolean active, @Nullable SessionAuthenticationStage sessionStage,
            @Nullable CsrfDefence csrfDefence, @Nullable StepUpCoordinator stepUpCoordinator,
            @Nullable CallbackEndpoint callbackEndpoint,
            @Nullable Supplier<LogoutEndpoint> logoutEndpoint,
            @Nullable BackchannelLogoutEndpoint backchannelLogoutEndpoint,
            @Nullable UserInfoEndpoint userInfoEndpoint, @Nullable LoginInitiationEndpoint loginInitiationEndpoint) {
        this.active = active;
        this.sessionStage = sessionStage;
        this.csrfDefence = csrfDefence;
        this.stepUpCoordinator = stepUpCoordinator;
        this.callbackEndpoint = callbackEndpoint;
        this.logoutEndpoint = logoutEndpoint;
        this.backchannelLogoutEndpoint = backchannelLogoutEndpoint;
        this.userInfoEndpoint = userInfoEndpoint;
        this.loginInitiationEndpoint = loginInitiationEndpoint;
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
     * @param logoutEndpoint            the lazy RP-initiated logout handler (resolves the
     *                                  discovery-dependent {@code end_session_endpoint} on first use)
     * @param backchannelLogoutEndpoint the OIDC back-channel logout receiver
     * @param userInfoEndpoint          the session/user-info fold handler (D11)
     * @param loginInitiationEndpoint   the login-initiation fold handler (D12)
     */
    @SuppressWarnings("java:S107") // wiring holder assembled once by BffRuntimeProducer
    public BffRuntime(SessionAuthenticationStage sessionStage, CsrfDefence csrfDefence,
            StepUpCoordinator stepUpCoordinator, CallbackEndpoint callbackEndpoint,
            Supplier<LogoutEndpoint> logoutEndpoint, BackchannelLogoutEndpoint backchannelLogoutEndpoint,
            UserInfoEndpoint userInfoEndpoint, LoginInitiationEndpoint loginInitiationEndpoint) {
        this(true,
                Objects.requireNonNull(sessionStage, "sessionStage"),
                Objects.requireNonNull(csrfDefence, "csrfDefence"),
                Objects.requireNonNull(stepUpCoordinator, "stepUpCoordinator"),
                Objects.requireNonNull(callbackEndpoint, "callbackEndpoint"),
                Objects.requireNonNull(logoutEndpoint, "logoutEndpoint"),
                Objects.requireNonNull(backchannelLogoutEndpoint, "backchannelLogoutEndpoint"),
                Objects.requireNonNull(userInfoEndpoint, "userInfoEndpoint"),
                Objects.requireNonNull(loginInitiationEndpoint, "loginInitiationEndpoint"));
    }

    /**
     * @return the inert runtime — a gateway serving no BFF variant in either session mode. It reports
     *         {@link #isActive()} {@code false}, exposes no session stage, and dispatches nothing.
     */
    public static BffRuntime inert() {
        return new BffRuntime(false, null, null, null, null, null, null, null, null);
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
     * Dispatches a matched reserved OIDC path to its handler and normalizes the outcome for the edge.
     *
     * @param kind the reserved endpoint the {@code ReservedPathRegistry} resolved for the request
     * @param req  the framework-agnostic request pieces the handlers consume
     * @param now  the reference instant (TTL anchor for session / pending resolution)
     * @return the normalized response the edge renders verbatim
     * @throws IllegalStateException when this runtime is inert (no reserved handler is wired)
     */
    public ReservedHttpResponse dispatch(ReservedEndpoint kind, ReservedHttpRequest req, Instant now) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(req, "req");
        Objects.requireNonNull(now, "now");
        if (!active) {
            throw new IllegalStateException("inert BFF runtime cannot dispatch a reserved path");
        }
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
        return new ReservedHttpResponse(outcome.status(), outcome.location(), null, Map.of(),
                outcome.setCookieHeaders());
    }

    private static ReservedHttpResponse render(BackchannelLogoutEndpoint.BackchannelLogoutOutcome outcome) {
        // The OIDC back-channel logout response is served uncacheable whatever its status: the 200
        // accepted and 400 rejected outcomes per the spec, and the 404 the endpoint's capability gate
        // returns for a session binding that cannot honour IdP-driven destruction.
        return new ReservedHttpResponse(outcome.status(), null, null, Map.of(CACHE_CONTROL, NO_STORE), List.of());
    }

    private static ReservedHttpResponse render(UserInfoEndpoint.UserInfoOutcome outcome) {
        return new ReservedHttpResponse(outcome.status(), null, JsonWriter.toJson(outcome.body()), outcome.headers(),
                List.of());
    }

    private static ReservedHttpResponse render(LoginInitiationEndpoint.LoginInitiationOutcome outcome) {
        return new ReservedHttpResponse(outcome.status(), outcome.location(), null, Map.of(),
                outcome.setCookieHeaders());
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
     * @param returnUrlParam the raw post-login {@code returnUrl} target for the login fold, may be absent
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
    // The component layout below — the line breaks between each @Nullable annotation and its
    // component — is what the formatter inherited from de.cuioss:cui-java-parent produces. It is
    // deliberately accepted rather than fought: this project declares no formatter plugin of its own,
    // and adding one to change this is out of scope. Do NOT hand-reflow the header; the next
    // format-check would simply undo it. ReservedHttpResponse below carries the identical note.
    public record ReservedHttpRequest(String rawQuery, @Nullable
            String cookieHeader,
    @Nullable
    String claimsParam, @Nullable
            String returnUrlParam, @Nullable
            String stateParam,
    @Nullable
    String rawFormBody, String httpMethod) {

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
     * fold), the fixed response headers, and the {@code Set-Cookie} header values to emit. Token
     * material never appears here — only opaque cookie headers, the redirect location, and allowlisted
     * disclosure.
     *
     * @param status           the HTTP status the edge returns
     * @param location         the redirect target, present only for a redirect outcome
     * @param jsonBody         the already-serialized JSON body, present only for the user-info fold
     * @param headers          the fixed response headers the edge emits verbatim
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit
     * @author API Sheriff Team
     * @since 1.0
     */
    // The component layout below — the line breaks between each @Nullable annotation and its
    // component — is what the formatter inherited from de.cuioss:cui-java-parent produces. It is
    // deliberately accepted rather than fought: this project declares no formatter plugin of its own,
    // and adding one to change this is out of scope. Do NOT hand-reflow the header; the next
    // format-check would simply undo it. ReservedHttpRequest above carries the identical note.
    public record ReservedHttpResponse(int status, @Nullable
            String location, @Nullable
            String jsonBody,
    Map<String, String> headers, List<String> setCookieHeaders) {

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
    }
}
