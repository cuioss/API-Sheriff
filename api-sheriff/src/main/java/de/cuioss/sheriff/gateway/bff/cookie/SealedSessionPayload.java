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
package de.cuioss.sheriff.gateway.bff.cookie;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The plaintext that {@link SealedSessionCookieCodec} seals into the cookie-mode session cookie
 * (D1, {@code session.mode: cookie}).
 * <p>
 * It carries the same material a server-mode session would hold in the store: the mediated access
 * token, the optional refresh token, and the raw ID token — the last is retained because a
 * stateless gateway has nowhere else to keep the {@code id_token_hint} RP-initiated logout needs —
 * plus the identity/session claims and the absolute {@link #loginInstant()} that anchors the
 * server-enforced TTL. The login instant is the anchor rather than an expiry so a re-seal can never
 * extend the session: the deadline is always recomputed from the original login.
 * <p>
 * <strong>Wire format.</strong> {@link #encode()} produces a compact, explicit, dependency-free
 * encoding: newline-separated fields in declaration order, each value URL-safe base64 of its UTF-8
 * bytes (so no field value can contain the separator), with an empty field standing for an absent
 * optional. It is an internal representation read only by {@link #decode(byte[])} after the GCM tag
 * has already authenticated the bytes — it is never parsed from unauthenticated input.
 * <p>
 * <strong>The plaintext never leaves the server unsealed.</strong> {@link #toString()} redacts every
 * credential-bearing component, mirroring {@code SessionRecord}.
 *
 * @param accessToken  the mediated access token injected as the upstream bearer
 * @param refreshToken the refresh token, {@code null} when the IdP granted none
 * @param idToken      the raw ID token retained for the logout {@code id_token_hint}
 * @param sub          the subject claim
 * @param sid          the IdP session id claim, {@code null} when absent
 * @param acr          the authentication context class, {@code null} when absent
 * @param authTime     the IdP authentication instant, {@code null} when absent
 * @param loginInstant the absolute login instant anchoring the server-enforced session TTL
 * @param sessionNonce the per-session random nonce minted once at login, folded into the derived
 *                     session identity so two logins by the same subject within one clock second
 *                     cannot collide. Re-sealed verbatim — never re-minted — so the identity is
 *                     stable for the life of the session
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
public record SealedSessionPayload(
String accessToken,
@Nullable String refreshToken,
String idToken,
String sub,
@Nullable String sid,
@Nullable String acr,
@Nullable Instant authTime,
Instant loginInstant,
String sessionNonce) {

    private static final String REDACTED = "***REDACTED***";
    private static final char FIELD_SEPARATOR = '\n';
    private static final int FIELD_COUNT = 9;

    /**
     * Canonical constructor rejecting absent mandatory components.
     * <p>
     * {@code sessionNonce} is additionally rejected when blank: it keys the derived session
     * identity, so an empty value would silently degrade that identity back to the colliding
     * pre-nonce shape instead of failing. The nonce value itself never reaches the exception
     * message.
     *
     * @throws NullPointerException     when a mandatory component is {@code null}
     * @throws IllegalArgumentException when {@code sessionNonce} is blank
     */
    public SealedSessionPayload {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(idToken, "idToken");
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(loginInstant, "loginInstant");
        Objects.requireNonNull(sessionNonce, "sessionNonce");
        if (sessionNonce.isBlank()) {
            throw new IllegalArgumentException("sessionNonce must not be blank");
        }
    }

    /**
     * Serializes this payload into the compact wire form the codec seals.
     *
     * @return the UTF-8 bytes of the encoded payload
     */
    public byte[] encode() {
        StringBuilder encoded = new StringBuilder();
        appendField(encoded, accessToken);
        appendField(encoded, refreshToken == null ? "" : refreshToken);
        appendField(encoded, idToken);
        appendField(encoded, sub);
        appendField(encoded, sid == null ? "" : sid);
        appendField(encoded, acr == null ? "" : acr);
        appendField(encoded, authTime == null ? "" : Long.toString(authTime.getEpochSecond()));
        appendField(encoded, Long.toString(loginInstant.getEpochSecond()));
        appendField(encoded, sessionNonce);
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a payload back from the wire form. The input MUST already have been authenticated by the
     * codec's GCM tag — this method is not a parser for untrusted input.
     *
     * The field-count guard is strict: only the current nine-field shape is accepted. There is no
     * legacy eight-field (pre-nonce) acceptance path — a clean break, so a payload predating the
     * session nonce is rejected outright rather than admitted with a synthesized nonce that would
     * silently change the derived session identity.
     *
     * @param encoded the UTF-8 bytes produced by {@link #encode()}
     * @return the decoded payload; empty when the bytes do not carry the expected field shape or
     *         carry a blank session nonce (a defensive guard against a key that authenticates a
     *         foreign format)
     */
    public static Optional<SealedSessionPayload> decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] fields = new String(encoded, StandardCharsets.UTF_8).split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length != FIELD_COUNT) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SealedSessionPayload(
                    decodeField(fields[0]),
                    nullableField(fields[1]),
                    decodeField(fields[2]),
                    decodeField(fields[3]),
                    nullableField(fields[4]),
                    nullableField(fields[5]),
                    epochSecondField(fields[6]),
                    Instant.ofEpochSecond(Long.parseLong(decodeField(fields[7]))),
                    decodeField(fields[8])));
        } catch (IllegalArgumentException | DateTimeException _) {
            // Base64 decode failure, epoch-second parse failure, an epoch second that parses as a
            // long but lies outside Instant's supported range, or a blank session nonce rejected by
            // the canonical constructor, on bytes that authenticated: a
            // foreign payload format under the same key. DateTimeException is NOT an
            // IllegalArgumentException, so it must be caught explicitly or it escapes unseal() as an
            // unhandled request error. Report "no session" rather than propagating.
            return Optional.empty();
        }
    }

    /**
     * Whether the session has reached its absolute deadline at {@code now} — the deadline is
     * inclusive, and is always computed from {@link #loginInstant()} so a re-seal cannot extend it.
     *
     * @param ttl the configured absolute session lifetime from login
     * @param now the reference instant
     * @return {@code true} when {@code now} is at or after the absolute deadline
     */
    public boolean isExpired(Duration ttl, Instant now) {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(now, "now");
        return !now.isBefore(loginInstant.plus(ttl));
    }

    /**
     * Overridden to redact every credential — the three tokens — plus the session nonce, which keys
     * the derived session identity and so is treated as secret material. The default record
     * {@code toString()} would otherwise print the raw token material into any log line, exception
     * message, or debugger view.
     *
     * @return a string representation with all credential-bearing fields redacted
     */
    @Override
    public String toString() {
        return "SealedSessionPayload[accessToken=%s, refreshToken=%s, idToken=%s, sub=%s, sid=%s, acr=%s, authTime=%s, loginInstant=%s, sessionNonce=%s]"
                .formatted(REDACTED, refreshToken == null ? "null" : REDACTED,
                        REDACTED, sub, sid, acr, authTime, loginInstant, REDACTED);
    }

    private static void appendField(StringBuilder target, String value) {
        if (!target.isEmpty()) {
            target.append(FIELD_SEPARATOR);
        }
        target.append(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String decodeField(String field) {
        return new String(Base64.getUrlDecoder().decode(field), StandardCharsets.UTF_8);
    }

    private static @Nullable String nullableField(String field) {
        if (field.isEmpty()) {
            return null;
        }
        String decoded = decodeField(field);
        return decoded.isEmpty() ? null : decoded;
    }

    private static @Nullable Instant epochSecondField(String field) {
        String seconds = nullableField(field);
        return seconds == null ? null : Instant.ofEpochSecond(Long.parseLong(seconds));
    }
}
