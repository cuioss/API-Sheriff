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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SealedSessionPayload} — the plaintext the cookie-mode codec seals. Covers the
 * {@code FORMAT_VERSION} 3 length-prefixed framing (its width, its endianness, and that field values
 * stay raw), the encoding round trip including absent optionals and values carrying bytes that would
 * have collided with the retired newline separator, every way the reader refuses a foreign frame,
 * the {@link SealedSessionPayload#MAX_PLAINTEXT_BYTES} bound at both ends, the absolute-TTL check
 * anchored on the login instant, the mandatory-component contract, and the redaction of every
 * credential from {@code toString()}.
 */
class SealedSessionPayloadTest {

    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(8);
    private static final String ACCESS_TOKEN = "raw-access-token-SECRET-material";
    private static final String REFRESH_TOKEN = "raw-refresh-token-SECRET-material";
    private static final String ID_TOKEN = "raw-id-token-SECRET-material";
    private static final String SUB = "user-sub-1";
    private static final String SESSION_NONCE = "session-nonce-SECRET-material";

    private static SealedSessionPayload full() {
        return new SealedSessionPayload(ACCESS_TOKEN, REFRESH_TOKEN, ID_TOKEN, SUB,
                "idp-sid-9", "urn:acr:silver",
                Instant.parse("2026-07-27T09:59:00Z"), LOGIN, SESSION_NONCE);
    }

    private static SealedSessionPayload minimal() {
        return new SealedSessionPayload(ACCESS_TOKEN, null, ID_TOKEN, SUB,
                null, null, null, LOGIN, SESSION_NONCE);
    }

    /** The 2-byte big-endian length prefix each field carries, times the nine fields. */
    private static final int FRAMING_BYTES = 18;

    /**
     * Builds the wire form {@code decode()} reads directly from raw field values, so a test can
     * express a payload shape {@code encode()} could never produce — a field count other than nine,
     * an epoch second outside {@link Instant}'s supported range, or a frame past
     * {@link SealedSessionPayload#MAX_PLAINTEXT_BYTES}.
     * <p>
     * It is written independently of the production encoder rather than by calling it, so a defect
     * in the framing cannot be inherited by the very tests that check the framing.
     */
    private static byte[] wireForm(String... fields) {
        byte[][] raw = Arrays.stream(fields)
                .map(field -> field.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        int total = 0;
        for (byte[] field : raw) {
            total += 2 + field.length;
        }
        ByteBuffer wire = ByteBuffer.allocate(total);
        for (byte[] field : raw) {
            wire.putShort((short) field.length);
            wire.put(field);
        }
        return wire.array();
    }

    /** The nine-field wire form with only the mandatory components populated. */
    private static byte[] minimalWireForm() {
        return wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "",
                Long.toString(LOGIN.getEpochSecond()), SESSION_NONCE);
    }

    @Nested
    @DisplayName("Encoding round trip")
    class RoundTrip {

        @Test
        @DisplayName("Should decode a fully-populated payload back to an equal record")
        void shouldRoundTripFullPayload() {
            SealedSessionPayload original = full();

            assertEquals(Optional.of(original), SealedSessionPayload.decode(original.encode()));
        }

        @Test
        @DisplayName("Should preserve absent components across the round trip")
        void shouldRoundTripAbsentComponents() {
            SealedSessionPayload original = minimal();

            SealedSessionPayload decoded = SealedSessionPayload.decode(original.encode()).orElseThrow();

            assertEquals(original, decoded);
            assertNull(decoded.refreshToken());
            assertNull(decoded.sid());
            assertNull(decoded.acr());
            assertNull(decoded.authTime());
            assertEquals(SESSION_NONCE, decoded.sessionNonce(),
                    "the session nonce is mandatory and survives the round trip verbatim");
        }

        @Test
        @DisplayName("Should round-trip values containing the retired separator and other structural characters")
        void shouldRoundTripSeparatorBearingValues() {
            SealedSessionPayload original = new SealedSessionPayload("token\nwith\nnewlines", "a=b;c",
                    ID_TOKEN, "sub\nwith\nnewline", "sid\n1", null, null, LOGIN,
                    "nonce\nwith\nnewline");

            assertEquals(Optional.of(original), SealedSessionPayload.decode(original.encode()),
                    "each field's length is stated up front, so no value can be confused with a delimiter — "
                            + "which is what lets the field bytes stay raw instead of being base64-armoured");
        }

        @Test
        @DisplayName("Should decode nothing from bytes carrying a foreign field shape")
        void shouldDecodeNothingFromForeignShape() {
            assertTrue(SealedSessionPayload.decode("not-the-expected-shape".getBytes(StandardCharsets.UTF_8)).isEmpty());
            assertTrue(SealedSessionPayload.decode(new byte[0]).isEmpty());
        }

        @Test
        @DisplayName("Should decode nothing when a declared field length runs past the buffer")
        void shouldDecodeNothingFromOverrunningLength() {
            byte[] wellFormed = minimalWireForm();
            byte[] truncated = Arrays.copyOf(wellFormed, wellFormed.length - 1);

            assertTrue(SealedSessionPayload.decode(wellFormed).isPresent(),
                    "positive control: the untruncated frame decodes, so the truncation is what the "
                            + "negative case below actually exercises");
            assertTrue(SealedSessionPayload.decode(truncated).isEmpty(),
                    "a length prefix promising more bytes than remain is a foreign shape, never a partial read");
        }

        @Test
        @DisplayName("Should decode nothing when a length prefix itself is truncated")
        void shouldDecodeNothingFromTruncatedLengthPrefix() {
            byte[] halfAPrefix = {0, 1, 'a', 0};

            assertTrue(SealedSessionPayload.decode(halfAPrefix).isEmpty(),
                    "the reader needs two whole bytes before it can trust a length at all");
        }

        @Test
        @DisplayName("Should decode nothing when bytes trail the ninth field")
        void shouldDecodeNothingFromTrailingBytes() {
            byte[] wellFormed = minimalWireForm();
            byte[] withTrailer = Arrays.copyOf(wellFormed, wellFormed.length + 1);

            assertTrue(SealedSessionPayload.decode(withTrailer).isEmpty(),
                    "the buffer must be consumed exactly — a tenth field smuggled behind the ninth is a "
                            + "foreign shape, not surplus to be ignored");
        }

        @Test
        @DisplayName("Should decode nothing from a legacy eight-field payload — no backward-compatible path")
        void shouldDecodeNothingFromLegacyEightFieldPayload() {
            byte[] legacy = wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "",
                    Long.toString(LOGIN.getEpochSecond()));

            assertTrue(SealedSessionPayload.decode(legacy).isEmpty(),
                    "the pre-nonce eight-field shape is rejected outright: admitting it would require "
                            + "synthesizing a nonce and would silently change the derived session identity");
        }

        @Test
        @DisplayName("Should decode nothing from a version-2 newline-joined, per-field-base64 payload")
        void shouldDecodeNothingFromVersionTwoFraming() {
            byte[] versionTwo = Arrays.stream(new String[]{ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "",
                    Long.toString(LOGIN.getEpochSecond()), SESSION_NONCE})
                    .map(field -> Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(field.getBytes(StandardCharsets.UTF_8)))
                    .reduce((left, right) -> left + "\n" + right)
                    .orElseThrow()
                    .getBytes(StandardCharsets.UTF_8);

            assertTrue(SealedSessionPayload.decode(versionTwo).isEmpty(),
                    "the retired framing has no acceptance path: its leading bytes are base64 text, which "
                            + "reads as a length prefix promising far more than the buffer holds");
        }

        @Test
        @DisplayName("Should decode nothing when an epoch second parses as a long but exceeds Instant's range")
        void shouldDecodeNothingFromOutOfRangeEpochSecond() {
            String beyondInstantMax = "999999999999999999";
            String beyondInstantMin = "-999999999999999999";
            String login = Long.toString(LOGIN.getEpochSecond());

            assertTrue(SealedSessionPayload
                            .decode(wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "", beyondInstantMax,
                                    SESSION_NONCE)).isEmpty(),
                    "DateTimeException is not an IllegalArgumentException, so an out-of-range login instant "
                            + "must still decode to no session rather than escaping unseal()");
            assertTrue(SealedSessionPayload
                    .decode(wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "", beyondInstantMin, SESSION_NONCE))
                    .isEmpty());
            assertTrue(SealedSessionPayload
                            .decode(wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", beyondInstantMax, login,
                                    SESSION_NONCE)).isEmpty(),
                    "the optional authTime field carries the same overflow risk as the login instant");
        }
    }

    @Nested
    @DisplayName("Length-prefixed framing")
    class Framing {

        @Test
        @DisplayName("Should cost exactly two bytes of framing per field and nothing else")
        void shouldCostTwoBytesOfFramingPerField() {
            byte[] encoded = minimal().encode();
            int valueBytes = ACCESS_TOKEN.length() + ID_TOKEN.length() + SUB.length()
                    + Long.toString(LOGIN.getEpochSecond()).length() + SESSION_NONCE.length();

            assertEquals(valueBytes + FRAMING_BYTES, encoded.length,
                    "nine 2-byte prefixes are the whole framing cost: absent optionals contribute their "
                            + "prefix and no value bytes, and there is no separator or padding on top");
        }

        @Test
        @DisplayName("Should write each length as a big-endian unsigned 16-bit prefix")
        void shouldWriteBigEndianLengthPrefixes() {
            byte[] encoded = minimal().encode();

            assertEquals(ACCESS_TOKEN.length(), Short.toUnsignedInt(ByteBuffer.wrap(encoded).getShort()),
                    "read little-endian instead, a 32-byte first field would measure 8192 — the byte "
                            + "order is part of the wire contract, not an implementation detail");
        }

        @Test
        @DisplayName("Should keep field values as raw UTF-8 rather than base64-armouring them")
        void shouldKeepFieldValuesRaw() {
            String encoded = new String(minimal().encode(), StandardCharsets.UTF_8);

            assertTrue(encoded.contains(ACCESS_TOKEN),
                    "the retired per-field base64 armouring is exactly the 4/3 expansion this format "
                            + "removed, so the raw bytes must appear verbatim");
            assertTrue(encoded.contains(SESSION_NONCE), encoded);
        }

        @Test
        @DisplayName("Should refuse a frame past the plaintext bound while reading one exactly at it")
        void shouldBoundThePlaintextItReads() {
            String login = Long.toString(LOGIN.getEpochSecond());
            int fixed = wireForm(ACCESS_TOKEN, "", "", SUB, "", "", "", login, SESSION_NONCE).length;
            int headroom = SealedSessionPayload.MAX_PLAINTEXT_BYTES - fixed;
            byte[] atBound = wireForm(ACCESS_TOKEN, "", "x".repeat(headroom), SUB, "", "", "", login,
                    SESSION_NONCE);
            byte[] pastBound = wireForm(ACCESS_TOKEN, "", "x".repeat(headroom + 1), SUB, "", "", "", login,
                    SESSION_NONCE);

            assertEquals(SealedSessionPayload.MAX_PLAINTEXT_BYTES, atBound.length);
            assertTrue(SealedSessionPayload.decode(atBound).isPresent(),
                    "positive control: the same frame one byte smaller is well-formed and is read normally, "
                            + "so the bound — not a malformation — is what refuses the one below");
            assertTrue(SealedSessionPayload.decode(pastBound).isEmpty(),
                    "an authenticated-but-corrupt buffer must not drive an allocation past the bound");
        }

        @Test
        @DisplayName("Should refuse to encode past the bound rather than narrow a length into its prefix")
        void shouldRefuseToEncodePastThePlaintextBound() {
            SealedSessionPayload oversized = new SealedSessionPayload(
                    "x".repeat(SealedSessionPayload.MAX_PLAINTEXT_BYTES), null, ID_TOKEN, SUB,
                    null, null, null, LOGIN, SESSION_NONCE);

            IllegalStateException refusal = assertThrows(IllegalStateException.class, oversized::encode);

            assertTrue(refusal.getMessage()
                            .contains(Integer.toString(SealedSessionPayload.MAX_PLAINTEXT_BYTES)),
                    refusal.getMessage());
        }
    }

    @Nested
    @DisplayName("Absolute lifetime")
    class AbsoluteLifetime {

        @Test
        @DisplayName("Should not be expired before the deadline")
        void shouldNotBeExpiredBeforeDeadline() {
            assertFalse(full().isExpired(TTL, LOGIN.plus(TTL).minusSeconds(1)));
        }

        @Test
        @DisplayName("Should be expired at and after the deadline (inclusive boundary)")
        void shouldBeExpiredAtAndAfterDeadline() {
            assertTrue(full().isExpired(TTL, LOGIN.plus(TTL)), "the boundary is inclusive");
            assertTrue(full().isExpired(TTL, LOGIN.plus(TTL).plusSeconds(1)));
        }

        @Test
        @DisplayName("Should anchor the deadline on the login instant, so it cannot drift")
        void shouldAnchorDeadlineOnLoginInstant() {
            SealedSessionPayload resealed = new SealedSessionPayload("rotated-access", "rotated-refresh",
                    ID_TOKEN, SUB, null, null, null, LOGIN, SESSION_NONCE);

            assertTrue(resealed.isExpired(TTL, LOGIN.plus(TTL)),
                    "re-sealing rotated material does not move the absolute deadline");
        }
    }

    @Nested
    @DisplayName("Contract")
    class Contract {

        @Test
        @DisplayName("Should reject an absent mandatory component")
        void shouldRejectAbsentMandatoryComponents() {
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(null, null, ID_TOKEN,
                    SUB, null, null, null, LOGIN, SESSION_NONCE));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, null,
                    null, SUB, null, null, null, LOGIN, SESSION_NONCE));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, null,
                    ID_TOKEN, null, null, null, null, LOGIN, SESSION_NONCE));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, null,
                    ID_TOKEN, SUB, null, null, null, null, SESSION_NONCE));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, null,
                            ID_TOKEN, SUB, null, null, null, LOGIN, null),
                    "the session nonce is mandatory — it keys the derived session identity");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t", "\n", "   "})
        @DisplayName("Should reject a blank session nonce")
        void shouldRejectBlankSessionNonce(String blank) {
            assertThrows(IllegalArgumentException.class, () -> new SealedSessionPayload(ACCESS_TOKEN,
                            null, ID_TOKEN, SUB, null, null, null,
                            LOGIN, blank),
                    "a blank nonce would silently degrade the derived identity to the colliding pre-nonce shape");
        }

        @Test
        @DisplayName("Should keep the rejected nonce out of the exception message")
        void shouldNotLeakNonceIntoRejectionMessage() {
            IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                    () -> new SealedSessionPayload(ACCESS_TOKEN, null, ID_TOKEN, SUB, null,
                            null, null, LOGIN, "   "));

            assertEquals("sessionNonce must not be blank", rejection.getMessage());
        }

        @Test
        @DisplayName("Should decode nothing from authenticated bytes carrying a blank session nonce")
        void shouldDecodeNothingFromBlankNonce() {
            byte[] blankNonce = wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "",
                    Long.toString(LOGIN.getEpochSecond()), "");

            assertTrue(SealedSessionPayload.decode(blankNonce).isEmpty(),
                    "the constructor rejection surfaces as no session, never as an escaping exception");
        }

        @Test
        @DisplayName("Should accept an absent nullable component as null")
        void shouldAcceptNullNullableComponents() {
            SealedSessionPayload payload = new SealedSessionPayload(ACCESS_TOKEN, null, ID_TOKEN, SUB,
                    null, null, null, LOGIN, SESSION_NONCE);

            assertNull(payload.refreshToken());
            assertNull(payload.sid());
            assertNull(payload.acr());
            assertNull(payload.authTime());
        }
    }

    @Nested
    @DisplayName("Redaction")
    class Redaction {

        @Test
        @DisplayName("Should redact every credential from toString()")
        void shouldRedactCredentials() {
            String rendered = full().toString();

            assertFalse(rendered.contains(ACCESS_TOKEN), rendered);
            assertFalse(rendered.contains(REFRESH_TOKEN), rendered);
            assertFalse(rendered.contains(ID_TOKEN), rendered);
            assertFalse(rendered.contains(SESSION_NONCE),
                    "the session nonce keys the derived identity and must not reach a log line");
            assertTrue(rendered.contains("***REDACTED***"), rendered);
        }

        @Test
        @DisplayName("Should distinguish an absent refresh token from a redacted present one")
        void shouldDistinguishAbsentRefreshToken() {
            assertTrue(minimal().toString().contains("refreshToken=null"), minimal().toString());
            assertTrue(full().toString().contains("refreshToken=***REDACTED***"), full().toString());
        }

        @Test
        @DisplayName("Should still render the non-credential session metadata for diagnosis")
        void shouldRenderNonCredentialMetadata() {
            String rendered = full().toString();

            assertTrue(rendered.contains(SUB), "the subject is an identity anchor, not a credential");
            assertTrue(rendered.contains(LOGIN.toString()), rendered);
        }
    }
}
