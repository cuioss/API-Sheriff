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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.CallbackParameters;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.commons.error.TokenSheriffException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * The OIDC auth-code callback endpoint ({@code oidc.redirect_uri}) — the browser-facing landing
 * of the code flow (D2/D2b/D2c). Framework-agnostic by construction (it consumes a raw parameter
 * string and a raw {@code Cookie} header and returns a {@link CallbackOutcome} the edge renders),
 * so it carries no JAX-RS/Vert.x coupling and is unit-testable without a container.
 * <p>
 * <strong>form_post callback.</strong> The engine drives the authorization request with
 * {@code response_mode=form_post}, so after a successful login Keycloak returns a 200 auto-submit
 * form that POSTs the {@code code}/{@code state} to the {@code redirect_uri} as an
 * {@code application/x-www-form-urlencoded} body — not a 302 with the code in the query. The edge
 * therefore hands this endpoint the raw form body for a POST callback and the raw query for a GET
 * callback; both are the same urlencoded parameter shape, so a single {@code parse} handles either.
 * <p>
 * <strong>Callback HPP defence (BFF-13).</strong> The endpoint parses the <em>raw</em> parameter
 * string with {@link CallbackParameters#parse(String)} — never {@link CallbackParameters#of(java.util.Map)}:
 * only the raw parse lets the engine re-detect a duplicated {@code code}/{@code state} (the
 * Keycloak CVE-2026-9689 class), which a collapsed map cannot. A form_post body is parsed by the
 * same {@code parse}, so the duplicate-parameter defence holds identically for the POST body.
 * <p>
 * <strong>Browser binding (D2b).</strong> The pending-authorization record is resolved by the
 * unguessable id carried in the {@link BindingCookieCodec browser-binding cookie}, not by the
 * returned {@code state} alone: a callback replayed in a different browser (which carries no
 * binding cookie) is rejected {@code 403} even with a valid {@code state}. The returned
 * {@code state} must additionally match the resolved record's {@code state} (constant-time), so a
 * callback is honoured only when <em>both</em> the binding cookie resolves the record <em>and</em>
 * the {@code state} matches — the pre-session analogue of the session cookie.
 * <p>
 * <strong>Engine-driven exchange.</strong> The endpoint hands the record's engine
 * {@code FlowContext} and the parsed parameters to the {@link CodeExchange} seam — the session
 * runtime binds it to {@link AuthorizationCodeFlow#exchange}, so the engine owns the code
 * exchange, PKCE, and {@code state}/{@code nonce}/{@code iss} validation (fail-closed). The seam
 * keeps the endpoint decoupled from the confidential-client wiring (discovery metadata, client
 * authentication) and unit-testable without a live token endpoint. On success the endpoint builds
 * the {@link SessionRecord} and binds it to the browser through the mode-neutral
 * {@link SessionBinding} seam — emitting whatever {@code Set-Cookie} that binding produces rather
 * than building one itself — clears the now-consumed binding cookie (single-use), and redirects the
 * browser to the record's same-origin-validated return URL.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CallbackEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(CallbackEndpoint.class);

    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int FOUND = 302;

    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_ACR = "acr";
    private static final String CLAIM_AUTH_TIME = "auth_time";

    private final CodeExchange codeExchange;
    private final PendingAuthorizationStore pendingStore;
    private final BindingCookieCodec bindingCookieCodec;
    private final SessionBinding sessionBinding;
    private final Duration sessionTtl;

    /**
     * Assembles the callback endpoint with the exchange seam and the gateway-side collaborators it drives.
     *
     * @param codeExchange       the engine code-exchange seam (bound to {@link AuthorizationCodeFlow#exchange})
     * @param pendingStore       the single-use pending-authorization store
     * @param bindingCookieCodec the browser-binding cookie codec
     * @param sessionBinding     the mode-neutral session binding the new session is bound through
     * @param sessionTtl         the absolute session lifetime from login
     */
    public CallbackEndpoint(CodeExchange codeExchange, PendingAuthorizationStore pendingStore,
            BindingCookieCodec bindingCookieCodec, SessionBinding sessionBinding, Duration sessionTtl) {
        this.codeExchange = Objects.requireNonNull(codeExchange, "codeExchange");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.bindingCookieCodec = Objects.requireNonNull(bindingCookieCodec, "bindingCookieCodec");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
    }

    /**
     * Handles one OIDC callback: validates the binding, drives the engine exchange, creates the
     * session, and returns the browser response.
     *
     * @param rawParameters the raw callback parameter string — the query for a GET callback or the
     *                      {@code application/x-www-form-urlencoded} body for a {@code response_mode=form_post}
     *                      POST callback (never map-collapsed — BFF-13)
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now         the reference instant (TTL anchor for pending resolution and session expiry)
     * @return the redirect outcome on success, or a {@code 400}/{@code 403} error outcome
     */
    public CallbackOutcome handle(String rawParameters, @Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(rawParameters, "rawParameters");
        Objects.requireNonNull(now, "now");

        CallbackParameters params;
        try {
            params = CallbackParameters.parse(rawParameters);
        } catch (IllegalArgumentException duplicateInjection) {
            // BFF-13: parse() rejects a duplicated code/state (RFC 9700 §4.7.3 parameter injection —
            // the Keycloak CVE-2026-9689 class). Only the raw parse can detect this; of(Map) cannot.
            LOGGER.debug(duplicateInjection, "OIDC callback rejected — duplicate query parameter (BFF-13)");
            return CallbackOutcome.error(BAD_REQUEST);
        }
        if (params.hasError()) {
            LOGGER.debug("OIDC callback carried an IdP error response: %s", params.error());
            return CallbackOutcome.error(BAD_REQUEST);
        }
        if (isBlank(params.state())) {
            LOGGER.debug("OIDC callback missing state parameter");
            return CallbackOutcome.error(BAD_REQUEST);
        }

        Optional<String> recordId = bindingCookieCodec.readRecordId(cookieHeader);
        if (recordId.isEmpty()) {
            LOGGER.debug("OIDC callback without a browser-binding cookie — rejected");
            return CallbackOutcome.error(FORBIDDEN);
        }

        Optional<PendingAuthorizationRecord> resolved = pendingStore.consume(recordId.get(), now);
        if (resolved.isEmpty()) {
            LOGGER.debug("OIDC callback binding cookie resolved no live pending record — rejected");
            return CallbackOutcome.error(FORBIDDEN);
        }
        PendingAuthorizationRecord pending = resolved.get();

        if (!constantTimeEquals(pending.flowContext().state(), params.state())) {
            LOGGER.debug("OIDC callback state did not match the bound pending record — rejected");
            return CallbackOutcome.error(FORBIDDEN);
        }

        try {
            AuthorizationCodeFlow.AuthenticationResult result = codeExchange.exchange(pending.flowContext(), params);
            return completeLogin(result, pending, now);
        } catch (TokenSheriffException engineFailure) {
            LOGGER.debug(engineFailure, "OIDC callback code exchange / token validation failed");
            return CallbackOutcome.error(BAD_REQUEST);
        }
    }

    private CallbackOutcome completeLogin(AuthorizationCodeFlow.AuthenticationResult result,
            PendingAuthorizationRecord pending, Instant now) {
        AccessTokenContent accessToken = result.accessToken();
        IdTokenContent idToken = result.idToken();
        Optional<String> subject = idToken.getSubject().or(accessToken::getSubject);
        if (subject.isEmpty()) {
            LOGGER.debug("OIDC callback validated tokens carried no subject — rejected");
            return CallbackOutcome.error(BAD_REQUEST);
        }

        SessionRecord session = SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken(accessToken.getRawToken())
                .refreshToken(Optional.empty())
                .idToken(idToken.getRawToken())
                .sub(subject.get())
                .sid(claim(idToken, CLAIM_SID))
                .expiresAt(now.plus(sessionTtl))
                .acr(claim(idToken, CLAIM_ACR))
                .authTime(claimEpochSeconds(idToken, CLAIM_AUTH_TIME))
                .build();
        SessionBinding.BoundSession bound = sessionBinding.bind(session, now);

        List<String> setCookies = new ArrayList<>(bound.setCookieHeaders());
        setCookies.add(bindingCookieCodec.toClearingSetCookieHeader());
        return CallbackOutcome.redirect(pending.returnUrl(), setCookies);
    }

    private static Optional<String> claim(TokenContent token, String name) {
        ClaimValue value = token.getClaims().get(name);
        if (value == null) {
            return Optional.empty();
        }
        String original = value.getOriginalString();
        return original == null || original.isBlank() ? Optional.empty() : Optional.of(original);
    }

    private static Optional<Instant> claimEpochSeconds(TokenContent token, String name) {
        return claim(token, name).flatMap(raw -> {
            try {
                return Optional.of(Instant.ofEpochSecond(Long.parseLong(raw.trim())));
            } catch (NumberFormatException _) {
                return Optional.empty();
            }
        });
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static boolean constantTimeEquals(@Nullable String expected, @Nullable String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The engine code-exchange seam. The session runtime binds it to the engine as
     * {@code (context, params) -> authorizationCodeFlow.exchange(providerMetadata, context, params,
     * clientAuthentication)}; a test binds it to a hand-built result. Keeping the confidential-client
     * wiring (provider metadata, client authentication) behind the seam decouples the endpoint from
     * it and makes the success and exchange-failure paths unit-testable without a live token endpoint.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface CodeExchange {

        /**
         * Exchanges the callback for validated tokens: the engine performs the code exchange and the
         * {@code state}/{@code nonce}/{@code iss} + token validation, fail-closed.
         *
         * @param context the pending record's engine transaction context (owns state/nonce/PKCE)
         * @param params  the parsed callback parameters (from the raw query — BFF-13)
         * @return the validated access + ID token result
         * @throws de.cuioss.sheriff.token.commons.error.TokenSheriffException when the exchange or
         *         token validation fails (invalid state/nonce, IdP error, signature/claim failure)
         */
        AuthorizationCodeFlow.AuthenticationResult exchange(FlowContext context, CallbackParameters params);
    }

    /**
     * The framework-agnostic result of a callback: either a {@code 302} redirect carrying the
     * post-login {@code Set-Cookie} headers, or an error status with no body semantics for the edge
     * to render. Token material never appears here — only the opaque cookie headers and the
     * same-origin return location.
     *
     * @param status         the HTTP status the edge returns
     * @param location       the redirect target, present only for a successful login
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, empty for an error
     * @author API Sheriff Team
     * @since 1.0
     */
    public record CallbackOutcome(int status, Optional<String> location, List<String> setCookieHeaders) {

        /**
         * Canonical constructor normalizing an absent location and defensively copying the cookies.
         */
        public CallbackOutcome {
            location = Objects.requireNonNullElse(location, Optional.empty());
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A successful-login {@code 302} redirect.
         *
         * @param location         the same-origin return URL
         * @param setCookieHeaders the session {@code Set-Cookie} and the binding-clearing {@code Set-Cookie}
         * @return the redirect outcome
         */
        public static CallbackOutcome redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new CallbackOutcome(FOUND, Optional.of(location), setCookieHeaders);
        }

        /**
         * An error outcome carrying no redirect and no cookies.
         *
         * @param status the {@code 4xx} status
         * @return the error outcome
         */
        public static CallbackOutcome error(int status) {
            return new CallbackOutcome(status, Optional.empty(), List.of());
        }

        /**
         * @return {@code true} when this outcome is a successful-login redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }
    }
}
