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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec.CookieSizeBudgetExceededException;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec.Unsealed;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.cuioss.tools.logging.CuiLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * Material the seal-time deflation cannot shrink, so a size-budget test still measures the budget
     * rather than the compressor.
     * <p>
     * A run of one repeated character deflates to almost nothing under {@code FORMAT_VERSION} 3, so
     * the {@code "x".repeat(n)} the version-2 tests used would now seal comfortably <em>inside</em>
     * the budget and quietly stop exercising it. Random bytes rendered as base64url are the honest
     * stand-in: base64 carries six bits per byte, so deflate recovers only that quarter and no more.
     */
    private static String incompressible(int approximateCharacters) {
        byte[] random = new byte[approximateCharacters * 3 / 4];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
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
        @DisplayName("Should stamp format version 3 — the deflated, length-prefixed payload shape")
        void shouldStampFormatVersionThree() {
            assertEquals(SealedSessionCookieCodec.FORMAT_VERSION, (byte) 3,
                    "length-prefixed raw UTF-8 framing plus deflation is a wire-format break from the "
                            + "version-2 newline-joined, per-field-base64 shape");
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

        @ParameterizedTest
        @ValueSource(bytes = {1, 2})
        @DisplayName("Should reject a cookie stamped with a retired format version, before any decrypt")
        void shouldRejectRetiredFormatVersions(byte retired) throws Exception {
            String sealed = codec.seal(payload());
            byte[] raw = Base64.getUrlDecoder().decode(sealed);
            raw[0] = retired;

            assertTrue(codec.unseal(Base64.getUrlEncoder().withoutPadding().encodeToString(raw)).isEmpty(),
                    "the version bump to 3 is a clean break: a cookie stamped with a retired version is "
                            + "refused at the version gate, with no Cipher constructed, rather than being "
                            + "inflated and parsed against the new framing");
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, "unknown-version");
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
            String huge = incompressible(BUDGET * 4);
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

    /**
     * The {@code FORMAT_VERSION} 3 packaging pipeline: {@code encode -> deflate -> seal -> base64url}
     * on the way out, and its exact inverse on the way back.
     * <p>
     * The rejection cases here all seal <em>hand-built</em> bytes under the codec's own key, so they
     * clear the GCM tag and reach the inflate step. That is the only state worth guarding: an
     * unauthenticated buffer never gets this far, and a buffer that authenticates but is not a
     * well-formed stream of this format is precisely what the bound exists for.
     */
    @Nested
    @DisplayName("Packaging pipeline")
    class PackagingPipeline {

        private static final String DISPOSITION_PAYLOAD = "payload-format";

        /**
         * Seals bytes the production {@code seal} would never produce, under the codec's key, cookie
         * name, version, and key id — so the value authenticates and the unseal path reaches inflate.
         */
        private String sealVerbatim(byte[] sealedPlaintext) throws Exception {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            byte[] name = COOKIE_NAME.getBytes(StandardCharsets.UTF_8);
            byte[] associatedData = ByteBuffer.allocate(name.length + 2)
                    .put(name).put(SealedSessionCookieCodec.FORMAT_VERSION).put(KEY_ID).array();

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData);
            byte[] sealed = cipher.doFinal(sealedPlaintext);

            byte[] value = ByteBuffer.allocate(14 + sealed.length)
                    .put(SealedSessionCookieCodec.FORMAT_VERSION).put(KEY_ID).put(nonce).put(sealed)
                    .array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }

        private static byte[] deflate(byte[] plaintext) {
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try {
                deflater.setInput(plaintext);
                deflater.finish();
                ByteArrayOutputStream compressed = new ByteArrayOutputStream(plaintext.length);
                byte[] chunk = new byte[1024];
                while (!deflater.finished()) {
                    compressed.write(chunk, 0, deflater.deflate(chunk));
                }
                return compressed.toByteArray();
            } finally {
                deflater.end();
            }
        }

        @Test
        @DisplayName("Should reject authenticated bytes that are not a deflate stream at all")
        void shouldRejectNonDeflateStream() throws Exception {
            String sealed = sealVerbatim("this is not a deflate stream".getBytes(StandardCharsets.UTF_8));

            assertTrue(codec.unseal(sealed).isEmpty(),
                    "a corrupt stream under a valid tag is no session, never an escaping exception");
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, DISPOSITION_PAYLOAD);
        }

        @Test
        @DisplayName("Should reject a well-formed deflate stream that inflates to a foreign frame")
        void shouldRejectForeignFrameInsideAValidStream() throws Exception {
            String sealed = sealVerbatim(deflate("nine fields this is not".getBytes(StandardCharsets.UTF_8)));

            assertTrue(codec.unseal(sealed).isEmpty(),
                    "inflating cleanly is not the same as carrying the payload shape — the frame guard "
                            + "still has to refuse it");
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, DISPOSITION_PAYLOAD);
        }

        @Test
        @DisplayName("Should refuse to inflate past the plaintext bound, however small the sealed value")
        void shouldBoundTheInflatedSize() throws Exception {
            // A megabyte of zeros deflates to roughly a kilobyte, so the sealed value is unremarkable
            // and only the INFLATED size is out of bounds. Without the cap, unsealing this one cookie
            // would allocate a megabyte per request.
            byte[] bomb = deflate(new byte[1024 * 1024]);
            String sealed = sealVerbatim(bomb);

            assertTrue(sealed.length() < BUDGET,
                    "the sealed value is inside the cookie budget — the budget is not what stops this");
            assertTrue(codec.unseal(sealed).isEmpty(),
                    "the inflated size is capped at MAX_PLAINTEXT_BYTES, so an authenticated-but-corrupt "
                            + "buffer cannot drive an unbounded allocation");
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, DISPOSITION_PAYLOAD);
        }

        @Test
        @DisplayName("Should round-trip a payload whose fields deflate to less than they measure")
        void shouldRoundTripAcrossCompression() throws Exception {
            SealedSessionPayload highlyCompressible = new SealedSessionPayload("a".repeat(4096),
                    "b".repeat(4096), "c".repeat(4096), SUB, null, null, null, LOGIN, SESSION_NONCE);

            String sealed = codec.seal(highlyCompressible);

            assertEquals(Optional.of(new Unsealed(highlyCompressible)), codec.unseal(sealed),
                    "compression is transparent to the payload contract");
            assertTrue(sealed.length() < BUDGET,
                    "12 KB of repeating material seals inside a 4 KB budget: " + sealed.length());
        }
    }

    /**
     * The measurement that settles whether cookie mode is viable <em>with a refresh token</em>: a
     * session carrying a live access, refresh and ID token must seal to a value the browser is
     * obliged to keep, with headroom left for claim-set growth.
     * <p>
     * <strong>What the fixture is, and what it is not.</strong> The three tokens are built to the
     * shape Keycloak issues for the {@code integration} realm — an RS256 access and ID token, an
     * HS512 refresh token, the realm's actual role and client names, and a random signature segment
     * of the right width for each algorithm. Randomness matters: a signature is the one part of a JWT
     * deflation cannot help with, so a fixture that used a repeating filler there would report a
     * ratio no real token can reach. This is a faithful <em>shape</em>, not a live token —
     * {@code BffCookieRefreshIT} is what measures the real thing against a running IdP, and this test
     * is the fast gate that fails first when a change costs bytes.
     */
    @Nested
    @DisplayName("Three-token size measurement")
    class ThreeTokenMeasurement {

        // cui-rewrite:disable CuiLogRecordPatternRecipe
        private static final CuiLogger LOGGER = new CuiLogger(ThreeTokenMeasurement.class);

        /**
         * The browser-safe value budget less a 10 % headroom reserve for future claim-set growth:
         * {@code 4019 - 402}. Spelled independently of the production constant, because it is the
         * figure the plan's success criterion names.
         */
        private static final int HEADROOM_FLOOR = 3617;

        /** The session nonce is 32 random bytes rendered base64url — 43 characters that never compress. */
        private static final int SESSION_NONCE_CHARACTERS = 43;

        private static final String RS256_HEADER = """
                {"alg":"RS256","typ":"JWT","kid":"eK3xY2mQvJ8sLd0aTn5PbRc7Zu1WgHfN9iOxKlBmVsE"}""";
        private static final String HS512_HEADER = """
                {"alg":"HS512","typ":"JWT","kid":"7f2a9c14-5e83-4b06-9d71-3a8c0e5b2f49"}""";
        private static final String ISSUER = "https://keycloak:8443/realms/integration";
        private static final String SUBJECT = "b4f2c9e1-3d7a-4856-9f10-2c8e5a7b6d34";
        private static final String IDP_SESSION = "7c1e9a52-6b48-4f03-8d75-1a9c2e4b8f60";

        /** RS256 signs with a 2048-bit key: 256 bytes, 342 base64url characters. */
        private static final int RS256_SIGNATURE_BYTES = 256;

        /** HS512 signs with a 512-bit MAC: 64 bytes, 86 base64url characters. */
        private static final int HS512_SIGNATURE_BYTES = 64;

        private static String jwt(String header, String claims, int signatureBytes) {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            byte[] signature = new byte[signatureBytes];
            new SecureRandom().nextBytes(signature);
            return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                    + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + "."
                    + encoder.encodeToString(signature);
        }

        private static String accessToken() {
            return jwt(RS256_HEADER, """
                    {"exp":1785276000,"iat":1785275700,"jti":"onrtac:2f8d1c4b-7a93-4e15-9c62-8b0d5f3a1e77",\
                    "iss":"%s","aud":["account"],"sub":"%s","typ":"Bearer","azp":"integration-client",\
                    "sid":"%s","acr":"1","allowed-origins":["https://localhost:10443"],\
                    "realm_access":{"roles":["offline_access","default-roles-integration",\
                    "uma_authorization","user"]},"resource_access":{"account":{"roles":["manage-account",\
                    "manage-account-links","view-profile"]}},"scope":"openid email profile",\
                    "email_verified":true,"name":"Integration User","preferred_username":"integration-user",\
                    "given_name":"Integration","family_name":"User","email":"integration-user@example.com"}\
                    """.formatted(ISSUER, SUBJECT, IDP_SESSION), RS256_SIGNATURE_BYTES);
        }

        private static String refreshToken() {
            return jwt(HS512_HEADER, """
                    {"exp":1785277500,"iat":1785275700,"jti":"5c9e2b7a-1f43-4d86-b0a2-7e6d38c14f95",\
                    "iss":"%s","aud":"%s","sub":"%s","typ":"Refresh","azp":"integration-client",\
                    "sid":"%s","scope":"openid email profile"}\
                    """.formatted(ISSUER, ISSUER, SUBJECT, IDP_SESSION), HS512_SIGNATURE_BYTES);
        }

        private static String idToken() {
            return jwt(RS256_HEADER, """
                    {"exp":1785276000,"iat":1785275700,"auth_time":1785275699,\
                    "jti":"a1d7f3e0-9b24-4c58-8e16-6f0a3d9c7b52","iss":"%s","aud":"integration-client",\
                    "sub":"%s","typ":"ID","azp":"integration-client",\
                    "nonce":"Qn7xK2vL9pTdRa4WsYc0Bg","sid":"%s","at_hash":"jK8sLd0aTn5PbRc7Zu1WgH",\
                    "acr":"1","email_verified":true,"name":"Integration User",\
                    "preferred_username":"integration-user","given_name":"Integration",\
                    "family_name":"User","email":"integration-user@example.com"}\
                    """.formatted(ISSUER, SUBJECT, IDP_SESSION), RS256_SIGNATURE_BYTES);
        }

        private static SealedSessionPayload threeTokenSession() {
            byte[] nonceMaterial = new byte[32];
            new SecureRandom().nextBytes(nonceMaterial);
            return new SealedSessionPayload(accessToken(), refreshToken(), idToken(), SUBJECT,
                    IDP_SESSION, "1", Instant.parse("2026-07-27T09:59:59Z"), LOGIN,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(nonceMaterial));
        }

        @Test
        @DisplayName("Should seal a live access + refresh + ID token at or below the 3617-byte headroom floor")
        void shouldSealThreeTokenSessionWithinHeadroom() throws Exception {
            SealedSessionPayload session = threeTokenSession();
            int framedBytes = session.encode().length;

            String sealed = codec.seal(session);

            int sealedBytes = sealed.getBytes(StandardCharsets.UTF_8).length;
            // Reported, not merely asserted: the settlement report quotes these figures, and an
            // assertion message that only surfaces on failure cannot supply them.
            // cui-rewrite:disable CuiLogRecordPatternRecipe
            LOGGER.info("FORMAT_VERSION 3 three-token measurement: framed plaintext %s bytes, "
                    + "sealed value %s bytes (%s percent of framed), headroom floor %s bytes, "
                    + "browser-safe value budget %s bytes; the %s-character session nonce is "
                    + "random and incompressible and is a fixed floor on the ratio",
                    framedBytes, sealedBytes,
                    // Locale.ROOT: the report quotes this figure, and a locale-dependent decimal
                    // comma would make the same run read differently on a different machine.
                    String.format(Locale.ROOT, "%.1f", 100.0 * sealedBytes / framedBytes),
                    HEADROOM_FLOOR, SealedSessionCookieCodec.BROWSER_SAFE_COOKIE_VALUE_BUDGET,
                    SESSION_NONCE_CHARACTERS);
            assertTrue(sealedBytes <= HEADROOM_FLOOR, () -> String.format(Locale.ROOT, """
                    a three-token session must seal to at most %d bytes — the %d-byte browser-safe value \
                    budget less a 10%% headroom reserve — but it sealed to %d.
                    Framed plaintext: %d bytes. Sealed value: %d bytes, i.e. %.1f%% of the framed size \
                    after deflation and the single outer base64url.
                    The %d-character session nonce is random and incompressible, so it is a fixed floor \
                    on any ratio this pipeline can reach.\
                    """, HEADROOM_FLOOR, SealedSessionCookieCodec.BROWSER_SAFE_COOKIE_VALUE_BUDGET,
                    sealedBytes, framedBytes, sealedBytes, 100.0 * sealedBytes / framedBytes,
                    SESSION_NONCE_CHARACTERS));
        }

        @Test
        @DisplayName("Should keep the whole emitted Set-Cookie header inside the browser's per-cookie guarantee")
        void shouldEmitADeliverableHeader() throws Exception {
            SealedSessionPayload session = threeTokenSession();

            String header = codec.toSetCookieHeader(codec.seal(session), LOGIN, LOGIN);

            int headerBytes = header.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(headerBytes <= SealedSessionCookieCodec.BROWSER_PER_COOKIE_HEADER_GUARANTEE,
                    () -> "the value budget is not the browser's budget: what has to fit 4096 is the whole "
                            + "Set-Cookie header, and this one is " + headerBytes + " bytes");
        }

        @Test
        @DisplayName("Should round-trip the three-token session, so the measurement is of a usable value")
        void shouldRoundTripTheThreeTokenSession() throws Exception {
            SealedSessionPayload session = threeTokenSession();

            assertEquals(Optional.of(new Unsealed(session)), codec.unseal(codec.seal(session)),
                    "a size that no longer unseals would be a meaningless measurement");
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

    /**
     * The {@code Set-Cookie} overhead derivation — the single source every browser-deliverability
     * comparison in the gateway goes through.
     * <p>
     * These rows are the anti-drift guard the derivation's javadoc promises. The overhead used to be
     * a fixed 77 while {@link SealedSessionCookieCodec#toSetCookieHeader} assembled the header from
     * the <em>configured</em> cookie name and TTL, so a gateway running either off the default
     * emitted a larger header than every guard believed. Pinning the arithmetic against a header the
     * codec actually formatted is what makes a change to one of them alone impossible to land quietly.
     */
    @Nested
    @DisplayName("Set-Cookie header overhead derivation")
    class HeaderOverhead {

        static Stream<Arguments> configurations() {
            return Stream.of(
                    Arguments.of("the default name and a four-digit Max-Age",
                            "__Host-sheriff-session", Duration.ofSeconds(3600)),
                    Arguments.of("the default name and a five-digit Max-Age",
                            "__Host-sheriff-session", Duration.ofSeconds(86_400)),
                    Arguments.of("the shorter name the documented examples use",
                            "__Host-sheriff", Duration.ofSeconds(3600)),
                    Arguments.of("a non-ASCII cookie name, where a byte differs from a code unit",
                            "__Host-sheriff-名", Duration.ofSeconds(3600)),
                    Arguments.of("a cookie name well past the default length",
                            "__Host-a-considerably-longer-session-cookie-name", Duration.ofHours(8)),
                    Arguments.of("a single-digit Max-Age", "s", Duration.ofSeconds(9)),
                    Arguments.of("a seven-digit Max-Age", "__Host-sheriff-session", Duration.ofDays(30)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("configurations")
        @DisplayName("Should report exactly the bytes the formatted header wraps around the value")
        void shouldMatchTheFormattedHeader(String label, String cookieName, Duration ttl) throws Exception {
            // Arrange — at the login instant Max-Age carries the full TTL, which is the widest the
            // attribute ever gets, so this is the case the derivation must reproduce exactly.
            SealedSessionCookieCodec configured =
                    new SealedSessionCookieCodec(cookieName, ttl, BUDGET, key, KEY_ID);
            String sealed = configured.seal(payload());

            // Act
            String header = configured.toSetCookieHeader(sealed, LOGIN, LOGIN);

            // Assert
            assertEquals(header.getBytes(StandardCharsets.UTF_8).length
                    - sealed.getBytes(StandardCharsets.UTF_8).length,
                    SealedSessionCookieCodec.setCookieHeaderOverhead(cookieName, ttl),
                    () -> "the derivation must count the same bytes the assembly emits: " + header);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("configurations")
        @DisplayName("Should never understate the overhead once Max-Age has shrunk below the full TTL")
        void shouldBoundEveryLaterHeader(String label, String cookieName, Duration ttl) throws Exception {
            // A re-seal mid-session carries a smaller remaining lifetime and therefore possibly fewer
            // Max-Age digits. A deliverability guard must not report a header smaller than one the
            // gateway can actually send, so the derivation is an upper bound over the whole session.
            SealedSessionCookieCodec configured =
                    new SealedSessionCookieCodec(cookieName, ttl, BUDGET, key, KEY_ID);
            String sealed = configured.seal(payload());
            int derived = SealedSessionCookieCodec.setCookieHeaderOverhead(cookieName, ttl);

            String halfway = configured.toSetCookieHeader(sealed, LOGIN, LOGIN.plus(ttl.dividedBy(2)));
            String expired = configured.toSetCookieHeader(sealed, LOGIN, LOGIN.plus(ttl).plusSeconds(60));

            assertTrue(halfway.getBytes(StandardCharsets.UTF_8).length
                    - sealed.getBytes(StandardCharsets.UTF_8).length <= derived, halfway);
            assertTrue(expired.getBytes(StandardCharsets.UTF_8).length
                    - sealed.getBytes(StandardCharsets.UTF_8).length <= derived, expired);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("configurations")
        @DisplayName("Should never emit a Max-Age above the configured TTL, even when now precedes the login instant")
        void shouldCapMaxAgeAtTheConfiguredTtl(String label, String cookieName, Duration ttl) throws Exception {
            // A clock reading BEFORE the login instant makes the naive remaining lifetime exceed the
            // configured TTL, which both emits a longer-lived cookie than configured and can add a
            // Max-Age digit the derivation did not count. The cap is what the javadoc already claims
            // ("at most sessionTtl"); this control is what makes the claim true rather than stated.
            SealedSessionCookieCodec configured =
                    new SealedSessionCookieCodec(cookieName, ttl, BUDGET, key, KEY_ID);
            String sealed = configured.seal(payload());
            int derived = SealedSessionCookieCodec.setCookieHeaderOverhead(cookieName, ttl);

            String beforeLogin = configured.toSetCookieHeader(sealed, LOGIN, LOGIN.minusSeconds(1));

            assertTrue(beforeLogin.contains("; Max-Age=" + ttl.toSeconds() + ";"),
                    () -> "Max-Age must be capped at the configured TTL: " + beforeLogin);
            assertTrue(beforeLogin.getBytes(StandardCharsets.UTF_8).length
                    - sealed.getBytes(StandardCharsets.UTF_8).length <= derived, beforeLogin);
        }

        @Test
        @DisplayName("Should keep the documented default-configuration constant equal to the derivation")
        void shouldPinTheDefaultConfigurationConstant() {
            // Twenty-two bytes of default cookie name, one for the separator, ten for the Max-Age
            // attribute label, four for a four-digit lifetime, and forty for the hardening
            // attributes: seventy-seven in total. The figure is spelled here independently of the
            // production arithmetic because it is the number the operator-facing catalogue quotes.
            assertEquals(77, SealedSessionCookieCodec.DEFAULT_SET_COOKIE_HEADER_OVERHEAD);
            assertEquals(SealedSessionCookieCodec.DEFAULT_SET_COOKIE_HEADER_OVERHEAD,
                    SealedSessionCookieCodec.setCookieHeaderOverhead(
                            "__Host-sheriff-session", Duration.ofSeconds(3600)),
                    "the default-configuration constant is the derivation's default-configuration case");
            assertEquals(4019, SealedSessionCookieCodec.BROWSER_SAFE_COOKIE_VALUE_BUDGET);
            assertEquals(SealedSessionCookieCodec.BROWSER_SAFE_COOKIE_VALUE_BUDGET,
                    SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET,
                    "the shipped default IS the browser-safe budget: a default above it puts every "
                            + "gateway that declares nothing into the band where the emitted header is "
                            + "past the guarantee and nothing says so");
        }

        @Test
        @DisplayName("Should refuse a blank or absent cookie name rather than derive a nonsense overhead")
        void shouldRefuseUnusableInputs() {
            Duration oneSecond = Duration.ofSeconds(1);
            assertThrows(IllegalArgumentException.class,
                    () -> SealedSessionCookieCodec.setCookieHeaderOverhead("  ", oneSecond));
            assertThrows(NullPointerException.class,
                    () -> SealedSessionCookieCodec.setCookieHeaderOverhead(COOKIE_NAME, null));
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
            String huge = incompressible(BUDGET * 4);
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
