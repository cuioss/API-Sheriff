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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec.Activity;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SessionActivityCookieCodec} — the AES-256-GCM codec of the cookie-mode activity
 * cookie.
 * <p>
 * The contracts under test are: round-trip fidelity, including the session identity the value is bound
 * to; a fresh nonce per seal; fail-closed unsealing for a value that is tampered with in any of nonce,
 * ciphertext or tag, truncated, sealed for another cookie name, under another key, under another key id
 * or in another format version; the separation from the sealed session cookie in both directions; the
 * exact attribute set of the {@code Set-Cookie}; the clamp of a last access in the future to the
 * reference instant; and the fixed length of the {@code name=value} pair, which is what the pre-route
 * {@code Cookie} header cap has to leave room for.
 */
@EnableTestLogger
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
    /** The length of the base64url encoding of the 54 sealed bytes, without padding. */
    private static final int SEALED_VALUE_CHARS = 72;

    private SecretKey key;
    private SessionActivityCookieCodec codec;

    @BeforeEach
    void setUp() {
        key = aesKey((byte) 0x44);
        codec = new SessionActivityCookieCodec(COOKIE_NAME, key, KEY_ID);
    }

    private static SecretKey aesKey(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return new SecretKeySpec(material, "AES");
    }

    /** A derived session identity in the binding's encoding: 16 bytes, base64url without padding. */
    private static String identity(byte fill) {
        byte[] identity = new byte[16];
        Arrays.fill(identity, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(identity);
    }

    /** Flips the lowest bit of one byte of a sealed value and re-encodes it. */
    private static String withFlippedByte(String sealedValue, int index) {
        byte[] raw = Base64.getUrlDecoder().decode(sealedValue);
        raw[index] ^= 0x01;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Seals a well-formed activity plaintext under the codec's key, cookie name and key id but with the
     * given format version bound into the header and the associated data, so the value authenticates and
     * only the version gate can refuse it.
     */
    private String sealWithVersion(byte version) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        byte[] name = ACTIVITY_COOKIE_NAME.getBytes(StandardCharsets.UTF_8);
        byte[] associatedData = ByteBuffer.allocate(name.length + 2).put(name).put(version).put(KEY_ID).array();
        byte[] plaintext = ByteBuffer.allocate(24)
                .put(Base64.getUrlDecoder().decode(SESSION_IDENTITY)).putLong(LAST_ACCESS.toEpochMilli()).array();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(associatedData);
        byte[] sealed = cipher.doFinal(plaintext);

        byte[] value = ByteBuffer.allocate(14 + sealed.length).put(version).put(KEY_ID).put(nonce).put(sealed)
                .array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Nested
    @DisplayName("Seal and unseal")
    class RoundTrip {

        @Test
        @DisplayName("Should read back the session identity and the last access it sealed")
        void shouldRoundTrip() {
            String sealed = codec.seal(SESSION_IDENTITY, LAST_ACCESS);

            Optional<Activity> unsealed = codec.unseal(sealed, NOW);

            assertEquals(Optional.of(new Activity(SESSION_IDENTITY, LAST_ACCESS)), unsealed);
        }

        @Test
        @DisplayName("Should bind the value to the session identity it was sealed for")
        void shouldBindToTheSessionIdentity() {
            Activity first = codec.unseal(codec.seal(SESSION_IDENTITY, LAST_ACCESS), NOW).orElseThrow();
            Activity second = codec.unseal(codec.seal(OTHER_SESSION_IDENTITY, LAST_ACCESS), NOW).orElseThrow();

            assertEquals(SESSION_IDENTITY, first.sessionIdentity());
            assertEquals(OTHER_SESSION_IDENTITY, second.sessionIdentity());
            assertNotEquals(first.sessionIdentity(), second.sessionIdentity(),
                    "the identity is what lets the binding refuse an activity cookie moved to another session");
        }

        @Test
        @DisplayName("Should draw a fresh nonce on every seal")
        void shouldDrawAFreshNoncePerSeal() {
            assertNotEquals(codec.seal(SESSION_IDENTITY, LAST_ACCESS), codec.seal(SESSION_IDENTITY, LAST_ACCESS),
                    "two seals of one activity never produce the same value");
        }

        @Test
        @DisplayName("Should count a last access in the future as the reference instant")
        void shouldClampAFutureLastAccess() {
            String sealedAhead = codec.seal(SESSION_IDENTITY, NOW.plus(Duration.ofHours(1)));

            Activity unsealed = codec.unseal(sealedAhead, NOW).orElseThrow();

            assertEquals(NOW, unsealed.lastAccess(), "a clock running ahead cannot buy idle time");
        }

        @Test
        @DisplayName("Should refuse to seal a session identity that is not 16 bytes wide")
        void shouldRefuseAnIdentityOfAnotherWidth() {
            String tooWide = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

            assertThrows(IllegalArgumentException.class, () -> codec.seal(tooWide, LAST_ACCESS));
        }
    }

    @Nested
    @DisplayName("Fail-closed unsealing")
    class FailClosed {

        // Layout: version(0) key-id(1) nonce(2..13) ciphertext(14..37) tag(38..53).
        @ParameterizedTest(name = "a flipped bit in byte {0} unseals to nothing")
        @ValueSource(ints = {2, 13, 14, 37, 38, 53})
        @DisplayName("Should unseal a tampered nonce, ciphertext or tag to nothing")
        void shouldRefuseATamperedValue(int index) {
            String tampered = withFlippedByte(codec.seal(SESSION_IDENTITY, LAST_ACCESS), index);

            assertTrue(codec.unseal(tampered, NOW).isEmpty());
        }

        @Test
        @DisplayName("Should unseal a truncated, an over-long and a non-base64 value to nothing")
        void shouldRefuseAMalformedValue() {
            String sealed = codec.seal(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(codec.unseal(sealed.substring(0, sealed.length() - 4), NOW).isEmpty(), "truncated");
            assertTrue(codec.unseal(sealed + "AAAA", NOW).isEmpty(), "over-long");
            assertTrue(codec.unseal("not base64url ~~~", NOW).isEmpty(), "not base64url");
        }

        @Test
        @DisplayName("Should unseal a value sealed for another cookie name to nothing")
        void shouldRefuseAnotherCookieName() {
            SessionActivityCookieCodec otherName = new SessionActivityCookieCodec(OTHER_COOKIE_NAME, key, KEY_ID);

            assertTrue(codec.unseal(otherName.seal(SESSION_IDENTITY, LAST_ACCESS), NOW).isEmpty(),
                    "the cookie name is bound into the associated data");
        }

        @Test
        @DisplayName("Should unseal a value sealed under another key to nothing, with the same key id or another")
        void shouldRefuseAnotherKey() {
            SecretKey otherKey = aesKey((byte) 0x55);
            SessionActivityCookieCodec sameId = new SessionActivityCookieCodec(COOKIE_NAME, otherKey, KEY_ID);
            SessionActivityCookieCodec otherId = new SessionActivityCookieCodec(COOKIE_NAME, otherKey, OTHER_KEY_ID);

            assertTrue(codec.unseal(sameId.seal(SESSION_IDENTITY, LAST_ACCESS), NOW).isEmpty(),
                    "another key under the same id fails the authentication tag");
            assertTrue(codec.unseal(otherId.seal(SESSION_IDENTITY, LAST_ACCESS), NOW).isEmpty(),
                    "another key id is refused at the key-id gate");
        }

        @Test
        @DisplayName("Should unseal a value stamped with another key id to nothing, even under the same key")
        void shouldRefuseAnotherKeyId() {
            SessionActivityCookieCodec otherId = new SessionActivityCookieCodec(COOKIE_NAME, key, OTHER_KEY_ID);

            assertTrue(codec.unseal(otherId.seal(SESSION_IDENTITY, LAST_ACCESS), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should unseal a value of another format version to nothing, although it authenticates")
        void shouldRefuseAnotherVersion() throws Exception {
            byte nextVersion = (byte) (SessionActivityCookieCodec.FORMAT_VERSION + 1);

            assertTrue(codec.unseal(sealWithVersion(nextVersion), NOW).isEmpty());
            assertTrue(codec.unseal(sealWithVersion(SessionActivityCookieCodec.FORMAT_VERSION), NOW).isPresent(),
                    "the same hand-sealed value in the current version unseals, so only the version refused it");
        }

        @Test
        @DisplayName("Should record a rejection at DEBUG only — a junk cookie is not a log-amplification lever")
        void shouldNotLogARejectionAboveDebug() {
            // DEBUG is captured for this class so the positive control below can see the record at all.
            TestLogLevel.DEBUG.addLogger(SessionActivityCookieCodec.class);

            codec.unseal("not base64url ~~~", NOW);
            codec.unseal(withFlippedByte(codec.seal(SESSION_IDENTITY, LAST_ACCESS), 53), NOW);

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

        /** The session codec under the very same key and key id, so only the format keeps the two apart. */
        private SealedSessionCookieCodec sessionCodec() {
            return new SealedSessionCookieCodec(COOKIE_NAME, TTL,
                    SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, key, KEY_ID);
        }

        @Test
        @DisplayName("Should unseal a sealed session cookie value presented as an activity cookie to nothing")
        void shouldRefuseASessionCookieValue() throws Exception {
            String sealedSession = sessionCodec().seal(new SealedSessionPayload("access", null, "id-token",
                    "user-sub-1", null, null, null, NOW, "session-nonce", Set.of("openid"), Set.of("openid")));

            assertTrue(codec.unseal(sealedSession, NOW).isEmpty());
        }

        @Test
        @DisplayName("Should unseal an activity cookie value presented as a session cookie to nothing")
        void shouldBeRefusedAsASessionCookie() {
            String sealedActivity = codec.seal(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(sessionCodec().unseal(sealedActivity).isEmpty());
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
        @DisplayName("Should seal to 72 characters, so name=value under the default cookie name is 104 bytes")
        void shouldPinThePairLength() {
            String sealed = codec.seal(SESSION_IDENTITY, LAST_ACCESS);

            assertEquals(SEALED_VALUE_CHARS, sealed.length(), "54 sealed bytes, base64url without padding");
            String pair = codec.cookieName() + "=" + sealed;
            assertEquals(104, pair.getBytes(StandardCharsets.UTF_8).length,
                    "the pair a browser sends back under the default cookie name");
        }

        @Test
        @DisplayName("Should emit exactly name, value, Max-Age of the remaining lifetime, Path, Secure, HttpOnly and SameSite=Lax")
        void shouldEmitTheHardenedSetCookie() {
            String sealed = codec.seal(SESSION_IDENTITY, LAST_ACCESS);

            String header = codec.toSetCookieHeader(sealed, NOW.plusSeconds(1234), NOW);

            assertEquals(ACTIVITY_COOKIE_NAME + "=" + sealed + "; Max-Age=1234; Path=/; Secure; HttpOnly; SameSite=Lax",
                    header);
        }

        @Test
        @DisplayName("Should write Max-Age=0 for a session whose absolute expiry has passed")
        void shouldClampMaxAgeAtZero() {
            String header = codec.toSetCookieHeader(codec.seal(SESSION_IDENTITY, LAST_ACCESS), NOW.minusSeconds(5),
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
            String sealed = codec.seal(SESSION_IDENTITY, LAST_ACCESS);
            String cookieHeader = COOKIE_NAME + "=sealed-session; " + ACTIVITY_COOKIE_NAME + "=" + sealed + "; last=z";

            assertEquals(Optional.of(new Activity(SESSION_IDENTITY, LAST_ACCESS)), codec.read(cookieHeader, NOW));
        }

        @Test
        @DisplayName("Should read nothing when the activity cookie is absent or empty-valued")
        void shouldReadNothingWithoutTheCookie() {
            String sealed = codec.seal(SESSION_IDENTITY, LAST_ACCESS);

            assertTrue(codec.read(null, NOW).isEmpty());
            assertTrue(codec.read("  ", NOW).isEmpty());
            assertTrue(codec.read("other=abc", NOW).isEmpty());
            assertTrue(codec.read(ACTIVITY_COOKIE_NAME + "=", NOW).isEmpty());
            assertTrue(codec.read(COOKIE_NAME + "=" + sealed, NOW).isEmpty(),
                    "a value under the session cookie's name is not the activity cookie");
        }

        @Test
        @DisplayName("Should reject a blank session cookie name")
        void shouldRejectABlankName() {
            assertThrows(IllegalArgumentException.class, () -> new SessionActivityCookieCodec("  ", key, KEY_ID));
        }
    }
}
