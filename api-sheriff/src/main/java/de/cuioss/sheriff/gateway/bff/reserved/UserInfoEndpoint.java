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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The session/user-info reserved endpoint ({@code oidc.user_info.path}) — a sixth-minus-one
 * reserved gateway path (D11). It is registered exactly like the other reserved endpoints (exact
 * match, on the OIDC host, resolved by the {@link ReservedPathRegistry} <em>before</em> the proxy
 * route table), and serves the browser a curated view of the caller's own identity and session
 * metadata. It is built <strong>above the session-resolution seam</strong> so a server-mode BFF
 * inherits it without additional wiring.
 * <p>
 * <strong>What it discloses.</strong> The default response is a curated identity subset — the
 * operator's {@linkplain ClaimAllowlistFilter#defaultView() default view} (typically {@code sub},
 * {@code name}, {@code roles}) — plus session metadata (the session {@code expires_at}, the IdP
 * {@code auth_time}, and the authentication-context {@code acr}). A {@code claims} parameter selects
 * specific claims or, with {@link #FULL_VIEW}, the full allowlisted view. The claim source is the
 * <strong>validated ID-token claims</strong> (resolved through the {@link ClaimSource} seam) and the
 * server-held session metadata <strong>only</strong> — raw access / refresh / ID tokens never appear
 * in any view.
 * <p>
 * <strong>Allowlist cap (secure default closed).</strong> Every response is filtered through the
 * operator-owned {@link ClaimAllowlistFilter}: a claim outside {@code allowed_claims} can never be
 * disclosed even when it is present in the validated ID token, and an <em>explicitly</em>-requested
 * disallowed claim is rejected {@code 403} before any disclosure. The operator — never the browser
 * client — is the sole party that widens disclosure; an empty allowlist discloses nothing.
 * <p>
 * <strong>Response semantics.</strong> No live session yields {@code 401}
 * {@code application/problem+json} — <em>never</em> a redirect: the endpoint is an XHR probe that
 * composes with the D4 content negotiation (a navigation redirect is the browser's concern, not this
 * probe's). {@code 403} is reserved for CSRF / a disallowed-claim request. Every response — success
 * or error — carries {@code Cache-Control: no-store}, so an identity view is never cached by any
 * intermediary or the browser.
 * <p>
 * The endpoint is framework-agnostic (a raw {@code Cookie} header and the raw {@code claims}
 * parameter in, a {@link UserInfoOutcome} the edge renders out — no JAX-RS/Vert.x coupling), so it is
 * unit-testable without a container; the session runtime wires it to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UserInfoEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(UserInfoEndpoint.class);

    /**
     * The {@code claims} parameter value that selects the full allowlisted view rather than a
     * specific claim list or the curated default view.
     */
    public static final String FULL_VIEW = "*";

    private static final int OK = 200;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;

    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String CLAIMS_MEMBER = "claims";
    private static final String SESSION_MEMBER = "session";
    private static final String EXPIRES_AT = "expires_at";
    private static final String AUTH_TIME = "auth_time";
    private static final String ACR = "acr";

    private final SessionBinding sessionBinding;
    private final ClaimAllowlistFilter claimFilter;
    private final ClaimSource claimSource;

    /**
     * Assembles the user-info endpoint with the session binding, the operator claim allowlist, and the
     * validated-claim resolution seam.
     *
     * @param sessionBinding the mode-neutral session binding resolving the request's live session
     * @param claimFilter    the operator-owned claim allowlist capping every disclosure
     * @param claimSource    the validated ID-token claim-resolution seam (never raw tokens)
     */
    public UserInfoEndpoint(SessionBinding sessionBinding, ClaimAllowlistFilter claimFilter,
            ClaimSource claimSource) {
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.claimFilter = Objects.requireNonNull(claimFilter, "claimFilter");
        this.claimSource = Objects.requireNonNull(claimSource, "claimSource");
    }

    /**
     * Serves one user-info request: resolves the live session, selects the disclosed claims (capped
     * by the operator allowlist), and returns the curated identity view plus session metadata.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param claimsParam  the raw {@code claims} selector: absent selects the curated default view,
     *                     {@link #FULL_VIEW} selects the full allowlisted view, otherwise a
     *                     comma-separated list of specific claim names; may be absent
     * @param now          the reference instant (the session-resolution TTL anchor)
     * @return the {@code 200} identity view, a {@code 401} {@code application/problem+json} when no
     *         live session exists (never a redirect), or a {@code 403} when an explicitly-requested
     *         claim is outside the operator allowlist — every outcome carries {@code Cache-Control:
     *         no-store}
     */
    public UserInfoOutcome handle(@Nullable String cookieHeader, @Nullable String claimsParam, Instant now) {
        Objects.requireNonNull(now, "now");

        Optional<SessionRecord> session = sessionBinding.resolve(cookieHeader, now);
        if (session.isEmpty()) {
            LOGGER.debug("user-info request without a live session — 401 problem+json (never a redirect)");
            return UserInfoOutcome.unauthenticated();
        }

        List<String> selection;
        if (isBlank(claimsParam)) {
            selection = claimFilter.defaultView();
        } else if (FULL_VIEW.equals(claimsParam.trim())) {
            selection = claimFilter.allowedClaims().stream().sorted().toList();
        } else {
            List<String> requested = parseSelection(claimsParam);
            for (String claim : requested) {
                if (!claimFilter.isAllowed(claim)) {
                    LOGGER.debug("user-info request named a claim outside the operator allowlist — rejected 403");
                    return UserInfoOutcome.forbidden();
                }
            }
            selection = requested.isEmpty() ? claimFilter.defaultView() : requested;
        }

        Map<String, Object> disclosed = claimFilter.filter(claimSource.claims(session.get()), selection);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(CLAIMS_MEMBER, disclosed);
        body.put(SESSION_MEMBER, sessionMetadata(session.get()));
        return UserInfoOutcome.identity(body);
    }

    /**
     * Projects the server-held session metadata — never raw token material — into the disclosed view.
     * The {@code sub} identity anchor and any identity claims are disclosed through the allowlisted
     * {@code claims} member; this member carries only the gateway's own session bookkeeping the
     * session owner is entitled to see.
     */
    private static Map<String, Object> sessionMetadata(SessionRecord session) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(EXPIRES_AT, session.expiresAt().toString());
        Instant authTime = session.authTime();
        if (authTime != null) {
            metadata.put(AUTH_TIME, authTime.toString());
        }
        String acr = session.acr();
        if (acr != null) {
            metadata.put(ACR, acr);
        }
        return Collections.unmodifiableMap(metadata);
    }

    private static List<String> parseSelection(String claimsParam) {
        return Arrays.stream(claimsParam.split(","))
                .map(String::trim)
                .filter(claim -> !claim.isEmpty())
                .distinct()
                .toList();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * The validated-claim resolution seam. The session runtime binds it to the engine's ID-token
     * parsing so the endpoint discloses <em>validated</em> ID-token claims; a test binds it to a fixed
     * claim map. Keeping the token parsing behind the seam both decouples the endpoint from the engine
     * and enforces the invariant that the endpoint never touches raw token material — it receives only
     * an already-validated, already-parsed claim map.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface ClaimSource {

        /**
         * Resolves the validated ID-token claims backing the given session's disclosure.
         *
         * @param session the resolved live session
         * @return the validated ID-token claims, keyed by claim name (never raw token material)
         */
        Map<String, Object> claims(SessionRecord session);
    }

    /**
     * The framework-agnostic result of a user-info request: the HTTP status the edge returns, the JSON
     * response body, and the fixed response headers ({@code Cache-Control: no-store} on every outcome,
     * plus the content type — {@code application/json} for a disclosed view, {@code
     * application/problem+json} for a {@code 401} / {@code 403} error). No token material ever appears
     * in the body — only allowlisted claims and server-held session metadata.
     *
     * @param status  the HTTP status the edge returns
     * @param body    the JSON response body (the identity view, or the RFC 9457 problem detail)
     * @param headers the fixed response headers the edge emits verbatim
     * @author API Sheriff Team
     * @since 1.0
     */
    public record UserInfoOutcome(int status, Map<String, Object> body, Map<String, String> headers) {

        /**
         * Canonical constructor defensively copying the body and headers into immutable, insertion-
         * ordered maps.
         */
        public UserInfoOutcome {
            body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /**
         * A {@code 200} disclosed identity view served {@code application/json}, uncacheable.
         *
         * @param body the curated identity view (allowlisted claims plus session metadata)
         * @return the disclosure outcome
         */
        public static UserInfoOutcome identity(Map<String, Object> body) {
            Objects.requireNonNull(body, "body");
            return new UserInfoOutcome(OK, body, jsonHeaders());
        }

        /**
         * A {@code 401} {@code application/problem+json} outcome — the no-live-session response. It is
         * <strong>never</strong> a redirect: the endpoint is an XHR probe.
         *
         * @return the unauthenticated outcome
         */
        public static UserInfoOutcome unauthenticated() {
            return new UserInfoOutcome(UNAUTHORIZED, problem(UNAUTHORIZED, "No live session"), problemHeaders());
        }

        /**
         * A {@code 403} {@code application/problem+json} outcome — reserved for CSRF / a request that
         * names a claim outside the operator allowlist.
         *
         * @return the forbidden outcome
         */
        public static UserInfoOutcome forbidden() {
            return new UserInfoOutcome(FORBIDDEN, problem(FORBIDDEN, "Claim not permitted"), problemHeaders());
        }

        /**
         * @return {@code true} when this outcome discloses an identity view
         */
        public boolean isDisclosed() {
            return status == OK;
        }

        private static Map<String, Object> problem(int status, String title) {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "about:blank");
            problem.put("title", title);
            problem.put("status", status);
            return Collections.unmodifiableMap(problem);
        }

        private static Map<String, String> jsonHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(CACHE_CONTROL, NO_STORE);
            headers.put(CONTENT_TYPE, APPLICATION_JSON);
            return headers;
        }

        private static Map<String, String> problemHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(CACHE_CONTROL, NO_STORE);
            headers.put(CONTENT_TYPE, PROBLEM_JSON);
            return headers;
        }
    }
}
