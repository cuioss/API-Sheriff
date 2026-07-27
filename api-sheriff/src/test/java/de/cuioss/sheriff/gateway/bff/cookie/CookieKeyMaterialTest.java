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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CookieKeyMaterial} — the two first-class cookie key-material modes (D2b).
 * <p>
 * The contracts under test are: passed-key acceptance including the decrypt-only rotation key; the
 * boot rejection of a non-base64 or wrong-length key with a message that names the field but never
 * echoes the value; generate-on-startup producing a distinct 256-bit key per boot; the companion
 * rule refusing a {@code previous_key} without an {@code encryption_key} rather than silently
 * ignoring it; and the absence of any key byte from {@link CookieKeyMaterial#toString()}.
 */
class CookieKeyMaterialTest {

    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final Duration TTL = Duration.ofHours(8);
    private static final int BUDGET = SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET;
    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final String CURRENT_KEY_B64 = base64Key((byte) 0x11);
    private static final String PREVIOUS_KEY_B64 = base64Key((byte) 0x33);

    private static String base64Key(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return Base64.getEncoder().encodeToString(material);
    }

    private static SealedSessionPayload payload() {
        return new SealedSessionPayload("access", Optional.empty(), "id-token", "user-sub-1",
                Optional.empty(), Optional.empty(), Optional.empty(), LOGIN);
    }

    /** Reads the key-id byte a sealed value is stamped with (value layout: version, key-id, …). */
    private static byte keyIdOf(String sealedValue) {
        return Base64.getUrlDecoder().decode(sealedValue)[1];
    }

    @Nested
    @DisplayName("Passed-key mode")
    class PassedKey {

        @Test
        @DisplayName("Should resolve a supplied key into the passed mode without a rotation key")
        void shouldResolvePassedKey() {
            CookieKeyMaterial material = CookieKeyMaterial.resolve(Optional.of(CURRENT_KEY_B64), Optional.empty());

            assertEquals(CookieKeyMaterial.Mode.PASSED, material.mode());
            assertFalse(material.hasPreviousKey(), "no rotation is in progress");
        }

        @Test
        @DisplayName("Should carry a key sealed before the rotation over into the rotating material")
        void shouldAcceptPreviousKey() throws Exception {
            // Before the rotation the retired key was THE key, so it sealed under its own id.
            CookieKeyMaterial beforeRotation =
                    CookieKeyMaterial.resolve(Optional.of(PREVIOUS_KEY_B64), Optional.empty());
            String sealedBeforeRotation = beforeRotation.codec(COOKIE_NAME, TTL, BUDGET).seal(payload());

            CookieKeyMaterial rotating =
                    CookieKeyMaterial.resolve(Optional.of(CURRENT_KEY_B64), Optional.of(PREVIOUS_KEY_B64));
            SealedSessionCookieCodec rotatingCodec = rotating.codec(COOKIE_NAME, TTL, BUDGET);

            assertTrue(rotating.hasPreviousKey());
            assertEquals(Optional.of(payload()), rotatingCodec.unseal(sealedBeforeRotation)
                            .map(SealedSessionCookieCodec.Unsealed::payload),
                    "the pre-rotation cookie still unseals — the key id follows the key, not its position");
            assertTrue(rotatingCodec.unseal(sealedBeforeRotation).orElseThrow().sealedWithPreviousKey(),
                    "and is flagged as the retired generation, so its next write rolls it over");
            assertEquals(rotating.currentKeyId(), keyIdOf(rotatingCodec.seal(payload())),
                    "new values are sealed under the current key id, never the retired one");
            assertNotEquals(beforeRotation.currentKeyId(), rotating.currentKeyId(),
                    "the two generations are distinguishable on the wire");
        }

        @Test
        @DisplayName("Should survive a round trip through the codec it builds")
        void shouldRoundTripThroughItsCodec() throws Exception {
            SealedSessionCookieCodec codec = CookieKeyMaterial
                    .resolve(Optional.of(CURRENT_KEY_B64), Optional.empty())
                    .codec(COOKIE_NAME, TTL, BUDGET);

            assertEquals(Optional.of(payload()), codec.unseal(codec.seal(payload()))
                    .map(SealedSessionCookieCodec.Unsealed::payload));
        }

        @Test
        @DisplayName("Should derive a salt bound to the key, so two different keys salt differently")
        void shouldDeriveKeyBoundIdentitySalt() {
            byte[] first = CookieKeyMaterial.resolve(Optional.of(CURRENT_KEY_B64), Optional.empty()).identitySalt();
            byte[] second = CookieKeyMaterial.resolve(Optional.of(PREVIOUS_KEY_B64), Optional.empty()).identitySalt();

            assertEquals(32, first.length, "the salt is a full SHA-256 digest");
            assertFalse(Arrays.equals(first, second), "the salt is bound to the sealing key");
        }
    }

    @Nested
    @DisplayName("Boot rejection of malformed key material")
    class BootRejection {

        @Test
        @DisplayName("Should reject a key that is not base64, naming the field but never the value")
        void shouldRejectNonBase64Key() {
            String offending = "not base64 ~~~";
            Optional<String> encryptionKey = Optional.of(offending);
            Optional<String> noPreviousKey = Optional.empty();

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(encryptionKey, noPreviousKey));

            assertTrue(thrown.getMessage().contains("session.encryption_key"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains(offending), "the offending value is never echoed");
        }

        @Test
        @DisplayName("Should reject a key of the wrong length with a message naming the expected size")
        void shouldRejectWrongLengthKey() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
            Optional<String> encryptionKey = Optional.of(tooShort);
            Optional<String> noPreviousKey = Optional.empty();

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(encryptionKey, noPreviousKey));

            assertTrue(thrown.getMessage().contains("32"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("AES-256"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains(tooShort), "the offending value is never echoed");
        }

        @Test
        @DisplayName("Should reject a malformed previous key naming the previous-key field")
        void shouldRejectMalformedPreviousKey() {
            Optional<String> encryptionKey = Optional.of(CURRENT_KEY_B64);
            Optional<String> malformedPreviousKey = Optional.of(Base64.getEncoder().encodeToString(new byte[8]));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(encryptionKey, malformedPreviousKey));

            assertTrue(thrown.getMessage().contains("session.previous_key"), thrown.getMessage());
        }

        @Test
        @DisplayName("Should refuse a previous key that derives the same wire id as the current key")
        void shouldRefuseCollidingKeyIds() {
            Optional<String> encryptionKey = Optional.of(CURRENT_KEY_B64);
            Optional<String> collidingPreviousKey = Optional.of(CURRENT_KEY_B64);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(encryptionKey, collidingPreviousKey));

            assertTrue(thrown.getMessage().contains("same key id"), thrown.getMessage());
        }

        @Test
        @DisplayName("Should refuse a previous key without an encryption key rather than ignore it")
        void shouldRefusePreviousKeyAlone() {
            Optional<String> noEncryptionKey = Optional.empty();
            Optional<String> previousKey = Optional.of(PREVIOUS_KEY_B64);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(noEncryptionKey, previousKey));

            assertTrue(thrown.getMessage().contains("session.previous_key"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("session.encryption_key"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Generate-on-startup mode")
    class GenerateOnStartup {

        @Test
        @DisplayName("Should select the generated mode when no encryption key is configured")
        void shouldSelectGeneratedMode() {
            CookieKeyMaterial material = CookieKeyMaterial.resolve(Optional.empty(), Optional.empty());

            assertEquals(CookieKeyMaterial.Mode.GENERATED, material.mode());
            assertFalse(material.hasPreviousKey(), "the generated mode has no previous key by construction");
        }

        @Test
        @DisplayName("Should generate a distinct key per boot, so a restart drops every session")
        void shouldGenerateADistinctKeyPerBoot() throws Exception {
            SealedSessionCookieCodec firstBoot =
                    CookieKeyMaterial.resolve(Optional.empty(), Optional.empty()).codec(COOKIE_NAME, TTL, BUDGET);
            SealedSessionCookieCodec secondBoot =
                    CookieKeyMaterial.resolve(Optional.empty(), Optional.empty()).codec(COOKIE_NAME, TTL, BUDGET);

            String sealedBeforeRestart = firstBoot.seal(payload());

            assertTrue(firstBoot.unseal(sealedBeforeRestart).isPresent(), "the sealing boot still reads its own value");
            assertTrue(secondBoot.unseal(sealedBeforeRestart).isEmpty(),
                    "a fresh key per boot means sessions do not survive a restart");
        }

        @Test
        @DisplayName("Should generate a 256-bit key, so the codec it builds is AES-256-GCM")
        void shouldGenerateA256BitKey() {
            CookieKeyMaterial generated = CookieKeyMaterial.resolve(Optional.empty(), Optional.empty());

            assertEquals(32, generated.currentKeyLengthBytes(),
                    "the generated key must be 256-bit — asserting the derived salt's length instead would "
                            + "pass for a 128-bit key too, since the salt is a fixed-width SHA-256 digest");
        }

        @Test
        @DisplayName("Should derive a distinct identity salt per boot, following the generated key")
        void shouldDeriveADistinctSaltPerBoot() {
            byte[] salt = CookieKeyMaterial.resolve(Optional.empty(), Optional.empty()).identitySalt();
            byte[] otherSalt = CookieKeyMaterial.resolve(Optional.empty(), Optional.empty()).identitySalt();

            assertEquals(32, salt.length, "the salt is a full SHA-256 digest");
            assertFalse(Arrays.equals(salt, otherSalt), "each boot's salt follows its own generated key");
        }
    }

    @Nested
    @DisplayName("No secret disclosure")
    class NoSecretDisclosure {

        @Test
        @DisplayName("Should keep key material out of toString in both modes")
        void shouldNotLeakKeyMaterialIntoToString() {
            String passed = CookieKeyMaterial
                    .resolve(Optional.of(CURRENT_KEY_B64), Optional.of(PREVIOUS_KEY_B64)).toString();
            String generated = CookieKeyMaterial.resolve(Optional.empty(), Optional.empty()).toString();

            assertFalse(passed.contains(CURRENT_KEY_B64), "the current key never appears in toString()");
            assertFalse(passed.contains(PREVIOUS_KEY_B64), "the previous key never appears in toString()");
            assertTrue(passed.contains("passed key"), passed);
            assertTrue(passed.contains("rotating=true"), passed);
            assertTrue(generated.contains("generated on startup"), generated);
            assertNotEquals(passed, generated);
        }
    }
}
