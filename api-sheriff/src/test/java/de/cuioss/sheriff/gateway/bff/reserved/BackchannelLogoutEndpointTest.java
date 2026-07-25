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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint.BackchannelLogoutOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BackchannelLogoutEndpoint}: the request/response edge over
 * {@link BackchannelLogoutReceiver}. The focus here is the {@code application/x-www-form-urlencoded}
 * body parsing — in particular that a malformed percent-encoded {@code logout_token} value fails
 * closed to {@code 400} rather than surfacing a {@code 500} (the receiver's signature seam is bound
 * to a hand-built token so the accepted path is exercised without a live IdP).
 */
class BackchannelLogoutEndpointTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "bff-client";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

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

    private BackchannelLogoutEndpoint endpoint(AtomicBoolean verifierInvoked) {
        LogoutTokenValidator validator = new LogoutTokenValidator(ISSUER, AUDIENCE, Duration.ofMinutes(2));
        BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(rawToken -> {
            verifierInvoked.set(true);
            return validLogoutToken();
        }, validator, new InMemorySessionStore(16));
        return new BackchannelLogoutEndpoint(receiver);
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
}
