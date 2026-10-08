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
package de.cuioss.sheriff.gateway.bff;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * DSL-style {@link LogRecord} catalogue for the Backend-for-Frontend {@code require: session}
 * surface — the session lifecycle, transparent token refresh, CSRF defence, logout events, and the
 * confidential client's own key material.
 * <p>
 * Structured {@code INFO} (1-99) and {@code WARN} (100-199) messages carry the shared
 * {@code ApiSheriff} prefix and a stable numeric identifier, so they are greppable and assertable.
 * Identifiers are allocated across every catalogue sharing the {@code ApiSheriff} prefix, not per
 * class, and that allocation is enforced by {@code LogMessagesCatalogueTest} rather than by an
 * inventory kept here by hand.
 * <p>
 * <strong>No sensitive data is logged.</strong> Session subjects ({@code sub}), IdP session ids
 * ({@code sid}), token material, and raw offending values never appear in a template: a rejection
 * records only its non-sensitive <em>disposition</em> ({@code untrusted-origin} / {@code signature}
 * / …). Exception-bearing
 * {@code WARN}s pass the throwable first, per the CUI logging contract; callers must not attach a
 * raw, unsanitized IdP exception whose message could re-inject untrusted content. {@code DEBUG} /
 * {@code TRACE} diagnostics use the logger directly and are not catalogued here.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class BffLogMessages {

    private static final String PREFIX = "ApiSheriff";

    /**
     * Info-level messages (INFO range 1-99; this catalogue owns 10-16, 20 and 21).
     */
    @UtilityClass
    public static final class INFO {

        /** A server-side session was established after a successful IdP login (require: session). */
        public static final LogRecord SESSION_CREATED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(10)
                .template("Server-side session established for a require:session route")
                .build();

        /**
         * A server-side session was destroyed. The reason is a bounded, non-sensitive disposition
         * ({@code logout} / {@code backchannel-logout} / {@code expiry} / {@code refresh-failure}) —
         * never the session id, subject, or IdP {@code sid}.
         */
        public static final LogRecord SESSION_DESTROYED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(11)
                .template("Server-side session destroyed (%s)")
                .build();

        /** The mediated tokens were transparently refreshed for a require:session route. */
        public static final LogRecord TOKEN_REFRESHED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(12)
                .template("Mediated tokens refreshed for a require:session route (single-flight)")
                .build();

        /**
         * A back-channel logout token was accepted and its affected sessions were destroyed. The
         * template carries only the bounded destroyed-session count — never the subject or {@code sid}.
         * <p>
         * <strong>{@code 0 session(s) destroyed} is a real and distinct outcome, not a non-event.</strong>
         * It says delivery, signature verification and the whole claim residual all succeeded, and that
         * the {@code sid} (or {@code sub}) the token named matched no session the gateway holds — which
         * is the one signal that separates a session-binding defect from a delivery or validation one.
         * Read it as such; do not treat it as interchangeable with a non-zero count.
         */
        public static final LogRecord BACKCHANNEL_LOGOUT = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(13)
                .template("Back-channel logout accepted — %s session(s) destroyed")
                .build();

        /** An RP-initiated logout completed its return leg for a require:session route. */
        public static final LogRecord RP_LOGOUT_COMPLETED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(14)
                .template("RP-initiated logout completed for a require:session route")
                .build();

        /**
         * A cookie-mode session was sealed into its {@code Set-Cookie}. The template carries only
         * the bounded sealed-value length — never the sealed value, the key, or any token material.
         */
        public static final LogRecord COOKIE_SESSION_SEALED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(15)
                .template("Cookie-mode session sealed (%s bytes)")
                .build();

        /**
         * No {@code encryption_key} was configured, so a cookie-mode sealing key was generated at
         * startup. Records only the non-sensitive fact and the affected scope — never key material.
         */
        public static final LogRecord COOKIE_KEY_GENERATED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(16)
                .template("Cookie-mode sealing key generated at startup (%s) — sessions do not survive a restart")
                .build();

        /**
         * A live session was widened: the identity provider granted the scopes a route needed, and
         * the grant was merged into the same session (same identity, same absolute expiry). The
         * template carries only the sorted names of the scopes the widening added to the session's
         * granted set — never a token, the session id or the subject.
         */
        public static final LogRecord SESSION_WIDENED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(20)
                .template("Live session widened for a require:session route — scopes added: %s")
                .build();

        /**
         * No {@code key_file} was configured for one of the confidential client's signing keys, so
         * the key was generated at startup. The first substitution is the bounded purpose label
         * ({@code client-authentication} / {@code sender-constraint}), the second the mode's
         * diagnostic name. Records only those two non-sensitive facts — never key material, and
         * never the key id.
         */
        public static final LogRecord SIGNING_KEY_GENERATED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(21)
                .template("Signing key for %s generated at startup (%s) — the key is not shared with other instances and is replaced on every restart")
                .build();
    }

    /**
     * Warn-level messages (WARN range 100-199; this catalogue owns 110-114, 127, 130-132, 134 and 135).
     */
    @UtilityClass
    public static final class WARN {

        /**
         * The fixed CSRF defence rejected an unsafe-method session request. Records the non-sensitive
         * rejection disposition ({@code untrusted-origin} / {@code no-origin-proof}) only — never the
         * raw offending {@code Origin} value.
         */
        public static final LogRecord CSRF_REJECTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(110)
                .template("CSRF defence rejected an unsafe-method session request: %s")
                .build();

        /**
         * A transparent token refresh ended with its session destroyed; the caller
         * re-authenticates. The record carries exactly one bounded, non-sensitive reason. The
         * reasons are defined by {@code de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator}
         * and described in the {@code ApiSheriff-111} entry of {@code doc/LogMessages.adoc}. A
         * failure before the provider processed the grant never destroys the session and is
         * recorded as {@link #SESSION_REFRESH_DEFERRED} instead. Never records the presented
         * refresh token or session id.
         */
        public static final LogRecord SESSION_REFRESH_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(111)
                .template("Token refresh failed for a require:session route (%s) — session destroyed")
                .build();

        /**
         * A back-channel logout token was rejected. Records the non-sensitive rejection disposition
         * only — never the raw logout token, the subject, or the IdP {@code sid}. The accepted set of
         * dispositions is closed and enumerated by
         * {@code de.cuioss.sheriff.gateway.bff.logout.LogoutRejection}: {@code no-idp-destruction-capability},
         * {@code missing-logout-token}, {@code signature-rejected}, {@code type-mismatch},
         * {@code issuer-mismatch}, {@code audience-mismatch}, {@code iat-outside-window},
         * {@code expired}, {@code events-missing}, {@code nonce-present}, {@code no-sub-or-sid},
         * {@code jti-missing}, {@code replayed}, {@code replay-memory-full}.
         * <p>
         * <strong>Latched per disposition.</strong> The back-channel path is reserved and
         * unauthenticated, so the record is emitted only on the FIRST occurrence of each disposition
         * per emitter and every repeat drops to {@code DEBUG}. Absence of a repeated {@code WARN}
         * therefore says nothing about the rejection <em>rate</em>; read the DEBUG channel for that.
         * The rule and its rationale live on
         * {@code de.cuioss.sheriff.gateway.bff.logout.LogoutRejectionLog}.
         */
        public static final LogRecord LOGOUT_TOKEN_REJECTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(112)
                .template("Back-channel logout token rejected: %s")
                .build();

        /**
         * A sealed session cookie failed to unseal and was treated as "no session". Records the
         * non-sensitive rejection disposition ({@code malformed} / {@code unknown-version} /
         * {@code unknown-key-id} / {@code authentication-tag} / {@code payload-format}) only —
         * never the offending cookie value or any key material.
         * <p>
         * <strong>Latched per disposition.</strong> The emitting path is reached per request and
         * pre-authentication, so the record is emitted only on the FIRST occurrence of each
         * disposition in a process and every repeat drops to {@code DEBUG} — see
         * {@code SealedSessionCookieCodec.reject}. Absence of a repeated {@code WARN} therefore
         * says nothing about the rejection <em>rate</em>; read the DEBUG channel for that.
         */
        public static final LogRecord COOKIE_UNSEAL_REJECTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(113)
                .template("Sealed session cookie rejected: %s — further rejections with this disposition stay at DEBUG")
                .build();

        /**
         * A sealed session cookie exceeded the browser-safe size budget, so the seal failed rather
         * than emitting a value the browser would silently drop. Records only the bounded length.
         */
        public static final LogRecord COOKIE_SIZE_BUDGET_EXCEEDED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(114)
                .template("Sealed session cookie exceeds the size budget (%s bytes) — seal refused")
                .build();

        /**
         * A transparent token refresh failed before the identity provider processed the grant — a
         * connection or DNS failure, a {@code 5xx}, or a {@code 4xx} not attributed to the
         * credential — so the presented refresh token is still valid and the session is kept. The
         * session's next attempt waits out the fixed back-off, which bounds this record during an
         * identity-provider outage to one per session per window while the per-instance back-off map
         * has room, and — once that map is saturated — to one per shared overflow window for all the
         * sessions it could not track. Records only the back-off in seconds — never the presented
         * refresh token or session id.
         */
        public static final LogRecord SESSION_REFRESH_DEFERRED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(127)
                .template("Token refresh failed before the identity provider processed it — session kept, next attempt in %s seconds")
                .build();

        /**
         * A session widening was refused because the identity the widening grant names differs from
         * the live session's (or from the one the widening was issued for). The session is left
         * unchanged and is never swapped to another identity. The template carries no value at all —
         * never a token, the session id or either subject.
         */
        public static final LogRecord SESSION_WIDENING_IDENTITY_MISMATCH = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(130)
                .template("Session widening refused — the granted identity differs from the live session; session unchanged")
                .build();

        /**
         * A session widening was refused and its grant was not merged into a session. The refusal
         * is terminal. The template carries only a bounded reason token — never the raw IdP error
         * description, a token, the session id or the subject. The reason tokens are defined by
         * {@code de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint} and described in the
         * {@code ApiSheriff-131} entry of {@code doc/LogMessages.adoc}.
         */
        public static final LogRecord SESSION_WIDENING_REFUSED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(131)
                .template("Session widening refused (%s) — no grant was merged into a session")
                .build();

        /**
         * The identity provider answered a token request with success, and the gateway refused the
         * response because the token is not bound to the gateway's DPoP proof key: the login or the
         * widening is refused, or the session is ended.
         * <p>
         * The first substitution is the leg, a closed set mapped from the request's
         * {@code grant_type} by an allow-list: {@code code-exchange} (the callback of a login and of
         * a session widening alike), {@code refresh} (the near-expiry and the scope-driven refresh
         * alike) or {@code other}. The second is the reason, a closed set: {@code token-type} (the response's
         * {@code token_type} is not {@code DPoP}), {@code unreadable-access-token} (the access token
         * is absent, is not a compact JWS with a parsable JSON payload, or could not be read for any
         * other reason), {@code cnf-absent} (the access token carries no {@code cnf.jkt}) or
         * {@code cnf-mismatch} (its {@code cnf.jkt} names another key).
         * <p>
         * <strong>Never carries token material.</strong> Neither the access token, the refresh
         * token, the ID token, a claim value, the received {@code jkt} nor the received token type
         * appears in the record — both substitutions are fixed tokens chosen by the gateway.
         * <p>
         * <strong>Not latched.</strong> The refusal is reached only on a success answer of the token
         * endpoint, which takes an authorization code or a refresh token the identity provider
         * issued. A caller without such a credential cannot reach it, so the record is no
         * log-amplification lever and is emitted on every occurrence (ADR-0051).
         */
        public static final LogRecord TOKEN_RESPONSE_NOT_BOUND = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(134)
                .template("Token response on the %s leg refused — the token is not bound to the gateway's DPoP proof key (%s)")
                .build();

        /**
         * An authorization request could not be pushed to the identity provider, so the login, the
         * session widening or the RFC 9470 step-up re-drive was refused with {@code 502} before
         * anything was stored and before a cookie was set. The only substitution is the reason, a closed set: {@code no-par-endpoint}
         * (the provider metadata advertises no {@code pushed_authorization_request_endpoint}),
         * {@code invalid-request} (the engine-built authorization request could not be split into
         * its parameters, or names one parameter twice) or {@code push-failed} (the push itself
         * failed: a transport failure, a timeout, a non-success answer, an unparsable answer or an
         * answer without a {@code request_uri}). Never records the authorization URL, a parameter
         * value or the {@code request_uri}.
         * <p>
         * The template names the one consequence the callers share — no redirect to the identity
         * provider was issued — and names none of them: the login, the session widening and the
         * step-up re-drive push through the same adapter instance, so the record cannot tell which
         * one was refused.
         * <p>
         * <strong>Latched per reason.</strong> The login-initiation path is reachable without a
         * credential, so the record is emitted only on the FIRST occurrence of each reason and every
         * repeat drops to {@code DEBUG} (ADR-0051) — see
         * {@code de.cuioss.sheriff.gateway.bff.login.PushedAuthorizationRequests}. Absence of a
         * repeated {@code WARN} therefore says nothing about the refusal <em>rate</em>; read the
         * DEBUG channel for that.
         */
        public static final LogRecord AUTHORIZATION_PUSH_REFUSED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(132)
                .template("Pushed authorization request refused: %s — no redirect to the identity provider was issued; further refusals with this reason stay at DEBUG")
                .build();

        /**
         * An RP-initiated logout found that the identity provider publishes no usable
         * {@code end_session_endpoint} — none at all, a blank one, or one that is not an absolute
         * {@code http} or {@code https} URI. The gateway's own session was ended and its cookies
         * were cleared, and the browser was sent to {@code final_redirect}; no end-session redirect
         * was sent, so the session at the identity provider is still open. The template takes no
         * substitution and therefore carries no value at all.
         * <p>
         * Provider metadata that could not be obtained is a different, transient condition and is
         * not recorded here: that logout is completed locally in the same way and leaves a
         * {@code DEBUG} line.
         * <p>
         * <strong>Latched.</strong> The condition is a property of the provider's metadata and
         * repeats on every logout, so the record is emitted on its FIRST occurrence in a process
         * and every repeat drops to {@code DEBUG} — see
         * {@code de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout}.
         */
        public static final LogRecord NO_END_SESSION_ENDPOINT = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(135)
                .template("The identity provider publishes no usable end_session_endpoint — logout ends the gateway session only and the session at the identity provider stays open; further occurrences stay at DEBUG")
                .build();
    }
}
