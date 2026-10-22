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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UpstreamSetCookieFilter}: an upstream {@code Set-Cookie} line that names a gateway
 * cookie is dropped, and every other line — every cookie of the upstream's own — is relayed.
 * <p>
 * The two halves are asserted against one filter instance over one configuration: the reserved
 * {@code __Host-sheriff-} prefix plus a configured session cookie outside it. The lines in the
 * {@code Relayed} cases are chosen to sit as close to a gateway cookie as a different cookie can: a
 * name that merely begins like one, a name that differs in case beyond the prefix, a gateway cookie
 * appearing only as a value or inside an attribute. A filter that dropped by substring would fail
 * them; a filter that dropped nothing fails the {@code Dropped} cases.
 */
@DisplayName("UpstreamSetCookieFilter — a gateway cookie is set by the gateway alone")
class UpstreamSetCookieFilterTest {

    private static final String SET_COOKIE = "Set-Cookie";
    private static final String SESSION = "__Host-sheriff-session";
    private static final String BINDING = "__Host-sheriff-binding";
    /** A configured session cookie name outside the reserved prefix. */
    private static final String CONFIGURED = "__Host-shop-session";

    private final UpstreamSetCookieFilter filter = new UpstreamSetCookieFilter(GatewayCookies.of(
            GatewayConfig.builder().version(1).oidc(OidcConfig.builder()
                    .redirectUri("https://gw.example.com/auth/callback")
                    .session(OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_SERVER)
                            .cookieName(CONFIGURED).build())
                    .build()).build()));

    @Nested
    @DisplayName("Dropped — the line names a gateway cookie")
    class Dropped {

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
        @DisplayName("Should drop a line whose cookie name is a gateway cookie name")
        void shouldDropOwnedName(String line) {
            assertFalse(filter.relays(SET_COOKIE, line));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "__HOST-sheriff-session=attacker",
                "__host-sheriff-session=attacker",
                "__hOsT-sheriff-binding=x",
                "__HOST-shop-session=x"})
        @DisplayName("Should drop a line that differs from a gateway cookie name only in the case of its prefix")
        void shouldDropPrefixCaseVariant(String line) {
            assertFalse(filter.relays(SET_COOKIE, line));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "=__Host-sheriff-session=attacker",
                " = __Host-sheriff-binding=x; Path=/",
                "=__Host-shop-session=attacker"})
        @DisplayName("Should drop a nameless cookie whose value would be read back as a gateway cookie")
        void shouldDropNamelessCookieCarryingOwnedName(String line) {
            assertFalse(filter.relays(SET_COOKIE, line));
        }

        @ParameterizedTest(name = "header name \"{0}\"")
        @ValueSource(strings = {"Set-Cookie", "set-cookie", "SET-COOKIE"})
        @DisplayName("Should recognise the Set-Cookie header whatever its case")
        void shouldMatchHeaderNameWithoutCase(String headerName) {
            assertFalse(filter.relays(headerName, SESSION + "=attacker"));
        }
    }

    @Nested
    @DisplayName("Relayed — the line is a cookie of the upstream's own")
    class Relayed {

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
            assertTrue(filter.relays(SET_COOKIE, line));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "__Host-Sheriff-Session=x",
                "__Host-SHERIFF-BINDING=x",
                "__Host-Shop-Session=x",
                "__Host-SHOP-SESSION=x"})
        @DisplayName("Should relay a name that differs from a gateway cookie name in case beyond the prefix")
        void shouldRelayNameCaseVariant(String line) {
            assertTrue(filter.relays(SET_COOKIE, line),
                    "a browser stores this under its own, different name");
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
            assertTrue(filter.relays(SET_COOKIE, line));
        }

        @ParameterizedTest(name = "header \"{0}\"")
        @ValueSource(strings = {"X-Set-Cookie", "Cookie", "Set-Cookie2", "__Host-sheriff-session"})
        @DisplayName("Should not judge a header that is not Set-Cookie")
        void shouldRelayOtherHeaders(String headerName) {
            assertTrue(filter.relays(headerName, SESSION + "=attacker"));
        }
    }

    @Nested
    @DisplayName("A gateway without a session runtime still owns its reserved cookies")
    class WithoutSessionRuntime {

        private final UpstreamSetCookieFilter bearerOnly = new UpstreamSetCookieFilter(
                GatewayCookies.of(GatewayConfig.builder().version(1).build()));

        @Test
        @DisplayName("Should drop a line under the reserved prefix on a gateway without an oidc block")
        void shouldDropReservedNamesWithoutOidc() {
            assertAll("the reserved names are the gateway's with or without a BFF",
                    () -> assertFalse(bearerOnly.relays(SET_COOKIE, "__Host-sheriff-x=1")),
                    () -> assertFalse(bearerOnly.relays(SET_COOKIE, SESSION + "=x")),
                    () -> assertFalse(bearerOnly.relays(SET_COOKIE, "=" + BINDING + "=x")));
        }

        @Test
        @DisplayName("control: an application cookie and an unconfigured name outside the prefix are relayed")
        void shouldRelayApplicationCookiesWithoutOidc() {
            assertAll("only the reserved names are owned",
                    () -> assertTrue(bearerOnly.relays(SET_COOKIE, "theme=dark")),
                    () -> assertTrue(bearerOnly.relays(SET_COOKIE, CONFIGURED + "=x"),
                            "a session cookie name is a gateway cookie only where it is configured"));
        }

        @Test
        @DisplayName("Should reject a missing definition")
        void shouldRejectMissingDefinition() {
            assertThrows(NullPointerException.class, () -> new UpstreamSetCookieFilter(null));
        }
    }
}
