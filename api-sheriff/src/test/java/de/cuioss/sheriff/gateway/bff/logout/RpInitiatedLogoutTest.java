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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.LogoutRedirect;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.LogoutReturn;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.TokenRevocation;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RpInitiatedLogout}: the gateway-side RP-initiated logout orchestration exercised
 * against the <em>real</em> engine {@link EndSessionFlow} (constructed with a
 * {@link PostLogoutRedirectValidator}), so the exact-match {@code post_logout_redirect_uri}
 * open-redirect defence and the constant-time {@code state} verification are the genuine engine code
 * paths — not stubs.
 * <p>
 * Covered: the initiate leg (best-effort revocation, engine end-session redirect carrying
 * {@code id_token_hint}/{@code post_logout_redirect_uri}/{@code state}, and the single-use
 * {@code __Host-sheriff-logout} cookie), the logout-state cookie round-trip through
 * {@link RpInitiatedLogout#completeReturn}, and the open-redirect rejection of an unregistered
 * {@code post_logout_redirect_uri}.
 */
class RpInitiatedLogoutTest {

    private static final String END_SESSION = "https://idp.example.com/protocol/openid-connect/logout";
    private static final String REGISTERED_RETURN = "https://gw.example.com/auth/logout/return";
    private static final String FINAL_REDIRECT = "/goodbye";
    private static final Duration STATE_TTL = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String RAW_ID_TOKEN = "raw-id-token-hint";

    private AtomicReference<SessionRecord> revoked;
    private EndSessionFlow endSessionFlow;
    private RpInitiatedLogout logout;
    private SessionRecord session;

    @BeforeEach
    void setUp() {
        revoked = new AtomicReference<>();
        TokenRevocation revocation = revoked::set;
        endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(REGISTERED_RETURN)));
        logout = new RpInitiatedLogout(endSessionFlow, revocation, END_SESSION, REGISTERED_RETURN, FINAL_REDIRECT,
                STATE_TTL);
        session = SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken("mediated-access-token")
                .idToken(RAW_ID_TOKEN)
                .sub("user-sub-1")
                .expiresAt(NOW.plus(Duration.ofHours(8)))
                .build();
    }

    private static String cookieValue(String setCookieHeader) {
        String firstPair = setCookieHeader.split(";", 2)[0];
        return firstPair.substring(firstPair.indexOf('=') + 1);
    }

    @Nested
    @DisplayName("Logout initiation")
    class Initiation {

        @Test
        @DisplayName("Should build an end-session redirect carrying id_token_hint, post_logout_redirect_uri, and state")
        void shouldBuildEndSessionRedirect() {
            LogoutRedirect redirect = logout.initiate(session);

            assertTrue(redirect.location().startsWith(END_SESSION), redirect.location());
            assertTrue(redirect.location().contains("id_token_hint="), "the id_token_hint is present");
            assertTrue(redirect.location().contains("post_logout_redirect_uri="),
                    "the exact post_logout_redirect_uri is present");
            String state = cookieValue(redirect.setCookieHeaders().getFirst());
            assertTrue(redirect.location().contains("state=" + state),
                    "the redirect state matches the minted cookie state");
        }

        @Test
        @DisplayName("Should set the single-use __Host-sheriff-logout state cookie hardened and short-lived")
        void shouldSetHardenedStateCookie() {
            LogoutRedirect redirect = logout.initiate(session);

            assertEquals(1, redirect.setCookieHeaders().size());
            String cookie = redirect.setCookieHeaders().getFirst();
            assertTrue(cookie.startsWith(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "="), cookie);
            assertTrue(cookie.contains("Max-Age=" + STATE_TTL.toSeconds()), cookie);
            assertTrue(cookie.contains("Path=/"), cookie);
            assertTrue(cookie.contains("Secure"), cookie);
            assertTrue(cookie.contains("HttpOnly"), cookie);
            assertTrue(cookie.contains("SameSite=Lax"), cookie);
        }

        @Test
        @DisplayName("Should mint a fresh unpredictable state on every initiation")
        void shouldMintFreshStatePerInitiation() {
            String first = cookieValue(logout.initiate(session).setCookieHeaders().getFirst());
            String second = cookieValue(logout.initiate(session).setCookieHeaders().getFirst());

            assertNotEquals(first, second, "each logout mints a distinct state");
        }

        @Test
        @DisplayName("Should revoke the mediated tokens best-effort during initiation")
        void shouldRevokeMediatedTokens() {
            logout.initiate(session);

            assertEquals(session, revoked.get(), "the session's tokens are handed to the revocation seam");
        }

        @Test
        @DisplayName("Should proceed with logout even when token revocation fails")
        void shouldProceedWhenRevocationFails() {
            TokenRevocation failing = ignored -> {
                throw new IllegalStateException("revocation endpoint unreachable");
            };
            RpInitiatedLogout resilient = new RpInitiatedLogout(endSessionFlow, failing, END_SESSION,
                    REGISTERED_RETURN, FINAL_REDIRECT, STATE_TTL);

            LogoutRedirect redirect = resilient.initiate(session);

            assertTrue(redirect.location().startsWith(END_SESSION),
                    "a revocation failure never strands the browser half-logged-out");
        }
    }

    @Nested
    @DisplayName("Open-redirect defence (exact post_logout_redirect_uri match)")
    class OpenRedirectDefence {

        @Test
        @DisplayName("Should reject an unregistered post_logout_redirect_uri via the engine validator")
        void shouldRejectUnregisteredReturnUri() {
            RpInitiatedLogout evil = new RpInitiatedLogout(endSessionFlow, revoked::set, END_SESSION,
                    "https://evil.example.com/steal", FINAL_REDIRECT, STATE_TTL);

            assertThrows(ClientProtocolException.class, () -> evil.initiate(session),
                    "the engine PostLogoutRedirectValidator rejects a non-exact-match return URI");
        }
    }

    @Nested
    @DisplayName("Return leg (logout-state cookie round-trip)")
    class ReturnLeg {

        @Test
        @DisplayName("Should redirect to final_redirect and clear the state cookie on a matching state")
        void shouldCompleteMatchingReturn() {
            LogoutRedirect initiated = logout.initiate(session);
            String state = cookieValue(initiated.setCookieHeaders().getFirst());
            String cookieHeader = RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=" + state;

            LogoutReturn result = logout.completeReturn(state, cookieHeader);

            assertTrue(result.isRedirect(), "a matching state completes the logout");
            assertEquals(302, result.status());
            assertEquals(FINAL_REDIRECT, result.location());
            String clearing = result.setCookieHeaders().getFirst();
            assertTrue(clearing.startsWith(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "="), clearing);
            assertTrue(clearing.contains("Max-Age=0"), "the single-use state cookie is cleared");
        }

        @Test
        @DisplayName("Should reject a returned state that does not match the cookie 400")
        void shouldRejectMismatchedState() {
            LogoutRedirect initiated = logout.initiate(session);
            String state = cookieValue(initiated.setCookieHeaders().getFirst());
            String cookieHeader = RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=" + state;

            LogoutReturn result = logout.completeReturn("not-the-state", cookieHeader);

            assertFalse(result.isRedirect());
            assertEquals(400, result.status());
            assertTrue(result.setCookieHeaders().isEmpty());
        }

        @Test
        @DisplayName("Should reject a return that carries no logout-state cookie 400")
        void shouldRejectMissingStateCookie() {
            LogoutReturn result = logout.completeReturn("some-state", null);

            assertFalse(result.isRedirect());
            assertEquals(400, result.status());
        }

        @Test
        @DisplayName("Should reject a return whose state parameter is absent 400")
        void shouldRejectMissingStateParameter() {
            LogoutRedirect initiated = logout.initiate(session);
            String state = cookieValue(initiated.setCookieHeaders().getFirst());
            String cookieHeader = RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=" + state;

            LogoutReturn result = logout.completeReturn(null, cookieHeader);

            assertEquals(400, result.status());
        }
    }

    @Nested
    @DisplayName("Accessors and argument contract")
    class Contract {

        @Test
        @DisplayName("Should expose the configured final_redirect landing")
        void shouldExposeFinalRedirect() {
            assertEquals(FINAL_REDIRECT, logout.finalRedirect());
        }

        @Test
        @DisplayName("Should reject a null session on initiate")
        void shouldRejectNullSession() {
            assertThrows(NullPointerException.class, () -> logout.initiate(null));
        }

        @Test
        @DisplayName("Should reject blank required constructor settings")
        void shouldRejectBlankSettings() {
            assertThrows(IllegalArgumentException.class, () -> new RpInitiatedLogout(endSessionFlow, revoked::set,
                    "  ", REGISTERED_RETURN, FINAL_REDIRECT, STATE_TTL));
        }
    }
}
