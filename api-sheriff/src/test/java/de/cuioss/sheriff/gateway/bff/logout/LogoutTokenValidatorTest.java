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
package de.cuioss.sheriff.gateway.bff.logout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator.LogoutSubject;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogoutTokenValidator}: the pure OIDC back-channel-logout-token claim residual
 * applied after the engine has signature-verified the token. Every negative case in the check
 * matrix — {@code iss} wrong, {@code aud} wrong, {@code iat} outside the freshness window (both
 * directions) or absent, {@code events} missing the back-channel event, {@code nonce} present, and
 * both {@code sub}/{@code sid} absent — must fail-closed to {@link Optional#empty()}, and the two
 * single-subject success shapes ({@code sub}-only, {@code sid}-only) must resolve.
 * <p>
 * The token is hand-built as an {@link IdTokenContent} claim carrier over a {@link ClaimValue} map,
 * so every case is exercised without a live IdP or a signed token.
 */
class LogoutTokenValidatorTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "bff-client";
    private static final Duration FRESHNESS = Duration.ofMinutes(2);
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String SUB = "user-sub-1";
    private static final String SID = "idp-sid-9";
    private static final String RAW = "raw-logout-token";
    private static final String OTHER_EVENT = "{\"http://schemas.openid.net/event/token-revoked\":{}}";

    private final LogoutTokenValidator validator = new LogoutTokenValidator(ISSUER, AUDIENCE, FRESHNESS);

    private static Map<String, ClaimValue> validClaims() {
        Map<String, ClaimValue> claims = new HashMap<>(Map.of(
                "iss", ClaimValue.forPlainString(ISSUER),
                "aud", ClaimValue.forList("aud", List.of(AUDIENCE)),
                "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                "events", ClaimValue.forPlainString(
                        "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}"),
                "sub", ClaimValue.forPlainString(SUB),
                "sid", ClaimValue.forPlainString(SID)));
        return claims;
    }

    private static TokenContent token(Map<String, ClaimValue> claims) {
        return new IdTokenContent(claims, RAW);
    }

    @Nested
    @DisplayName("Accepted logout tokens")
    class Accepted {

        @Test
        @DisplayName("Should accept a fully valid logout token and resolve sub + sid")
        void shouldAcceptValidToken() {
            Optional<LogoutSubject> result = validator.validate(token(validClaims()), NOW);

            assertTrue(result.isPresent(), "a fully valid logout token is accepted");
            assertEquals(Optional.of(SUB), result.get().sub());
            assertEquals(Optional.of(SID), result.get().sid());
        }

        @Test
        @DisplayName("Should accept a token carrying only sub (sid absent)")
        void shouldAcceptSubOnly() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("sid");

            Optional<LogoutSubject> result = validator.validate(token(claims), NOW);

            assertTrue(result.isPresent());
            assertEquals(Optional.of(SUB), result.get().sub());
            assertTrue(result.get().sid().isEmpty(), "sid is absent");
        }

        @Test
        @DisplayName("Should accept a token carrying only sid (sub absent)")
        void shouldAcceptSidOnly() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("sub");

            Optional<LogoutSubject> result = validator.validate(token(claims), NOW);

            assertTrue(result.isPresent());
            assertTrue(result.get().sub().isEmpty(), "sub is absent");
            assertEquals(Optional.of(SID), result.get().sid());
        }

        @Test
        @DisplayName("Should accept a plain-string aud equal to the client id")
        void shouldAcceptPlainStringAudience() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("aud", ClaimValue.forPlainString(AUDIENCE));

            assertTrue(validator.validate(token(claims), NOW).isPresent());
        }

        @Test
        @DisplayName("Should accept an aud array that contains the client id among others")
        void shouldAcceptAudienceListContainingClient() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("aud", ClaimValue.forList("aud", List.of("other-client", AUDIENCE)));

            assertTrue(validator.validate(token(claims), NOW).isPresent());
        }
    }

    @Nested
    @DisplayName("Rejected logout tokens (fail-closed matrix)")
    class Rejected {

        @Test
        @DisplayName("Should reject a token whose iss does not equal the expected issuer")
        void shouldRejectWrongIssuer() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("iss", ClaimValue.forPlainString("https://evil.example.com"));

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token whose aud does not contain the client id")
        void shouldRejectWrongAudience() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("aud", ClaimValue.forList("aud", List.of("some-other-client")));

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token whose iat is older than the freshness window")
        void shouldRejectStaleIat() {
            Map<String, ClaimValue> claims = validClaims();
            Instant tooOld = NOW.minus(FRESHNESS).minusSeconds(1);
            claims.put("iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(tooOld, ZoneOffset.UTC)));

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token whose iat is further in the future than the freshness window")
        void shouldRejectFutureIat() {
            Map<String, ClaimValue> claims = validClaims();
            Instant tooNew = NOW.plus(FRESHNESS).plusSeconds(1);
            claims.put("iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(tooNew, ZoneOffset.UTC)));

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token that carries no iat")
        void shouldRejectMissingIat() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("iat");

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token whose events claim is absent")
        void shouldRejectMissingEvents() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("events");

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token whose events claim lacks the back-channel-logout event")
        void shouldRejectEventsWithoutBackchannelEvent() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(OTHER_EVENT));

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a scalar events claim that merely equals the event URI (not a JSON object)")
        void shouldRejectScalarEventsEqualToUri() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT));

            assertTrue(validator.validate(token(claims), NOW).isEmpty(),
                    "a scalar events string that only contains the event URI must not destroy a session");
        }

        @Test
        @DisplayName("Should reject an events claim that is a JSON array containing the event URI")
        void shouldRejectEventsArray() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "[\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\"]"));

            assertTrue(validator.validate(token(claims), NOW).isEmpty(), "events must be a JSON object, not an array");
        }

        @Test
        @DisplayName("Should reject an events object carrying the event URI only as a value, not a member key")
        void shouldRejectEventsUriInValuePosition() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "{\"event\":\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\"}"));

            assertTrue(validator.validate(token(claims), NOW).isEmpty(),
                    "the back-channel-logout URI must be a member key, not a member value");
        }

        @Test
        @DisplayName("Should accept an events object that carries the member key with surrounding whitespace")
        void shouldAcceptEventsObjectWithWhitespace() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "{ \"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\" : {} }"));

            assertTrue(validator.validate(token(claims), NOW).isPresent(),
                    "compact and pretty-printed object serializations are both accepted");
        }

        @Test
        @DisplayName("Should reject a token that carries a nonce (prohibited in a logout token)")
        void shouldRejectPresentNonce() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("nonce", ClaimValue.forPlainString("n-0S6_WzA2Mj"));

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a token that carries neither sub nor sid")
        void shouldRejectMissingSubAndSid() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("sub");
            claims.remove("sid");

            assertTrue(validator.validate(token(claims), NOW).isEmpty());
        }

        @Test
        @DisplayName("Should reject a fresh valid token when now is far past its freshness window")
        void shouldRejectWhenNowOutsideWindow() {
            Instant farLater = NOW.plus(Duration.ofHours(1));

            assertTrue(validator.validate(token(validClaims()), farLater).isEmpty(),
                    "a token minted at NOW is stale relative to a much later now");
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null token")
        void shouldRejectNullToken() {
            assertThrows(NullPointerException.class, () -> validator.validate(null, NOW));
        }

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> validator.validate(token(validClaims()), null));
        }

        @Test
        @DisplayName("Should reject null constructor arguments")
        void shouldRejectNullConstructorArguments() {
            assertAllReject();
        }

        private void assertAllReject() {
            assertThrows(NullPointerException.class, () -> new LogoutTokenValidator(null, AUDIENCE, FRESHNESS));
            assertThrows(NullPointerException.class, () -> new LogoutTokenValidator(ISSUER, null, FRESHNESS));
            assertThrows(NullPointerException.class, () -> new LogoutTokenValidator(ISSUER, AUDIENCE, null));
        }
    }

    @Nested
    @DisplayName("LogoutSubject record")
    class LogoutSubjectRecord {

        @Test
        @DisplayName("Should normalize null components to Optional.empty")
        void shouldNormalizeNullComponents() {
            LogoutSubject subject = new LogoutSubject(null, null);

            assertFalse(subject.sub().isPresent());
            assertFalse(subject.sid().isPresent());
        }
    }
}
