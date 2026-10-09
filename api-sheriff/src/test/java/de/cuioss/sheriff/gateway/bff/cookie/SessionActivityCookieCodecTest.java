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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SessionActivityCookieCodec} — the HMAC-SHA-256 codec of the cookie-mode activity
 * cookie.
 * <p>
 * The contracts under test are: round-trip fidelity; the binding to one session, whose identity is
 * authenticated but not carried in the value; a deterministic value, so that presenting one activity
 * cookie repeatedly consumes nothing; fail-closed verification for a value that is tampered with in the
 * last access or the tag, truncated, signed for another cookie name, under another key, under another
 * key id or in another format version; the separation from the sealed session cookie in both
 * directions; the exact attribute set of the {@code Set-Cookie}; the clamp of a last access in the
 * future to the reference instant; and the fixed length of the {@code name=value} pair, which is what
 * the pre-route {@code Cookie} header cap has to leave room for.
 */
@EnableTestLogger
@Tag("isolated-fork")
class SessionActivityCookieCodecTest {

    private static final String COOKIE_NAME = SessionCookieCodec.DEFAULT_COOKIE_NAME;
    private static final String ACTIVITY_COOKIE_NAME = COOKIE_NAME + "-activity";
    private static final String OTHER_COOKIE_NAME = "__Host-other-session";
    private static final byte KEY_ID = 1;
    private static final byte OTHER_KEY_ID = 2;
    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final Instant LAST_ACCESS = NOW.minusSeconds(90);
    private static final String SESSION_IDENTITY = identity((byte) 0x0a);
    private static final String OTHER_SESSION_IDENTITY = identity((byte) 0x0b);
    /** The length of the base64url encoding of the 42 signed bytes, without padding. */
    private static final int SIGNED_VALUE_CHARS = 56;

    private SecretKey key;
    private SessionActivityCookieCodec codec;

    @BeforeEach
    void setUp() {
        key = macKey((byte) 0x44);
        codec = new SessionActivityCookieCodec(COOKIE_NAME, key, KEY_ID);
    }

    private static SecretKey macKey(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return new SecretKeySpec(material, "HmacSHA256");
    }

    /** A derived session identity in the binding's encoding: 16 bytes, base64url without padding. */
    private static String identity(byte fill) {
        byte[] identity = new byte[16];
        Arrays.fill(identity, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(identity);
    }

    /** Flips the lowest bit of one byte of a signed value and re-encodes it. */
    private static String withFlippedByte(String signedValue, int index) {
        byte[] raw = Base64.getUrlDecoder().decode(signedValue);
        raw[index] ^= 0x01;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Signs a well-formed activity value under the codec's key, cookie name and key id but with the
     * given format version in the header and in the authenticated data, so the tag is right for that
     * version and only the version gate can refuse the value.
     */
    private String signWithVersion(byte version) throws Exception {
        byte[] name = ACTIVITY_COOKIE_NAME.getBytes(StandardCharsets.UTF_8);
        long lastAccessMillis = LAST_ACCESS.toEpochMilli();
        byte[] authenticated = ByteBuffer.allocate(name.length + 2 + 16 + Long.BYTES)
                .put(name).put(version).put(KEY_ID).put(Base64.getUrlDecoder().decode(SESSION_IDENTITY))
                .putLong(lastAccessMillis).array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        byte[] value = ByteBuffer.allocate(42).put(version).put(KEY_ID).putLong(lastAccessMillis)
                .put(mac.doFinal(authenticated)).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Nested
    @DisplayName("Sign and verify")
    class RoundTrip {

        @Test
        @DisplayName("Should read back the last access it signed for the session")
        void shouldRoundTrip() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertEquals(Optional.of(LAST_ACCESS), codec.verify(signed, SESSION_IDENTITY, NOW));
        }

        @Test
        @DisplayName("Should refuse a value presented with another session than the one it was signed for")
        void shouldBindToTheSessionIdentity() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(codec.verify(signed, OTHER_SESSION_IDENTITY, NOW).isEmpty(),
                    "an activity cookie moved to another session does not keep that session alive");
            assertNotEquals(signed, codec.sign(OTHER_SESSION_IDENTITY, LAST_ACCESS),
                    "the session identity is part of what the tag authenticates");
        }

        @Test
        @DisplayName("Should not carry the session identity in the value")
        void shouldNotCarryTheSessionIdentity() {
            byte[] raw = Base64.getUrlDecoder().decode(codec.sign(SESSION_IDENTITY, LAST_ACCESS));
            byte[] identity = Base64.getUrlDecoder().decode(SESSION_IDENTITY);

            // Layout: version(0) key-id(1) last-access(2..9) tag(10..41).
            assertEquals(LAST_ACCESS.toEpochMilli(), ByteBuffer.wrap(raw, 2, Long.BYTES).getLong(),
                    "the only payload is the last access");
            for (int offset = 0; offset + identity.length <= raw.length; offset++) {
                assertNotEquals(-1, Arrays.mismatch(identity,
                                Arrays.copyOfRange(raw, offset, offset + identity.length)),
                        "the identity does not appear at offset " + offset);
            }
        }

        @Test
        @DisplayName("Should give the same value for the same session and instant, so a repeated request consumes nothing")
        void shouldBeDeterministic() {
            assertEquals(codec.sign(SESSION_IDENTITY, LAST_ACCESS), codec.sign(SESSION_IDENTITY, LAST_ACCESS),
                    "no random input goes into a value, so no per-key budget is spent by signing");
            assertNotEquals(codec.sign(SESSION_IDENTITY, LAST_ACCESS),
                    codec.sign(SESSION_IDENTITY, LAST_ACCESS.plusMillis(1)), "another instant is another value");
        }

        @Test
        @DisplayName("Should count a last access in the future as the reference instant")
        void shouldClampAFutureLastAccess() {
            String signedAhead = codec.sign(SESSION_IDENTITY, NOW.plus(Duration.ofHours(1)));

            assertEquals(Optional.of(NOW), codec.verify(signedAhead, SESSION_IDENTITY, NOW),
                    "a value from a clock running ahead is worth no more than an access now");
        }

        @Test
        @DisplayName("Should refuse a session identity that is not 16 bytes wide, on signing and on verifying")
        void shouldRefuseAnIdentityOfAnotherWidth() {
            String tooWide = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertThrows(IllegalArgumentException.class, () -> codec.sign(tooWide, LAST_ACCESS));
            assertThrows(IllegalArgumentException.class, () -> codec.verify(signed, tooWide, NOW));
        }
    }

    @Nested
    @DisplayName("Fail-closed verification")
    class FailClosed {

        // Layout: version(0) key-id(1) last-access(2..9) tag(10..41).
        @ParameterizedTest(name = "a flipped bit in byte {0} verifies to nothing")
        @ValueSource(ints = {2, 9, 10, 41})
        @DisplayName("Should verify a tampered last access or tag to nothing")
        void shouldRefuseATamperedValue(int index) {
            String tampered = withFlippedByte(codec.sign(SESSION_IDENTITY, LAST_ACCESS), index);

            assertTrue(codec.verify(tampered, SESSION_IDENTITY, NOW).isEmpty());
        }

        @Test
        @DisplayName("Should verify a truncated, an over-long and a non-base64 value to nothing")
        void shouldRefuseAMalformedValue() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(codec.verify(signed.substring(0, signed.length() - 4), SESSION_IDENTITY, NOW).isEmpty(),
                    "truncated");
            assertTrue(codec.verify(signed + "AAAA", SESSION_IDENTITY, NOW).isEmpty(), "over-long");
            assertTrue(codec.verify("not base64url ~~~", SESSION_IDENTITY, NOW).isEmpty(), "not base64url");
        }

        @Test
        @DisplayName("Should verify a value signed for another cookie name to nothing")
        void shouldRefuseAnotherCookieName() {
            SessionActivityCookieCodec otherName = new SessionActivityCookieCodec(OTHER_COOKIE_NAME, key, KEY_ID);

            assertTrue(codec.verify(otherName.sign(SESSION_IDENTITY, LAST_ACCESS), SESSION_IDENTITY, NOW).isEmpty(),
                    "the cookie name is part of the authenticated data");
        }

        @Test
        @DisplayName("Should verify a value signed under another key to nothing, with the same key id or another")
        void shouldRefuseAnotherKey() {
            SecretKey otherKey = macKey((byte) 0x55);
            SessionActivityCookieCodec sameId = new SessionActivityCookieCodec(COOKIE_NAME, otherKey, KEY_ID);
            SessionActivityCookieCodec otherId = new SessionActivityCookieCodec(COOKIE_NAME, otherKey, OTHER_KEY_ID);

            assertTrue(codec.verify(sameId.sign(SESSION_IDENTITY, LAST_ACCESS), SESSION_IDENTITY, NOW).isEmpty(),
                    "another key under the same id fails the authentication tag");
            assertTrue(codec.verify(otherId.sign(SESSION_IDENTITY, LAST_ACCESS), SESSION_IDENTITY, NOW).isEmpty(),
                    "another key id is refused at the key-id gate");
        }

        @Test
        @DisplayName("Should verify a value stamped with another key id to nothing, even under the same key")
        void shouldRefuseAnotherKeyId() {
            SessionActivityCookieCodec otherId = new SessionActivityCookieCodec(COOKIE_NAME, key, OTHER_KEY_ID);

            assertTrue(codec.verify(otherId.sign(SESSION_IDENTITY, LAST_ACCESS), SESSION_IDENTITY, NOW).isEmpty());
        }

        @Test
        @DisplayName("Should verify a value of another format version to nothing, although its tag is right")
        void shouldRefuseAnotherVersion() throws Exception {
            byte nextVersion = (byte) (SessionActivityCookieCodec.FORMAT_VERSION + 1);

            assertTrue(codec.verify(signWithVersion(nextVersion), SESSION_IDENTITY, NOW).isEmpty());
            assertTrue(codec.verify(signWithVersion(SessionActivityCookieCodec.FORMAT_VERSION), SESSION_IDENTITY, NOW)
                            .isPresent(),
                    "the same hand-signed value in the current version verifies, so only the version refused it");
        }

        @Test
        @DisplayName("Should record a rejection at DEBUG only — a junk cookie is not a log-amplification lever")
        void shouldNotLogARejectionAboveDebug() {
            // DEBUG is captured for this class so the positive control below can see the record at all.
            TestLogLevel.DEBUG.addLogger(SessionActivityCookieCodec.class);

            codec.verify("not base64url ~~~", SESSION_IDENTITY, NOW);
            codec.verify(withFlippedByte(codec.sign(SESSION_IDENTITY, LAST_ACCESS), 41), SESSION_IDENTITY, NOW);

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG, "Session activity cookie ignored");
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO, SessionActivityCookieCodec.class);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, SessionActivityCookieCodec.class);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.ERROR, SessionActivityCookieCodec.class);
        }
    }

    @Nested
    @DisplayName("Separation from the sealed session cookie")
    class SeparationFromSessionCookie {

        private static final Duration TTL = Duration.ofHours(8);

        /** The session codec under the very same key bytes and key id, so only the format keeps the two apart. */
        private SealedSessionCookieCodec sessionCodec() {
            return new SealedSessionCookieCodec(COOKIE_NAME, TTL, SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET,
                    new SecretKeySpec(key.getEncoded(), "AES"), KEY_ID);
        }

        @Test
        @DisplayName("Should verify a sealed session cookie value presented as an activity cookie to nothing")
        void shouldRefuseASessionCookieValue() throws Exception {
            String sealedSession = sessionCodec().seal(new SealedSessionPayload("access", null, "id-token",
                    "user-sub-1", null, null, null, NOW, "session-nonce", Set.of("openid"), Set.of("openid")));

            assertTrue(codec.verify(sealedSession, SESSION_IDENTITY, NOW).isEmpty());
        }

        @Test
        @DisplayName("Should unseal an activity cookie value presented as a session cookie to nothing")
        void shouldBeRefusedAsASessionCookie() {
            String signedActivity = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(sessionCodec().unseal(signedActivity).isEmpty());
        }
    }

    @Nested
    @DisplayName("Cookie header forms")
    class CookieHeaders {

        @Test
        @DisplayName("Should name the cookie after the session cookie with the -activity suffix")
        void shouldNameTheCookie() {
            assertEquals(ACTIVITY_COOKIE_NAME, codec.cookieName());
            assertTrue(codec.cookieName().startsWith("__Host-"), "a suffix keeps the __Host- prefix");
        }

        @Test
        @DisplayName("Should sign to 56 characters, so name=value under the default cookie name is 88 bytes")
        void shouldPinThePairLength() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertEquals(SIGNED_VALUE_CHARS, signed.length(), "42 signed bytes, base64url without padding");
            String pair = codec.cookieName() + "=" + signed;
            assertEquals(88, pair.getBytes(StandardCharsets.UTF_8).length,
                    "the pair a browser sends back under the default cookie name");
        }

        @Test
        @DisplayName("Should emit exactly name, value, Max-Age of the remaining lifetime, Path, Secure, HttpOnly and SameSite=Lax")
        void shouldEmitTheHardenedSetCookie() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            String header = codec.toSetCookieHeader(signed, NOW.plusSeconds(1234), NOW);

            assertEquals(ACTIVITY_COOKIE_NAME + "=" + signed + "; Max-Age=1234; Path=/; Secure; HttpOnly; SameSite=Lax",
                    header);
        }

        @Test
        @DisplayName("Should write Max-Age=0 for a session whose absolute expiry has passed")
        void shouldClampMaxAgeAtZero() {
            String header = codec.toSetCookieHeader(codec.sign(SESSION_IDENTITY, LAST_ACCESS), NOW.minusSeconds(5),
                    NOW);

            assertTrue(header.contains("; Max-Age=0;"), header);
        }

        @Test
        @DisplayName("Should clear the cookie with an empty value and Max-Age=0, keeping the hardening attributes")
        void shouldClearTheCookie() {
            assertEquals(ACTIVITY_COOKIE_NAME + "=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax",
                    codec.toClearingSetCookieHeader());
        }

        @Test
        @DisplayName("Should read the activity cookie out of a Cookie header carrying it among others")
        void shouldReadFromTheCookieHeader() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);
            String cookieHeader = COOKIE_NAME + "=sealed-session; " + ACTIVITY_COOKIE_NAME + "=" + signed + "; last=z";

            assertEquals(Optional.of(LAST_ACCESS), codec.read(cookieHeader, SESSION_IDENTITY, NOW));
        }

        @Test
        @DisplayName("Should read nothing when the activity cookie is absent or empty-valued")
        void shouldReadNothingWithoutTheCookie() {
            String signed = codec.sign(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(codec.read(null, SESSION_IDENTITY, NOW).isEmpty());
            assertTrue(codec.read("  ", SESSION_IDENTITY, NOW).isEmpty());
            assertTrue(codec.read("other=abc", SESSION_IDENTITY, NOW).isEmpty());
            assertTrue(codec.read(ACTIVITY_COOKIE_NAME + "=", SESSION_IDENTITY, NOW).isEmpty());
            assertTrue(codec.read(COOKIE_NAME + "=" + signed, SESSION_IDENTITY, NOW).isEmpty(),
                    "a value under the session cookie's name is not the activity cookie");
        }

        @Test
        @DisplayName("Should reject a blank session cookie name")
        void shouldRejectABlankName() {
            assertThrows(IllegalArgumentException.class, () -> new SessionActivityCookieCodec("  ", key, KEY_ID));
        }
    }
}
