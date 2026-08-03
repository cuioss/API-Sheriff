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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec.CookieSizeBudgetExceededException;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec.Unsealed;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SealedSessionCookieCodec} — the AES-256-GCM crypto core of the stateless
 * cookie-mode BFF variant.
 * <p>
 * The security-relevant contracts under test are: round-trip fidelity; a fresh nonce per seal (two
 * seals of one payload never produce the same value); fail-closed unsealing for a flipped byte in
 * <em>each</em> of ciphertext / nonce / tag; the associated-data binding, so a value cannot be
 * replayed under a different cookie name, format version, or key id; the ~4 KB size budget failing
 * the seal rather than truncating; and the absence of key or token material from every emitted
 * header and {@code toString()}.
 * <p>
 * The codec holds exactly one key, so a value stamped with any other key id is refused at the
 * key-id gate before a cipher is constructed — there is no decrypt-only companion key and no
 * try-every-key fallback. That makes withdrawing a key a fail-closed clean break rather than a
 * staged rollover.
 * <p>
 * {@link BoundedRejectionLogging} additionally pins that the rejection WARN is latched per
 * disposition: the unseal path is per-request and pre-authentication, so an unbounded record there
 * would be a remotely-reachable log-amplification lever.
 */
@EnableTestLogger
class SealedSessionCookieCodecTest {

    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final String OTHER_COOKIE_NAME = "__Host-other-session";
    private static final Duration TTL = Duration.ofHours(8);
    private static final int BUDGET = SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET;
    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final byte KEY_ID = 1;
    private static final byte OTHER_KEY_ID = 2;

    /** The id a cookie sealed before a key change still carries — a generation the codec no longer holds. */
    private static final byte WITHDRAWN_KEY_ID = 7;
    private static final String ACCESS_TOKEN = "raw-access-token-SECRET-material";
    private static final String REFRESH_TOKEN = "raw-refresh-token-SECRET-material";
    private static final String ID_TOKEN = "raw-id-token-SECRET-material";
    private static final String SUB = "user-sub-1";
    private static final String SESSION_NONCE = "session-nonce-SECRET-material";

    private SecretKey key;
    private SealedSessionCookieCodec codec;

    @BeforeEach
    void setUp() {
        key = aesKey((byte) 0x11);
        codec = new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, key, KEY_ID);
    }

    private static SecretKey aesKey(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return new SecretKeySpec(material, "AES");
    }

    private static SealedSessionPayload payload() {
        return new SealedSessionPayload(ACCESS_TOKEN, REFRESH_TOKEN, ID_TOKEN, SUB,
                "idp-sid-9", "urn:acr:silver",
                Instant.parse("2026-07-27T09:59:00Z"), LOGIN, SESSION_NONCE);
    }

    private static String flipByteAt(String sealedValue, int index) {
        byte[] raw = Base64.getUrlDecoder().decode(sealedValue);
        raw[index] ^= (byte) 0xFF;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("Should unseal a sealed payload back to an equal payload")
        void shouldRoundTrip() throws Exception {
            SealedSessionPayload original = payload();

            String sealed = codec.seal(original);
            Optional<Unsealed> unsealed = codec.unseal(sealed);

            assertEquals(Optional.of(new Unsealed(original)), unsealed,
                    "the payload survives the round trip intact, authenticated by the sealing key");
        }

        @Test
        @DisplayName("Should draw a fresh nonce per seal, so two seals of one payload differ")
        void shouldUseAFreshNoncePerSeal() throws Exception {
            SealedSessionPayload original = payload();

            String first = codec.seal(original);
            String second = codec.seal(original);

            assertNotEquals(first, second,
                    "a fresh random nonce per seal is mandatory — GCM nonce reuse under one key is catastrophic");
            assertEquals(codec.unseal(first), codec.unseal(second), "both seals still unseal to the same payload");
        }

        @Test
        @DisplayName("Should carry the version and key id in the first two bytes of the value")
        void shouldCarryVersionAndKeyIdHeader() throws Exception {
            byte[] raw = Base64.getUrlDecoder().decode(codec.seal(payload()));

            assertEquals(SealedSessionCookieCodec.FORMAT_VERSION, raw[0]);
            assertEquals(KEY_ID, raw[1]);
        }

        @Test
        @DisplayName("Should stamp format version 2 — the nine-field payload shape")
        void shouldStampFormatVersionTwo() {
            assertEquals(SealedSessionCookieCodec.FORMAT_VERSION, (byte) 2,
                    "the per-session nonce raised the payload to nine fields, which is a wire-format break");
        }
    }

    @Nested
    @DisplayName("Fail-closed unsealing")
    class FailClosed {

        @Test
        @DisplayName("Should reject a flipped ciphertext byte as no session")
        void shouldRejectFlippedCiphertextByte() throws Exception {
            String sealed = codec.seal(payload());
            // Byte 14 is the first ciphertext byte: version(1) + key-id(1) + nonce(12).
            String tampered = flipByteAt(sealed, 14);

            assertTrue(codec.unseal(tampered).isEmpty(), "a tampered ciphertext is no session, never an error");
        }

        @Test
        @DisplayName("Should reject a flipped nonce byte as no session")
        void shouldRejectFlippedNonceByte() throws Exception {
            String sealed = codec.seal(payload());

            assertTrue(codec.unseal(flipByteAt(sealed, 2)).isEmpty(), "a tampered nonce fails the tag check");
        }

        @Test
        @DisplayName("Should reject a flipped tag byte as no session")
        void shouldRejectFlippedTagByte() throws Exception {
            String sealed = codec.seal(payload());
            int lastByte = Base64.getUrlDecoder().decode(sealed).length - 1;

            assertTrue(codec.unseal(flipByteAt(sealed, lastByte)).isEmpty(), "a tampered tag fails the tag check");
        }

        @Test
        @DisplayName("Should reject a value sealed for a different cookie name (AAD binding)")
        void shouldRejectCookieNameMismatch() throws Exception {
            String sealed = codec.seal(payload());
            SealedSessionCookieCodec otherName = new SealedSessionCookieCodec(OTHER_COOKIE_NAME, TTL, BUDGET, key, KEY_ID);

            assertTrue(otherName.unseal(sealed).isEmpty(),
                    "the cookie name is bound into the associated data, so a value cannot be replayed under another name");
        }

        @Test
        @DisplayName("Should reject an unknown format version")
        void shouldRejectUnknownVersion() throws Exception {
            String sealed = codec.seal(payload());
            byte[] raw = Base64.getUrlDecoder().decode(sealed);
            raw[0] = (byte) (SealedSessionCookieCodec.FORMAT_VERSION + 1);

            assertTrue(codec.unseal(Base64.getUrlEncoder().withoutPadding().encodeToString(raw)).isEmpty(),
                    "an unknown format version is refused before any decrypt attempt");
        }

        @Test
        @DisplayName("Should reject a cookie stamped with the retired version 1 format")
        void shouldRejectLegacyFormatVersionOne() throws Exception {
            String sealed = codec.seal(payload());
            byte[] raw = Base64.getUrlDecoder().decode(sealed);
            raw[0] = 1;

            assertTrue(codec.unseal(Base64.getUrlEncoder().withoutPadding().encodeToString(raw)).isEmpty(),
                    "the version bump to 2 is a clean break: a v1 cookie is refused outright rather than "
                            + "parsed against the nine-field payload shape");
        }

        @Test
        @DisplayName("Should reject an unknown key id without trying the key it does hold")
        void shouldRejectUnknownKeyId() throws Exception {
            SealedSessionCookieCodec otherKeyId = new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, key, OTHER_KEY_ID);
            String sealedUnderOtherId = otherKeyId.seal(payload());

            assertTrue(codec.unseal(sealedUnderOtherId).isEmpty(),
                    "the key-id gate is deterministic — never a try-every-key decrypt, even though this value "
                            + "happens to be sealed under the very key the codec holds");
        }

        @Test
        @DisplayName("Should treat a value sealed under a withdrawn key as no session, never an error")
        void shouldRejectValueSealedUnderWithdrawnKey() throws Exception {
            SecretKey withdrawnKey = aesKey((byte) 0x33);
            SealedSessionCookieCodec beforeTheKeyChange =
                    new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, withdrawnKey, WITHDRAWN_KEY_ID);
            String sealedUnderWithdrawnKey = beforeTheKeyChange.seal(payload());

            assertTrue(codec.unseal(sealedUnderWithdrawnKey).isEmpty(),
                    "with one key and no decrypt-only companion, a key change simply invalidates the "
                            + "outstanding cookies — the owner re-authenticates, the gateway never errors");
        }

        @Test
        @DisplayName("Should reject a value sealed under a different key")
        void shouldRejectForeignKey() throws Exception {
            SealedSessionCookieCodec foreign =
                    new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, aesKey((byte) 0x22), KEY_ID);
            String sealedUnderForeignKey = foreign.seal(payload());

            assertTrue(codec.unseal(sealedUnderForeignKey).isEmpty(), "a foreign key fails the tag check");
        }

        @Test
        @DisplayName("Should reject a value that is not base64 or is too short")
        void shouldRejectMalformedValue() {
            assertTrue(codec.unseal("not base64 ~~~").isEmpty());
            assertTrue(codec.unseal("AAAA").isEmpty(), "a value shorter than the header plus tag is malformed");
            assertTrue(codec.unseal("").isEmpty());
        }
    }

    @Nested
    @DisplayName("Size budget")
    class SizeBudget {

        @Test
        @DisplayName("Should refuse to seal a payload whose value exceeds the budget, never truncate")
        void shouldRefuseOversizedPayload() {
            String huge = "x".repeat(BUDGET * 2);
            SealedSessionPayload oversized = new SealedSessionPayload(huge, null, ID_TOKEN, SUB,
                    null, null, null, LOGIN, SESSION_NONCE);

            CookieSizeBudgetExceededException thrown =
                    assertThrows(CookieSizeBudgetExceededException.class, () -> codec.seal(oversized));

            assertEquals(BUDGET, thrown.budget());
            assertTrue(thrown.sealedLength() > thrown.budget(),
                    "the reported length is the value that would have been emitted");
        }

        @Test
        @DisplayName("Should seal a realistic payload well inside the budget")
        void shouldSealRealisticPayload() {
            assertDoesNotThrow(() -> codec.seal(payload()));
        }
    }

    @Nested
    @DisplayName("Set-Cookie hardening and lifetime")
    class SetCookieHeader {

        @Test
        @DisplayName("Should emit the landed hardening attributes")
        void shouldEmitHardenedAttributes() throws Exception {
            String header = codec.toSetCookieHeader(codec.seal(payload()), LOGIN, LOGIN);

            assertTrue(header.startsWith(COOKIE_NAME + "="), header);
            assertTrue(header.contains("Path=/"), header);
            assertTrue(header.contains("Secure"), header);
            assertTrue(header.contains("HttpOnly"), header);
            assertTrue(header.contains("SameSite=Lax"), header);
        }

        @Test
        @DisplayName("Should set Max-Age to the remaining lifetime, so a re-seal never extends the session")
        void shouldSetMaxAgeToRemainingLifetime() throws Exception {
            String sealed = codec.seal(payload());
            Instant halfway = LOGIN.plus(TTL.dividedBy(2));

            String atLogin = codec.toSetCookieHeader(sealed, LOGIN, LOGIN);
            String atHalfway = codec.toSetCookieHeader(sealed, LOGIN, halfway);

            assertTrue(atLogin.contains("Max-Age=" + TTL.toSeconds()), atLogin);
            assertTrue(atHalfway.contains("Max-Age=" + TTL.dividedBy(2).toSeconds()),
                    "a re-seal halfway through the session carries only the remaining lifetime: " + atHalfway);
        }

        @Test
        @DisplayName("Should clamp Max-Age at zero past the absolute deadline")
        void shouldClampMaxAgeAtZero() throws Exception {
            String header = codec.toSetCookieHeader(codec.seal(payload()), LOGIN, LOGIN.plus(TTL).plusSeconds(60));

            assertTrue(header.contains("Max-Age=0"), header);
        }

        @Test
        @DisplayName("Should clear the cookie with an immediate expiry")
        void shouldClearCookie() {
            String header = codec.toClearingSetCookieHeader();

            assertTrue(header.startsWith(COOKIE_NAME + "=;"), header);
            assertTrue(header.contains("Max-Age=0"), header);
        }
    }

    @Nested
    @DisplayName("Cookie header reading")
    class Reading {

        @Test
        @DisplayName("Should read the sealed value out of a Cookie header among other cookies")
        void shouldReadSealedValue() {
            String header = "other=1; " + COOKIE_NAME + "=sealed-value; another=2";

            assertEquals(Optional.of("sealed-value"), codec.readSealedValue(header));
        }

        @Test
        @DisplayName("Should read nothing from an absent, blank, empty-valued, or unrelated Cookie header")
        void shouldReadNothingWithoutTheCookie() {
            assertTrue(codec.readSealedValue(null).isEmpty());
            assertTrue(codec.readSealedValue("   ").isEmpty());
            assertTrue(codec.readSealedValue("other=1").isEmpty());
            assertTrue(codec.readSealedValue(COOKIE_NAME + "=").isEmpty());
        }
    }

    @Nested
    @DisplayName("No secret disclosure")
    class NoSecretDisclosure {

        @Test
        @DisplayName("Should keep token material out of the emitted Set-Cookie header")
        void shouldNotLeakTokensIntoTheHeader() throws Exception {
            String header = codec.toSetCookieHeader(codec.seal(payload()), LOGIN, LOGIN);

            assertFalse(header.contains(ACCESS_TOKEN), "the access token is sealed, never emitted in the clear");
            assertFalse(header.contains(REFRESH_TOKEN), "the refresh token is sealed, never emitted in the clear");
            assertFalse(header.contains(ID_TOKEN), "the ID token is sealed, never emitted in the clear");
        }

        @Test
        @DisplayName("Should keep key material out of the exception message on an oversized seal")
        void shouldNotLeakKeyMaterialIntoTheOverBudgetMessage() {
            String huge = "x".repeat(BUDGET * 2);
            SealedSessionPayload oversized = new SealedSessionPayload(huge, null, ID_TOKEN, SUB,
                    null, null, null, LOGIN, SESSION_NONCE);

            CookieSizeBudgetExceededException thrown =
                    assertThrows(CookieSizeBudgetExceededException.class, () -> codec.seal(oversized));

            assertFalse(thrown.getMessage().contains(huge), "the offending payload is never echoed");
            assertFalse(thrown.getMessage().contains(ID_TOKEN), "no token material appears in the message");
        }
    }

    /**
     * The rejection WARN is bounded: one catalogued record per disposition per codec, every repeat
     * at DEBUG.
     * <p>
     * {@code unseal} runs per request and is reached before the caller is authenticated, so an
     * unconditional WARN is a log-amplification lever any client can pull with a junk cookie — and
     * one that fires without any attacker for a whole re-authentication window after a key change,
     * because with a single sealing key every still-live cookie takes the {@code unknown-key-id}
     * branch on every one of its requests.
     */
    @Nested
    @DisplayName("Bounded rejection logging")
    class BoundedRejectionLogging {

        /** Far more repeats than any bound under test, so an unlatched record could not pass. */
        private static final int REPEATS = 50;

        private static final String MALFORMED = "malformed";
        private static final String UNKNOWN_KEY_ID = "unknown-key-id";
        private static final String AUTHENTICATION_TAG = "authentication-tag";

        @Test
        @DisplayName("Should record ONE WARN for a whole re-authentication window of withdrawn-key cookies")
        void shouldLatchTheUnknownKeyIdWarn() throws Exception {
            SealedSessionCookieCodec beforeTheKeyChange =
                    new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, aesKey((byte) 0x33), WITHDRAWN_KEY_ID);
            String sealedUnderWithdrawnKey = beforeTheKeyChange.seal(payload());

            for (int request = 0; request < REPEATS; request++) {
                assertTrue(codec.unseal(sealedUnderWithdrawnKey).isEmpty(), "every attempt is still 'no session'");
            }

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, UNKNOWN_KEY_ID);
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    BffLogMessages.WARN.COOKIE_UNSEAL_REJECTED.resolveIdentifierString());
        }

        @Test
        @DisplayName("Should record ONE WARN however many junk cookies an unauthenticated client sends")
        void shouldLatchTheMalformedWarnAgainstAnUnauthenticatedClient() {
            for (int request = 0; request < REPEATS; request++) {
                assertTrue(codec.unseal("not base64 ~~~").isEmpty(), "a junk cookie is 'no session'");
            }

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, MALFORMED);
        }

        @Test
        @DisplayName("Should still record each distinct disposition once, so no rejection class is silenced")
        void shouldRecordEachDispositionOnce() throws Exception {
            String tampered = flipByteAt(codec.seal(payload()), 14);

            for (int request = 0; request < REPEATS; request++) {
                codec.unseal("not base64 ~~~");
                codec.unseal(tampered);
            }

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, MALFORMED);
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, AUTHENTICATION_TAG);
        }
    }
}
