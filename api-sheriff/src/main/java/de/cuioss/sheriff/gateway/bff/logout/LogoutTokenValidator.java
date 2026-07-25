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
package de.cuioss.sheriff.gateway.bff.logout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValueType;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The pure OIDC back-channel-logout-token check pipeline (D2c residual, BFF-09).
 * <p>
 * The engine's issuer/JWKS infrastructure verifies the logout token's <em>signature</em> before it
 * reaches this validator (reuse of {@code token-sheriff-validation}, ADR-11); the library carries no
 * back-channel-logout support, so the gateway-side claim residual lives here. The pipeline applies
 * the full <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">OpenID Connect
 * Back-Channel Logout</a> validation on the already-signature-verified token:
 * <ol>
 *   <li>{@code iss} equals the expected issuer,</li>
 *   <li>{@code aud} contains the expected audience (the confidential client id),</li>
 *   <li>{@code iat} is present and within a short freshness window (the replay guard, BFF-09),</li>
 *   <li>{@code events} contains the {@value #BACKCHANNEL_LOGOUT_EVENT} member,</li>
 *   <li>{@code nonce} is <strong>absent</strong> (a nonce is prohibited in a logout token),</li>
 *   <li>at least one of {@code sub} / {@code sid} is present.</li>
 * </ol>
 * Any failure returns {@link Optional#empty()} fail-closed — the token is rejected and no session is
 * touched. On success the resolved {@code sub}/{@code sid} back the O(1) secondary-index destruction.
 * The validator is framework-agnostic and stateless, so every negative case is unit-testable without
 * a live IdP.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutTokenValidator {

    private static final CuiLogger LOGGER = new CuiLogger(LogoutTokenValidator.class);

    /** The OIDC back-channel-logout event member the {@code events} claim must carry. */
    public static final String BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";

    private static final String CLAIM_ISS = "iss";
    private static final String CLAIM_SUB = "sub";
    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_AUD = "aud";
    private static final String CLAIM_IAT = "iat";
    private static final String CLAIM_EVENTS = "events";
    private static final String CLAIM_NONCE = "nonce";

    private final String expectedIssuer;
    private final String expectedAudience;
    private final Duration freshnessWindow;

    /**
     * Creates a validator bound to the confidential client's issuer, audience, and replay-guard window.
     *
     * @param expectedIssuer   the OIDC issuer the logout token's {@code iss} must equal
     * @param expectedAudience the audience (the client id) the token's {@code aud} must contain
     * @param freshnessWindow  the symmetric {@code iat} freshness window — the token is rejected when
     *                         {@code iat} is older than, or further in the future than, this window
     */
    public LogoutTokenValidator(String expectedIssuer, String expectedAudience, Duration freshnessWindow) {
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer");
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience");
        this.freshnessWindow = Objects.requireNonNull(freshnessWindow, "freshnessWindow");
    }

    /**
     * Runs the full logout-token check pipeline over an already-signature-verified token.
     *
     * @param logoutToken the signature-verified logout token
     * @param now         the reference instant for the {@code iat} freshness check
     * @return the resolved {@code sub}/{@code sid} to destroy by, or empty when any check fails
     */
    public Optional<LogoutSubject> validate(TokenContent logoutToken, Instant now) {
        Objects.requireNonNull(logoutToken, "logoutToken");
        Objects.requireNonNull(now, "now");

        Optional<String> issuer = claim(logoutToken, CLAIM_ISS);
        if (issuer.isEmpty() || !expectedIssuer.equals(issuer.get())) {
            LOGGER.debug("Back-channel logout token rejected — issuer mismatch");
            return Optional.empty();
        }
        if (!audience(logoutToken).contains(expectedAudience)) {
            LOGGER.debug("Back-channel logout token rejected — audience does not contain the client id");
            return Optional.empty();
        }
        if (!isFresh(logoutToken, now)) {
            LOGGER.debug("Back-channel logout token rejected — iat missing or outside the freshness window");
            return Optional.empty();
        }
        if (claim(logoutToken, CLAIM_EVENTS).filter(LogoutTokenValidator::hasBackchannelLogoutEventMember).isEmpty()) {
            LOGGER.debug("Back-channel logout token rejected — events is not a JSON object carrying the back-channel-logout member");
            return Optional.empty();
        }
        if (claim(logoutToken, CLAIM_NONCE).isPresent()) {
            LOGGER.debug("Back-channel logout token rejected — a nonce is prohibited in a logout token");
            return Optional.empty();
        }
        Optional<String> sub = claim(logoutToken, CLAIM_SUB);
        Optional<String> sid = claim(logoutToken, CLAIM_SID);
        if (sub.isEmpty() && sid.isEmpty()) {
            LOGGER.debug("Back-channel logout token rejected — neither sub nor sid present");
            return Optional.empty();
        }
        return Optional.of(new LogoutSubject(sub, sid));
    }

    private static List<String> audience(TokenContent token) {
        ClaimValue aud = token.getClaims().get(CLAIM_AUD);
        if (aud == null || aud.isNotPresentForClaimValueType()) {
            return List.of();
        }
        if (aud.getType() == ClaimValueType.STRING_LIST) {
            return aud.getAsList();
        }
        String original = aud.getOriginalString();
        return original == null || original.isBlank() ? List.of() : List.of(original);
    }

    private boolean isFresh(TokenContent token, Instant now) {
        ClaimValue iat = token.getClaims().get(CLAIM_IAT);
        if (iat == null || iat.isNotPresentForClaimValueType()) {
            return false;
        }
        Instant issuedAt = iat.getDateTime().toInstant();
        return !issuedAt.isBefore(now.minus(freshnessWindow)) && !issuedAt.isAfter(now.plus(freshnessWindow));
    }

    /**
     * Whether the (string-extracted) {@code events} claim is a JSON <em>object</em> that carries the
     * {@value #BACKCHANNEL_LOGOUT_EVENT} URI as a member <em>key</em>, per
     * <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">OpenID Connect
     * Back-Channel Logout</a> §2.4. The engine surfaces a JSON-object claim as its serialized text, so
     * the structural check here rejects a scalar or array {@code events} value that merely
     * <em>contains</em> the event URI — a malformed or same-name scalar claim must never destroy a
     * session. The URI must appear as a quoted key immediately followed (modulo whitespace) by the
     * {@code :} name-separator; a value-position occurrence ({@code "x":"…backchannel-logout"}) is not
     * followed by {@code :} and is therefore rejected.
     *
     * @param events the events claim's original string (the object's serialized JSON text)
     * @return {@code true} only when {@code events} is a JSON object with the back-channel-logout key
     */
    private static boolean hasBackchannelLogoutEventMember(String events) {
        String trimmed = events.strip();
        // events MUST be a JSON object; a scalar string or a JSON array is rejected outright.
        if (trimmed.length() < 2 || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        String quotedKey = '"' + BACKCHANNEL_LOGOUT_EVENT + '"';
        for (int at = trimmed.indexOf(quotedKey); at >= 0; at = trimmed.indexOf(quotedKey, at + 1)) {
            int cursor = at + quotedKey.length();
            while (cursor < trimmed.length() && Character.isWhitespace(trimmed.charAt(cursor))) {
                cursor++;
            }
            if (cursor < trimmed.length() && trimmed.charAt(cursor) == ':') {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> claim(TokenContent token, String name) {
        ClaimValue value = token.getClaims().get(name);
        if (value == null || value.isNotPresentForClaimValueType()) {
            return Optional.empty();
        }
        String original = value.getOriginalString();
        return original == null || original.isBlank() ? Optional.empty() : Optional.of(original);
    }

    /**
     * The subject a validated logout token resolves to — at least one of {@code sub}/{@code sid} is
     * present (the pipeline rejects a token carrying neither). {@code sid} drives the precise
     * single-session destruction; {@code sub} the all-sessions-for-subject destruction.
     *
     * @param sub the subject claim, empty when the token carried only a {@code sid}
     * @param sid the IdP session id claim, empty when the token carried only a {@code sub}
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LogoutSubject(Optional<String> sub, Optional<String> sid) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public LogoutSubject {
            sub = Objects.requireNonNullElse(sub, Optional.empty());
            sid = Objects.requireNonNullElse(sid, Optional.empty());
        }
    }
}
