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
 * @param refreshToken the refresh token, empty when the IdP granted none
 * @param idToken      the raw ID token retained for the logout {@code id_token_hint}
 * @param sub          the subject claim
 * @param sid          the IdP session id claim, empty when absent
 * @param acr          the authentication context class, empty when absent
 * @param authTime     the IdP authentication instant, empty when absent
 * @param loginInstant the absolute login instant anchoring the server-enforced session TTL
 * @author API Sheriff Team
 * @since 1.0
 */
public record SealedSessionPayload(String accessToken, Optional<String> refreshToken, String idToken,
String sub, Optional<String> sid, Optional<String> acr, Optional<Instant> authTime, Instant loginInstant) {

    private static final String REDACTED = "***REDACTED***";
    private static final char FIELD_SEPARATOR = '\n';
    private static final int FIELD_COUNT = 8;

    /**
     * Canonical constructor rejecting absent mandatory components and normalizing absent optionals
     * to {@link Optional#empty()}.
     */
    public SealedSessionPayload {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(idToken, "idToken");
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(loginInstant, "loginInstant");
        refreshToken = Objects.requireNonNullElse(refreshToken, Optional.empty());
        sid = Objects.requireNonNullElse(sid, Optional.empty());
        acr = Objects.requireNonNullElse(acr, Optional.empty());
        authTime = Objects.requireNonNullElse(authTime, Optional.empty());
    }

    /**
     * Serializes this payload into the compact wire form the codec seals.
     *
     * @return the UTF-8 bytes of the encoded payload
     */
    public byte[] encode() {
        StringBuilder encoded = new StringBuilder();
        appendField(encoded, accessToken);
        appendField(encoded, refreshToken.orElse(""));
        appendField(encoded, idToken);
        appendField(encoded, sub);
        appendField(encoded, sid.orElse(""));
        appendField(encoded, acr.orElse(""));
        appendField(encoded, authTime.map(instant -> Long.toString(instant.getEpochSecond())).orElse(""));
        appendField(encoded, Long.toString(loginInstant.getEpochSecond()));
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a payload back from the wire form. The input MUST already have been authenticated by the
     * codec's GCM tag — this method is not a parser for untrusted input.
     *
     * @param encoded the UTF-8 bytes produced by {@link #encode()}
     * @return the decoded payload; empty when the bytes do not carry the expected field shape
     *         (a defensive guard against a key that authenticates a foreign format)
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
                    optionalField(fields[1]),
                    decodeField(fields[2]),
                    decodeField(fields[3]),
                    optionalField(fields[4]),
                    optionalField(fields[5]),
                    optionalField(fields[6]).map(seconds -> Instant.ofEpochSecond(Long.parseLong(seconds))),
                    Instant.ofEpochSecond(Long.parseLong(decodeField(fields[7])))));
        } catch (IllegalArgumentException | DateTimeException _) {
            // Base64 decode failure, epoch-second parse failure, or an epoch second that parses as a
            // long but lies outside Instant's supported range, on bytes that authenticated: a
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
     * Overridden to redact every credential — the three tokens. The default record
     * {@code toString()} would otherwise print the raw token material into any log line, exception
     * message, or debugger view.
     *
     * @return a string representation with all credential-bearing fields redacted
     */
    @Override
    public String toString() {
        return "SealedSessionPayload[accessToken=%s, refreshToken=%s, idToken=%s, sub=%s, sid=%s, acr=%s, authTime=%s, loginInstant=%s]"
                .formatted(REDACTED, refreshToken.isPresent() ? "Optional[" + REDACTED + "]" : "Optional.empty",
                        REDACTED, sub, sid, acr, authTime, loginInstant);
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

    private static Optional<String> optionalField(String field) {
        if (field.isEmpty()) {
            return Optional.empty();
        }
        String decoded = decodeField(field);
        return decoded.isEmpty() ? Optional.empty() : Optional.of(decoded);
    }
}
