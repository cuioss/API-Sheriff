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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CookieKeyMaterial} — the two first-class cookie key-material modes (D2b).
 * <p>
 * The contracts under test are: a provided key file that holds one line of base64 decoding to
 * exactly 32 bytes, with any surrounding whitespace, is accepted, reported as
 * {@link CookieKeyMaterial.Mode#PROVIDED} and seals under exactly those bytes; every other file
 * content, an oversize file, a missing path and a directory are boot refusals that name
 * {@code oidc.session.key_file} and the defect but never the path, the content or a decoder message;
 * the key id, identity salt and activity key follow the key and not the file that holds it;
 * generate-on-startup produces a distinct 256-bit key per boot and records only the mode; the
 * single-key clean break, where a value sealed under a withdrawn key is refused outright; and the
 * absence of any key byte from {@link CookieKeyMaterial#toString()}.
 * <p>
 * Every key file is written to a temporary directory inside the test.
 */
@EnableTestLogger
class CookieKeyMaterialTest {

    private static final String FIELD = "oidc.session.key_file";
    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final Duration TTL = Duration.ofHours(8);
    private static final int BUDGET = SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET;
    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final byte[] CURRENT_KEY = keyBytes((byte) 0x11);
    private static final String CURRENT_KEY_B64 = Base64.getEncoder().encodeToString(CURRENT_KEY);

    /** A second, unrelated key — the replacement in a key change, and the "different key" contrast. */
    private static final String OTHER_KEY_B64 = Base64.getEncoder().encodeToString(keyBytes((byte) 0x33));

    private static final String GENERATED_KEY_RECORD =
            BffLogMessages.INFO.COOKIE_KEY_GENERATED.resolveIdentifierString();

    @TempDir
    Path directory;

    private int fileCounter;

    private static byte[] keyBytes(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return material;
    }

    private static SealedSessionPayload payload() {
        return new SealedSessionPayload("access", null, "id-token", "user-sub-1",
                null, null, null, LOGIN, "session-nonce", Set.of("openid", "profile"), Set.of("openid", "profile"));
    }

    /** Reads the key-id byte a sealed value is stamped with (value layout: version, key-id, …). */
    private static byte keyIdOf(String sealedValue) {
        return Base64.getUrlDecoder().decode(sealedValue)[1];
    }

    /** Writes a key file holding exactly the given bytes and returns its path. */
    private Path keyFile(byte[] content) throws IOException {
        fileCounter++;
        return Files.write(directory.resolve("session-" + fileCounter + ".key"), content);
    }

    /** Writes a key file holding the given text as ASCII and returns its path. */
    private Path keyFile(String content) throws IOException {
        return keyFile(content.getBytes(StandardCharsets.US_ASCII));
    }

    /** Resolves a key file holding the base64 line followed by a newline, as {@code openssl} writes it. */
    private CookieKeyMaterial provided(String base64) throws IOException {
        return CookieKeyMaterial.resolve(keyFile(base64 + "\n").toString());
    }

    private static int infoRecordsContaining(String part) {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.INFO, part).size();
    }

    /**
     * Asserts a boot refusal of a key file: the field and the defect are named, the path and every
     * non-blank line of the file are not echoed, and no cause is chained.
     */
    private static void assertRefusal(Path keyFile, String defect) throws IOException {
        List<String> contentLines = Files.isRegularFile(keyFile)
                ? new String(Files.readAllBytes(keyFile), StandardCharsets.ISO_8859_1).lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList()
                : List.of();
        String configured = keyFile.toString();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CookieKeyMaterial.resolve(configured), "the key file should be refused");

        String message = thrown.getMessage();
        assertAll("refusal of " + defect,
                () -> assertTrue(message.startsWith(FIELD + " "), "the refusal should name the field: " + message),
                () -> assertTrue(message.contains(defect), "the refusal should name the defect: " + message),
                () -> assertFalse(message.contains(configured), "the configured path should never be echoed"),
                () -> assertTrue(contentLines.stream().noneMatch(message::contains),
                        "no line of the key file should be echoed: " + message),
                () -> assertNull(thrown.getCause(), "no cause should be chained, a decoder text names the content"));
    }

    /** The whitespace forms in which one base64 line is written to a key file. */
    enum AcceptedForm {

        TRAILING_LF(line -> line + "\n"),
        TRAILING_CRLF(line -> line + "\r\n"),
        SURROUNDING_SPACES(line -> "  " + line + " \t "),
        NO_NEWLINE(UnaryOperator.identity());

        private final UnaryOperator<String> writer;

        AcceptedForm(UnaryOperator<String> writer) {
            this.writer = writer;
        }

        String write(String base64Line) {
            return writer.apply(base64Line);
        }
    }

    @Nested
    @DisplayName("Provided key file")
    class ProvidedKeyFile {

        @ParameterizedTest
        @EnumSource(AcceptedForm.class)
        @DisplayName("Should accept one base64 line in each whitespace form and round-trip through its codec")
        void shouldAcceptEachWhitespaceForm(AcceptedForm form) throws Exception {
            CookieKeyMaterial material = CookieKeyMaterial.resolve(keyFile(form.write(CURRENT_KEY_B64)).toString());
            SealedSessionCookieCodec codec = material.codec(COOKIE_NAME, TTL, BUDGET);

            assertAll("provided key file, " + form,
                    () -> assertEquals(CookieKeyMaterial.Mode.PROVIDED, material.mode(),
                            "the mode should be reported as provided"),
                    () -> assertEquals(Optional.of(payload()), codec.unseal(codec.seal(payload()))
                            .map(SealedSessionCookieCodec.Unsealed::payload), "the codec should round-trip"));
        }

        @Test
        @DisplayName("Should derive one key id from every accepted form of one key")
        void shouldDeriveOneKeyIdFromEveryForm() throws Exception {
            byte expected = provided(CURRENT_KEY_B64).currentKeyId();

            for (AcceptedForm form : AcceptedForm.values()) {
                CookieKeyMaterial material = CookieKeyMaterial.resolve(keyFile(form.write(CURRENT_KEY_B64)).toString());
                assertEquals(expected, material.currentKeyId(), "the key id follows the key, not its form: " + form);
            }
        }

        @Test
        @DisplayName("Should seal under exactly the 32 bytes the base64 line encodes")
        void shouldSealUnderTheEncodedBytes() throws Exception {
            CookieKeyMaterial material = provided(CURRENT_KEY_B64);
            SealedSessionCookieCodec overEncodedBytes = new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET,
                    new SecretKeySpec(CURRENT_KEY, "AES"), material.currentKeyId());

            String sealed = material.codec(COOKIE_NAME, TTL, BUDGET).seal(payload());

            assertAll("sealing key",
                    () -> assertEquals(32, material.currentKeyLengthBytes(), "the key should be AES-256"),
                    () -> assertEquals(Optional.of(payload()), overEncodedBytes.unseal(sealed)
                                    .map(SealedSessionCookieCodec.Unsealed::payload),
                            "a codec over the encoded bytes should unseal what the material sealed"));
        }

        @Test
        @DisplayName("Should record no generated key for a provided key file")
        void shouldRecordNoGeneratedKey() throws Exception {
            provided(CURRENT_KEY_B64);

            assertEquals(0, infoRecordsContaining(GENERATED_KEY_RECORD),
                    "a provided key should not be recorded as generated");
        }

        @Test
        @DisplayName("Should refuse a value sealed under a withdrawn key — a key change is a clean break")
        void shouldRefuseAValueSealedUnderAWithdrawnKey() throws Exception {
            CookieKeyMaterial withdrawn = provided(OTHER_KEY_B64);
            String sealedUnderWithdrawnKey = withdrawn.codec(COOKIE_NAME, TTL, BUDGET).seal(payload());

            CookieKeyMaterial replacement = provided(CURRENT_KEY_B64);
            SealedSessionCookieCodec replacementCodec = replacement.codec(COOKIE_NAME, TTL, BUDGET);

            assertNotEquals(withdrawn.currentKeyId(), replacement.currentKeyId(),
                    "the two generations are distinguishable on the wire — the id follows the key, not its position");
            assertTrue(replacementCodec.unseal(sealedUnderWithdrawnKey).isEmpty(),
                    "there is no decrypt-only companion key, so the withdrawn generation is simply 'no session'");
            assertEquals(replacement.currentKeyId(), keyIdOf(replacementCodec.seal(payload())),
                    "new values are stamped with the one active key id");
        }
    }

    /**
     * The derivations follow the key bytes, not the file: a deployment that holds the same file —
     * or the same key in another file — derives the same values, and another key derives others.
     */
    @Nested
    @DisplayName("Portability of the derived values")
    class Portability {

        private static final String SESSION_IDENTITY =
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

        @Test
        @DisplayName("Should derive the same key id, identity salt and activity key from one file read twice")
        void shouldDeriveTheSameValuesFromOneFile() throws Exception {
            String file = keyFile(CURRENT_KEY_B64 + "\n").toString();
            CookieKeyMaterial first = CookieKeyMaterial.resolve(file);
            CookieKeyMaterial second = CookieKeyMaterial.resolve(file);

            String signed = first.activityCodec(COOKIE_NAME).sign(SESSION_IDENTITY, LOGIN);

            assertAll("two replicas holding one key file",
                    () -> assertEquals(first.currentKeyId(), second.currentKeyId(), "the key id should match"),
                    () -> assertArrayEquals(first.identitySalt(), second.identitySalt(), "the salt should match"),
                    () -> assertEquals(Optional.of(LOGIN),
                            second.activityCodec(COOKIE_NAME).verify(signed, SESSION_IDENTITY, LOGIN),
                            "each should read the other's activity cookie"));
        }

        @Test
        @DisplayName("Should derive a different key id, identity salt and activity key from a different key file")
        void shouldDeriveOtherValuesFromAnotherKey() throws Exception {
            CookieKeyMaterial current = provided(CURRENT_KEY_B64);
            CookieKeyMaterial other = provided(OTHER_KEY_B64);

            String signedByOther = other.activityCodec(COOKIE_NAME).sign(SESSION_IDENTITY, LOGIN);

            assertAll("two different key files",
                    () -> assertNotEquals(current.currentKeyId(), other.currentKeyId(), "the key id should differ"),
                    () -> assertFalse(Arrays.equals(current.identitySalt(), other.identitySalt()),
                            "the salt is bound to the sealing key"),
                    () -> assertEquals(32, current.identitySalt().length, "the salt is a full SHA-256 digest"),
                    () -> assertTrue(current.activityCodec(COOKIE_NAME)
                                    .verify(signedByOther, SESSION_IDENTITY, LOGIN).isEmpty(),
                            "replacing the sealing key replaces the activity key with it"));
        }
    }

    /**
     * The activity key has no accessor, so its separation from the sealing key is proven through what
     * the two codecs accept from each other.
     */
    @Nested
    @DisplayName("Activity key")
    class ActivityKey {

        private static final String SESSION_IDENTITY =
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

        @Test
        @DisplayName("Should sign the activity cookie under a key that is not the sealing key")
        void shouldNotSignUnderTheSealingKey() throws Exception {
            CookieKeyMaterial material = provided(CURRENT_KEY_B64);
            String signedByMaterial = material.activityCodec(COOKIE_NAME).sign(SESSION_IDENTITY, LOGIN);
            byte stampedKeyId = keyIdOf(signedByMaterial);
            // A codec over the sealing key itself, stamped with the very id the derived codec uses, so the
            // key-id gate lets the value through and only the key decides.
            SessionActivityCookieCodec overSealingKey = new SessionActivityCookieCodec(COOKIE_NAME,
                    new SecretKeySpec(CURRENT_KEY, "AES"), stampedKeyId);

            assertTrue(overSealingKey.verify(signedByMaterial, SESSION_IDENTITY, LOGIN).isEmpty(),
                    "the sealing key does not verify a value the activity codec signed");
            assertTrue(material.activityCodec(COOKIE_NAME)
                            .verify(overSealingKey.sign(SESSION_IDENTITY, LOGIN), SESSION_IDENTITY, LOGIN).isEmpty(),
                    "and the activity codec does not verify a value signed under the sealing key");
        }

        @Test
        @DisplayName("Should keep the sealed session cookie and the activity cookie unreadable by each other's codec")
        void shouldKeepTheTwoCodecsApart() throws Exception {
            CookieKeyMaterial material = provided(CURRENT_KEY_B64);
            SealedSessionCookieCodec sessionCodec = material.codec(COOKIE_NAME, TTL, BUDGET);
            SessionActivityCookieCodec activityCodec = material.activityCodec(COOKIE_NAME);

            assertTrue(activityCodec.verify(sessionCodec.seal(payload()), SESSION_IDENTITY, LOGIN).isEmpty(),
                    "a sealed session value is no activity cookie");
            assertTrue(sessionCodec.unseal(activityCodec.sign(SESSION_IDENTITY, LOGIN)).isEmpty(),
                    "a signed activity value is no session");
        }
    }

    @Nested
    @DisplayName("Boot refusal of an unusable key file")
    class BootRefusal {

        @Test
        @DisplayName("Should refuse an empty file")
        void shouldRefuseAnEmptyFile() throws Exception {
            assertRefusal(keyFile(new byte[0]), "holds no key");
        }

        @Test
        @DisplayName("Should refuse a file holding whitespace only")
        void shouldRefuseAWhitespaceOnlyFile() throws Exception {
            assertRefusal(keyFile("  \t\r\n\n"), "holds no key");
        }

        @ParameterizedTest
        @ValueSource(ints = {31, 33})
        @DisplayName("Should refuse a value that decodes to the wrong length, naming the count only")
        void shouldRefuseTheWrongDecodedLength(int length) throws Exception {
            byte[] material = new byte[length];
            Arrays.fill(material, (byte) 0x5A);

            assertRefusal(keyFile(Base64.getEncoder().encodeToString(material) + "\n"),
                    "decodes to %d bytes; it must decode to exactly 32 bytes (AES-256)".formatted(length));
        }

        @ParameterizedTest
        @ValueSource(strings = {"-", "_", "!"})
        @DisplayName("Should refuse a value holding a character outside the standard base64 alphabet")
        void shouldRefuseACharacterOutsideTheAlphabet(String character) throws Exception {
            String outsideAlphabet = character + CURRENT_KEY_B64.substring(1);

            assertRefusal(keyFile(outsideAlphabet + "\n"), "is not one line of base64");
        }

        @Test
        @DisplayName("Should refuse the base64 of a 32-byte key split over two lines")
        void shouldRefuseAValueSplitOverTwoLines() throws Exception {
            String split = CURRENT_KEY_B64.substring(0, 22) + "\n" + CURRENT_KEY_B64.substring(22) + "\n";

            assertRefusal(keyFile(split), "is not one line of base64");
        }

        @Test
        @DisplayName("Should refuse a PEM-shaped file, so a PRIVATE KEY block never passes as a sealing key")
        void shouldRefuseAPemShapedFile() throws Exception {
            byte[] body = new byte[48];
            Arrays.fill(body, (byte) 0x2C);
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(body)
                    + "\n-----END PRIVATE KEY-----\n";

            assertRefusal(keyFile(pem), "is not one line of base64");
        }

        @Test
        @DisplayName("Should refuse a raw 32-byte binary key holding a byte outside the alphabet")
        void shouldRefuseARawBinaryKey() throws Exception {
            byte[] raw = new byte[32];
            Arrays.fill(raw, (byte) 0x80);

            assertRefusal(keyFile(raw), "is not one line of base64");
        }

        @Test
        @DisplayName("Should refuse a file above the 256-byte bound before decoding it")
        void shouldRefuseAnOversizeFile() throws Exception {
            String oversize = CURRENT_KEY_B64 + " ".repeat(CookieKeyMaterial.MAX_KEY_FILE_BYTES);

            assertRefusal(keyFile(oversize), "names a file larger than the 256 byte limit");
        }

        @Test
        @DisplayName("Should refuse a missing file")
        void shouldRefuseAMissingFile() throws Exception {
            assertRefusal(directory.resolve("absent.key"), "names a file that does not exist");
        }

        @Test
        @DisplayName("Should refuse a directory, which is not a regular file")
        void shouldRefuseADirectory() throws Exception {
            assertRefusal(directory, "does not name a regular file");
        }
    }

    @Nested
    @DisplayName("Generate-on-startup mode")
    class GenerateOnStartup {

        @Test
        @DisplayName("Should select the generated mode when no key file is configured")
        void shouldSelectGeneratedMode() {
            CookieKeyMaterial material = CookieKeyMaterial.resolve(null);

            assertEquals(CookieKeyMaterial.Mode.GENERATED, material.mode());
        }

        @Test
        @DisplayName("Should record the generated key once, with the mode and no key material")
        void shouldRecordTheGeneratedKey() {
            CookieKeyMaterial.resolve(null);

            assertAll("generated-key record",
                    () -> assertEquals(1, infoRecordsContaining(GENERATED_KEY_RECORD), "one record per generated key"),
                    () -> assertEquals(1, infoRecordsContaining(
                            "Cookie-mode sealing key generated at startup (generated on startup)"),
                            "the record should carry the mode"));
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
        void shouldNotLeakKeyMaterialIntoToString() throws Exception {
            String provided = provided(CURRENT_KEY_B64).toString();
            String generated = CookieKeyMaterial.resolve(null).toString();

            assertFalse(provided.contains(CURRENT_KEY_B64), "the sealing key never appears in toString()");
            assertTrue(provided.contains("provided key file"), provided);
            assertTrue(generated.contains("generated on startup"), generated);
            assertNotEquals(provided, generated);
        }
    }
}
