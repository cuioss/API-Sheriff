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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SealedSessionPayload} — the plaintext the cookie-mode codec seals. Covers the
 * encoding round trip (including absent optionals and values that contain the field separator), the
 * absolute-TTL check anchored on the login instant, the mandatory-component contract, and the
 * redaction of every credential from {@code toString()}.
 */
class SealedSessionPayloadTest {

    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(8);
    private static final String ACCESS_TOKEN = "raw-access-token-SECRET-material";
    private static final String REFRESH_TOKEN = "raw-refresh-token-SECRET-material";
    private static final String ID_TOKEN = "raw-id-token-SECRET-material";
    private static final String SUB = "user-sub-1";

    private static SealedSessionPayload full() {
        return new SealedSessionPayload(ACCESS_TOKEN, Optional.of(REFRESH_TOKEN), ID_TOKEN, SUB,
                Optional.of("idp-sid-9"), Optional.of("urn:acr:silver"),
                Optional.of(Instant.parse("2026-07-27T09:59:00Z")), LOGIN);
    }

    private static SealedSessionPayload minimal() {
        return new SealedSessionPayload(ACCESS_TOKEN, Optional.empty(), ID_TOKEN, SUB,
                Optional.empty(), Optional.empty(), Optional.empty(), LOGIN);
    }

    /**
     * Builds the wire form {@code decode()} reads directly from raw field values, so a test can
     * express a payload shape {@code encode()} could never produce — such as an epoch second outside
     * {@link Instant}'s supported range.
     */
    private static byte[] wireForm(String... fields) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return Arrays.stream(fields)
                .map(field -> encoder.encodeToString(field.getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8);
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
        @DisplayName("Should preserve absent optionals across the round trip")
        void shouldRoundTripAbsentOptionals() {
            SealedSessionPayload original = minimal();

            SealedSessionPayload decoded = SealedSessionPayload.decode(original.encode()).orElseThrow();

            assertEquals(original, decoded);
            assertTrue(decoded.refreshToken().isEmpty());
            assertTrue(decoded.sid().isEmpty());
            assertTrue(decoded.acr().isEmpty());
            assertTrue(decoded.authTime().isEmpty());
        }

        @Test
        @DisplayName("Should round-trip values containing the field separator and other structural characters")
        void shouldRoundTripSeparatorBearingValues() {
            SealedSessionPayload original = new SealedSessionPayload("token\nwith\nnewlines", Optional.of("a=b;c"),
                    ID_TOKEN, "sub\nwith\nnewline", Optional.of("sid\n1"), Optional.empty(), Optional.empty(), LOGIN);

            assertEquals(Optional.of(original), SealedSessionPayload.decode(original.encode()),
                    "each field is base64-encoded, so no value can collide with the separator");
        }

        @Test
        @DisplayName("Should decode nothing from bytes carrying a foreign field shape")
        void shouldDecodeNothingFromForeignShape() {
            assertTrue(SealedSessionPayload.decode("not-the-expected-shape".getBytes(StandardCharsets.UTF_8)).isEmpty());
            assertTrue(SealedSessionPayload.decode(new byte[0]).isEmpty());
        }

        @Test
        @DisplayName("Should decode nothing when a field is not valid base64")
        void shouldDecodeNothingFromMalformedFields() {
            byte[] malformed = "~~~\n~~~\n~~~\n~~~\n\n\n\n~~~".getBytes(StandardCharsets.UTF_8);

            assertTrue(SealedSessionPayload.decode(malformed).isEmpty(),
                    "a base64 failure on authenticated-but-foreign bytes is no session, never an exception");
        }

        @Test
        @DisplayName("Should decode nothing when an epoch second parses as a long but exceeds Instant's range")
        void shouldDecodeNothingFromOutOfRangeEpochSecond() {
            String beyondInstantMax = "999999999999999999";
            String beyondInstantMin = "-999999999999999999";
            String login = Long.toString(LOGIN.getEpochSecond());

            assertTrue(SealedSessionPayload
                    .decode(wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "", beyondInstantMax)).isEmpty(),
                    "DateTimeException is not an IllegalArgumentException, so an out-of-range login instant "
                            + "must still decode to no session rather than escaping unseal()");
            assertTrue(SealedSessionPayload
                    .decode(wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", "", beyondInstantMin)).isEmpty());
            assertTrue(SealedSessionPayload
                    .decode(wireForm(ACCESS_TOKEN, "", ID_TOKEN, SUB, "", "", beyondInstantMax, login)).isEmpty(),
                    "the optional authTime field carries the same overflow risk as the login instant");
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
            SealedSessionPayload resealed = new SealedSessionPayload("rotated-access", Optional.of("rotated-refresh"),
                    ID_TOKEN, SUB, Optional.empty(), Optional.empty(), Optional.empty(), LOGIN);

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
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(null, Optional.empty(), ID_TOKEN,
                    SUB, Optional.empty(), Optional.empty(), Optional.empty(), LOGIN));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, Optional.empty(),
                    null, SUB, Optional.empty(), Optional.empty(), Optional.empty(), LOGIN));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, Optional.empty(),
                    ID_TOKEN, null, Optional.empty(), Optional.empty(), Optional.empty(), LOGIN));
            assertThrows(NullPointerException.class, () -> new SealedSessionPayload(ACCESS_TOKEN, Optional.empty(),
                    ID_TOKEN, SUB, Optional.empty(), Optional.empty(), Optional.empty(), null));
        }

        @Test
        @DisplayName("Should normalize a null optional to empty")
        void shouldNormalizeNullOptionals() {
            SealedSessionPayload payload = new SealedSessionPayload(ACCESS_TOKEN, null, ID_TOKEN, SUB,
                    null, null, null, LOGIN);

            assertTrue(payload.refreshToken().isEmpty());
            assertTrue(payload.sid().isEmpty());
            assertTrue(payload.acr().isEmpty());
            assertTrue(payload.authTime().isEmpty());
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
            assertTrue(rendered.contains("***REDACTED***"), rendered);
        }

        @Test
        @DisplayName("Should distinguish an absent refresh token from a redacted present one")
        void shouldDistinguishAbsentRefreshToken() {
            assertTrue(minimal().toString().contains("refreshToken=Optional.empty"), minimal().toString());
            assertTrue(full().toString().contains("refreshToken=Optional[***REDACTED***]"), full().toString());
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
