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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint.BackchannelLogoutOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;

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
 */
class BackchannelLogoutEndpointTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "bff-client";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final byte CURRENT_KEY_ID = 1;

    private static TokenContent validLogoutToken() {
        Map<String, ClaimValue> claims = Map.of(
                "iss", ClaimValue.forPlainString(ISSUER),
                "aud", ClaimValue.forList("aud", List.of(AUDIENCE)),
                "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                "events", ClaimValue.forPlainString(
                        "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}"),
                "sub", ClaimValue.forPlainString("user-sub-1"));
        return new IdTokenContent(claims, "raw-logout-token");
    }

    /** The store-backed binding — {@code SUPPORTED} IdP destruction, so the gate stays open. */
    private static SessionBinding serverBinding() {
        return new ServerSessionBinding(new InMemorySessionStore(16),
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(8)));
    }

    /** The stateless binding — {@code UNSUPPORTED} IdP destruction, so the gate closes the endpoint. */
    private static SessionBinding cookieBinding() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        SecretKey sealingKey = new SecretKeySpec(key, "AES");
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        return new CookieSessionBinding(
                new SealedSessionCookieCodec(COOKIE_NAME, Duration.ofHours(8), sealingKey, CURRENT_KEY_ID), salt);
    }

    private BackchannelLogoutEndpoint endpoint(AtomicBoolean verifierInvoked) {
        return endpoint(verifierInvoked, serverBinding());
    }

    private BackchannelLogoutEndpoint endpoint(AtomicBoolean verifierInvoked, SessionBinding binding) {
        LogoutTokenValidator validator = new LogoutTokenValidator(ISSUER, AUDIENCE, Duration.ofMinutes(2));
        BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(rawToken -> {
            verifierInvoked.set(true);
            return validLogoutToken();
        }, validator, binding);
        return new BackchannelLogoutEndpoint(receiver, binding);
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
    }
}
