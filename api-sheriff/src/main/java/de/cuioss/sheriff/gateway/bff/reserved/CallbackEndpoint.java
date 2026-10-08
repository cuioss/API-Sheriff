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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
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
 * <strong>Query-mode callback.</strong> The gateway drives the authorization request with
 * {@code response_mode=query} (see
 * {@link de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder}), so
 * after a successful login the IdP answers a {@code 302} that navigates the browser to the
 * {@code redirect_uri} with the {@code code}/{@code state} in the query string. That top-level GET
 * navigation is the only callback shape on which the browser sends the {@code SameSite=Lax}
 * browser-binding cookie the {@code 403} branch below requires; the engine's built-in
 * {@code response_mode=form_post} produced a cross-site POST instead, on which a Lax cookie is
 * dropped, so every real-browser login dead-ended there. The edge therefore hands this endpoint the
 * raw query, and no body is read for the callback path at all.
 * <p>
 * <strong>Callback HPP defence (BFF-13) — verified on the query path.</strong> The endpoint parses
 * the <em>raw</em> parameter string with {@link CallbackParameters#parse(String)} — never
 * {@link CallbackParameters#of(java.util.Map)}: only the raw parse lets the engine re-detect a
 * duplicated {@code code}/{@code state} (the Keycloak CVE-2026-9689 class), which a collapsed map
 * cannot. The defence was re-verified end to end for the query shape rather than assumed to carry
 * over: the edge populates {@code ReservedHttpRequest.rawQuery} from the genuinely raw Vert.x
 * {@code HttpServerRequest.query()} — the untouched request-target query string, not a
 * first-value-wins projection of the parsed parameter map — and
 * {@code BffRuntime.callbackParameters} feeds exactly that string to the same {@code parse}. No
 * stage between the edge and this endpoint collapses, reorders or de-duplicates the query, so a
 * duplicated {@code code} or {@code state} still reaches {@code parse} and is still rejected
 * {@code 400}.
 * <p>
 * <strong>Accepted tradeoff — the code travels in the URL.</strong> {@code response_mode=query}
 * places the authorization code in the callback URL, exposing it to the {@code Referer} header, to
 * proxy / CDN and server access logs, and to browser history. That exposure is the standard reason
 * {@code form_post} is preferred; it is accepted here <em>deliberately, by operator decision</em>,
 * because it is what makes the browser-facing flow work at all. It is bounded — not removed — by
 * PKCE (the engine always emits {@code code_challenge}/{@code code_challenge_method} and refuses a
 * provider that does not advertise {@code S256}, so a leaked code is not redeemable without the
 * gateway-held verifier), by the single-use, short-lived nature of the code at the token endpoint,
 * and by the binding-cookie + {@code state} double-check this endpoint performs below, which
 * rejects {@code 403} a code replayed from a different browser even while it is still live. The
 * full statement of the tradeoff and of how each mitigation was verified lives on
 * {@link de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder}.
 * <p>
 * <strong>Browser binding (D2b).</strong> The pending-authorization record is resolved by the
 * unguessable id carried in the {@link BindingCookieCodec browser-binding cookie}, not by the
 * returned {@code state} alone: a callback replayed in a different browser (which carries no
 * binding cookie) is rejected {@code 403} even with a valid {@code state}. The returned
 * {@code state} must additionally match the resolved record's {@code state} (constant-time), so a
 * callback is honoured only when <em>both</em> the binding cookie resolves the record <em>and</em>
 * the {@code state} matches.
 * <p>
 * <strong>Engine-driven exchange.</strong> The endpoint hands the record's engine
 * {@code FlowContext} and the parsed parameters to the {@link CodeExchange} seam — the session
 * runtime binds it to {@link AuthorizationCodeFlow#exchange}, so the engine owns the code
 * exchange, PKCE, and {@code state}/{@code nonce}/{@code iss} validation (fail-closed). The seam
 * keeps the endpoint decoupled from the confidential-client wiring (discovery metadata, client
 * authentication) and unit-testable without a live token endpoint. On success the endpoint builds
 * the {@link SessionRecord} — <strong>including the refresh token the exchange returned</strong>,
 * which is what makes the session refreshable at all (see {@code completeLogin}) — and binds it to
 * the browser through the mode-neutral {@link SessionBinding} seam — emitting whatever
 * {@code Set-Cookie} that binding produces rather than building one itself — clears the
 * now-consumed binding cookie (single-use), and redirects the browser to the record's
 * same-origin-validated return URL.
 * <p>
 * <strong>A login replaces the session the browser already had.</strong> When the callback request
 * still carries a cookie that resolves a live session, that session is destroyed through
 * {@link SessionBinding#destroy} before the new one is bound — whether it belongs to the same subject
 * or to another. A browser therefore holds at most one session of this gateway, and a session
 * established before the login cannot outlive it. In server mode the earlier session is removed from
 * the store; in cookie mode there is nothing held to remove, and the new cookie replaces the earlier
 * one in the browser. A widening callback is the opposite case and keeps its session (below). If the
 * new session then cannot be bound, the answer is the {@code 500} described above and carries no
 * cookie: in server mode the browser is left without a session, in cookie mode it still holds the
 * cookie it presented.
 * <p>
 * <strong>Active scope set.</strong> The new session's active scope set {@code A} — the {@code scope}
 * every later near-expiry refresh grant sends — is the access token's granted {@code scope} claim, or
 * the scope set the authorization request asked for (recorded on the pending record) when the token
 * carries no {@code scope} claim. A fresh login sets the session's granted scope set {@code S} to
 * that same derived set ({@code S = A} at login): at this point the IdP has granted exactly what the
 * token carries, and nothing more.
 * <p>
 * <strong>The refresh token never reaches the browser in the clear.</strong> It is a component of
 * the {@link SessionRecord}, so it lives wherever the active binding puts that record: server-side
 * in the store under an opaque handle in server mode, and inside the AES-256-GCM sealed value in
 * cookie mode. Neither the redirect nor the {@link CallbackOutcome} carries token material, and
 * {@link SessionRecord#toString()} redacts it, so it cannot reach a log line or a stack trace.
 * Whether it is retained at all is the operator's {@code oidc.session.refresh.enabled} switch,
 * applied one layer out where the {@link CodeExchange} seam is bound.
 * <p>
 * <strong>IdP error responses are answered after the binding check.</strong> An error response is
 * honoured only once the binding cookie has resolved the pending record and the returned
 * {@code state} has matched it (constant time), so a forged error from another browser can never
 * drive a widening re-drive. For a plain login the error is answered {@code 400}.
 * <p>
 * <strong>Widening callbacks.</strong> A pending record created by {@link SessionWidening} belongs to
 * a live session widening its scopes, and its callback is answered differently from a login:
 * <ul>
 *   <li><strong>Error, silent attempt.</strong> {@code login_required}, {@code interaction_required},
 *       {@code consent_required} or {@code account_selection_required} re-drives exactly one
 *       interactive attempt through {@link SessionWidening#redriveInteractive}, with the same return
 *       URL and scopes, answered as a {@code 302} carrying a new binding cookie.</li>
 *   <li><strong>Any other error, or any error on the interactive attempt</strong>, is terminal:
 *       {@code 403}, no {@code Set-Cookie}, the live session unchanged and no further redirect.</li>
 *   <li><strong>Success.</strong> The live session is resolved from the request {@code Cookie}
 *       ({@code 403} when there is none); the new ID token's {@code sub}, the live session's
 *       {@code sub} and the one the widening was issued for must all agree ({@code 403} otherwise —
 *       a session is never swapped to another identity); and the grant must carry every scope the
 *       widening sought beyond the session's granted set ({@code 403} otherwise, so a narrower grant
 *       can never send the browser round the widening again). The grant is then merged into the
 *       <em>live</em> session, which keeps its {@code sessionId}, {@code sessionNonce},
 *       {@code expiresAt} and {@code authTime}, takes the new access, refresh and ID tokens and the
 *       new {@code acr}, and sets both {@code A} and {@code S} to the granted scope. It is written
 *       through {@link SessionBinding#persistReissuingCookie} — the updating write that also
 *       re-issues the cookie value the browser holds and, like {@code persist}, never creates a
 *       session — and the browser is redirected to the recorded return URL with the cookies that
 *       write returned. A write that reports the session gone is answered like a missing live
 *       session: a terminal {@code 403}, no {@code Location}, no {@code Set-Cookie}, nothing stored
 *       (see below). A write the binding cannot hold answers {@code 500}, like a bind failure on
 *       login.</li>
 * </ul>
 * <strong>A widening re-issues the cookie and keeps the session.</strong> The step-up endpoint and the
 * scope widening both land here, so this one write covers both. In server mode the browser receives a
 * new cookie value and the value it presented stops resolving at once; the session is the same
 * session — same id, same authentication time, same absolute expiry, still ended by a back-channel
 * logout. In cookie mode the session is re-sealed with its nonce carried verbatim, so its derived
 * identity does not change either.
 * <p>
 * <strong>A terminated session is not brought back by the merge.</strong> In server mode a logout or
 * a validated back-channel logout may destroy the session between the resolve above and the
 * write. The binding then writes nothing and reports the session gone; the callback answers
 * {@code 403} with no {@code Location} and no {@code Set-Cookie} and records
 * {@code ApiSheriff-131} with the reason {@code session-terminated}. The tokens of the refused grant
 * are dropped, exactly as the other refusals drop theirs — they are stored nowhere and are not
 * revoked. In cookie mode the binding holds no state a logout could have removed, cannot observe
 * one, and re-seals the merged session; that limit is the stateless variant's and is documented
 * with it.
 * <p>
 * <strong>The merged granted set is what this grant returned.</strong> {@code S} becomes the granted
 * scope itself, never the union with what an earlier grant returned. A scope the identity provider
 * has stopped granting therefore leaves {@code S} on the merge, the next request needing it seeks it
 * <em>beyond</em> {@code S}, and a grant that again lacks it is refused {@code 403} by the check
 * above — the browser is not redirected a further time.
 * <p>
 * <strong>The merged session follows the identity-provider session of the grant.</strong> It takes
 * the {@code sid} of the new ID token when that token carries one, and keeps its own otherwise. The
 * tokens it now holds belong to the identity-provider session that answered the widening, which is
 * not necessarily the one the gateway session was created from; indexing the session under that
 * {@code sid} is what lets a back-channel logout for it find the session. In server mode the store
 * re-indexes the session on the write, so a logout token naming the previous {@code sid} no longer
 * matches it.
 * <p>
 * The merge is a check-then-act on the live session: a concurrent refresh may rotate its tokens
 * between the resolve and the write. Between those two writers the last one wins over two IdP-fresh
 * token sets of the same identity; the identity is re-checked on the resolved record, never taken
 * from the pending record alone. A termination is not a writer in that sense: in server mode it wins
 * over the merge, as described above.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CallbackEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(CallbackEndpoint.class);

    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int INTERNAL_ERROR = 500;
    private static final int FOUND = 302;

    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_ACR = "acr";
    private static final String CLAIM_AUTH_TIME = "auth_time";
    private static final String CLAIM_SCOPE = "scope";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The IdP errors on a silent widening attempt that owe exactly one interactive re-drive: the four
     * errors OpenID Connect Core 1.0 §3.1.2.6 defines for a {@code prompt=none} request that cannot
     * complete without the user.
     */
    private static final Set<String> INTERACTION_NEEDED = Set.of("login_required", "interaction_required",
            "consent_required", "account_selection_required");

    /**
     * The closed set of OAuth 2.0 / OIDC authorization-error codes a widening refusal may name in its
     * log line. Any other value is IdP- or attacker-supplied free text and is logged as
     * {@link #REASON_OTHER}.
     */
    private static final Set<String> KNOWN_AUTHORIZATION_ERRORS = Set.of("invalid_scope", "access_denied",
            "login_required", "interaction_required", "consent_required", "account_selection_required",
            "invalid_request", "unauthorized_client", "unsupported_response_type", "server_error",
            "temporarily_unavailable");
    private static final String REASON_OTHER = "other";
    private static final String REASON_SCOPE_NOT_GRANTED = "scope-not-granted";

    /**
     * The bounded reason recorded when the session a widening was about to be merged into was
     * terminated between the callback's resolve and its write — reported by a binding that can
     * observe it (server mode).
     */
    private static final String REASON_SESSION_TERMINATED = "session-terminated";

    private final CodeExchange codeExchange;
    private final PendingAuthorizationStore pendingStore;
    private final BindingCookieCodec bindingCookieCodec;
    private final SessionBinding sessionBinding;
    private final Duration sessionTtl;
    private final SessionWidening sessionWidening;

    /**
     * Assembles the callback endpoint with the exchange seam and the gateway-side collaborators it drives.
     *
     * @param codeExchange       the engine code-exchange seam (bound to {@link AuthorizationCodeFlow#exchange})
     * @param pendingStore       the single-use pending-authorization store
     * @param bindingCookieCodec the browser-binding cookie codec
     * @param sessionBinding     the mode-neutral session binding
     * @param sessionTtl         the absolute session lifetime from login
     * @param sessionWidening    the widening coordinator the single interactive re-drive of a silent
     *                           widening goes through; required, because a widening pending record
     *                           can only exist once it has run
     */
    public CallbackEndpoint(CodeExchange codeExchange, PendingAuthorizationStore pendingStore,
            BindingCookieCodec bindingCookieCodec, SessionBinding sessionBinding, Duration sessionTtl,
            SessionWidening sessionWidening) {
        this.codeExchange = Objects.requireNonNull(codeExchange, "codeExchange");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.bindingCookieCodec = Objects.requireNonNull(bindingCookieCodec, "bindingCookieCodec");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.sessionWidening = Objects.requireNonNull(sessionWidening, "sessionWidening");
    }

    /**
     * Handles one OIDC callback: validates the binding, then either answers an IdP error response or
     * drives the engine exchange and creates the session (login) or merges into the live session
     * (widening), and returns the browser response.
     *
     * @param rawParameters the raw callback parameter string — the untouched query string of the
     *                      {@code response_mode=query} GET callback, never map-collapsed (BFF-13)
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now         the reference instant (TTL anchor for pending resolution and session expiry)
     * @return the redirect outcome on success or on the interactive re-drive of a silent widening, a
     *         {@code 400}/{@code 403} error outcome when the callback is rejected or a widening is
     *         refused, or a {@code 500} error outcome when the validated session could not be bound
     *         or persisted (e.g. the sealed cookie-mode value exceeds the cookie-size budget)
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

        // Only now — the binding cookie resolved the record and the state matched — is an IdP error
        // response honoured, so a forged error from another browser can never drive a re-drive.
        if (params.hasError()) {
            return answerError(params, pending, now);
        }

        AuthorizationCodeFlow.AuthenticationResult result;
        try {
            result = codeExchange.exchange(pending.flowContext(), params);
        } catch (TokenSheriffException engineFailure) {
            LOGGER.debug(engineFailure, "OIDC callback code exchange / token validation failed");
            return CallbackOutcome.error(BAD_REQUEST);
        }
        PendingAuthorizationRecord.Widening widening = pending.widening();
        return widening == null ? completeLogin(result, pending, cookieHeader, now)
                : completeWidening(result, pending, widening, cookieHeader, now);
    }

    /**
     * Answers an IdP error response on an already-bound callback: {@code 400} for a login; for a
     * widening, exactly one interactive re-drive when a silent attempt needs interaction, otherwise a
     * terminal {@code 403} leaving the live session unchanged.
     */
    private CallbackOutcome answerError(CallbackParameters params, PendingAuthorizationRecord pending, Instant now) {
        String error = params.error();
        String reason = error != null && KNOWN_AUTHORIZATION_ERRORS.contains(error) ? error : REASON_OTHER;
        PendingAuthorizationRecord.Widening widening = pending.widening();
        if (widening == null) {
            LOGGER.debug("OIDC callback carried an IdP error response for a login (%s)", reason);
            return CallbackOutcome.error(BAD_REQUEST);
        }
        if (widening.attempt() == PendingAuthorizationRecord.Widening.Attempt.SILENT
                && INTERACTION_NEEDED.contains(reason)) {
            LoginRedirect redrive = sessionWidening.redriveInteractive(pending, now);
            LOGGER.debug("Silent session widening needs interaction (%s) — re-driving one interactive attempt",
                    reason);
            return CallbackOutcome.redirect(redrive.authorizationUrl(), redrive.setCookieHeaders());
        }
        LOGGER.warn(BffLogMessages.WARN.SESSION_WIDENING_REFUSED, reason);
        return CallbackOutcome.error(FORBIDDEN);
    }

    /**
     * Merges a successful widening grant into the live session the request carries, after checking
     * that it is the same identity and that the grant carries the scopes the widening sought. The
     * merge is written through the binding's re-issuing updating write, so a session the binding
     * reports gone at that point is refused {@code 403} instead of being created anew, and the cookies
     * that write produced are the ones the redirect carries.
     */
    private CallbackOutcome completeWidening(AuthorizationCodeFlow.AuthenticationResult result,
            PendingAuthorizationRecord pending, PendingAuthorizationRecord.Widening widening,
            @Nullable String cookieHeader, Instant now) {
        AccessTokenContent accessToken = result.accessToken();
        IdTokenContent idToken = result.idToken();
        Optional<String> subject = idToken.getSubject().or(accessToken::getSubject);
        if (subject.isEmpty()) {
            LOGGER.debug("OIDC widening callback validated tokens carried no subject — rejected");
            return CallbackOutcome.error(BAD_REQUEST);
        }
        Optional<SessionRecord> resolvedLive = sessionBinding.resolve(cookieHeader, now);
        if (resolvedLive.isEmpty()) {
            LOGGER.debug("OIDC widening callback carried no live session — rejected");
            return CallbackOutcome.error(FORBIDDEN);
        }
        SessionRecord live = resolvedLive.get();
        // The identity is re-checked on the resolved record, never taken from the pending record alone.
        if (!live.sub().equals(subject.get()) || !live.sub().equals(widening.sub())) {
            LOGGER.warn(BffLogMessages.WARN.SESSION_WIDENING_IDENTITY_MISMATCH);
            return CallbackOutcome.error(FORBIDDEN);
        }

        Set<String> granted = activeScopes(accessToken, pending);
        Set<String> sought = new TreeSet<>(pending.requestedScopes());
        sought.removeAll(live.grantedScopes());
        if (!granted.containsAll(sought)) {
            // A narrower grant merged into the session would leave the route's scope missing and send
            // the browser round the widening again — refuse instead, the session unchanged.
            LOGGER.warn(BffLogMessages.WARN.SESSION_WIDENING_REFUSED, REASON_SCOPE_NOT_GRANTED);
            return CallbackOutcome.error(FORBIDDEN);
        }
        // The merged tokens belong to the identity-provider session that answered this widening, so the
        // session is indexed under that session's sid; an ID token carrying none leaves its own in place.
        String grantSid = claim(idToken, CLAIM_SID);
        SessionRecord merged = SessionRecord.builder()
                .sessionId(live.sessionId())
                .accessToken(accessToken.getRawToken())
                .refreshToken(result.refreshToken())
                .idToken(idToken.getRawToken())
                .sub(live.sub())
                .sid(grantSid != null ? grantSid : live.sid())
                .expiresAt(live.expiresAt())
                .acr(claim(idToken, CLAIM_ACR))
                .authTime(live.authTime())
                .sessionNonce(live.sessionNonce())
                .activeScopes(granted)
                // S is what this grant returned, never the union with an earlier one: a scope the
                // identity provider stopped granting leaves S here, so the next request for it seeks it
                // beyond S and a grant lacking it again is refused above instead of merged.
                .grantedScopes(granted)
                .build();
        Optional<SessionBinding.BoundSession> persisted;
        try {
            // The re-issuing updating write: it never creates a session, so a session a logout
            // destroyed since the resolve above is not brought back by this merge, and it replaces
            // the cookie value the browser holds. The session keeps its identity.
            persisted = sessionBinding.persistReissuingCookie(merged, now);
        } catch (IllegalStateException persistFailure) {
            // As on login: the grant was valid, but the binding cannot hold the merged session (for
            // example the sealed cookie-mode value outgrew the cookie-size budget). The live session
            // is left as it was; only the binding's own bounded reason is logged.
            LOGGER.debug(persistFailure, "OIDC widening callback could not persist the widened session");
            return CallbackOutcome.error(INTERNAL_ERROR);
        }
        if (persisted.isEmpty()) {
            // The session was terminated between the resolve and the persist (server mode: a logout or
            // a back-channel logout). Nothing was written, so it stays terminated; the answer is the
            // one a callback without a live session gets, and the grant's tokens are dropped unstored.
            LOGGER.warn(BffLogMessages.WARN.SESSION_WIDENING_REFUSED, REASON_SESSION_TERMINATED);
            return CallbackOutcome.error(FORBIDDEN);
        }

        Set<String> added = new TreeSet<>(granted);
        added.removeAll(live.grantedScopes());
        LOGGER.info(BffLogMessages.INFO.SESSION_WIDENED, String.join(" ", added));
        List<String> setCookies = new ArrayList<>(persisted.get().setCookieHeaders());
        setCookies.add(bindingCookieCodec.toClearingSetCookieHeader());
        return CallbackOutcome.redirect(pending.returnUrl(), setCookies);
    }

    /**
     * Completes a login: ends the session the request still carries, then binds the new one.
     */
    private CallbackOutcome completeLogin(AuthorizationCodeFlow.AuthenticationResult result,
            PendingAuthorizationRecord pending, @Nullable String cookieHeader, Instant now) {
        AccessTokenContent accessToken = result.accessToken();
        IdTokenContent idToken = result.idToken();
        Optional<String> subject = idToken.getSubject().or(accessToken::getSubject);
        if (subject.isEmpty()) {
            LOGGER.debug("OIDC callback validated tokens carried no subject — rejected");
            return CallbackOutcome.error(BAD_REQUEST);
        }

        // A fresh login: the granted set S starts equal to the active set A.
        Set<String> loginScopes = activeScopes(accessToken, pending);
        SessionRecord session = SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken(accessToken.getRawToken())
                // The refresh token the authorization server issued alongside the validated tokens.
                // It is load-bearing rather than decorative: TokenRefreshCoordinator.refresh returns on
                // its very first guard when session.refreshToken() is null, BEFORE any logging, so a
                // session created without it can never be refreshed. Leaving it out is
                // what made a near-expiry refresh silently never fire and an IdP-revoked session keep
                // answering 200. null stays a normal outcome: an authorization server legitimately
                // grants no refresh token, and SessionRecord documents the component as nullable.
                .refreshToken(result.refreshToken())
                .idToken(idToken.getRawToken())
                .sub(subject.get())
                .sid(claim(idToken, CLAIM_SID))
                .expiresAt(now.plus(sessionTtl))
                .acr(claim(idToken, CLAIM_ACR))
                .authTime(claimEpochSeconds(idToken, CLAIM_AUTH_TIME))
                .activeScopes(loginScopes)
                .grantedScopes(loginScopes)
                .build();
        // A login never leaves an earlier session behind: the session the request's cookie still
        // resolves is ended before the new one is bound, whoever it belonged to. It is ended first, so
        // a binding at its capacity bound regains that session's place for the new one.
        Optional<SessionRecord> previous = sessionBinding.resolve(cookieHeader, now);
        if (previous.isPresent()) {
            sessionBinding.destroy(previous.get());
            LOGGER.debug("OIDC callback ended the session the request carried before binding the new one");
        }
        SessionBinding.BoundSession bound;
        try {
            bound = sessionBinding.bind(session, now);
        } catch (IllegalStateException bindFailure) {
            // bind() is documented to throw when the binding cannot hold the session: the stateless
            // cookie binding refuses a sealed value beyond the browser-safe cookie-size budget (a
            // realistic outcome for a large ID token or claim set), and the server binding refuses at
            // store capacity. The exchange itself succeeded and the caller did nothing wrong, so this
            // is an honest gateway-side 500 rather than a 4xx — and it must not escape handle() as an
            // unhandled exception. Only the binding's own bounded reason is logged; the session's
            // token material never reaches the log or the response.
            LOGGER.debug(bindFailure, "OIDC callback could not bind the new session — login not completed");
            return CallbackOutcome.error(INTERNAL_ERROR);
        }

        List<String> setCookies = new ArrayList<>(bound.setCookieHeaders());
        setCookies.add(bindingCookieCodec.toClearingSetCookieHeader());
        return CallbackOutcome.redirect(pending.returnUrl(), setCookies);
    }

    /**
     * Derives the granted scope set: the scope the access token was granted, or
     * — when the token carries no {@code scope} claim — the set the authorization request asked for,
     * as recorded on the pending record. The granted scope is authoritative because the identity
     * provider may narrow or widen the request; the requested set is the only other honest source.
     * <p>
     * The claim is read from the validated token's claim map rather than through
     * {@code AccessTokenContent#getScopes()}, so an absent claim is an ordinary fallback rather than an
     * exception. A list-typed claim is taken element by element; a plain-string claim is split on
     * whitespace (RFC 6749 §3.3), and each element is split the same way so no scope name can carry the
     * delimiter.
     */
    private static Set<String> activeScopes(AccessTokenContent accessToken, PendingAuthorizationRecord pending) {
        ClaimValue value = accessToken.getClaims().get(CLAIM_SCOPE);
        if (value == null) {
            return pending.requestedScopes();
        }
        List<String> listed = value.getAsList();
        List<String> candidates;
        if (listed != null && !listed.isEmpty()) {
            candidates = listed;
        } else {
            String original = value.getOriginalString();
            candidates = original == null ? List.of() : List.of(original);
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            for (String scope : WHITESPACE.split(candidate.strip())) {
                if (!scope.isEmpty()) {
                    granted.add(scope);
                }
            }
        }
        return granted.isEmpty() ? pending.requestedScopes() : Set.copyOf(granted);
    }

    private static @Nullable String claim(TokenContent token, String name) {
        ClaimValue value = token.getClaims().get(name);
        if (value == null) {
            return null;
        }
        String original = value.getOriginalString();
        return original == null || original.isBlank() ? null : original;
    }

    private static @Nullable Instant claimEpochSeconds(TokenContent token, String name) {
        String raw = claim(token, name);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(raw.trim()));
        } catch (NumberFormatException | DateTimeException _) {
            // An IdP-supplied auth_time is external input: it may not parse as a long, and a value
            // that does parse can still exceed Instant's range (DateTimeException is NOT a
            // NumberFormatException). Either way the claim is simply absent, never a 500.
            return null;
        }
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
         * @param params  the parsed callback parameters (from the raw, never map-collapsed query — BFF-13)
         * @return the validated access + ID token result, carrying the refresh token the
         *         authorization server issued alongside them (the record's {@code refreshToken}
         *         component is {@code null} when it issued none, or when the runtime's binding of
         *         this seam dropped it because {@code oidc.session.refresh.enabled} is off)
         * @throws de.cuioss.sheriff.token.commons.error.TokenSheriffException when the exchange or
         *         token validation fails (invalid state/nonce, IdP error, signature/claim failure)
         */
        AuthorizationCodeFlow.AuthenticationResult exchange(FlowContext context, CallbackParameters params);
    }

    /**
     * The framework-agnostic result of a callback: either a {@code 302} redirect carrying
     * {@code Set-Cookie} headers, or an error status with no body semantics for the edge
     * to render. Token material never appears here — only the opaque cookie headers and the
     * redirect location.
     *
     * @param status         the HTTP status the edge returns
     * @param location       the redirect target, {@code null} for an error
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, empty for an error
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record CallbackOutcome(int status, @Nullable String location, List<String> setCookieHeaders) {

        /**
         * Canonical constructor defensively copying the cookies.
         */
        public CallbackOutcome {
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A {@code 302} redirect.
         *
         * @param location         the redirect target
         * @param setCookieHeaders the {@code Set-Cookie} header values to emit
         * @return the redirect outcome
         */
        public static CallbackOutcome redirect(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new CallbackOutcome(FOUND, location, setCookieHeaders);
        }

        /**
         * An error outcome carrying no redirect and no cookies.
         *
         * @param status the error status ({@code 400} / {@code 403} rejected, {@code 500} unbindable)
         * @return the error outcome
         */
        public static CallbackOutcome error(int status) {
            return new CallbackOutcome(status, null, List.of());
        }

        /**
         * @return {@code true} when this outcome is a redirect
         */
        public boolean isRedirect() {
            return status == FOUND;
        }
    }
}
