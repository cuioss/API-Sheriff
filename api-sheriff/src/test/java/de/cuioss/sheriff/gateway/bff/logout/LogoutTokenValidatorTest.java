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
package de.cuioss.sheriff.gateway.bff.logout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator.LogoutSubject;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator.Verdict;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogoutTokenValidator}: the pure OIDC back-channel-logout-token claim residual
 * applied after the seam ahead of it has verified the token's signature. Every negative case in the
 * check matrix — {@code iss} wrong, {@code aud} wrong, {@code iat} outside the freshness window (both
 * directions) or absent, {@code events} missing the back-channel event, {@code nonce} present, and
 * both {@code sub}/{@code sid} absent — must fail closed, and must name the <em>specific</em>
 * {@link LogoutRejection} that refused: that reason is what reaches the operator as
 * {@code ApiSheriff-112}, so a verdict that merely says "rejected" is not good enough.
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

    /**
     * The {@code events} claim as the engine surfaces it when it arrived as a JSON <em>object</em> —
     * a deserialized {@code Map} run through {@code Object#toString()}. This, not the JSON form, is
     * what a real identity provider's logout token reaches the validator as.
     */
    private static final String EVENTS_ENGINE_FORM =
            "{" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "={}}";

    /** The same claim as it arrives when it was carried on the wire as a JSON string. */
    private static final String EVENTS_JSON_FORM =
            "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}";

    private final LogoutTokenValidator validator = new LogoutTokenValidator(ISSUER, AUDIENCE, FRESHNESS);

    private static Map<String, ClaimValue> validClaims() {
        return new HashMap<>(Map.of(
                "iss", ClaimValue.forPlainString(ISSUER),
                "aud", ClaimValue.forList("aud", List.of(AUDIENCE)),
                "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                "events", ClaimValue.forPlainString(EVENTS_JSON_FORM),
                "sub", ClaimValue.forPlainString(SUB),
                "sid", ClaimValue.forPlainString(SID)));
    }

    private static TokenContent token(Map<String, ClaimValue> claims) {
        return new IdTokenContent(claims, RAW);
    }

    private static LogoutSubject assertAccepted(Verdict verdict) {
        return assertInstanceOf(Verdict.Accepted.class, verdict, "expected the token to be accepted").subject();
    }

    private static void assertRejectedFor(Verdict verdict, LogoutRejection expected) {
        Verdict.Rejected rejected = assertInstanceOf(Verdict.Rejected.class, verdict,
                "expected the token to be rejected");
        assertEquals(expected, rejected.reason(),
                "the verdict must name the check that actually refused — that reason is logged verbatim");
    }

    @Nested
    @DisplayName("Accepted logout tokens")
    class Accepted {

        @Test
        @DisplayName("Should accept a fully valid logout token and resolve sub + sid")
        void shouldAcceptValidToken() {
            LogoutSubject subject = assertAccepted(validator.validate(token(validClaims()), NOW));

            assertEquals(SUB, subject.sub());
            assertEquals(SID, subject.sid());
        }

        @Test
        @DisplayName("Should accept a token carrying only sub (sid absent)")
        void shouldAcceptSubOnly() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("sid");

            LogoutSubject subject = assertAccepted(validator.validate(token(claims), NOW));

            assertEquals(SUB, subject.sub());
            assertNull(subject.sid(), "sid is absent");
        }

        @Test
        @DisplayName("Should accept a token carrying only sid (sub absent)")
        void shouldAcceptSidOnly() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("sub");

            LogoutSubject subject = assertAccepted(validator.validate(token(claims), NOW));

            assertNull(subject.sub(), "sub is absent");
            assertEquals(SID, subject.sid());
        }

        @Test
        @DisplayName("Should accept a plain-string aud equal to the client id")
        void shouldAcceptPlainStringAudience() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("aud", ClaimValue.forPlainString(AUDIENCE));

            assertAccepted(validator.validate(token(claims), NOW));
        }

        @Test
        @DisplayName("Should accept an aud array that contains the client id among others")
        void shouldAcceptAudienceListContainingClient() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("aud", ClaimValue.forList("aud", List.of("other-client", AUDIENCE)));

            assertAccepted(validator.validate(token(claims), NOW));
        }
    }

    /**
     * The {@code events} claim reaches this validator as a string in one of two shapes, depending on
     * whether it travelled as a JSON object (the spec shape, stringified by the engine from the
     * deserialized {@code Map}) or as a JSON string (round-tripped verbatim). Both must be accepted
     * with the member-<em>key</em> discrimination intact — the engine form is the one a real identity
     * provider actually produces, and matching only the JSON form rejected every such token.
     */
    @Nested
    @DisplayName("events claim — both surfaced representations")
    class EventsRepresentations {

        @Test
        @DisplayName("Should accept the engine's Map#toString form a real IdP token arrives as")
        void shouldAcceptEngineMapForm() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(EVENTS_ENGINE_FORM));

            assertAccepted(validator.validate(token(claims), NOW));
        }

        @Test
        @DisplayName("Should accept the engine form alongside other event members")
        void shouldAcceptEngineMapFormAmongOthers() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString("{http://schemas.openid.net/event/token-revoked={}, "
                    + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "={}}"));

            assertAccepted(validator.validate(token(claims), NOW));
        }

        @Test
        @DisplayName("Should reject the engine form carrying the event URI only as a member value")
        void shouldRejectEngineMapFormValuePosition() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "{event=" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "}"));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
        }

        @Test
        @DisplayName("Should reject the engine form of a different event")
        void shouldRejectEngineMapFormOtherEvent() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString("{http://schemas.openid.net/event/token-revoked={}}"));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
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

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.ISSUER_MISMATCH);
        }

        @Test
        @DisplayName("Should reject a token carrying no iss at all")
        void shouldRejectMissingIssuer() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("iss");

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.ISSUER_MISMATCH);
        }

        @Test
        @DisplayName("Should reject a token whose aud does not contain the client id")
        void shouldRejectWrongAudience() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("aud", ClaimValue.forList("aud", List.of("some-other-client")));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.AUDIENCE_MISMATCH);
        }

        @Test
        @DisplayName("Should reject a token whose iat is older than the freshness window")
        void shouldRejectStaleIat() {
            Map<String, ClaimValue> claims = validClaims();
            Instant tooOld = NOW.minus(FRESHNESS).minusSeconds(1);
            claims.put("iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(tooOld, ZoneOffset.UTC)));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.IAT_OUTSIDE_WINDOW);
        }

        @Test
        @DisplayName("Should reject a token whose iat is further in the future than the freshness window")
        void shouldRejectFutureIat() {
            Map<String, ClaimValue> claims = validClaims();
            Instant tooNew = NOW.plus(FRESHNESS).plusSeconds(1);
            claims.put("iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(tooNew, ZoneOffset.UTC)));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.IAT_OUTSIDE_WINDOW);
        }

        @Test
        @DisplayName("Should reject a token that carries no iat")
        void shouldRejectMissingIat() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("iat");

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.IAT_OUTSIDE_WINDOW);
        }

        @Test
        @DisplayName("Should reject a token whose events claim is absent")
        void shouldRejectMissingEvents() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("events");

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
        }

        @Test
        @DisplayName("Should reject a token whose events claim lacks the back-channel-logout event")
        void shouldRejectEventsWithoutBackchannelEvent() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(OTHER_EVENT));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
        }

        @Test
        @DisplayName("Should reject a scalar events claim that merely equals the event URI (not an object)")
        void shouldRejectScalarEventsEqualToUri() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
        }

        @Test
        @DisplayName("Should reject an events claim that is a JSON array containing the event URI")
        void shouldRejectEventsArray() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "[\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\"]"));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
        }

        @Test
        @DisplayName("Should reject an events object carrying the event URI only as a value, not a member key")
        void shouldRejectEventsUriInValuePosition() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "{\"event\":\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\"}"));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.EVENTS_MISSING);
        }

        @Test
        @DisplayName("Should accept an events object that carries the member key with surrounding whitespace")
        void shouldAcceptEventsObjectWithWhitespace() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("events", ClaimValue.forPlainString(
                    "{ \"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\" : {} }"));

            assertAccepted(validator.validate(token(claims), NOW));
        }

        @Test
        @DisplayName("Should reject a token that carries a nonce (prohibited in a logout token)")
        void shouldRejectPresentNonce() {
            Map<String, ClaimValue> claims = validClaims();
            claims.put("nonce", ClaimValue.forPlainString("n-0S6_WzA2Mj"));

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.NONCE_PRESENT);
        }

        @Test
        @DisplayName("Should reject a token that carries neither sub nor sid")
        void shouldRejectMissingSubAndSid() {
            Map<String, ClaimValue> claims = validClaims();
            claims.remove("sub");
            claims.remove("sid");

            assertRejectedFor(validator.validate(token(claims), NOW), LogoutRejection.NO_SUB_OR_SID);
        }

        @Test
        @DisplayName("Should reject a fresh valid token when now is far past its freshness window")
        void shouldRejectWhenNowOutsideWindow() {
            Instant farLater = NOW.plus(Duration.ofHours(1));

            assertRejectedFor(validator.validate(token(validClaims()), farLater),
                    LogoutRejection.IAT_OUTSIDE_WINDOW);
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
            TokenContent token = token(validClaims());
            assertThrows(NullPointerException.class, () -> validator.validate(token, null));
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
        @DisplayName("Should accept absent components as null")
        void shouldAcceptNullComponents() {
            LogoutSubject subject = new LogoutSubject(null, null);

            assertNull(subject.sub());
            assertNull(subject.sid());
        }
    }
}
