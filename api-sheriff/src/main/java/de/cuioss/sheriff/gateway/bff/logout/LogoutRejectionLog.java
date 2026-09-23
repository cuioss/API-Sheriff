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
package de.cuioss.sheriff.gateway.bff.logout;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The single emission point for {@code ApiSheriff-112} ({@code LOGOUT_TOKEN_REJECTED}), carrying the
 * flood policy the reserved, unauthenticated back-channel path requires.
 * <p>
 * <strong>Why this exists rather than a bare {@code LOGGER.warn}.</strong> Every rejection on the
 * back-channel path used to be recorded at {@code DEBUG}, which made the whole path invisible at the
 * default log level: a delivery that never arrived and a delivery that arrived and was rejected
 * produced byte-identical silence, and neither could be told apart without a {@code DEBUG} re-run
 * (BFF-2). Recording every rejection at {@code WARN} instead is not an option either — the path is
 * reserved and unauthenticated, so any caller who can reach the gateway could drive an unbounded
 * {@code WARN} flood by posting to it without a {@code logout_token}, a session, or any credential.
 * <p>
 * <strong>The rule, decided rather than forgotten.</strong> The two cases are split by
 * {@link LogoutRejection#isSignatureVerified()}:
 * <ul>
 *   <li><strong>Signature-verified rejections</strong> ({@code issuer-mismatch},
 *       {@code audience-mismatch}, {@code iat-outside-window}, {@code events-missing},
 *       {@code nonce-present}, {@code no-sub-or-sid}) are reachable only by a token the configured
 *       identity provider actually signed. An attacker cannot mint one, so there is no flood to
 *       bound: they are recorded at {@code WARN} on every occurrence.</li>
 *   <li><strong>Pre-signature rejections</strong> ({@code no-idp-destruction-capability},
 *       {@code missing-logout-token}, {@code signature-rejected}) are attacker-triggerable, so they
 *       are <em>latched</em>: the FIRST occurrence of each reason in a process is recorded at
 *       {@code WARN} and every repeat drops to {@code DEBUG}. The flood is therefore bounded to at
 *       most one line per reason for the lifetime of the process, while the first real occurrence
 *       still reaches the default log level — which is the whole point.</li>
 * </ul>
 * The latch follows the same shape as {@code SealedSessionCookieCodec}'s per-disposition latch, and it
 * carries the same caveat: absence of a repeated {@code WARN} says nothing about the rejection
 * <em>rate</em>, and an attacker who consumes a reason's latch early makes a later genuine occurrence
 * of that same reason {@code DEBUG}-only. Read the {@code DEBUG} channel for either question.
 * <p>
 * Instances are owned by the component whose {@link CuiLogger} they emit through, so the record is
 * attributed to the class that actually rejected the request. The latch is per instance, which means
 * per assembled runtime — the endpoint and the receiver each hold their own.
 * <p>
 * Thread-safe: the latch is a concurrent set and the logger is stateless.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutRejectionLog {

    private final CuiLogger logger;
    private final Set<LogoutRejection> latched = ConcurrentHashMap.newKeySet();

    /**
     * Binds the emitter to the owning component's logger, so {@code ApiSheriff-112} is attributed to
     * the class that rejected the request.
     *
     * @param logger the owning component's logger
     */
    public LogoutRejectionLog(CuiLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Records one back-channel logout rejection under the flood policy documented on this type.
     *
     * @param reason the bounded, non-sensitive rejection reason
     */
    public void recordRejection(LogoutRejection reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isSignatureVerified() || latched.add(reason)) {
            logger.warn(BffLogMessages.WARN.LOGOUT_TOKEN_REJECTED, reason.token());
            return;
        }
        logger.debug("Back-channel logout token rejected again (%s) — this reason has already been "
                + "reported once and stays at DEBUG for the rest of the process", reason.token());
    }
}
