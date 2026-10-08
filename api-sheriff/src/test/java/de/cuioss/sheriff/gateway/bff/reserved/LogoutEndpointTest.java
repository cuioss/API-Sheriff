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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.EndSessionEndpointSource;
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
        store = new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE, sessionId -> {
        });
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
        return endpoint(revocation, () -> Optional.of(END_SESSION), postLogoutRedirectUri, sessionBinding);
    }

    private LogoutEndpoint endpoint(TokenRevocation revocation, EndSessionEndpointSource endSessionEndpoint,
            String postLogoutRedirectUri, SessionBinding sessionBinding) {
        RpInitiatedLogout logout = new RpInitiatedLogout(endSessionFlow, revocation, endSessionEndpoint,
                postLogoutRedirectUri, FINAL_REDIRECT, STATE_TTL);
        return new LogoutEndpoint(logout, sessionBinding);
    }

    /** A logout endpoint over the server-mode binding whose provider answers {@code endSessionEndpoint}. */
    private LogoutEndpoint endpointOver(EndSessionEndpointSource endSessionEndpoint) {
        return endpoint(ignored -> {
        }, endSessionEndpoint, REGISTERED_RETURN, binding);
    }

    /**
     * Asserts that a clearing {@code Set-Cookie} can take effect on a {@code __Host-} cookie: a user
     * agent accepts a line for such a name only with {@code Secure}, {@code Path=/} and no
     * {@code Domain}, and a clearing line it does not accept leaves the cookie in the browser.
     */
    private static void assertClearsHostPrefixedCookie(String setCookie) {
        List<String> attributes = Arrays.stream(setCookie.split(";")).map(String::strip).toList();
        assertAll(setCookie,
                () -> assertTrue(attributes.getFirst().startsWith("__Host-"),
                        "precondition: the cookie carries the __Host- prefix"),
                () -> assertTrue(attributes.getFirst().endsWith("="), "the clearing line carries an empty value"),
                () -> assertTrue(attributes.contains("Max-Age=0"), "the cookie is expired at once"),
                () -> assertTrue(attributes.contains("Path=/"), "a __Host- cookie is accepted with Path=/ only"),
                () -> assertTrue(attributes.contains("Secure"), "a __Host- cookie is accepted with Secure only"),
                () -> assertTrue(attributes.stream().noneMatch(
                                attribute -> attribute.regionMatches(true, 0, "Domain", 0, "Domain".length())),
                        "a __Host- cookie is refused when it names a Domain"));
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(LogoutPath.class)
    @DisplayName("Should clear every cookie with a line a browser accepts for a __Host- name, in both modes")
    void shouldClearWithLinesTheHostPrefixAdmits(LogoutPath path) {
        SessionBinding cookieBinding = cookieBinding();
        String setCookie = cookieBinding.bind(session(), NOW).setCookieHeaders().getFirst();
        String sealedCookie = setCookie.substring(0, setCookie.indexOf(';'));

        LogoutOutcome cookieMode = endpoint(path.postLogoutRedirectUri, cookieBinding)
                .logout(path.carriesSession ? sealedCookie : null, NOW);
        LogoutOutcome serverMode = endpoint(path.postLogoutRedirectUri)
                .logout(path.carriesSession ? cookieHeader : null, NOW);

        List<String> cookieModeClearing = cookieMode.setCookieHeaders().stream()
                .filter(header -> header.contains("Max-Age=0")).toList();
        List<String> serverModeClearing = serverMode.setCookieHeaders().stream()
                .filter(header -> header.contains("Max-Age=0")).toList();
        assertEquals(2, cookieModeClearing.size(), "cookie mode clears the session and the activity cookie");
        assertEquals(1, serverModeClearing.size(), "server mode clears the session cookie");
        cookieModeClearing.forEach(LogoutEndpointTest::assertClearsHostPrefixedCookie);
        serverModeClearing.forEach(LogoutEndpointTest::assertClearsHostPrefixedCookie);
    }

    @Test
    @DisplayName("Should clear the logout-state cookie on the return leg with a line the __Host- prefix admits")
    void shouldClearLogoutStateCookieOnReturn() {
        LogoutEndpoint endpoint = endpoint(REGISTERED_RETURN);
        String stateCookie = endpoint.logout(cookieHeader, NOW).setCookieHeaders().stream()
                .filter(header -> header.startsWith(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "="))
                .findFirst().orElseThrow();
        String statePair = stateCookie.substring(0, stateCookie.indexOf(';'));
        String state = statePair.substring(statePair.indexOf('=') + 1);

        LogoutOutcome returned = endpoint.completeReturn(state, statePair);

        assertEquals(FINAL_REDIRECT, returned.location());
        assertEquals(1, returned.setCookieHeaders().size());
        assertClearsHostPrefixedCookie(returned.setCookieHeaders().getFirst());
        assertFalse(returned.carriesIdToken(), "the return leg's location holds no token");
    }

    @Test
    @DisplayName("Should end the session before the end-session redirect is built")
    void shouldDestroyBeforeInitiating() {
        AtomicReference<Boolean> liveWhenInitiated = new AtomicReference<>();
        TokenRevocation observingStore = ignored ->
                liveWhenInitiated.set(store.resolve(COOKIE_HANDLE, NOW).isPresent());
        LogoutEndpoint endpoint = endpoint(observingStore, () -> Optional.of(END_SESSION), REGISTERED_RETURN,
                binding);

        LogoutOutcome outcome = endpoint.logout(cookieHeader, NOW);

        assertTrue(outcome.location().startsWith(END_SESSION), "precondition: the redirect was built");
        assertEquals(Boolean.FALSE, liveWhenInitiated.get(),
                "the session was already gone when the redirect construction began");
    }

    @Test
    @DisplayName("Should send the browser to the provider with the ID token marked and a 60-second state cookie")
    void shouldMarkTheEndSessionRedirect() {
        LogoutOutcome outcome = endpoint(REGISTERED_RETURN).logout(cookieHeader, NOW);

        assertTrue(outcome.carriesIdToken(), "the end-session location carries the id_token_hint");
        assertTrue(outcome.location().contains("id_token_hint="), "the hint is in the location");
        assertTrue(outcome.location().contains("post_logout_redirect_uri="));
        assertTrue(outcome.location().contains("state="));
        assertTrue(outcome.setCookieHeaders().stream().anyMatch(header ->
                        header.startsWith(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=")
                                && header.contains("Max-Age=60;")),
                "the logout-state cookie lives 60 seconds: " + outcome.setCookieHeaders());
        assertFalse(outcome.toString().contains("id_token_hint"), "the text form holds no end-session location");
        assertFalse(outcome.toString().contains("raw-id-token"), "the text form holds no token");
    }

    /**
     * The ways the browser cannot be sent to the identity provider. In each the local session ends, its
     * cookie is cleared and the browser lands on {@code final_redirect} — never on an error answer.
     */
    enum NoEndSessionRedirect {
        PROVIDER_PUBLISHES_NONE(Optional::empty),
        ENDPOINT_BLANK(() -> Optional.of(" ")),
        ENDPOINT_NOT_HTTP(() -> Optional.of("javascript:alert(1)")),
        ENDPOINT_RELATIVE(() -> Optional.of("/protocol/openid-connect/logout")),
        DISCOVERY_FAILS(() -> {
            throw new IllegalStateException("discovery unreachable");
        });

        private final EndSessionEndpointSource source;

        NoEndSessionRedirect(EndSessionEndpointSource source) {
            this.source = source;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NoEndSessionRedirect.class)
    @DisplayName("Should end the local session and land on final_redirect when no end-session redirect exists")
    void shouldLogOutLocallyWithoutEndSessionRedirect(NoEndSessionRedirect reason) {
        LogoutOutcome outcome = endpointOver(reason.source).logout(cookieHeader, NOW);

        assertAll(reason.name(),
                () -> assertEquals(302, outcome.status(), "never an error answer"),
                () -> assertEquals(FINAL_REDIRECT, outcome.location()),
                () -> assertFalse(outcome.carriesIdToken(), "no token leaves the gateway on this path"),
                () -> assertTrue(store.resolve(COOKIE_HANDLE, NOW).isEmpty(), "the session is ended"),
                () -> assertEquals(0, store.size(), "nothing is left in the store"),
                () -> assertEquals(binding.clearingSetCookieHeaders(), outcome.setCookieHeaders(),
                        "the session cookie is cleared and no logout-state cookie is set"));
    }

    @Test
    @DisplayName("Should end the local session of a session that holds no ID token")
    void shouldLogOutLocallyWithoutIdToken() {
        store.destroyById(store.resolve(COOKIE_HANDLE, NOW).orElseThrow().sessionId());
        store.create(SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken("mediated-access-token")
                .idToken("")
                .sub("user-sub-1")
                .expiresAt(NOW.plus(SESSION_TTL))
                .build(), COOKIE_HANDLE, NOW);

        LogoutOutcome outcome = endpoint(REGISTERED_RETURN).logout(cookieHeader, NOW);

        assertEquals(FINAL_REDIRECT, outcome.location());
        assertFalse(outcome.carriesIdToken());
        assertEquals(0, store.size(), "the session is ended");
    }
}
