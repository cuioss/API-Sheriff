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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ReservedPathRegistry}: the exact-match carve-out (D2) that guarantees a proxy
 * route such as {@code path_prefix: /auth} never swallows the exact {@code /auth/callback}, the
 * OIDC-host gate that the five browser-facing endpoints carry and the back-channel receiver
 * deliberately does not, plus the empty registry when no OIDC callback is configured.
 */
class ReservedPathRegistryTest {

    private static final String OIDC_HOST = "gw.example.com";
    /**
     * A host that is not the OIDC host — the internal name an identity provider dials the gateway by
     * on a shared container network, and the browser-invisible virtual host a proxied namespace would
     * otherwise own.
     */
    private static final String FOREIGN_HOST = "api-sheriff.internal";
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final String LOGOUT_RETURN_PATH = "/auth/logout/return";
    private static final String BACKCHANNEL_PATH = "/auth/backchannel";

    private static ReservedPathRegistry fullyConfigured() {
        OidcConfig.Logout logout = OidcConfig.Logout.builder()
                .path(LOGOUT_PATH)
                .postLogoutRedirectUri("https://" + OIDC_HOST + LOGOUT_RETURN_PATH)
                .backchannelPath(BACKCHANNEL_PATH)
                .build();
        OidcConfig oidc = OidcConfig.builder()
                .redirectUri("https://" + OIDC_HOST + CALLBACK_PATH)
                .logout(logout)
                .build();
        return ReservedPathRegistry.from(oidc);
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
    @DisplayName("Host scoping — OIDC-host-only for the browser endpoints, every host for the back channel")
    class HostScoping {

        private final ReservedPathRegistry registry = fullyConfigured();

        @ParameterizedTest(name = "browser-facing path \"{0}\" is not reserved on a foreign host")
        @ValueSource(strings = {CALLBACK_PATH, LOGOUT_PATH, LOGOUT_RETURN_PATH})
        @DisplayName("Should not match a browser-facing reserved path on a foreign host")
        void shouldNotMatchForeignHost(String path) {
            assertTrue(registry.match(FOREIGN_HOST, path).isEmpty(), path);
            assertFalse(registry.isReserved(FOREIGN_HOST, path), path);
        }

        /**
         * The matched positive control for the exemption, and the reason the negative control above is
         * parameterized over the browser-facing paths only: the identity provider dials the
         * back-channel receiver server-to-server at the address the relying party registered, which in
         * a container deployment is an internal service name and never the browser-facing OIDC host.
         * Gating this path on the OIDC host made the delivered logout token reach the proxy route
         * table, answer {@code 404}, and leave the session it was meant to destroy alive — the defect
         * {@code BffBackchannelLogoutIT} caught. A regression to the old rule re-breaks that suite,
         * which needs a container; this assertion is the same contract stated where it runs in seconds.
         */
        @Test
        @DisplayName("Should match the back-channel receiver on a foreign host — the IdP dials it by an internal name")
        void shouldMatchBackchannelOnForeignHost() {
            assertEquals(Optional.of(ReservedEndpoint.BACKCHANNEL_LOGOUT),
                    registry.match(FOREIGN_HOST, BACKCHANNEL_PATH));
            assertTrue(registry.isReserved(FOREIGN_HOST, BACKCHANNEL_PATH));
        }

        @Test
        @DisplayName("Should still match the back-channel receiver on the OIDC host itself")
        void shouldMatchBackchannelOnOidcHost() {
            assertEquals(Optional.of(ReservedEndpoint.BACKCHANNEL_LOGOUT),
                    registry.match(OIDC_HOST, BACKCHANNEL_PATH));
        }

        @Test
        @DisplayName("Should match the OIDC host case-insensitively")
        void shouldMatchHostCaseInsensitively() {
            assertEquals(Optional.of(ReservedEndpoint.CALLBACK), registry.match("GW.EXAMPLE.COM", CALLBACK_PATH));
        }

        @Test
        @DisplayName("Should not match a browser-facing path when the request host or the path is absent")
        void shouldNotMatchNullHostOrPath() {
            assertTrue(registry.match(null, CALLBACK_PATH).isEmpty());
            assertTrue(registry.match(OIDC_HOST, null).isEmpty());
            assertTrue(registry.match(null, null).isEmpty());
        }

        /**
         * An absent {@code Host} carries no host to compare, and the back-channel endpoint compares
         * none — so the exemption holds here too rather than falling back to the gate. Pinned because
         * the two absences are answered by different branches: a {@code null} path has no entry to
         * resolve at all and stays empty (asserted above), while a {@code null} host reaches the
         * endpoint and is admitted.
         */
        @Test
        @DisplayName("Should match the back-channel receiver when the request carries no Host at all")
        void shouldMatchBackchannelWithoutHost() {
            assertEquals(Optional.of(ReservedEndpoint.BACKCHANNEL_LOGOUT),
                    registry.match(null, BACKCHANNEL_PATH));
        }
    }

    @Nested
    @DisplayName("Empty registry when no OIDC callback is configured")
    class EmptyRegistry {

        @Test
        @DisplayName("Should be empty and never match when no oidc block is present")
        void shouldBeEmptyWithoutOidc() {
            ReservedPathRegistry registry = ReservedPathRegistry.from(null);
            assertTrue(registry.isEmpty());
            assertFalse(registry.isReserved(OIDC_HOST, CALLBACK_PATH));
        }

        @Test
        @DisplayName("Should be empty when the oidc block declares no redirect_uri (no OIDC host to bind to)")
        void shouldBeEmptyWithoutRedirectUri() {
            OidcConfig oidc = OidcConfig.builder()
                    .logout(OidcConfig.Logout.builder().path(LOGOUT_PATH).build())
                    .build();
            ReservedPathRegistry registry = ReservedPathRegistry.from(oidc);
            assertTrue(registry.isEmpty(), "no redirect_uri -> no OIDC host -> no reserved paths");
            assertFalse(registry.isReserved(OIDC_HOST, LOGOUT_PATH));
        }
    }
}
