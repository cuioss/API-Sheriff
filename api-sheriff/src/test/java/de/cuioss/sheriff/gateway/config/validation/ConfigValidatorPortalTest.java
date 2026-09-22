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
package de.cuioss.sheriff.gateway.config.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CatalogConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.validation.rule.PortalRules;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the application-portal rules the {@link ConfigValidator} runs through {@link PortalRules}:
 * a canonical {@code portal.path}, no collision with a reserved OIDC path or an enabled exact route, and
 * an origin-relative {@code catalog.entry}.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed. Errors are filtered to the two portal pointers, because the
 * fixtures are deliberately minimal and other rules may report unrelated gaps in them. The refused
 * spellings are literals on purpose: each is a specific shape the rule exists to refuse, so the exact
 * string is the contract under test.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — application portal")
class ConfigValidatorPortalTest {

    private static final String OIDC_HOST = "gw.example.com";

    private final ConfigValidator validator = new ConfigValidator();

    private static String endpointId() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private static PortalConfig portal(String path) {
        return PortalConfig.builder().path(path).title(Generators.nonBlankStrings().next()).build();
    }

    private static GatewayConfig gateway(@Nullable PortalConfig portal, @Nullable OidcConfig oidc) {
        return GatewayConfig.builder().version(1).portal(portal).oidc(oidc).build();
    }

    private static RouteConfig exactRedirectRoute(String path) {
        return RouteConfig.builder()
                .id(endpointId())
                .match(MatchConfig.builder().path(path).build())
                .redirect(RedirectConfig.builder().location("/elsewhere").status(302).build())
                .build();
    }

    private static RouteConfig prefixRedirectRoute(String prefix) {
        return RouteConfig.builder()
                .id(endpointId())
                .match(MatchConfig.builder().pathPrefix(prefix).build())
                .redirect(RedirectConfig.builder().location("/elsewhere").status(302).build())
                .build();
    }

    private static EndpointConfig endpoint(boolean enabled, @Nullable CatalogConfig catalog, RouteConfig route) {
        return EndpointConfig.builder()
                .id(endpointId())
                .enabled(enabled)
                .auth(new AuthConfig(Require.NONE, null))
                .routes(List.of(route))
                .catalog(catalog)
                .build();
    }

    private static CatalogConfig catalog(String entry) {
        return CatalogConfig.builder().title(Generators.nonBlankStrings().next()).entry(entry).build();
    }

    private List<ConfigError> portalErrors(GatewayConfig gateway, List<EndpointConfig> endpoints) {
        return validator.validate(gateway, endpoints, new ResolvedTopology(Map.of())).stream()
                .filter(error -> PortalRules.PORTAL_PATH_POINTER.equals(error.pointer())
                        || PortalRules.CATALOG_ENTRY_POINTER.equals(error.pointer()))
                .toList();
    }

    private static void assertRefused(List<ConfigError> errors, String pointer, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(error -> pointer.equals(error.pointer()) && error.message().contains(messageContains)),
                () -> "expected an error at " + pointer + " containing '" + messageContains + "', but got: " + errors);
    }

    @Nested
    @DisplayName("portal.path must be canonical")
    class CanonicalPath {

        @ParameterizedTest(name = "accepts {0}")
        @ValueSource(strings = {"/", "/portal", "/apps/overview", "/start-page"})
        void acceptsCanonicalPath(String path) {
            assertEquals(List.of(), portalErrors(gateway(portal(path), null), List.of()));
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"portal", "//portal", "/apps//overview", "/apps/./overview", "/apps/../overview",
                "/portal?x=1", "/portal#top", "/apps%2foverview", "/apps%5Coverview", "/%2e/portal"})
        void refusesNonCanonicalPath(String path) {
            List<ConfigError> errors = portalErrors(gateway(portal(path), null), List.of());

            assertRefused(errors, PortalRules.PORTAL_PATH_POINTER, "canonical gateway path");
            assertTrue(errors.stream().allMatch(error -> "gateway.yaml".equals(error.file())), errors::toString);
        }

        @Test
        void noPortalBlockReportsNothing() {
            assertEquals(List.of(), portalErrors(gateway(null, null), List.of()));
        }
    }

    @Nested
    @DisplayName("portal.path must not equal a reserved OIDC path")
    class ReservedOidcPath {

        private static final String CALLBACK = "/auth/callback";
        private static final String LOGOUT = "/auth/logout";
        private static final String LOGOUT_RETURN = "/auth/logout/return";
        private static final String BACKCHANNEL = "/auth/backchannel";
        private static final String USER_INFO = "/session/userinfo";
        private static final String LOGIN = "/session/login";

        private OidcConfig oidc() {
            return OidcConfig.builder()
                    .redirectUri("https://" + OIDC_HOST + CALLBACK)
                    .logout(OidcConfig.Logout.builder()
                            .path(LOGOUT)
                            .postLogoutRedirectUri("https://" + OIDC_HOST + LOGOUT_RETURN)
                            .backchannelPath(BACKCHANNEL)
                            .build())
                    .userInfo(OidcConfig.UserInfo.builder().path(USER_INFO).build())
                    .login(new OidcConfig.Login(LOGIN, null))
                    .build();
        }

        @ParameterizedTest(name = "refuses the reserved path {0}")
        @ValueSource(strings = {CALLBACK, LOGOUT, LOGOUT_RETURN, BACKCHANNEL, USER_INFO, LOGIN})
        void refusesEveryReservedPathKind(String reserved) {
            assertRefused(portalErrors(gateway(portal(reserved), oidc()), List.of()),
                    PortalRules.PORTAL_PATH_POINTER, "reserved OIDC path");
        }

        @ParameterizedTest(name = "accepts the non-reserved path {0}")
        @ValueSource(strings = {"/", "/auth", "/auth/callback/extra", "/session"})
        void acceptsPathNextToReservedPaths(String path) {
            assertEquals(List.of(), portalErrors(gateway(portal(path), oidc()), List.of()));
        }

        @Test
        void acceptsAnyPathWithoutOidcBlock() {
            assertEquals(List.of(), portalErrors(gateway(portal(CALLBACK), null), List.of()));
        }
    }

    @Nested
    @DisplayName("portal.path must not equal an enabled exact route")
    class ExactRouteCollision {

        @Test
        void refusesEqualExactRoute() {
            String path = "/" + endpointId();
            EndpointConfig endpoint = endpoint(true, null, exactRedirectRoute(path));

            List<ConfigError> errors = portalErrors(gateway(portal(path), null), List.of(endpoint));

            assertRefused(errors, PortalRules.PORTAL_PATH_POINTER, "unreachable");
            assertRefused(errors, PortalRules.PORTAL_PATH_POINTER, endpoint.id());
        }

        @Test
        void acceptsPrefixRouteCoveringThePortalPath() {
            EndpointConfig root = endpoint(true, null, prefixRedirectRoute("/"));

            assertEquals(List.of(), portalErrors(gateway(portal("/"), null), List.of(root)),
                    "claiming the context root next to a prefix route is the purpose of the portal seam");
        }

        @Test
        void acceptsExactRouteOnAnotherPath() {
            EndpointConfig endpoint = endpoint(true, null, exactRedirectRoute("/portal/"));

            assertEquals(List.of(), portalErrors(gateway(portal("/portal"), null), List.of(endpoint)),
                    "exact matching is un-normalized, so /portal and /portal/ are distinct addresses");
        }

        @Test
        void doesNotEvaluateDisabledEndpoint() {
            EndpointConfig disabled = endpoint(false, null, exactRedirectRoute("/portal"));

            assertEquals(List.of(), portalErrors(gateway(portal("/portal"), null), List.of(disabled)));
        }
    }

    @Nested
    @DisplayName("catalog.entry must stay on the gateway's own origin")
    class CatalogEntry {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"https://evil.example/", "//evil.example", "/\\evil.example", "/%5Cevil.example",
                "/%5cevil.example", "/%2F%2Fevil.example", "evil.example", "/apps/../admin", "/apps/%2e%2e/admin",
                "/apps%2fx"})
        void refusesCrossOriginSpelling(String entry) {
            EndpointConfig endpoint = endpoint(true, catalog(entry), prefixRedirectRoute("/apps"));

            List<ConfigError> errors = portalErrors(gateway(null, null), List.of(endpoint));

            assertRefused(errors, PortalRules.CATALOG_ENTRY_POINTER, "outside the gateway's own origin");
            assertTrue(errors.stream().allMatch(error -> ("endpoints/" + endpoint.id() + ".yaml").equals(error.file())),
                    errors::toString);
        }

        @ParameterizedTest(name = "accepts {0}")
        @ValueSource(strings = {"/", "/apps/", "/apps/orders", "/apps/orders?tab=open", "/apps/orders#list"})
        void acceptsOriginRelativeEntry(String entry) {
            EndpointConfig endpoint = endpoint(true, catalog(entry), prefixRedirectRoute("/apps"));

            assertEquals(List.of(), portalErrors(gateway(null, null), List.of(endpoint)));
        }

        @Test
        void doesNotEvaluateDisabledEndpoint() {
            EndpointConfig disabled = endpoint(false, catalog("https://evil.example/"), prefixRedirectRoute("/apps"));

            assertEquals(List.of(), portalErrors(gateway(null, null), List.of(disabled)));
        }

        @Test
        void reportsEveryOffendingEndpoint() {
            EndpointConfig first = endpoint(true, catalog("//a.example"), prefixRedirectRoute("/a"));
            EndpointConfig second = endpoint(true, catalog("https://b.example/"), prefixRedirectRoute("/b"));

            assertEquals(2, portalErrors(gateway(null, null), List.of(first, second)).size(),
                    "the rule never fails fast");
        }
    }
}
