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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutRejection;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenReplayGuard;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.logout.VerifiedLogoutToken;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint.BackchannelLogoutOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BackchannelLogoutEndpoint}: the request/response edge over
 * {@link BackchannelLogoutReceiver}.
 * <p>
 * Two concerns are covered. In <strong>server mode</strong> the focus is the
 * {@code application/x-www-form-urlencoded} body parsing — in particular that a malformed
 * percent-encoded {@code logout_token} value fails closed to {@code 400} rather than surfacing a
 * {@code 500} (the receiver's signature seam is bound to a hand-built token so the accepted path is
 * exercised without a live IdP). In <strong>cookie mode</strong> the focus is the capability gate:
 * a binding reporting {@link SessionBinding.IdpDestruction#UNSUPPORTED} answers {@code 404} for every
 * request without ever parsing the body or reaching the receiver.
 * <p>
 * The third concern is the <strong>log-flood latch</strong> both attacker-reachable rejections on this
 * reserved, unauthenticated path run under: each is reported at {@code WARN} on its first occurrence
 * and silent afterwards. Both halves are asserted together, because losing either one — the record or
 * the bound — is a defect.
 */
@EnableTestLogger
class BackchannelLogoutEndpointTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "bff-client";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final byte CURRENT_KEY_ID = 1;

    private static Map<String, ClaimValue> validLogoutClaims() {
        return new HashMap<>(Map.of(
                "iss", ClaimValue.forPlainString(ISSUER),
                "aud", ClaimValue.forList("aud", List.of(AUDIENCE)),
                "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                "exp", ClaimValue.forDateTime("exp", OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC)),
                "jti", ClaimValue.forPlainString("logout-token-id-1"),
                "events", ClaimValue.forPlainString(
                        "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}"),
                "sub", ClaimValue.forPlainString("user-sub-1")));
    }

    private static VerifiedLogoutToken validLogoutToken() {
        return typed(validLogoutClaims(), LogoutTokenValidator.LOGOUT_TOKEN_TYPE);
    }

    private static VerifiedLogoutToken typed(Map<String, ClaimValue> claims, @Nullable String headerType) {
        return new VerifiedLogoutToken(new IdTokenContent(claims, "raw-logout-token"), headerType);
    }

    /** The store-backed binding — {@code SUPPORTED} IdP destruction, so the gate stays open. */
    private static SessionBinding serverBinding() {
        return new ServerSessionBinding(
                new InMemorySessionStore(16, Duration.ofHours(8), Integer.MAX_VALUE, sessionId -> { }),
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(8)));
    }

    /** An endpoint over the server-mode binding whose signature seam hands out {@code token}. */
    private static BackchannelLogoutEndpoint endpointReceiving(VerifiedLogoutToken token, int replayCapacity) {
        return endpointReceiving(() -> token, replayCapacity);
    }

    /** As {@link #endpointReceiving(VerifiedLogoutToken, int)}, asking {@code tokens} on every delivery. */
    private static BackchannelLogoutEndpoint endpointReceiving(Supplier<VerifiedLogoutToken> tokens,
            int replayCapacity) {
        SessionBinding binding = serverBinding();
        BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(rawToken -> tokens.get(),
                new LogoutTokenValidator(ISSUER, AUDIENCE, Duration.ofMinutes(2)), binding,
                new LogoutTokenReplayGuard(replayCapacity));
        return new BackchannelLogoutEndpoint(receiver, binding);
    }

    /** The stateless binding — {@code UNSUPPORTED} IdP destruction, so the gate closes the endpoint. */
    private static SessionBinding cookieBinding() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        SecretKey sealingKey = new SecretKeySpec(key, "AES");
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        byte[] activityKey = new byte[32];
        Arrays.fill(activityKey, (byte) 0x44);
        return new CookieSessionBinding(
                new SealedSessionCookieCodec(COOKIE_NAME, Duration.ofHours(8),
                        SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, sealingKey, CURRENT_KEY_ID), salt,
                new SessionActivityCookieCodec(COOKIE_NAME, new SecretKeySpec(activityKey, "AES"), (byte) 2),
                Duration.ofHours(8));
    }

    private BackchannelLogoutEndpoint endpoint(AtomicBoolean verifierInvoked) {
        return endpoint(verifierInvoked, serverBinding());
    }

    private BackchannelLogoutEndpoint endpoint(AtomicBoolean verifierInvoked, SessionBinding binding) {
        LogoutTokenValidator validator = new LogoutTokenValidator(ISSUER, AUDIENCE, Duration.ofMinutes(2));
        BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(rawToken -> {
            verifierInvoked.set(true);
            return validLogoutToken();
        }, validator, binding, new LogoutTokenReplayGuard(16));
        return new BackchannelLogoutEndpoint(receiver, binding);
    }

    /**
     * A logout token the identity provider signed and the gateway still does not act on. Each refusal
     * answers {@code 400} and destroys nothing; the accepted delivery beside each one is what shows
     * the endpoint is not refusing everything.
     */
    @Nested
    @DisplayName("Signed tokens that are refused")
    class RefusedSignedTokens {

        private static final String BODY = "logout_token=abc.def.ghi";

        private static void assertRefused(BackchannelLogoutOutcome outcome) {
            assertEquals(400, outcome.status());
            assertFalse(outcome.isAccepted());
            assertEquals(0, outcome.destroyed());
        }

        @Test
        @DisplayName("Should accept an untyped token and refuse a token typed as something else 400")
        void shouldRefuseWrongType() {
            assertTrue(endpointReceiving(typed(validLogoutClaims(), null), 16).receive(BODY, NOW).isAccepted(),
                    "a token without a typ header is accepted");

            assertRefused(endpointReceiving(typed(validLogoutClaims(), "JWT"), 16).receive(BODY, NOW));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    LogoutRejection.TYPE_MISMATCH.token());
        }

        @Test
        @DisplayName("Should refuse a token without exp and a token whose exp has passed 400")
        void shouldRefuseMissingOrPassedExpiry() {
            Map<String, ClaimValue> withoutExp = validLogoutClaims();
            withoutExp.remove("exp");
            Map<String, ClaimValue> passedExp = validLogoutClaims();
            passedExp.put("exp", ClaimValue.forDateTime("exp", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));

            assertRefused(endpointReceiving(typed(withoutExp, null), 16).receive(BODY, NOW));
            assertRefused(endpointReceiving(typed(passedExp, null), 16).receive(BODY, NOW));
        }

        @Test
        @DisplayName("Should refuse a token without jti 400")
        void shouldRefuseMissingJti() {
            Map<String, ClaimValue> withoutJti = validLogoutClaims();
            withoutJti.remove("jti");

            assertRefused(endpointReceiving(typed(withoutJti, null), 16).receive(BODY, NOW));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    LogoutRejection.JTI_MISSING.token());
        }

        @Test
        @DisplayName("Should accept a token once and refuse its second delivery 400")
        void shouldRefuseSecondDelivery() {
            BackchannelLogoutEndpoint endpoint = endpointReceiving(validLogoutToken(), 16);

            assertEquals(200, endpoint.receive(BODY, NOW).status());
            assertRefused(endpoint.receive(BODY, NOW.plusSeconds(5)));
        }

        @Test
        @DisplayName("Should refuse a new token while the replay memory is full 400")
        void shouldRefuseWhenReplayMemoryIsFull() {
            Map<String, ClaimValue> claims = validLogoutClaims();
            BackchannelLogoutEndpoint endpoint = endpointReceiving(() -> typed(claims, null), 1);

            assertEquals(200, endpoint.receive(BODY, NOW).status());
            claims.put("jti", ClaimValue.forPlainString("logout-token-id-2"));

            assertRefused(endpoint.receive(BODY, NOW));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    LogoutRejection.REPLAY_MEMORY_FULL.token());
        }
    }

    @Test
    @DisplayName("Should reject a logout_token carrying malformed percent-encoding 400, without invoking the receiver")
    void shouldRejectMalformedPercentEncoding() {
        AtomicBoolean verifierInvoked = new AtomicBoolean(false);

        BackchannelLogoutOutcome outcome = endpoint(verifierInvoked).receive("logout_token=%ZZ", NOW);

        assertEquals(400, outcome.status(), "malformed percent-encoding fails closed to 400, not 500");
        assertFalse(outcome.isAccepted());
        assertFalse(verifierInvoked.get(), "a body the endpoint could not decode never reaches the receiver");
    }

    @Test
    @DisplayName("Should reject a malformed percent-encoded parameter name 400")
    void shouldRejectMalformedParameterName() {
        AtomicBoolean verifierInvoked = new AtomicBoolean(false);

        BackchannelLogoutOutcome outcome = endpoint(verifierInvoked).receive("logout%ZZtoken=abc", NOW);

        assertEquals(400, outcome.status());
        assertFalse(verifierInvoked.get());
    }

    @Test
    @DisplayName("Should reject a request with no logout_token parameter 400")
    void shouldRejectAbsentToken() {
        BackchannelLogoutOutcome outcome = endpoint(new AtomicBoolean(false)).receive("other=value", NOW);

        assertEquals(400, outcome.status());
    }

    @Test
    @DisplayName("Should accept a well-formed percent-encoded logout_token 200")
    void shouldAcceptWellFormedToken() {
        AtomicBoolean verifierInvoked = new AtomicBoolean(false);

        BackchannelLogoutOutcome outcome = endpoint(verifierInvoked).receive("logout_token=abc.def.ghi", NOW);

        assertEquals(200, outcome.status());
        assertTrue(outcome.isAccepted());
        assertTrue(verifierInvoked.get(), "a well-formed token reaches the signature-verification seam");
    }

    /**
     * The cookie-mode capability gate: the stateless binding reports
     * {@link SessionBinding.IdpDestruction#UNSUPPORTED}, so every back-channel request is answered
     * {@code 404} before the body is parsed — the gateway never claims a destruction it cannot perform.
     */
    @Nested
    @DisplayName("Cookie mode — capability-gated to 404")
    class CookieModeGate {

        @Test
        @DisplayName("Should answer 404 for a well-formed logout_token without ever parsing it")
        void shouldGateWellFormedToken() {
            AtomicBoolean verifierInvoked = new AtomicBoolean(false);

            BackchannelLogoutOutcome outcome =
                    endpoint(verifierInvoked, cookieBinding()).receive("logout_token=abc.def.ghi", NOW);

            assertEquals(404, outcome.status(), "a binding without IdP destruction gates the endpoint off");
            assertFalse(outcome.isAccepted());
            assertEquals(0, outcome.destroyed(), "a gated request destroys nothing");
            assertFalse(verifierInvoked.get(),
                    "the gate fires before the body is parsed — the logout_token is never read");
        }

        @Test
        @DisplayName("Should answer 404 for an absent body rather than the server-mode 400")
        void shouldGateAbsentBody() {
            AtomicBoolean verifierInvoked = new AtomicBoolean(false);

            BackchannelLogoutOutcome outcome = endpoint(verifierInvoked, cookieBinding()).receive(null, NOW);

            assertEquals(404, outcome.status(), "the gate precedes the missing-token 400 path");
            assertFalse(verifierInvoked.get());
        }

        @Test
        @DisplayName("Should answer 404 for a malformed body, never the server-mode 400")
        void shouldGateMalformedBody() {
            AtomicBoolean verifierInvoked = new AtomicBoolean(false);

            BackchannelLogoutOutcome outcome =
                    endpoint(verifierInvoked, cookieBinding()).receive("logout_token=%ZZ", NOW);

            assertEquals(404, outcome.status());
            assertFalse(verifierInvoked.get());
        }

        /**
         * The latch, in both halves: the FIRST gated request is reported at the default log level so a
         * genuine misconfiguration is visible without a DEBUG re-run, and the four repeats that follow
         * add nothing — which is what bounds an attacker on this reserved, unauthenticated path to a
         * single line for the life of the process.
         * <p>
         * {@code assertSingleLogMessagePresentContaining} is the assertion that carries both halves at
         * once: it fails on zero occurrences (the record was lost) and equally on five (the flood guard
         * was lost). Asserting only presence, or only absence, would pass in one of the two failure
         * modes this rule exists to prevent.
         */
        @Test
        @DisplayName("Should report the capability-gate rejection once, then latch it to DEBUG")
        void shouldLatchCapabilityGateWarning() {
            BackchannelLogoutEndpoint endpoint = endpoint(new AtomicBoolean(false), cookieBinding());

            for (int request = 0; request < 5; request++) {
                assertEquals(404, endpoint.receive("logout_token=abc.def.ghi", NOW).status());
            }

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY.token());
        }
    }

    /**
     * The server-mode half of the same latch: {@code missing-logout-token} is equally
     * attacker-triggerable, so it too is reported once and then falls silent.
     */
    @Nested
    @DisplayName("Server mode — latched missing-token reporting")
    class MissingTokenReporting {

        @Test
        @DisplayName("Should report a missing logout_token once, then latch it to DEBUG")
        void shouldLatchMissingTokenWarning() {
            BackchannelLogoutEndpoint endpoint = endpoint(new AtomicBoolean(false));

            for (int request = 0; request < 5; request++) {
                assertEquals(400, endpoint.receive("other=value", NOW).status());
            }

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    LogoutRejection.MISSING_LOGOUT_TOKEN.token());
        }

        @Test
        @DisplayName("Should not report a rejection at all when the token is accepted")
        void shouldNotReportOnAcceptance() {
            assertTrue(endpoint(new AtomicBoolean(false)).receive("logout_token=abc.def.ghi", NOW).isAccepted());

            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, BackchannelLogoutEndpoint.class);
        }
    }
}
