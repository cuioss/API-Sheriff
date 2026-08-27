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
 * The contracts under test are: passed-key acceptance; the boot rejection of a non-base64 or
 * wrong-length key with a message that names the field but never echoes the value;
 * generate-on-startup producing a distinct 256-bit key per boot; the single-key clean break, where
 * a value sealed under a withdrawn key is refused outright because there is no decrypt-only
 * companion key to fall back on; and the absence of any key byte from
 * {@link CookieKeyMaterial#toString()}.
 */
class CookieKeyMaterialTest {

    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final Duration TTL = Duration.ofHours(8);
    private static final int BUDGET = SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET;
    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final String CURRENT_KEY_B64 = base64Key((byte) 0x11);

    /** A second, unrelated key — the replacement in a key change, and the "different key" contrast. */
    private static final String OTHER_KEY_B64 = base64Key((byte) 0x33);

    private static String base64Key(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return Base64.getEncoder().encodeToString(material);
    }

    private static SealedSessionPayload payload() {
        return new SealedSessionPayload("access", null, "id-token", "user-sub-1",
                null, null, null, LOGIN, "session-nonce");
    }

    /** Reads the key-id byte a sealed value is stamped with (value layout: version, key-id, …). */
    private static byte keyIdOf(String sealedValue) {
        return Base64.getUrlDecoder().decode(sealedValue)[1];
    }

    @Nested
    @DisplayName("Passed-key mode")
    class PassedKey {

        @Test
        @DisplayName("Should resolve a supplied key into the passed mode")
        void shouldResolvePassedKey() {
            CookieKeyMaterial material = CookieKeyMaterial.resolve(CURRENT_KEY_B64);

            assertEquals(CookieKeyMaterial.Mode.PASSED, material.mode());
        }

        @Test
        @DisplayName("Should survive a round trip through the codec it builds")
        void shouldRoundTripThroughItsCodec() throws Exception {
            SealedSessionCookieCodec codec = CookieKeyMaterial
                    .resolve(CURRENT_KEY_B64)
                    .codec(COOKIE_NAME, TTL, BUDGET);

            assertEquals(Optional.of(payload()), codec.unseal(codec.seal(payload()))
                    .map(SealedSessionCookieCodec.Unsealed::payload));
        }

        @Test
        @DisplayName("Should refuse a value sealed under a withdrawn key — a key change is a clean break")
        void shouldRefuseAValueSealedUnderAWithdrawnKey() throws Exception {
            CookieKeyMaterial withdrawn = CookieKeyMaterial.resolve(OTHER_KEY_B64);
            String sealedUnderWithdrawnKey = withdrawn.codec(COOKIE_NAME, TTL, BUDGET).seal(payload());

            CookieKeyMaterial replacement = CookieKeyMaterial.resolve(CURRENT_KEY_B64);
            SealedSessionCookieCodec replacementCodec = replacement.codec(COOKIE_NAME, TTL, BUDGET);

            assertNotEquals(withdrawn.currentKeyId(), replacement.currentKeyId(),
                    "the two generations are distinguishable on the wire — the id follows the key, not its position");
            assertTrue(replacementCodec.unseal(sealedUnderWithdrawnKey).isEmpty(),
                    "there is no decrypt-only companion key, so the withdrawn generation is simply 'no session'");
            assertEquals(replacement.currentKeyId(), keyIdOf(replacementCodec.seal(payload())),
                    "new values are stamped with the one active key id");
        }

        @Test
        @DisplayName("Should derive a salt bound to the key, so two different keys salt differently")
        void shouldDeriveKeyBoundIdentitySalt() {
            byte[] first = CookieKeyMaterial.resolve(CURRENT_KEY_B64).identitySalt();
            byte[] second = CookieKeyMaterial.resolve(OTHER_KEY_B64).identitySalt();

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

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(offending));

            assertTrue(thrown.getMessage().contains("session.encryption_key"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains(offending), "the offending value is never echoed");
        }

        @Test
        @DisplayName("Should reject a key of the wrong length with a message naming the expected size")
        void shouldRejectWrongLengthKey() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CookieKeyMaterial.resolve(tooShort));

            assertTrue(thrown.getMessage().contains("32"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("AES-256"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains(tooShort), "the offending value is never echoed");
        }
    }

    @Nested
    @DisplayName("Generate-on-startup mode")
    class GenerateOnStartup {

        @Test
        @DisplayName("Should select the generated mode when no encryption key is configured")
        void shouldSelectGeneratedMode() {
            CookieKeyMaterial material = CookieKeyMaterial.resolve(null);

            assertEquals(CookieKeyMaterial.Mode.GENERATED, material.mode());
        }

        @Test
        @DisplayName("Should generate a distinct key per boot, so a restart drops every session")
        void shouldGenerateADistinctKeyPerBoot() throws Exception {
            SealedSessionCookieCodec firstBoot =
                    CookieKeyMaterial.resolve(null).codec(COOKIE_NAME, TTL, BUDGET);
            SealedSessionCookieCodec secondBoot =
                    CookieKeyMaterial.resolve(null).codec(COOKIE_NAME, TTL, BUDGET);

            String sealedBeforeRestart = firstBoot.seal(payload());

            assertTrue(firstBoot.unseal(sealedBeforeRestart).isPresent(), "the sealing boot still reads its own value");
            assertTrue(secondBoot.unseal(sealedBeforeRestart).isEmpty(),
                    "a fresh key per boot means sessions do not survive a restart");
        }

        @Test
        @DisplayName("Should generate a 256-bit key, so the codec it builds is AES-256-GCM")
        void shouldGenerateA256BitKey() {
            CookieKeyMaterial generated = CookieKeyMaterial.resolve(null);

            assertEquals(32, generated.currentKeyLengthBytes(),
                    "the generated key must be 256-bit — asserting the derived salt's length instead would "
                            + "pass for a 128-bit key too, since the salt is a fixed-width SHA-256 digest");
        }

        @Test
        @DisplayName("Should derive a distinct identity salt per boot, following the generated key")
        void shouldDeriveADistinctSaltPerBoot() {
            byte[] salt = CookieKeyMaterial.resolve(null).identitySalt();
            byte[] otherSalt = CookieKeyMaterial.resolve(null).identitySalt();

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
            String passed = CookieKeyMaterial.resolve(CURRENT_KEY_B64).toString();
            String generated = CookieKeyMaterial.resolve(null).toString();

            assertFalse(passed.contains(CURRENT_KEY_B64), "the sealing key never appears in toString()");
            assertTrue(passed.contains("passed key"), passed);
            assertTrue(generated.contains("generated on startup"), generated);
            assertNotEquals(passed, generated);
        }
    }
}
