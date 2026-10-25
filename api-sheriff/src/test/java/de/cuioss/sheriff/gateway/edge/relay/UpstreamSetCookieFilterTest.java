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
package de.cuioss.sheriff.gateway.edge.relay;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UpstreamSetCookieFilter}: which upstream {@code Set-Cookie} lines the route's
 * {@code cookies} policy relays, and that a relayed line crosses with its {@code Domain} attribute
 * removed.
 * <p>
 * Every case runs against one filter over one configuration: the reserved {@code __Host-sheriff-}
 * prefix plus a configured session cookie outside it. The gateway-cookie cases run under
 * {@code allow: all} — the mode that relays the most — so a drop there is the gateway rule, not the
 * mode. The cookie names and attribute spellings are the contract under test, so they are literals.
 */
@DisplayName("UpstreamSetCookieFilter — the route's cookie policy decides which Set-Cookie lines cross")
class UpstreamSetCookieFilterTest {

    private static final String SET_COOKIE = "Set-Cookie";
    private static final String SESSION = "__Host-sheriff-session";
    private static final String BINDING = "__Host-sheriff-binding";
    /** A configured session cookie name outside the reserved prefix. */
    private static final String CONFIGURED = "__Host-shop-session";

    private static final CookiesConfig ALL = new CookiesConfig(CookieAllow.ALL, null, null);
    private static final CookiesConfig LISTED = new CookiesConfig(
            new CookieAllow.Named(List.of("theme", "JSESSIONID")), null, null);

    private final UpstreamSetCookieFilter filter = new UpstreamSetCookieFilter(GatewayCookies.of(
            ConfigFixtures.gateway().oidc(OidcConfig.builder()
                    .redirectUri("https://gw.example.com/auth/callback")
                    .session(OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_SERVER)
                            .cookieName(CONFIGURED).build())
                    .build()).build()));

    private @Nullable String relayed(String line, CookiesConfig policy) {
        return filter.relayedValue(SET_COOKIE, line, policy);
    }

    @Nested
    @DisplayName("A gateway cookie is dropped under every mode")
    class GatewayCookie {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "__Host-sheriff-session=attacker; Path=/; Secure; HttpOnly",
                "__Host-sheriff-binding=x",
                "__Host-sheriff-session=",
                "__Host-sheriff-session=; Max-Age=0; Path=/; Secure",
                "__Host-sheriff-session2=x",
                "__Host-sheriff-binding-old=x",
                "__Host-shop-session=attacker",
                " __Host-sheriff-session=attacker",
                "__Host-sheriff-session =attacker",
                "\t__Host-sheriff-session\t=attacker; Path=/",
                "__Host-sheriff-session=a=b=c"})
        @DisplayName("Should drop a line whose cookie name is a gateway cookie name under allow: all")
        void shouldDropOwnedName(String line) {
            assertNull(relayed(line, ALL));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "__HOST-sheriff-session=attacker",
                "__host-sheriff-session=attacker",
                "__hOsT-sheriff-binding=x",
                "__HOST-shop-session=x"})
        @DisplayName("Should drop a line that differs from a gateway cookie name only in the case of its prefix")
        void shouldDropPrefixCaseVariant(String line) {
            assertNull(relayed(line, ALL));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "=__Host-sheriff-session=attacker",
                " = __Host-sheriff-binding=x; Path=/",
                "=__Host-shop-session=attacker",
                "__Host-sheriff-session",
                " __Host-sheriff-binding ; Path=/"})
        @DisplayName("Should drop a nameless cookie whose value would be read back as a gateway cookie")
        void shouldDropNamelessCookieCarryingOwnedName(String line) {
            assertNull(relayed(line, ALL));
        }

        @Test
        @DisplayName("Should drop a gateway cookie even under a list that names it")
        void shouldDropOwnedNameEvenWhenListed() {
            CookiesConfig listingTheGatewayCookie = new CookiesConfig(
                    new CookieAllow.Named(List.of(SESSION, CONFIGURED)), null, null);

            assertAll("a list never re-admits a gateway cookie",
                    () -> assertNull(relayed(SESSION + "=attacker", listingTheGatewayCookie)),
                    () -> assertNull(relayed(CONFIGURED + "=attacker", listingTheGatewayCookie)));
        }

        @ParameterizedTest(name = "header name \"{0}\"")
        @ValueSource(strings = {"Set-Cookie", "set-cookie", "SET-COOKIE"})
        @DisplayName("Should recognise the Set-Cookie header whatever its case")
        void shouldMatchHeaderNameWithoutCase(String headerName) {
            assertNull(filter.relayedValue(headerName, SESSION + "=attacker", ALL));
        }
    }

    @Nested
    @DisplayName("allow: none drops every line")
    class ModeNone {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "theme=dark",
                "JSESSIONID=abc; Path=/app; HttpOnly",
                "app=1",
                "=dark",
                "__Host-sheriff-session=attacker"})
        @DisplayName("Should drop every line, listed-looking, unlisted, nameless or gateway")
        void shouldDropEveryLine(String line) {
            assertNull(relayed(line, CookiesConfig.NONE));
        }
    }

    @Nested
    @DisplayName("A list relays only the lines setting a listed cookie")
    class ModeList {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"theme=dark", "JSESSIONID=abc; Path=/app; HttpOnly", " theme =dark"})
        @DisplayName("Should relay a line setting a listed cookie unchanged")
        void shouldRelayListedName(String line) {
            assertEquals(line, relayed(line, LISTED));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"app=1", "Theme=dark", "THEME=dark", "theme2=dark", "jsessionid=abc"})
        @DisplayName("Should drop a line setting an unlisted cookie — names compare exactly and case-sensitively")
        void shouldDropUnlistedName(String line) {
            assertNull(relayed(line, LISTED));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"=dark", "dark", "=theme=dark", "  =theme"})
        @DisplayName("Should drop a nameless line — it is never listed")
        void shouldDropNamelessLine(String line) {
            assertNull(relayed(line, LISTED));
        }

        @Test
        @DisplayName("Should drop a gateway cookie under a list")
        void shouldDropGatewayCookie() {
            assertNull(relayed(BINDING + "=x", LISTED));
        }
    }

    @Nested
    @DisplayName("allow: all relays every line that is not a gateway cookie")
    class ModeAll {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "JSESSIONID=abc; Path=/app; HttpOnly",
                "theme=dark",
                "__Host-app=1; Path=/; Secure",
                "__Host-sheriff=x",
                "x__Host-sheriff-session=x",
                "__Host-shop-session2=x",
                "__Host-shop-sessio=x",
                "shop-session=x"})
        @DisplayName("Should relay a cookie whose name is not a gateway cookie name")
        void shouldRelayOtherName(String line) {
            assertEquals(line, relayed(line, ALL));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "__Host-Sheriff-Session=x",
                "__Host-SHERIFF-BINDING=x",
                "__Host-Shop-Session=x",
                "__Host-SHOP-SESSION=x"})
        @DisplayName("Should relay a name that differs from a gateway cookie name in case beyond the prefix")
        void shouldRelayNameCaseVariant(String line) {
            assertEquals(line, relayed(line, ALL), "a browser stores this under its own, different name");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "note=__Host-sheriff-session",
                "note=__Host-sheriff-session=x",
                "note=1; Comment=__Host-sheriff-session=x",
                "note=1; __Host-sheriff-session=x",
                "note=1; Path=/__Host-sheriff-binding=x"})
        @DisplayName("Should relay a cookie that carries a gateway cookie name only in its value or an attribute")
        void shouldRelayOwnedNameOutsideTheNamePosition(String line) {
            assertEquals(line, relayed(line, ALL));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"=dark", "dark; Path=/"})
        @DisplayName("Should relay a nameless line that does not name a gateway cookie")
        void shouldRelayNamelessLine(String line) {
            assertEquals(line, relayed(line, ALL));
        }
    }

    @Nested
    @DisplayName("A relayed line crosses with its Domain attribute removed")
    class DomainAttribute {

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "theme=dark; Domain=example.com|theme=dark",
                "theme=dark; Domain=.example.com; Path=/; Secure|theme=dark; Path=/; Secure",
                "theme=dark;  domain = example.com ; Path=/|theme=dark; Path=/",
                "theme=dark; DOMAIN=a.example.com; Path=/; Domain=b.example.com; HttpOnly|theme=dark; Path=/; HttpOnly",
                "theme=dark; Domain|theme=dark",
                "theme=dark;Domain=example.com;Path=/app|theme=dark;Path=/app"})
        @DisplayName("Should remove every Domain attribute, whatever its case and spacing")
        void shouldRemoveDomain(String line, String expected) {
            assertEquals(expected, relayed(line, ALL));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "theme=Domain=example.com",
                "theme=Domain=example.com; Path=/",
                "theme=dark; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=60",
                "theme=dark; Domainx=1",
                "theme=dark; X-Domain=example.com",
                "theme=dark"})
        @DisplayName("control: a value holding Domain=, a look-alike attribute and every other attribute are kept as sent")
        void shouldKeepEverythingElse(String line) {
            assertEquals(line, relayed(line, ALL));
        }

        @Test
        @DisplayName("Should remove Domain from a line a list relays")
        void shouldRemoveDomainUnderList() {
            assertEquals("JSESSIONID=abc; Path=/app",
                    relayed("JSESSIONID=abc; Domain=example.com; Path=/app", LISTED));
        }
    }

    @Nested
    @DisplayName("Other headers and the gateway's reserved names without a session runtime")
    class OtherHeaders {

        @ParameterizedTest(name = "header \"{0}\"")
        @ValueSource(strings = {"X-Set-Cookie", "Cookie", "Set-Cookie2", "__Host-sheriff-session"})
        @DisplayName("Should not judge a header that is not Set-Cookie, even under allow: none")
        void shouldReturnOtherHeadersUnchanged(String headerName) {
            String value = SESSION + "=attacker; Domain=example.com";

            assertEquals(value, filter.relayedValue(headerName, value, CookiesConfig.NONE));
        }

        @Test
        @DisplayName("Should drop a line under the reserved prefix on a gateway without an oidc block")
        void shouldDropReservedNamesWithoutOidc() {
            UpstreamSetCookieFilter bearerOnly = new UpstreamSetCookieFilter(
                    GatewayCookies.of(ConfigFixtures.gateway().build()));

            assertAll("the reserved names are the gateway's with or without a BFF",
                    () -> assertNull(bearerOnly.relayedValue(SET_COOKIE, "__Host-sheriff-x=1", ALL)),
                    () -> assertNull(bearerOnly.relayedValue(SET_COOKIE, SESSION + "=x", ALL)),
                    () -> assertNull(bearerOnly.relayedValue(SET_COOKIE, "=" + BINDING + "=x", ALL)),
                    () -> assertEquals("theme=dark", bearerOnly.relayedValue(SET_COOKIE, "theme=dark", ALL)),
                    () -> assertEquals(CONFIGURED + "=x", bearerOnly.relayedValue(SET_COOKIE, CONFIGURED + "=x", ALL),
                            "a session cookie name is a gateway cookie only where it is configured"));
        }

        @Test
        @DisplayName("Should reject a missing definition, header or policy")
        void shouldRejectMissingArguments() {
            assertAll("every argument is required",
                    () -> assertThrows(NullPointerException.class, () -> new UpstreamSetCookieFilter(null)),
                    () -> assertThrows(NullPointerException.class, () -> filter.relayedValue(null, "a=1", ALL)),
                    () -> assertThrows(NullPointerException.class, () -> filter.relayedValue(SET_COOKIE, null, ALL)),
                    () -> assertThrows(NullPointerException.class, () -> filter.relayedValue(SET_COOKIE, "a=1", null)));
        }
    }
}
