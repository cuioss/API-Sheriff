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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValueType;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import org.jspecify.annotations.Nullable;

/**
 * The pure OIDC back-channel-logout-token check pipeline (D2c residual, BFF-09).
 * <p>
 * The engine's issuer/JWKS infrastructure verifies the logout token's <em>signature</em> before it
 * reaches this validator (reuse of {@code token-sheriff-validation}, ADR-11); the library carries no
 * back-channel-logout support, so the gateway-side claim residual lives here. This validator is the
 * <strong>sole</strong> authority over a logout token's claims — the seam ahead of it verifies the
 * signature and nothing else, deliberately, because the
 * <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">OpenID Connect Back-Channel
 * Logout</a> claim set is not the ID-token claim set (no mandatory {@code sub}, no {@code azp}, a
 * {@code jti} and an {@code events} claim of its own). The pipeline applies the full back-channel
 * validation on the already-signature-verified token:
 * <ol>
 *   <li>a {@code typ} header, when the token carries one, is {@value #LOGOUT_TOKEN_TYPE} (compared
 *       without regard to case, with or without the {@code application/} prefix) — a token that
 *       carries no {@code typ} passes this check, a token typed as anything else is refused,</li>
 *   <li>{@code iss} equals the expected issuer,</li>
 *   <li>{@code aud} contains the expected audience (the confidential client id),</li>
 *   <li>{@code iat} is present and within a short freshness window (BFF-09),</li>
 *   <li>{@code exp} is present and lies after the reference instant — the specification lists
 *       {@code exp} among the required claims and validates it as for an ID token,</li>
 *   <li>{@code events} contains the {@value #BACKCHANNEL_LOGOUT_EVENT} member,</li>
 *   <li>{@code nonce} is <strong>absent</strong> (a nonce is prohibited in a logout token),</li>
 *   <li>at least one of {@code sub} / {@code sid} is present — {@code sid} alone is the shape
 *       {@code backchannel.logout.session.required=true} produces and is fully supported,</li>
 *   <li>{@code jti} is present — it is what lets the caller recognise a repeat of this token.</li>
 * </ol>
 * Any failure yields a {@link Verdict.Rejected} fail-closed — the token is rejected and no session is
 * touched. The verdict names the failing check as a bounded {@link LogoutRejection}, so the caller can
 * record <em>which</em> check refused without this validator deciding how loudly to say it. On success
 * the resolved {@code sub}/{@code sid} back the O(1) secondary-index destruction, and the verdict
 * carries the {@code jti} with the last instant at which this token would still pass the freshness
 * check — what {@link LogoutTokenReplayGuard} needs to refuse a repeat. The validator is
 * framework-agnostic and stateless, so every negative case is unit-testable without a live IdP;
 * whether a token was seen before is not its question.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutTokenValidator {

    /** The OIDC back-channel-logout event member the {@code events} claim must carry. */
    public static final String BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";

    private static final String CLAIM_ISS = "iss";
    private static final String CLAIM_SUB = "sub";
    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_AUD = "aud";
    private static final String CLAIM_IAT = "iat";
    private static final String CLAIM_EVENTS = "events";
    private static final String CLAIM_NONCE = "nonce";
    private static final String CLAIM_EXP = "exp";
    private static final String CLAIM_JTI = "jti";

    /** The explicit type a logout token's {@code typ} header carries. */
    public static final String LOGOUT_TOKEN_TYPE = "logout+jwt";

    /** The media-type prefix RFC 7515 allows a {@code typ} value to omit. */
    private static final String APPLICATION_PREFIX = "application/";

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
     * @param verified the signature-verified logout token with its {@code typ} header
     * @param now      the reference instant for the {@code iat} freshness and the {@code exp} check
     * @return {@link Verdict.Accepted} carrying the resolved {@code sub}/{@code sid} to destroy by
     *         and the token's {@code jti}, or {@link Verdict.Rejected} naming the failing check
     */
    public Verdict validate(VerifiedLogoutToken verified, Instant now) {
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(now, "now");

        if (!isLogoutTokenType(verified.headerType())) {
            return new Verdict.Rejected(LogoutRejection.TYPE_MISMATCH);
        }
        TokenContent logoutToken = verified.content();
        Optional<String> issuer = claim(logoutToken, CLAIM_ISS);
        if (issuer.isEmpty() || !expectedIssuer.equals(issuer.get())) {
            return new Verdict.Rejected(LogoutRejection.ISSUER_MISMATCH);
        }
        if (!audience(logoutToken).contains(expectedAudience)) {
            return new Verdict.Rejected(LogoutRejection.AUDIENCE_MISMATCH);
        }
        Optional<Instant> issuedAt = instantClaim(logoutToken, CLAIM_IAT).filter(iat -> isFresh(iat, now));
        if (issuedAt.isEmpty()) {
            return new Verdict.Rejected(LogoutRejection.IAT_OUTSIDE_WINDOW);
        }
        // The reference instant must lie before exp; an absent exp is refused like a passed one.
        if (instantClaim(logoutToken, CLAIM_EXP).filter(now::isBefore).isEmpty()) {
            return new Verdict.Rejected(LogoutRejection.EXPIRED);
        }
        if (claim(logoutToken, CLAIM_EVENTS).filter(LogoutTokenValidator::hasBackchannelLogoutEventMember).isEmpty()) {
            return new Verdict.Rejected(LogoutRejection.EVENTS_MISSING);
        }
        if (claim(logoutToken, CLAIM_NONCE).isPresent()) {
            return new Verdict.Rejected(LogoutRejection.NONCE_PRESENT);
        }
        String sub = claim(logoutToken, CLAIM_SUB).orElse(null);
        String sid = claim(logoutToken, CLAIM_SID).orElse(null);
        if (sub == null && sid == null) {
            return new Verdict.Rejected(LogoutRejection.NO_SUB_OR_SID);
        }
        Optional<String> jti = claim(logoutToken, CLAIM_JTI);
        if (jti.isEmpty()) {
            return new Verdict.Rejected(LogoutRejection.JTI_MISSING);
        }
        return new Verdict.Accepted(new LogoutSubject(sub, sid), jti.get(), issuedAt.get().plus(freshnessWindow));
    }

    /**
     * Whether a token's {@code typ} header admits it as a logout token. A token without the header
     * is admitted: the specification recommends the explicit type and does not require it. A header
     * that is present must name a logout token — RFC 7515 §4.1.9 compares the value as a media type,
     * without regard to case, and lets the {@code application/} prefix be omitted. A header that is
     * present and blank names no logout token and is refused.
     */
    private static boolean isLogoutTokenType(@Nullable String headerType) {
        if (headerType == null) {
            return true;
        }
        String type = headerType.strip();
        if (type.regionMatches(true, 0, APPLICATION_PREFIX, 0, APPLICATION_PREFIX.length())) {
            type = type.substring(APPLICATION_PREFIX.length());
        }
        return LOGOUT_TOKEN_TYPE.equalsIgnoreCase(type);
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

    private boolean isFresh(Instant issuedAt, Instant now) {
        return !issuedAt.isBefore(now.minus(freshnessWindow)) && !issuedAt.isAfter(now.plus(freshnessWindow));
    }

    /** Reads a date-typed claim; empty when the claim is absent or carries no date. */
    private static Optional<Instant> instantClaim(TokenContent token, String name) {
        ClaimValue value = token.getClaims().get(name);
        if (value == null || value.isNotPresentForClaimValueType()) {
            return Optional.empty();
        }
        OffsetDateTime dateTime = value.getDateTime();
        return dateTime == null ? Optional.empty() : Optional.of(dateTime.toInstant());
    }

    /**
     * Whether the (string-extracted) {@code events} claim is an <em>object</em> that carries the
     * {@value #BACKCHANNEL_LOGOUT_EVENT} URI as a member <em>key</em>, per
     * <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">OpenID Connect
     * Back-Channel Logout</a> §2.4.
     * <p>
     * <strong>Two textual forms reach this check, and both are real.</strong> The engine surfaces a
     * claim it has no {@code ClaimMapper} for as a plain string, and which string that is depends on
     * how the value arrived on the wire:
     * <ul>
     *   <li>A JSON <em>string</em> value round-trips verbatim, so an {@code events} claim serialized
     *       as text reaches us as JSON object syntax —
     *       {@code {"http://…/backchannel-logout":{}}}.</li>
     *   <li>A JSON <em>object</em> value — which is what the spec prescribes and what Keycloak
     *       actually sends — is deserialized into a {@code Map} and then stringified with
     *       {@code Object#toString()}, so it reaches us in <em>Java map</em> syntax:
     *       {@code {http://…/backchannel-logout={}}} — unquoted key, {@code =} instead of {@code :}.
     *       (See {@code TokenBuilder.extractClaims} together with
     *       {@code MapRepresentation#getString}: a non-{@code String} value falls through to the
     *       {@code toString()} branch.) Matching only the JSON form therefore rejected every
     *       spec-shaped logout token a real identity provider sends.</li>
     * </ul>
     * Both forms are accepted, and in both the URI must occupy a <em>key</em> position — a scalar or
     * array {@code events} value that merely <em>contains</em> the event URI is still rejected, because
     * a malformed or same-name scalar claim must never destroy a session. In JSON syntax the key is
     * quoted and followed (modulo whitespace) by the {@code :} name-separator; in Java map syntax it is
     * unquoted, followed by {@code =}, and preceded by the opening brace or an entry separator. A
     * value-position occurrence satisfies neither.
     *
     * @param events the events claim's original string, in either of the two forms above
     * @return {@code true} only when {@code events} is an object carrying the back-channel-logout key
     */
    private static boolean hasBackchannelLogoutEventMember(String events) {
        String trimmed = events.strip();
        // events MUST be an object; a scalar string or an array is rejected outright.
        if (trimmed.length() < 2 || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        return hasJsonMemberKey(trimmed) || hasMapMemberKey(trimmed);
    }

    /** The JSON object form: {@code "<uri>"} immediately followed, modulo whitespace, by {@code :}. */
    private static boolean hasJsonMemberKey(String events) {
        String quotedKey = '"' + BACKCHANNEL_LOGOUT_EVENT + '"';
        for (int at = events.indexOf(quotedKey); at >= 0; at = events.indexOf(quotedKey, at + 1)) {
            int cursor = at + quotedKey.length();
            while (cursor < events.length() && Character.isWhitespace(events.charAt(cursor))) {
                cursor++;
            }
            if (cursor < events.length() && events.charAt(cursor) == ':') {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code Map#toString()} form: {@code <uri>=} at an entry start — that is, immediately after the
     * opening brace or after an entry separator. The preceding-character test is what keeps a
     * value-position occurrence ({@code {x=…/backchannel-logout}}) out: there the URI is preceded by
     * {@code =} and followed by {@code }}, so neither side matches.
     */
    private static boolean hasMapMemberKey(String events) {
        for (int at = events.indexOf(BACKCHANNEL_LOGOUT_EVENT); at >= 0;
             at = events.indexOf(BACKCHANNEL_LOGOUT_EVENT, at + 1)) {
            int after = at + BACKCHANNEL_LOGOUT_EVENT.length();
            if (after >= events.length() || events.charAt(after) != '=') {
                continue;
            }
            int before = at - 1;
            while (before > 0 && Character.isWhitespace(events.charAt(before))) {
                before--;
            }
            char preceding = events.charAt(before);
            if (preceding == '{' || preceding == ',') {
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
     * The outcome of the claim residual: either the resolved subject to destroy by, or the single
     * bounded reason the token was refused. Modelled as a sealed pair rather than an
     * {@link Optional} so a rejection can name <em>which</em> check refused — that reason is what the
     * caller records as {@code ApiSheriff-112}, and without it every refusal is indistinguishable
     * from every other one at the log.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public sealed interface Verdict {

        /**
         * The token passed every check.
         *
         * @param subject         the resolved {@code sub}/{@code sid} to destroy by
         * @param jti             the token's {@code jti}
         * @param acceptableUntil the last instant at which this token would still pass the
         *                        {@code iat} freshness check — its {@code iat} plus the window
         */
        record Accepted(LogoutSubject subject, String jti, Instant acceptableUntil) implements Verdict {
        }

        /**
         * The token failed a check and nothing is destroyed.
         *
         * @param reason the bounded, non-sensitive reason naming the failing check
         */
        record Rejected(LogoutRejection reason) implements Verdict {
        }
    }

    /**
     * The subject a validated logout token resolves to — at least one of {@code sub}/{@code sid} is
     * present (the pipeline rejects a token carrying neither). {@code sid} drives the precise
     * single-session destruction; {@code sub} the all-sessions-for-subject destruction.
     *
     * @param sub the subject claim, {@code null} when the token carried only a {@code sid}
     * @param sid the IdP session id claim, {@code null} when the token carried only a {@code sub}
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record LogoutSubject(@Nullable String sub, @Nullable String sid) {
    }
}
