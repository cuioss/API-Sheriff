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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;

/**
 * Tests for {@link ReservedPathRegistry}: the exact-match, OIDC-host-only carve-out (D2) that
 * guarantees a proxy route such as {@code path_prefix: /auth} never swallows the exact
 * {@code /auth/callback}, plus the empty registry when no OIDC callback is configured.
 */
class ReservedPathRegistryTest {

    private static final String OIDC_HOST = "gw.example.com";
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final String LOGOUT_RETURN_PATH = "/auth/logout/return";
    private static final String BACKCHANNEL_PATH = "/auth/backchannel";

    private static ReservedPathRegistry fullyConfigured() {
        OidcConfig.Logout logout = OidcConfig.Logout.builder()
                .path(Optional.of(LOGOUT_PATH))
                .postLogoutRedirectUri(Optional.of("https://" + OIDC_HOST + LOGOUT_RETURN_PATH))
                .backchannelPath(Optional.of(BACKCHANNEL_PATH))
                .build();
        OidcConfig oidc = OidcConfig.builder()
                .redirectUri(Optional.of("https://" + OIDC_HOST + CALLBACK_PATH))
                .logout(Optional.of(logout))
                .build();
        return ReservedPathRegistry.from(Optional.of(oidc));
    }

    @Nested
    @DisplayName("Exact-match carve-out (a /auth proxy route never swallows /auth/callback)")
    class ExactMatch {

        private final ReservedPathRegistry registry = fullyConfigured();

        @Test
        @DisplayName("Should resolve the exact callback path on the OIDC host")
        void shouldResolveExactCallback() {
            assertEquals(Optional.of(ReservedEndpoint.CALLBACK), registry.match(OIDC_HOST, CALLBACK_PATH));
            assertTrue(registry.isReserved(OIDC_HOST, CALLBACK_PATH));
        }

        @ParameterizedTest(name = "reserved path \"{0}\" is matched exactly")
        @ValueSource(strings = {LOGOUT_PATH, LOGOUT_RETURN_PATH, BACKCHANNEL_PATH})
        @DisplayName("Should resolve each configured reserved path exactly")
        void shouldResolveEachReservedPath(String path) {
            assertTrue(registry.isReserved(OIDC_HOST, path), path);
        }

        @ParameterizedTest(name = "prefix/sibling path \"{0}\" is not reserved")
        @ValueSource(strings = {"/auth", "/auth/", "/auth/callbacks", "/auth/callback/extra", "/auth/other",
                "/authcallback", "/"})
        @DisplayName("Should not match a prefix, sibling, or extended path — the exact carve-out")
        void shouldNotMatchPrefixOrSibling(String path) {
            assertFalse(registry.isReserved(OIDC_HOST, path), path);
            assertTrue(registry.match(OIDC_HOST, path).isEmpty(), path);
        }
    }

    @Nested
    @DisplayName("OIDC-host-only matching")
    class HostScoping {

        private final ReservedPathRegistry registry = fullyConfigured();

        @Test
        @DisplayName("Should not match the reserved path on a foreign host")
        void shouldNotMatchForeignHost() {
            assertTrue(registry.match("app.example.com", CALLBACK_PATH).isEmpty());
            assertFalse(registry.isReserved("other.host", CALLBACK_PATH));
        }

        @Test
        @DisplayName("Should match the OIDC host case-insensitively")
        void shouldMatchHostCaseInsensitively() {
            assertEquals(Optional.of(ReservedEndpoint.CALLBACK), registry.match("GW.EXAMPLE.COM", CALLBACK_PATH));
        }

        @Test
        @DisplayName("Should not match when the request host or path is absent")
        void shouldNotMatchNullHostOrPath() {
            assertTrue(registry.match(null, CALLBACK_PATH).isEmpty());
            assertTrue(registry.match(OIDC_HOST, null).isEmpty());
        }
    }

    @Nested
    @DisplayName("Empty registry when no OIDC callback is configured")
    class EmptyRegistry {

        @Test
        @DisplayName("Should be empty and never match when no oidc block is present")
        void shouldBeEmptyWithoutOidc() {
            ReservedPathRegistry registry = ReservedPathRegistry.from(Optional.empty());
            assertTrue(registry.isEmpty());
            assertFalse(registry.isReserved(OIDC_HOST, CALLBACK_PATH));
        }

        @Test
        @DisplayName("Should be empty when the oidc block declares no redirect_uri (no OIDC host to bind to)")
        void shouldBeEmptyWithoutRedirectUri() {
            OidcConfig oidc = OidcConfig.builder()
                    .logout(Optional.of(OidcConfig.Logout.builder().path(Optional.of(LOGOUT_PATH)).build()))
                    .build();
            ReservedPathRegistry registry = ReservedPathRegistry.from(Optional.of(oidc));
            assertTrue(registry.isEmpty(), "no redirect_uri -> no OIDC host -> no reserved paths");
            assertFalse(registry.isReserved(OIDC_HOST, LOGOUT_PATH));
        }

        @Test
        @DisplayName("Should reject a null oidc argument")
        void shouldRejectNullArgument() {
            assertThrows(NullPointerException.class, () -> ReservedPathRegistry.from(null));
        }
    }
}
