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
package de.cuioss.sheriff.gateway.bff;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What holds for every cookie the session runtime writes and reads, whichever class writes it: the
 * session cookie of both session modes, the activity cookie, the login-binding cookie and the
 * logout-state cookie. Each case collects the header lines from the production classes themselves, so
 * a cookie written with other attributes fails here whatever its own codec test says.
 */
@EnableGeneratorController
@DisplayName("The gateway's cookies — attributes, clearing lines, exact names, and what a replayed value can do")
class GatewayCookieContractTest {

    private static final Instant LOGIN = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final String ORIGIN = "https://gw.example.com";
    private static final String SESSION_COOKIE_NAME = SessionCookieCodec.DEFAULT_COOKIE_NAME;
    private static final String ACTIVITY_COOKIE_NAME = SESSION_COOKIE_NAME + "-activity";
    private static final String BINDING_COOKIE_NAME = "__Host-sheriff-binding";

    private final InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT, Integer.MAX_VALUE,
            sessionId -> {
            });
    private final SessionBinding serverBinding = new ServerSessionBinding(store,
            new SessionCookieCodec(SESSION_COOKIE_NAME, SESSION_TTL));
    private final SessionBinding cookieBinding = cookieBinding();
    private final BindingCookieCodec bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);

    private static SessionBinding cookieBinding() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        byte[] activityKey = new byte[32];
        Arrays.fill(activityKey, (byte) 0x44);
        return new CookieSessionBinding(
                new SealedSessionCookieCodec(SESSION_COOKIE_NAME, SESSION_TTL,
                        SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"), (byte) 1),
                salt,
                new SessionActivityCookieCodec(SESSION_COOKIE_NAME, new SecretKeySpec(activityKey, "AES"), (byte) 2),
                IDLE_TIMEOUT);
    }

    private static SessionRecord newSession() {
        return session(SessionRecord.newSessionId(), Generators.letterStrings(16, 32).next());
    }

    private static SessionRecord session(String sessionId, String accessToken) {
        return SessionRecord.builder().sessionId(sessionId)
                .accessToken(accessToken)
                .idToken(Generators.letterStrings(16, 32).next())
                .sub("user-1")
                .expiresAt(LOGIN.plus(SESSION_TTL)).build();
    }

    private static LogoutEndpoint logoutEndpoint(SessionBinding binding) {
        RpInitiatedLogout rpInitiatedLogout = new RpInitiatedLogout(
                new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(ORIGIN + "/auth/logout/return"))),
                session -> {
                }, () -> Optional.of("https://idp.example.com/logout"),
                ORIGIN + "/auth/logout/return", "/", Duration.ofMinutes(1));
        return new LogoutEndpoint(rpInitiatedLogout, binding);
    }

    /** The {@code name=value} pair a browser sends back for {@code setCookie}. */
    private static String pair(String setCookie) {
        return setCookie.split(";", 2)[0];
    }

    private static String nameOf(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf('='));
    }

    private static String valueOf(String setCookie) {
        String pair = pair(setCookie);
        return pair.substring(pair.indexOf('=') + 1);
    }

    /** The attributes of a {@code Set-Cookie} line: everything after the name-value pair, trimmed. */
    private static List<String> attributesOf(String setCookie) {
        return Stream.of(setCookie.split(";")).skip(1).map(String::strip).toList();
    }

    private static boolean hasDomain(String setCookie) {
        return attributesOf(setCookie).stream()
                .anyMatch(attribute -> attribute.toLowerCase(Locale.ROOT).startsWith("domain"));
    }

    @Nested
    @DisplayName("every cookie that is set carries the attributes the __Host- prefix and the session need")
    class SettingLines {

        /** Every line that sets a cookie with a value: both session cookies, the activity, binding and logout-state cookie. */
        private List<String> settingLines() {
            List<String> lines = new ArrayList<>();
            SessionRecord serverSession = newSession();
            String serverCookie = serverBinding.bind(serverSession, LOGIN).setCookieHeaders().getFirst();
            lines.add(serverCookie);
            String sealedCookie = cookieBinding.bind(newSession(), LOGIN).setCookieHeaders().getFirst();
            lines.add(sealedCookie);
            SessionRecord resolved = cookieBinding.resolve(pair(sealedCookie), LOGIN).orElseThrow();
            lines.addAll(cookieBinding.recordAccess(resolved, pair(sealedCookie), LOGIN.plusSeconds(600)));
            lines.add(bindingCodec.toSetCookieHeader(Generators.letterStrings(16, 32).next()));
            lines.addAll(logoutEndpoint(serverBinding).logout(pair(serverCookie), LOGIN).setCookieHeaders().stream()
                    .filter(line -> !valueOf(line).isEmpty()).toList());
            return lines;
        }

        @Test
        @DisplayName("the five cookies are all collected: session (both modes), activity, login-binding, logout-state")
        void collectsAllFiveCookies() {
            assertEquals(List.of(SESSION_COOKIE_NAME, SESSION_COOKIE_NAME, ACTIVITY_COOKIE_NAME, BINDING_COOKIE_NAME,
                            RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME),
                    settingLines().stream().map(GatewayCookieContractTest::nameOf).toList());
        }

        @Test
        @DisplayName("each is __Host-, Path=/, Secure, HttpOnly, SameSite=Lax and names no Domain")
        void everySettingLineIsHostPrefixedAndLax() {
            assertAll(settingLines().stream().map(line -> (Executable) () -> assertAll(nameOf(line),
                    () -> assertTrue(nameOf(line).startsWith("__Host-"), line),
                    () -> assertTrue(attributesOf(line).contains("Path=/"), line),
                    () -> assertTrue(attributesOf(line).contains("Secure"), line),
                    () -> assertTrue(attributesOf(line).contains("HttpOnly"), line),
                    () -> assertTrue(attributesOf(line).contains("SameSite=Lax"), line),
                    () -> assertFalse(hasDomain(line), line))));
        }
    }

    @Nested
    @DisplayName("every clearing line can clear a __Host- cookie: same name, Path=/, Secure, no Domain, Max-Age=0")
    class ClearingLines {

        private List<String> clearingLines() {
            List<String> lines = new ArrayList<>();
            lines.addAll(serverBinding.clearingSetCookieHeaders());
            lines.addAll(cookieBinding.clearingSetCookieHeaders());
            lines.add(bindingCodec.toClearingSetCookieHeader());
            return lines;
        }

        @Test
        @DisplayName("the clearing lines of both bindings and of the login-binding cookie are collected")
        void collectsTheClearingLines() {
            assertEquals(List.of(SESSION_COOKIE_NAME, SESSION_COOKIE_NAME, ACTIVITY_COOKIE_NAME, BINDING_COOKIE_NAME),
                    clearingLines().stream().map(GatewayCookieContractTest::nameOf).toList());
        }

        @Test
        @DisplayName("each has an empty value, Max-Age=0, Path=/, Secure and no Domain")
        void everyClearingLineSatisfiesThePrefix() {
            assertAll(clearingLines().stream().map(line -> (Executable) () -> assertAll(nameOf(line),
                    () -> assertEquals("", valueOf(line), line),
                    () -> assertTrue(attributesOf(line).contains("Max-Age=0"), line),
                    () -> assertTrue(attributesOf(line).contains("Path=/"), line),
                    () -> assertTrue(attributesOf(line).contains("Secure"), line),
                    () -> assertFalse(hasDomain(line), line))));
        }
    }

    @Nested
    @DisplayName("a cookie is read by its exact name only")
    class ExactName {

        @ParameterizedTest
        @ValueSource(strings = {"__host-sheriff-session", "__HOST-SHERIFF-SESSION", "__Host-Sheriff-Session",
                "__Secure-sheriff-session", "sheriff-session"})
        @DisplayName("server mode: a live handle presented under another spelling of the name resolves nothing")
        void serverModeIgnoresAnotherSpelling(String otherName) {
            String cookie = pair(serverBinding.bind(newSession(), LOGIN).setCookieHeaders().getFirst());
            String handle = cookie.substring(cookie.indexOf('=') + 1);

            assertAll("the handle is only a session under the exact name",
                    () -> assertTrue(serverBinding.resolve(cookie, LOGIN).isPresent(), "control: the exact name"),
                    () -> assertEquals(Optional.empty(), serverBinding.resolve(otherName + "=" + handle, LOGIN)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"__host-sheriff-session", "__HOST-SHERIFF-SESSION", "__Host-Sheriff-Session",
                "__Secure-sheriff-session", "sheriff-session"})
        @DisplayName("cookie mode: a sealed value presented under another spelling of the name resolves nothing")
        void cookieModeIgnoresAnotherSpelling(String otherName) {
            String cookie = pair(cookieBinding.bind(newSession(), LOGIN).setCookieHeaders().getFirst());
            String sealed = cookie.substring(cookie.indexOf('=') + 1);

            assertAll("the sealed value is only a session under the exact name",
                    () -> assertTrue(cookieBinding.resolve(cookie, LOGIN).isPresent(), "control: the exact name"),
                    () -> assertEquals(Optional.empty(), cookieBinding.resolve(otherName + "=" + sealed, LOGIN)));
        }
    }

    @Nested
    @DisplayName("cookie mode: an activity cookie replayed from an earlier moment cannot keep the session alive")
    class ActivityCookieReplay {

        @Test
        @DisplayName("the session is idle by the earlier cookie's own instant, whatever later cookie was issued since")
        void earlierActivityCookieOnlyShortens() {
            String sessionCookie = pair(cookieBinding.bind(newSession(), LOGIN).setCookieHeaders().getFirst());
            SessionRecord session = cookieBinding.resolve(sessionCookie, LOGIN).orElseThrow();
            String earlier = pair(cookieBinding.recordAccess(session, sessionCookie, LOGIN.plus(Duration.ofMinutes(10)))
                    .getFirst());
            String later = pair(cookieBinding.recordAccess(session, sessionCookie + "; " + earlier,
                    LOGIN.plus(Duration.ofMinutes(35))).getFirst());
            Instant now = LOGIN.plus(Duration.ofMinutes(50));

            assertAll("the idle deadline is counted from the instant sealed into the presented cookie",
                    () -> assertTrue(cookieBinding.resolve(sessionCookie + "; " + later, now).isPresent(),
                            "control: with the cookie of the access at minute 35 the session is live at minute 50"),
                    () -> assertEquals(Optional.empty(), cookieBinding.resolve(sessionCookie + "; " + earlier, now),
                            "with the cookie of the access at minute 10 it has been idle since minute 40"),
                    () -> assertEquals(Optional.empty(), cookieBinding.resolve(sessionCookie, now),
                            "and without any activity cookie it has been idle since minute 30"));
        }
    }

    @Nested
    @DisplayName("server mode: a refresh after a cookie re-issue does not bring the earlier cookie value back")
    class ReissueThenRefresh {

        @Test
        @DisplayName("the value the browser held before the re-issue stays dead; the re-issued one carries the refreshed session")
        void refreshKeepsTheReissuedValue() {
            SessionRecord session = newSession();
            String before = pair(serverBinding.bind(session, LOGIN).setCookieHeaders().getFirst());
            String reissued = pair(serverBinding.persistReissuingCookie(session, LOGIN).orElseThrow()
                    .setCookieHeaders().getFirst());
            String refreshedToken = Generators.letterStrings(16, 32).next();

            Optional<SessionBinding.BoundSession> refreshed = serverBinding.persist(
                    session(session.sessionId(), refreshedToken), LOGIN);

            assertAll("the refresh wrote under the re-issued value",
                    () -> assertTrue(refreshed.isPresent(), "precondition: the session was there to update"),
                    () -> assertEquals(Optional.empty(), serverBinding.resolve(before, LOGIN),
                            "the value from before the re-issue resolves nothing"),
                    () -> assertEquals(Optional.of(refreshedToken),
                            serverBinding.resolve(reissued, LOGIN).map(SessionRecord::accessToken),
                            "the re-issued value resolves the refreshed session"),
                    () -> assertEquals(1, store.size()));
        }
    }
}
