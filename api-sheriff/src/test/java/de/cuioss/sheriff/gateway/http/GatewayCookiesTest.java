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
package de.cuioss.sheriff.gateway.http;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link GatewayCookies}: the reserved prefix is a gateway cookie on every gateway, the
 * configured session cookie and — in cookie mode only — its activity cookie are added to it, and a
 * name is read and compared the way a user agent reads and stores it.
 * <p>
 * Every matched case is paired with a name as close to it as a different cookie can be, so a
 * definition that matched by substring, ignored case beyond the prefix, or matched nothing, fails.
 */
@EnableGeneratorController
@DisplayName("GatewayCookies — the one definition of the gateway's own cookies")
class GatewayCookiesTest {

    private static final GatewayCookies WITHOUT_OIDC = GatewayCookies.of(GatewayConfig.builder().version(1).build());

    @Nested
    @DisplayName("the reserved prefix — a gateway cookie with or without a BFF")
    class ReservedPrefix {

        @Test
        @DisplayName("Should match every name under the reserved prefix on a gateway without an oidc block")
        void shouldMatchPrefixWithoutOidc() {
            String laterCookie = GatewayCookies.RESERVED_PREFIX + Generators.letterStrings(3, 12).next();

            assertAll("names under the prefix",
                    () -> assertTrue(WITHOUT_OIDC.matchesName(laterCookie), laterCookie),
                    () -> assertTrue(WITHOUT_OIDC.matchesName(GatewayCookies.RESERVED_PREFIX),
                            "the bare prefix is under the prefix"),
                    () -> assertTrue(WITHOUT_OIDC.matchesName(SessionCookieCodec.DEFAULT_COOKIE_NAME),
                            "the default session cookie"));
        }

        @Test
        @DisplayName("Should match the login-binding and the logout-state cookie through the prefix")
        void shouldMatchBffCookiesThroughThePrefix() {
            assertAll("the cookies no list names any more",
                    () -> assertTrue(WITHOUT_OIDC.matchesName(BindingCookieCodec.COOKIE_NAME),
                            BindingCookieCodec.COOKIE_NAME),
                    () -> assertTrue(WITHOUT_OIDC.matchesName(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME),
                            RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"__HOST-sheriff-session", "__host-sheriff-binding", "__hOsT-sheriff-x"})
        @DisplayName("Should compare the __Host- part of the prefix without regard to case")
        void shouldMatchPrefixCaseVariant(String name) {
            assertTrue(WITHOUT_OIDC.matchesName(name));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"__Host-Sheriff-session", "__Host-SHERIFF-binding", "__Host-sheriff", "__Host-sheriffx",
                "x__Host-sheriff-session", "__Secure-sheriff-session", "sheriff-session"})
        @DisplayName("Should not match a name that only resembles the prefix")
        void shouldNotMatchPrefixLookAlike(String name) {
            assertFalse(WITHOUT_OIDC.matchesName(name));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"__Secure-app", "JSESSIONID", "__Host-app", "theme", ""})
        @DisplayName("Should not match a cookie of an application's own")
        void shouldNotMatchApplicationCookie(String name) {
            assertFalse(WITHOUT_OIDC.matchesName(name));
        }
    }

    @Nested
    @DisplayName("the configured session cookie and its activity cookie")
    class ConfiguredNames {

        private final String customName = "__Host-" + Generators.letterStrings(4, 12).next();

        @Test
        @DisplayName("Should match a configured session cookie name outside the reserved prefix")
        void shouldMatchConfiguredSessionName() {
            GatewayCookies configured = GatewayCookies.of(gatewayWithSession(OidcConfig.Session.MODE_SERVER, customName));

            assertAll("the configured name and its control",
                    () -> assertTrue(configured.matchesName(customName), customName),
                    () -> assertFalse(WITHOUT_OIDC.matchesName(customName),
                            "control: the name is not a gateway cookie where it is not configured"));
        }

        @Test
        @DisplayName("Should match the activity cookie in cookie mode only")
        void shouldMatchActivityCookieInCookieModeOnly() {
            String activity = customName + SessionActivityCookieCodec.COOKIE_NAME_SUFFIX;
            GatewayCookies cookieMode = GatewayCookies.of(gatewayWithSession(OidcConfig.Session.MODE_COOKIE, customName));
            GatewayCookies serverMode = GatewayCookies.of(gatewayWithSession(OidcConfig.Session.MODE_SERVER, customName));

            assertAll("the activity cookie belongs to cookie mode",
                    () -> assertTrue(cookieMode.matchesName(activity), activity),
                    () -> assertTrue(cookieMode.matchesName(customName), "the session cookie in cookie mode"),
                    () -> assertFalse(serverMode.matchesName(activity),
                            "server mode keeps the last access in its store and sets no activity cookie"));
        }

        @Test
        @DisplayName("Should fall back to the default session cookie when no name is configured")
        void shouldUseDefaultSessionNameWhenOmitted() {
            GatewayCookies cookieMode = GatewayCookies.of(gatewayWithSession(OidcConfig.Session.MODE_COOKIE, null));

            assertAll("the defaults",
                    () -> assertTrue(cookieMode.matchesName(SessionCookieCodec.DEFAULT_COOKIE_NAME)),
                    () -> assertTrue(cookieMode.matchesName(
                            SessionCookieCodec.DEFAULT_COOKIE_NAME + SessionActivityCookieCodec.COOKIE_NAME_SUFFIX)),
                    () -> assertFalse(cookieMode.matchesName(customName),
                            "control: an unconfigured name outside the prefix is not a gateway cookie"));
        }

        @Test
        @DisplayName("Should compare the configured name case-sensitively beyond its __Host- prefix")
        void shouldCompareConfiguredNameCaseSensitively() {
            GatewayCookies configured = GatewayCookies.of(gatewayWithSession(OidcConfig.Session.MODE_SERVER, customName));
            String prefixVariant = "__HOST-" + customName.substring("__Host-".length());
            String remainderVariant = "__Host-" + customName.substring("__Host-".length()).toUpperCase(Locale.ROOT);

            assertAll("a browser stores the remainder as written",
                    () -> assertTrue(configured.matchesName(prefixVariant), prefixVariant),
                    () -> assertFalse(configured.matchesName(remainderVariant), remainderVariant));
        }

        private GatewayConfig gatewayWithSession(String mode, @Nullable String cookieName) {
            OidcConfig oidc = OidcConfig.builder()
                    .redirectUri("https://gw.example.com/auth/callback")
                    .session(OidcConfig.Session.builder().mode(mode).cookieName(cookieName).build())
                    .build();
            return GatewayConfig.builder().version(1).oidc(oidc).build();
        }
    }

    @Nested
    @DisplayName("a cookie pair and a Set-Cookie line — the user-agent reading")
    class UserAgentReading {

        @Test
        @DisplayName("Should count a nameless crumb whose value names a gateway cookie as that cookie")
        void shouldMatchNamelessCrumb() {
            assertAll("the bare value a browser sends back",
                    () -> assertTrue(WITHOUT_OIDC.matchesPair("", SessionCookieCodec.DEFAULT_COOKIE_NAME + "=x")),
                    () -> assertTrue(WITHOUT_OIDC.namesGatewayCookie("=" + BindingCookieCodec.COOKIE_NAME + "=x; Path=/")),
                    () -> assertFalse(WITHOUT_OIDC.matchesPair("", SessionCookieCodec.DEFAULT_COOKIE_NAME),
                            "a value without = is read back as no name at all"),
                    () -> assertFalse(WITHOUT_OIDC.matchesPair("", "theme=dark"), "control: an application crumb"));
        }

        @Test
        @DisplayName("Should judge a named pair by its name, never by its value")
        void shouldJudgeNamedPairByName() {
            assertAll("name position only",
                    () -> assertTrue(WITHOUT_OIDC.matchesPair(SessionCookieCodec.DEFAULT_COOKIE_NAME, "x")),
                    () -> assertFalse(WITHOUT_OIDC.matchesPair("note", SessionCookieCodec.DEFAULT_COOKIE_NAME + "=x")));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "__Host-sheriff-session=attacker; Path=/; Secure; HttpOnly",
                " __Host-sheriff-session=attacker",
                "__Host-sheriff-session =attacker",
                "\t__Host-sheriff-session\t=attacker; Path=/",
                "__Host-sheriff-session=a=b=c",
                "__Host-sheriff-anything-new=x",
                " = __Host-sheriff-binding=x; Path=/"})
        @DisplayName("Should read the name of a Set-Cookie line with surrounding spaces and tabs removed")
        void shouldNameGatewayCookieInLine(String line) {
            assertTrue(WITHOUT_OIDC.namesGatewayCookie(line));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "JSESSIONID=abc; Path=/app; HttpOnly",
                "note=__Host-sheriff-session=x",
                "note=1; __Host-sheriff-session=x",
                "note=1; Path=/__Host-sheriff-binding=x",
                "__Host-sheriff-session"})
        @DisplayName("Should not name a gateway cookie that appears only in a value or an attribute")
        void shouldNotNameGatewayCookieOutsideTheNamePosition(String line) {
            assertFalse(WITHOUT_OIDC.namesGatewayCookie(line));
        }

        @Test
        @DisplayName("Should read the cookie name of a Set-Cookie line from its pair only, trimmed, empty when nameless")
        void shouldReadSetCookieName() {
            assertAll("the name a user agent reads",
                    () -> assertEquals("theme", GatewayCookies.setCookieName("theme=dark; Path=/; Secure")),
                    () -> assertEquals("theme", GatewayCookies.setCookieName(" \ttheme\t =dark")),
                    () -> assertEquals("a", GatewayCookies.setCookieName("a=b=c")),
                    () -> assertEquals("", GatewayCookies.setCookieName("=theme=dark; Path=/")),
                    () -> assertEquals("", GatewayCookies.setCookieName("bare; Domain=x=y"),
                            "a pair without = is nameless, whatever an attribute holds"),
                    () -> assertEquals("", GatewayCookies.setCookieName("")));
        }

        @Test
        @DisplayName("Should reject a missing configuration")
        void shouldRejectMissingConfiguration() {
            assertThrows(NullPointerException.class, () -> GatewayCookies.of(null));
        }
    }
}
