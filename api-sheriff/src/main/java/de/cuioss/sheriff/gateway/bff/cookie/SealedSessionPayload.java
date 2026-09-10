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
package de.cuioss.sheriff.gateway.bff.cookie;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
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
 * encoding: the nine fields in declaration order, each written as a 2-byte big-endian unsigned
 * length followed by its raw UTF-8 bytes ({@value #FIELD_COUNT} fields = {@value #FRAMING_BYTES}
 * bytes of framing), with a zero length standing for an absent optional. Length prefixes rather
 * than a separator character are what let the value bytes stay <em>raw</em>: nothing in a field can
 * be confused with a delimiter, so no per-field base64 armouring — and the 4/3 expansion it costs —
 * is needed. That expansion is the reason the format changed; the sealed value is base64url-encoded
 * exactly once, for transport, by the codec.
 * <p>
 * It is an internal representation read only by {@link #decode(byte[])} after the GCM tag has
 * already authenticated the bytes — it is never parsed from unauthenticated input. The reader is
 * nonetheless bounded by {@link #MAX_PLAINTEXT_BYTES} so an authenticated-but-corrupt buffer cannot
 * drive an unbounded allocation.
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

    /**
     * The largest encoded plaintext this format admits, in bytes — an <em>allocation</em> bound, not
     * a business limit.
     * <p>
     * {@link #decode(byte[])} refuses a buffer past it, and the codec's bounded inflate stops there
     * too, so a corrupt-but-authenticated compressed stream cannot expand without limit. The real
     * size gate stays where it always was: the seal-time cookie-value budget, which rejects a
     * session that does not fit the browser long before this bound is anywhere near.
     * <p>
     * It also keeps every field inside its 2-byte length prefix <em>by construction</em>, so no
     * separate per-field guard is needed: with {@value #FRAMING_BYTES} bytes of framing always
     * present, a single field can never exceed {@code MAX_PLAINTEXT_BYTES - FRAMING_BYTES}, which is
     * below the 65535 a 2-byte unsigned length can carry.
     */
    public static final int MAX_PLAINTEXT_BYTES = 64 * 1024;

    private static final String REDACTED = "***REDACTED***";
    private static final int FIELD_COUNT = 9;
    private static final int LENGTH_PREFIX_BYTES = 2;
    private static final int FRAMING_BYTES = FIELD_COUNT * LENGTH_PREFIX_BYTES;

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
     * Serializes this payload into the compact wire form the codec compresses and seals.
     *
     * @return the length-prefixed raw UTF-8 bytes of the encoded payload
     * @throws IllegalStateException when the framed payload would exceed
     *         {@link #MAX_PLAINTEXT_BYTES} — refused rather than written, because a length silently
     *         narrowed into its 2-byte prefix would produce a frame {@link #decode(byte[])} reads
     *         back as different material
     */
    public byte[] encode() {
        byte[][] fields = {
                utf8(accessToken),
                utf8(refreshToken),
                utf8(idToken),
                utf8(sub),
                utf8(sid),
                utf8(acr),
                utf8(authTime == null ? null : Long.toString(authTime.getEpochSecond())),
                utf8(Long.toString(loginInstant.getEpochSecond())),
                utf8(sessionNonce)
        };
        int total = FRAMING_BYTES;
        for (byte[] field : fields) {
            total += field.length;
        }
        if (total > MAX_PLAINTEXT_BYTES) {
            throw new IllegalStateException("sealed session plaintext is %d bytes, over the %d byte bound"
                    .formatted(total, MAX_PLAINTEXT_BYTES));
        }
        ByteBuffer encoded = ByteBuffer.allocate(total);
        for (byte[] field : fields) {
            encoded.putShort((short) field.length);
            encoded.put(field);
        }
        return encoded.array();
    }

    /**
     * Reads a payload back from the wire form. The input MUST already have been authenticated by the
     * codec's GCM tag — this method is not a parser for untrusted input.
     *
     * The field-count guard is strict: only the current nine-field shape is accepted, and the buffer
     * must be consumed exactly — trailing bytes after the ninth field are a foreign shape and are
     * refused. There is no legacy acceptance path for any earlier framing: a clean break, so a
     * payload predating this format is rejected outright rather than admitted with synthesized
     * components that would silently change the derived session identity.
     *
     * @param encoded the length-prefixed UTF-8 bytes produced by {@link #encode()}
     * @return the decoded payload; empty when the bytes do not carry the expected field shape,
     *         exceed {@link #MAX_PLAINTEXT_BYTES}, or carry a blank session nonce (a defensive guard
     *         against a key that authenticates a foreign format)
     */
    public static Optional<SealedSessionPayload> decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_PLAINTEXT_BYTES) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        String[] fields = new String[FIELD_COUNT];
        for (int index = 0; index < FIELD_COUNT; index++) {
            if (buffer.remaining() < LENGTH_PREFIX_BYTES) {
                return Optional.empty();
            }
            int length = Short.toUnsignedInt(buffer.getShort());
            if (buffer.remaining() < length) {
                return Optional.empty();
            }
            byte[] value = new byte[length];
            buffer.get(value);
            fields[index] = new String(value, StandardCharsets.UTF_8);
        }
        if (buffer.hasRemaining()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SealedSessionPayload(
                    fields[0],
                    nullableField(fields[1]),
                    fields[2],
                    fields[3],
                    nullableField(fields[4]),
                    nullableField(fields[5]),
                    epochSecondField(fields[6]),
                    Instant.ofEpochSecond(Long.parseLong(fields[7])),
                    fields[8]));
        } catch (IllegalArgumentException | DateTimeException _) {
            // Epoch-second parse failure, an epoch second that parses as a long but lies outside
            // Instant's supported range, or a blank session nonce rejected by the canonical
            // constructor, on bytes that authenticated: a foreign payload format under the same key.
            // DateTimeException is NOT an IllegalArgumentException, so it must be caught explicitly
            // or it escapes unseal() as an unhandled request error. Report "no session" rather than
            // propagating.
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

    private static byte[] utf8(@Nullable String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static @Nullable String nullableField(String field) {
        return field.isEmpty() ? null : field;
    }

    private static @Nullable Instant epochSecondField(String field) {
        return field.isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(field));
    }
}
