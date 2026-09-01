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
 * surface — the session lifecycle, transparent token refresh, CSRF defence, and logout events.
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
 * / …), a lifecycle event records only a non-sensitive reason or a bounded count. Exception-bearing
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
     * Info-level messages (INFO range 1-99; this catalogue owns 10-16).
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
    }

    /**
     * Warn-level messages (WARN range 100-199; this catalogue owns 110-114).
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
         * A transparent token refresh failed (IdP rejection or engine-detected refresh-token reuse)
         * and its session was destroyed; the caller re-authenticates. Records only a bounded,
         * non-sensitive reason — never the presented refresh token or session id.
         */
        public static final LogRecord SESSION_REFRESH_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(111)
                .template("Token refresh failed for a require:session route (%s) — session destroyed")
                .build();

        /**
         * A back-channel logout token was rejected. Records the non-sensitive rejection disposition
         * ({@code signature} / {@code claims}) only — never the raw logout token.
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
    }
}
