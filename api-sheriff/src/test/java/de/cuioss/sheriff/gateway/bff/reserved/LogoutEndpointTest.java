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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;


import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.TokenRevocation;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint.LogoutOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogoutEndpoint}, focused on the invariant that local logout ALWAYS succeeds. When
 * the IdP end-session redirect construction fails ({@link RpInitiatedLogout#initiate} throws, e.g. the
 * engine {@link PostLogoutRedirectValidator} rejects an unregistered {@code post_logout_redirect_uri}),
 * the endpoint must still destroy the server-side session and clear the session cookie before landing
 * the browser on {@code final_redirect} — a redirect-construction failure must never leave the local
 * session usable.
 */
class LogoutEndpointTest {

    private static final String END_SESSION = "https://idp.example.com/protocol/openid-connect/logout";
    private static final String REGISTERED_RETURN = "https://gw.example.com/auth/logout/return";
    private static final String FINAL_REDIRECT = "/goodbye";
    private static final Duration STATE_TTL = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    private InMemorySessionStore store;
    private SessionCookieCodec cookieCodec;
    private EndSessionFlow endSessionFlow;
    private String sessionId;
    private String cookieHeader;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16);
        cookieCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(8));
        endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(REGISTERED_RETURN)));
        sessionId = SessionRecord.newSessionId();
        SessionRecord session = SessionRecord.builder()
                .sessionId(sessionId)
                .accessToken("mediated-access-token")
                .idToken("raw-id-token")
                .sub("user-sub-1")
                .expiresAt(NOW.plus(Duration.ofHours(8)))
                .build();
        store.create(session);
        cookieHeader = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId;
    }

    private LogoutEndpoint endpoint(String postLogoutRedirectUri) {
        TokenRevocation revocation = ignored -> {
        };
        RpInitiatedLogout logout = new RpInitiatedLogout(endSessionFlow, revocation, END_SESSION,
                postLogoutRedirectUri, FINAL_REDIRECT, STATE_TTL);
        return new LogoutEndpoint(logout, store, cookieCodec);
    }

    @Test
    @DisplayName("Should destroy the local session and clear the cookie when end-session redirect construction fails")
    void shouldAlwaysLogOutLocallyOnInitiationFailure() {
        // An unregistered post_logout_redirect_uri makes the engine validator throw inside initiate().
        LogoutEndpoint endpoint = endpoint("https://evil.example.com/steal");

        LogoutOutcome outcome = endpoint.logout(cookieHeader, NOW);

        assertTrue(outcome.isRedirect(), "local logout must still land the browser on a safe redirect");
        assertEquals(FINAL_REDIRECT, outcome.location().orElseThrow(),
                "a failed IdP redirect falls back to final_redirect");
        assertTrue(store.resolve(sessionId, NOW).isEmpty(),
                "the server-side session is destroyed even when the IdP redirect could not be built");
        assertTrue(outcome.setCookieHeaders().stream().anyMatch(header -> header.contains("Max-Age=0")),
                "the session cookie is cleared on the fallback path");
    }

    @Test
    @DisplayName("Should redirect to the IdP end_session_endpoint and destroy the session on the happy path")
    void shouldRedirectToIdpAndDestroyOnSuccess() {
        LogoutEndpoint endpoint = endpoint(REGISTERED_RETURN);

        LogoutOutcome outcome = endpoint.logout(cookieHeader, NOW);

        assertTrue(outcome.isRedirect());
        assertTrue(outcome.location().orElseThrow().startsWith(END_SESSION),
                "a registered return URI yields the IdP end-session redirect");
        assertTrue(store.resolve(sessionId, NOW).isEmpty(), "the server-side session is destroyed");
    }

    @Test
    @DisplayName("Should land on final_redirect and clear the cookie when no live session is present")
    void shouldLandOnFinalRedirectWithoutSession() {
        LogoutEndpoint endpoint = endpoint(REGISTERED_RETURN);

        LogoutOutcome outcome = endpoint.logout(null, NOW);

        assertTrue(outcome.isRedirect());
        assertEquals(FINAL_REDIRECT, outcome.location().orElseThrow());
        assertTrue(outcome.setCookieHeaders().stream().anyMatch(header -> header.contains("Max-Age=0")));
    }
}
