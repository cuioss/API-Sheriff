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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord.Widening;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The step-up reserved endpoint ({@code oidc.step_up.path}) — the seventh reserved gateway path. It
 * is registered exactly like the other browser-facing reserved endpoints (exact match, on the OIDC
 * host, resolved by the {@link ReservedPathRegistry} <em>before</em> the proxy route table), and it
 * is the target of the {@code step_up_url} a session route names when it refuses a non-navigation
 * request for a scope the live session does not carry. A navigation to it widens the live session to
 * the scopes the route behind the given return URL needs, then lands the browser back on that URL.
 * <p>
 * <strong>Live session only.</strong> The endpoint widens an existing session; it never starts one.
 * Without a live session it answers {@code 401} {@code application/problem+json} — no IdP redirect,
 * no pending record, no cookie. The rejection is logged exactly as {@link UserInfoEndpoint} logs its
 * own no-session {@code 401}: one fixed-text {@code DEBUG} line that interpolates no value, so neither
 * the return URL, the cookie, a token nor a session id reaches the log.
 * <p>
 * <strong>Return-URL safety (never an open redirect).</strong> The {@code returnUrl} parameter is
 * same-origin-validated through {@link PendingAuthorizationRecord#sameOrigin(String, String)}; an
 * absent, cross-origin, schema-relative ({@code //host}), backslash-authority or unparseable target
 * falls back to the configured {@code oidc.login.default_return_url}, exactly as a login does.
 * <p>
 * <strong>What is requested.</strong> The needed set of the target route is resolved by
 * {@link ReturnTargetScopes} — the single derivation the login-initiation endpoint uses. Two outcomes
 * follow, both {@code 302}:
 * <ul>
 *   <li>the needed set lies inside the session's granted-scope set {@code S}: a redirect straight to
 *       the return URL, because the route itself then obtains the missing scopes by a refresh inside
 *       the grant — no IdP authorization round trip is owed;</li>
 *   <li>otherwise: a redirect into {@link SessionWidening#initiate} as a
 *       {@link Widening.Attempt#SILENT silent} attempt for {@code S ∪ needed} — the same
 *       {@code prompt=none}-first, then exactly one interactive attempt, sequence a navigation to the
 *       route is widened through.</li>
 * </ul>
 * <p>
 * The endpoint is framework-agnostic (a raw {@code returnUrl} parameter and a raw {@code Cookie}
 * header in, a {@link StepUpOutcome} the edge renders out — no JAX-RS/Vert.x coupling), so it is
 * unit-testable without a container; the session runtime wires it to the request/response edge.
 * <p>
 * <strong>Thread safety.</strong> Immutable; the collaborators are safe for concurrent use. One
 * instance serves every request of a runtime.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class StepUpEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(StepUpEndpoint.class);

    private static final int FOUND = 302;
    private static final int UNAUTHORIZED = 401;

    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String PROBLEM_JSON = "application/problem+json";

    private final SessionWidening sessionWidening;
    private final SessionBinding sessionBinding;
    private final ReturnTargetScopes returnTargetScopes;
    private final String gatewayOrigin;
    private final String defaultReturnUrl;

    /**
     * Assembles the step-up endpoint with the runtime's single widening coordinator and the
     * session-resolution seam.
     *
     * @param sessionWidening    the runtime's widening coordinator — the same instance the callback
     *                           re-drives the interactive attempt through
     * @param sessionBinding     the mode-neutral session binding resolving the request's live session
     * @param returnTargetScopes the boot-built resolver mapping the return target to the scope set its
     *                           route needs
     * @param gatewayOrigin      the gateway's own origin (the {@code redirect_uri} origin) used to
     *                           same-origin-validate the return URL
     * @param defaultReturnUrl   the resolved {@code oidc.login.default_return_url} ({@code /} when
     *                           unset): the landing when no valid same-origin return URL is supplied
     */
    public StepUpEndpoint(SessionWidening sessionWidening, SessionBinding sessionBinding,
            ReturnTargetScopes returnTargetScopes, String gatewayOrigin, String defaultReturnUrl) {
        this.sessionWidening = Objects.requireNonNull(sessionWidening, "sessionWidening");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.returnTargetScopes = Objects.requireNonNull(returnTargetScopes, "returnTargetScopes");
        this.gatewayOrigin = Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
        this.defaultReturnUrl = Objects.requireNonNull(defaultReturnUrl, "defaultReturnUrl");
    }

    /**
     * Serves one step-up request: resolves the live session, validates the return URL, and either
     * redirects straight back to it or starts a silent widening of the live session.
     *
     * @param returnUrl    the raw {@code returnUrl} target the browser asked to be widened for, may be
     *                     absent
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant (session-resolution TTL anchor and the widening pending
     *                     record's TTL anchor)
     * @return a {@code 401} {@code application/problem+json} without a live session; otherwise a
     *         {@code 302} straight to the validated return URL (no cookies) when its route's needed
     *         scopes lie inside the granted set, or to the IdP authorization URL carrying the binding
     *         {@code Set-Cookie} for a silent widening
     */
    public StepUpOutcome handle(@Nullable String returnUrl, @Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");

        Optional<SessionRecord> session = sessionBinding.resolve(cookieHeader, now);
        if (session.isEmpty()) {
            LOGGER.debug("step-up request without a live session — 401 problem+json, no IdP redirect");
            return StepUpOutcome.unauthenticated();
        }
        SessionRecord live = session.get();

        String target = returnUrl != null && PendingAuthorizationRecord.sameOrigin(returnUrl, gatewayOrigin)
                ? returnUrl : defaultReturnUrl;
        Set<String> needed = returnTargetScopes.resolve(target);
        if (live.grantedScopes().containsAll(needed)) {
            LOGGER.debug("step-up target needs no scope outside the granted set — redirecting straight back");
            return StepUpOutcome.redirect(target, List.of());
        }

        LoginRedirect widening = sessionWidening.initiate(live, target, needed, Widening.Attempt.SILENT, now);
        LOGGER.debug("step-up target needs a scope outside the granted set — starting a silent widening");
        return StepUpOutcome.redirect(widening.authorizationUrl(), widening.setCookieHeaders());
    }

    /**
     * The framework-agnostic result of a step-up request: a {@code 302} redirect (straight to the
     * validated return URL, or to the IdP authorization URL carrying the browser-binding
     * {@code Set-Cookie}), or the {@code 401} {@code application/problem+json} answer to a request
     * without a live session. No token material ever appears here — only the opaque binding-cookie
     * header, the redirect location and a fixed problem body.
     *
     * @param status           the HTTP status the edge returns ({@code 302} or {@code 401})
     * @param location         the redirect target, present only for a redirect
     * @param body             the RFC 9457 problem body, empty for a redirect
     * @param headers          the fixed response headers the edge emits verbatim, empty for a redirect
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit — the binding cookie on a
     *                         widening, empty otherwise
     * @author API Sheriff Team
     * @since 1.0
     */
    // The marker below suppresses AnnotationNewlineFormat for this declaration, for the reason
    // BffRuntime.ReservedHttpRequest documents: the recipe would split @Nullable from its component.
    // cui-rewrite:disable AnnotationNewlineFormat
    public record StepUpOutcome(
    int status,
    @Nullable String location,
    Map<String, Object> body,
    Map<String, String> headers,
    List<String> setCookieHeaders) {

        /**
         * Canonical constructor defensively copying the body, headers and cookies into immutable
         * collections.
         */
        public StepUpOutcome {
            body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A {@code 302} redirect carrying the {@code Set-Cookie} headers.
         *
         * @param location         the redirect target
         * @param setCookieHeaders the {@code Set-Cookie} header values to emit
         * @return the redirect outcome
         */
        public static StepUpOutcome redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new StepUpOutcome(FOUND, location, Map.of(), Map.of(), setCookieHeaders);
        }

        /**
         * The {@code 401} {@code application/problem+json} answer to a request without a live
         * session, uncacheable. It is <strong>never</strong> a redirect: without a session there is
         * nothing to widen, and starting a login here would turn the step-up path into a second login
         * entry point.
         *
         * @return the unauthenticated outcome
         */
        public static StepUpOutcome unauthenticated() {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "about:blank");
            problem.put("title", "No live session");
            problem.put("status", UNAUTHORIZED);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(CACHE_CONTROL, NO_STORE);
            headers.put(CONTENT_TYPE, PROBLEM_JSON);
            return new StepUpOutcome(UNAUTHORIZED, null, problem, headers, List.of());
        }

        /**
         * @return {@code true} when this outcome is a redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }
    }
}
