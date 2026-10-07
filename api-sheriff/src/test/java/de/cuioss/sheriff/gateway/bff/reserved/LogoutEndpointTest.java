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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.TokenRevocation;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint.LogoutOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    private static final Duration SESSION_TTL = Duration.ofHours(8);
    /** The opaque value the server-mode session cookie carries — never the session id. */
    private static final String COOKIE_HANDLE = "opaque-cookie-handle";
    private static final String UNREGISTERED_RETURN = "https://evil.example.com/steal";

    private InMemorySessionStore store;
    private SessionBinding binding;
    private EndSessionFlow endSessionFlow;
    private String cookieHeader;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16, SESSION_TTL);
        binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
        endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(REGISTERED_RETURN)));
        store.create(session(), COOKIE_HANDLE, NOW);
        cookieHeader = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + COOKIE_HANDLE;
    }

    private static SessionRecord session() {
        return SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken("mediated-access-token")
                .idToken("raw-id-token")
                .sub("user-sub-1")
                .expiresAt(NOW.plus(SESSION_TTL))
                .build();
    }

    private LogoutEndpoint endpoint(String postLogoutRedirectUri) {
        return endpoint(postLogoutRedirectUri, binding);
    }

    private LogoutEndpoint endpoint(String postLogoutRedirectUri, SessionBinding sessionBinding) {
        TokenRevocation revocation = ignored -> {
        };
        RpInitiatedLogout logout = new RpInitiatedLogout(endSessionFlow, revocation, END_SESSION,
                postLogoutRedirectUri, FINAL_REDIRECT, STATE_TTL);
        return new LogoutEndpoint(logout, sessionBinding);
    }

    /** The stateless binding, which sets two cookies: the session cookie and its activity cookie. */
    private static SessionBinding cookieBinding() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        byte[] activityKey = new byte[32];
        Arrays.fill(activityKey, (byte) 0x44);
        return new CookieSessionBinding(
                new SealedSessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL,
                        SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"), (byte) 1),
                salt,
                new SessionActivityCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                        new SecretKeySpec(activityKey, "AES"), (byte) 2),
                SESSION_TTL);
    }

    /**
     * The three ways a logout request ends: on the IdP redirect, on the fallback after the IdP redirect
     * could not be built, and on the direct landing of a request that carried no live session.
     */
    enum LogoutPath {
        IDP_REDIRECT(REGISTERED_RETURN, true),
        INITIATION_FAILURE(UNREGISTERED_RETURN, true),
        NO_LIVE_SESSION(REGISTERED_RETURN, false);

        private final String postLogoutRedirectUri;
        private final boolean carriesSession;

        LogoutPath(String postLogoutRedirectUri, boolean carriesSession) {
            this.postLogoutRedirectUri = postLogoutRedirectUri;
            this.carriesSession = carriesSession;
        }
    }

    @ParameterizedTest(name = "cookie mode, {0}")
    @EnumSource(LogoutPath.class)
    @DisplayName("Should clear the session cookie and the activity cookie on every logout path in cookie mode")
    void shouldClearBothCookiesOnEveryPathInCookieMode(LogoutPath path) {
        SessionBinding cookieBinding = cookieBinding();
        String setCookie = cookieBinding.bind(session(), NOW).setCookieHeaders().getFirst();
        String sealedCookie = setCookie.substring(0, setCookie.indexOf(';'));
        LogoutEndpoint endpoint = endpoint(path.postLogoutRedirectUri, cookieBinding);

        LogoutOutcome outcome = endpoint.logout(path.carriesSession ? sealedCookie : null, NOW);

        List<String> clearing = cookieBinding.clearingSetCookieHeaders();
        assertAll(path.name(),
                () -> assertEquals(2, clearing.size(), "precondition: the cookie-mode binding sets two cookies"),
                () -> assertTrue(outcome.setCookieHeaders().containsAll(clearing),
                        "every clearing cookie of the binding is emitted: " + outcome.setCookieHeaders()),
                () -> assertTrue(outcome.setCookieHeaders().contains(
                                SessionCookieCodec.DEFAULT_COOKIE_NAME + "-activity=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax"),
                        "the activity cookie is cleared by name"));
    }

    @ParameterizedTest(name = "server mode, {0}")
    @EnumSource(LogoutPath.class)
    @DisplayName("Should clear exactly the one session cookie on every logout path in server mode")
    void shouldClearTheSessionCookieOnEveryPathInServerMode(LogoutPath path) {
        LogoutEndpoint endpoint = endpoint(path.postLogoutRedirectUri);

        LogoutOutcome outcome = endpoint.logout(path.carriesSession ? cookieHeader : null, NOW);

        List<String> clearing = binding.clearingSetCookieHeaders();
        assertAll(path.name(),
                () -> assertEquals(1, clearing.size(), "precondition: the server-mode binding sets one cookie"),
                () -> assertTrue(outcome.setCookieHeaders().containsAll(clearing)),
                () -> assertEquals(1, outcome.setCookieHeaders().stream()
                                .filter(header -> header.startsWith(SessionCookieCodec.DEFAULT_COOKIE_NAME)).count(),
                        "no activity cookie is cleared in server mode: " + outcome.setCookieHeaders()));
    }

    @Test
    @DisplayName("Should destroy the local session and clear the cookie when end-session redirect construction fails")
    void shouldAlwaysLogOutLocallyOnInitiationFailure() {
        // An unregistered post_logout_redirect_uri makes the engine validator throw inside initiate().
        LogoutEndpoint endpoint = endpoint(UNREGISTERED_RETURN);

        LogoutOutcome outcome = endpoint.logout(cookieHeader, NOW);

        assertTrue(outcome.isRedirect(), "local logout must still land the browser on a safe redirect");
        assertEquals(FINAL_REDIRECT, outcome.location(),
                "a failed IdP redirect falls back to final_redirect");
        assertTrue(store.resolve(COOKIE_HANDLE, NOW).isEmpty(),
                "the server-side session is destroyed even when the IdP redirect could not be built");
        assertEquals(0, store.size(), "nothing is left in the store");
        assertTrue(outcome.setCookieHeaders().stream().anyMatch(header -> header.contains("Max-Age=0")),
                "the session cookie is cleared on the fallback path");
    }

    @Test
    @DisplayName("Should redirect to the IdP end_session_endpoint and destroy the session on the happy path")
    void shouldRedirectToIdpAndDestroyOnSuccess() {
        LogoutEndpoint endpoint = endpoint(REGISTERED_RETURN);

        LogoutOutcome outcome = endpoint.logout(cookieHeader, NOW);

        assertTrue(outcome.isRedirect());
        assertTrue(outcome.location().startsWith(END_SESSION),
                "a registered return URI yields the IdP end-session redirect");
        assertTrue(store.resolve(COOKIE_HANDLE, NOW).isEmpty(), "the server-side session is destroyed");
        assertEquals(0, store.size(), "nothing is left in the store");
    }

    @Test
    @DisplayName("Should land on final_redirect and clear the cookie when no live session is present")
    void shouldLandOnFinalRedirectWithoutSession() {
        LogoutEndpoint endpoint = endpoint(REGISTERED_RETURN);

        LogoutOutcome outcome = endpoint.logout(null, NOW);

        assertTrue(outcome.isRedirect());
        assertEquals(FINAL_REDIRECT, outcome.location());
        assertTrue(outcome.setCookieHeaders().stream().anyMatch(header -> header.contains("Max-Age=0")));
    }
}
