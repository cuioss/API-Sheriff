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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;


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
 * OIDC-host gate that the five browser-facing endpoints carry and the two the identity provider
 * dials — the back-channel receiver and the client JWKS endpoint — deliberately do not, the client
 * JWKS path that is reserved without being declared, plus the empty registry when no OIDC callback
 * is configured.
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
    /**
     * Deliberately the literal and not {@code ClientAuthenticationSettings.DEFAULT_JWKS_PATH}: the
     * default is a documented contract of the configuration surface, so a change of the constant must
     * turn these tests red rather than follow it.
     */
    private static final String DEFAULT_JWKS_PATH = "/auth/jwks";

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

    @Nested
    @DisplayName("Host-independent reserved path set")
    class ReservedPaths {

        private static final String USER_INFO_PATH = "/session/userinfo";
        private static final String LOGIN_PATH = "/session/login";

        /** Declares the six paths that need a declaration; the seventh, the client JWKS path, defaults. */
        private OidcConfig allSevenKinds() {
            OidcConfig.Logout logout = OidcConfig.Logout.builder()
                    .path(LOGOUT_PATH)
                    .postLogoutRedirectUri("https://" + OIDC_HOST + LOGOUT_RETURN_PATH)
                    .backchannelPath(BACKCHANNEL_PATH)
                    .build();
            return OidcConfig.builder()
                    .redirectUri("https://" + OIDC_HOST + CALLBACK_PATH)
                    .logout(logout)
                    .userInfo(OidcConfig.UserInfo.builder().path(USER_INFO_PATH).build())
                    .login(new OidcConfig.Login(LOGIN_PATH, null))
                    .build();
        }

        @Test
        @DisplayName("Should return every reserved path in declaration order, the client JWKS path last")
        void shouldReturnEveryReservedPathInDeclarationOrderWithTheClientJwksPathLast() {
            assertEquals(List.of(CALLBACK_PATH, LOGOUT_PATH, LOGOUT_RETURN_PATH, BACKCHANNEL_PATH, USER_INFO_PATH,
                    LOGIN_PATH, DEFAULT_JWKS_PATH), List.copyOf(ReservedPathRegistry.reservedPaths(allSevenKinds())));
        }

        @Test
        @DisplayName("Should be empty without an oidc block")
        void shouldBeEmptyWithoutOidc() {
            assertTrue(ReservedPathRegistry.reservedPaths(null).isEmpty());
        }

        @Test
        @DisplayName("Should agree with the registry: every returned path matches on the OIDC host")
        void shouldAgreeWithRegistryMatching() {
            OidcConfig oidc = allSevenKinds();
            ReservedPathRegistry registry = ReservedPathRegistry.from(oidc);
            Set<String> paths = ReservedPathRegistry.reservedPaths(oidc);
            assertEquals(ReservedEndpoint.values().length, paths.size(),
                    "one path per reserved kind — a kind added to the registry is a path this fixture must carry");
            for (String path : paths) {
                assertTrue(registry.isReserved(OIDC_HOST, path), path);
            }
        }

        @Test
        @DisplayName("Should derive the paths without an OIDC host, since the set is host-independent")
        void shouldDeriveWithoutRedirectUri() {
            OidcConfig oidc = OidcConfig.builder()
                    .logout(OidcConfig.Logout.builder().path(LOGOUT_PATH).build())
                    .build();
            assertEquals(Set.of(LOGOUT_PATH, DEFAULT_JWKS_PATH), ReservedPathRegistry.reservedPaths(oidc));
        }

        @Test
        @DisplayName("Should return an unmodifiable set")
        void shouldBeUnmodifiable() {
            Set<String> paths = ReservedPathRegistry.reservedPaths(allSevenKinds());
            assertThrows(UnsupportedOperationException.class, () -> paths.add("/x"));
        }
    }

    /**
     * The client JWKS path is the one reserved path that needs no declaration, and the one whose
     * key is proven here to <em>act</em> rather than merely parse: a declared {@code jwks_path} moves
     * the endpoint, so the default path is then no longer reserved.
     */
    @Nested
    @DisplayName("Client JWKS path — reserved by default, moved by jwks_path, matched on every host")
    class ClientJwksPath {

        private static final String DECLARED_JWKS_PATH = "/auth/client-keys";

        private static OidcConfig.OidcConfigBuilder withRedirectUri() {
            return OidcConfig.builder().redirectUri("https://" + OIDC_HOST + CALLBACK_PATH);
        }

        private static OidcConfig.ClientAuthenticationSettings declaring(String jwksPath) {
            return OidcConfig.ClientAuthenticationSettings.builder().jwksPath(jwksPath).build();
        }

        @Test
        @DisplayName("Should reserve the default path when no client_authentication block is declared")
        void shouldReserveTheDefaultPathWithoutABlock() {
            ReservedPathRegistry registry = ReservedPathRegistry.from(withRedirectUri().build());

            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS), registry.match(OIDC_HOST, DEFAULT_JWKS_PATH));
        }

        @Test
        @DisplayName("Should reserve the default path when the block declares a key file and no jwks_path")
        void shouldReserveTheDefaultPathWithoutAKey() {
            OidcConfig oidc = withRedirectUri().clientAuthentication(OidcConfig.ClientAuthenticationSettings
                    .builder().keyFile("/etc/sheriff/keys/client-auth.pem").build()).build();

            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS),
                    ReservedPathRegistry.from(oidc).match(OIDC_HOST, DEFAULT_JWKS_PATH));
        }

        @Test
        @DisplayName("Should reserve a declared jwks_path, and then no longer the default path")
        void shouldMoveTheEndpointToADeclaredPath() {
            ReservedPathRegistry registry = ReservedPathRegistry
                    .from(withRedirectUri().clientAuthentication(declaring(DECLARED_JWKS_PATH)).build());

            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS), registry.match(OIDC_HOST, DECLARED_JWKS_PATH),
                    "the declared path is the reserved one");
            assertFalse(registry.isReserved(OIDC_HOST, DEFAULT_JWKS_PATH),
                    "the key acts: declaring a path releases the default, it does not add a second one");
        }

        @Test
        @DisplayName("Should match the client JWKS path on the OIDC host, on a foreign host and without a Host")
        void shouldMatchOnEveryHost() {
            ReservedPathRegistry registry = ReservedPathRegistry.from(withRedirectUri().build());

            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS), registry.match(OIDC_HOST, DEFAULT_JWKS_PATH),
                    "the OIDC host");
            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS), registry.match(FOREIGN_HOST, DEFAULT_JWKS_PATH),
                    "the identity provider may dial the key set by an internal name, not the OIDC host");
            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS), registry.match(null, DEFAULT_JWKS_PATH),
                    "the endpoint compares no host, so an absent Host is admitted too");
        }

        @Test
        @DisplayName("Should keep the browser-facing callback host-gated in the same registry (matched control)")
        void shouldKeepTheCallbackHostGated() {
            ReservedPathRegistry registry = ReservedPathRegistry.from(withRedirectUri().build());

            assertFalse(registry.isReserved(FOREIGN_HOST, CALLBACK_PATH),
                    "the every-host match is the property of two kinds, not of the registry");
        }

        @ParameterizedTest(name = "\"{0}\" is not the client JWKS path")
        @ValueSource(strings = {"/auth", "/auth/", "/auth/jwk", "/auth/jwks/", "/auth/jwks.json", "/auth/jwks/keys"})
        @DisplayName("Should not match a prefix, a sibling or an extension of the client JWKS path")
        void shouldNotMatchAPrefixOrSibling(String path) {
            ReservedPathRegistry registry = ReservedPathRegistry.from(withRedirectUri().build());

            assertTrue(registry.match(OIDC_HOST, path).isEmpty(), path);
            assertTrue(registry.match(FOREIGN_HOST, path).isEmpty(), path);
        }

        @Test
        @DisplayName("Should reserve nothing without a redirect_uri, a declared jwks_path included")
        void shouldReserveNothingWithoutRedirectUri() {
            ReservedPathRegistry registry = ReservedPathRegistry
                    .from(OidcConfig.builder().clientAuthentication(declaring(DECLARED_JWKS_PATH)).build());

            assertTrue(registry.isEmpty(), "no redirect_uri -> the gateway serves no BFF variant");
            assertFalse(registry.isReserved(OIDC_HOST, DECLARED_JWKS_PATH));
            assertFalse(registry.isReserved(FOREIGN_HOST, DEFAULT_JWKS_PATH));
        }

        @Test
        @DisplayName("Should reserve the path with a client secret configured — the registry does not read the mode")
        void shouldReserveThePathInClientSecretMode() {
            ReservedPathRegistry registry = ReservedPathRegistry
                    .from(withRedirectUri().clientSecret("configured-secret").build());

            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS), registry.match(FOREIGN_HOST, DEFAULT_JWKS_PATH));
        }

        @Test
        @DisplayName("Should resolve the kind a path is reserved as, keeping the first registration on a collision")
        void shouldResolveTheReservedKind() {
            OidcConfig distinct = withRedirectUri()
                    .logout(OidcConfig.Logout.builder().path(LOGOUT_PATH).build()).build();
            OidcConfig colliding = withRedirectUri()
                    .logout(OidcConfig.Logout.builder().path(DEFAULT_JWKS_PATH).build()).build();

            assertEquals(Optional.of(ReservedEndpoint.CLIENT_JWKS),
                    ReservedPathRegistry.reservedKind(distinct, DEFAULT_JWKS_PATH),
                    "no other key names the path, so the client JWKS endpoint owns it");
            assertEquals(Optional.of(ReservedEndpoint.LOGOUT),
                    ReservedPathRegistry.reservedKind(colliding, DEFAULT_JWKS_PATH),
                    "the logout path was registered first, so the client JWKS endpoint lost the path");
            assertEquals(Optional.empty(), ReservedPathRegistry.reservedKind(distinct, "/not-reserved"));
            assertEquals(Optional.empty(), ReservedPathRegistry.reservedKind(null, DEFAULT_JWKS_PATH));
        }
    }
}
