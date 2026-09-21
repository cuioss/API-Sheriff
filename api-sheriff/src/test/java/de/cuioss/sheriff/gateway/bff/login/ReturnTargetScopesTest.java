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
package de.cuioss.sheriff.gateway.bff.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ReturnTargetScopes}: a {@code /auth/login?returnUrl=} target resolves to the
 * {@code neededScopes} of the authenticated route it lands on, and to {@code oidc.scopes} in every
 * other case.
 * <p>
 * The scope names are literals on purpose: each route's set must be distinguishable from every other
 * route's and from {@code oidc.scopes}, so that an assertion names which route was selected.
 */
@DisplayName("ReturnTargetScopes — login scope set resolved from the return target")
class ReturnTargetScopesTest {

    private static final String ORIGIN = "https://gw.example.com";
    private static final Set<String> OIDC_SCOPES = Set.of("openid", "profile", "email");
    private static final Set<String> SPECIAL_SCOPES = Set.of("openid", "profile", "email", "special:read");
    private static final Set<String> APP_SCOPES = Set.of("openid", "profile", "email", "app:read");
    private static final Set<String> API_SCOPES = Set.of("openid", "profile", "email", "api:read");
    private static final Set<String> HEADER_SCOPES = Set.of("openid", "profile", "email", "tenant:read");
    private static final Set<String> PUBLIC_SCOPES = Set.of("openid", "profile", "email", "public:read");

    /**
     * The table in route-selection order, exactly as the route-table builder freezes it: every exact
     * route first, then prefixes longest-first.
     */
    private final ReturnTargetScopes resolver = new ReturnTargetScopes(new RouteTable(List.of(
            route("special", MatchConfig.builder().path("/api/special").build(), Require.SESSION, SPECIAL_SCOPES),
            route("tenant", MatchConfig.builder().pathPrefix("/tenant")
                    .headers(List.of(new MatchConfig.HeaderMatcher("X-Tenant", null, "acme"))).build(),
                    Require.SESSION, HEADER_SCOPES),
            route("public", MatchConfig.builder().pathPrefix("/public").build(), Require.NONE, PUBLIC_SCOPES),
            route("app", MatchConfig.builder().pathPrefix("/app").build(), Require.SESSION, APP_SCOPES),
            route("api", MatchConfig.builder().pathPrefix("/api").build(), Require.BEARER, API_SCOPES),
            route("tenant-fallback", MatchConfig.builder().pathPrefix("/tenant").build(), Require.SESSION,
                    APP_SCOPES))), ORIGIN, OIDC_SCOPES);

    private static ResolvedRoute route(String id, MatchConfig match, Require require, Set<String> neededScopes) {
        return ResolvedRoute.builder()
                .id(id)
                .match(match)
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("https", "upstream.example", 443, ""))
                .neededScopes(neededScopes)
                .build();
    }

    @Nested
    @DisplayName("A target landing on an authenticated route")
    class AuthenticatedRoute {

        @Test
        @DisplayName("Should resolve a session route to its neededScopes (oidc.scopes united with the endpoint's)")
        void shouldResolveSessionRoute() {
            assertEquals(APP_SCOPES, resolver.resolve("/app/orders"));
        }

        @Test
        @DisplayName("Should resolve a bearer route to its neededScopes too")
        void shouldResolveBearerRoute() {
            assertEquals(API_SCOPES, resolver.resolve("/api/orders"));
        }

        @Test
        @DisplayName("Should resolve a same-origin absolute URL like its relative path")
        void shouldResolveAbsoluteSameOriginUrl() {
            assertEquals(APP_SCOPES, resolver.resolve(ORIGIN + "/app/orders"));
        }

        @ParameterizedTest(name = "\"{0}\" matches on its path alone")
        @ValueSource(strings = {"/app/orders?tab=open&page=2", "/app/orders#section", "/app/orders?next=/public"})
        @DisplayName("Should ignore the query and fragment when matching")
        void shouldIgnoreQueryForMatching(String returnUrl) {
            assertEquals(APP_SCOPES, resolver.resolve(returnUrl));
        }

        @Test
        @DisplayName("Should match the dot-segment-normalized path")
        void shouldMatchNormalizedPath() {
            assertEquals(APP_SCOPES, resolver.resolve("/public/../app/orders"));
        }
    }

    @Nested
    @DisplayName("Route-table order")
    class Ordering {

        @Test
        @DisplayName("Should select an exact route before a prefix route covering the same path")
        void shouldPreferExactRoute() {
            assertEquals(SPECIAL_SCOPES, resolver.resolve("/api/special"));
        }

        @Test
        @DisplayName("Should fall through to the prefix route for a sibling of the exact path")
        void shouldFallThroughToPrefix() {
            assertEquals(API_SCOPES, resolver.resolve("/api/special/nested"));
        }

        @Test
        @DisplayName("Should never select a header-matched route, and fall through to the next matching route")
        void shouldSkipHeaderMatchedRoute() {
            assertEquals(APP_SCOPES, resolver.resolve("/tenant/dashboard"),
                    "a header matcher cannot be evaluated before the request happens, so the "
                            + "header-free fallback route is selected instead");
        }
    }

    @Nested
    @DisplayName("Every other target falls back to oidc.scopes")
    class Fallback {

        @Test
        @DisplayName("Should fall back for a require:none route, whatever its endpoint scopes")
        void shouldFallBackForUnauthenticatedRoute() {
            assertEquals(OIDC_SCOPES, resolver.resolve("/public/landing"));
        }

        @Test
        @DisplayName("Should fall back when no route matches")
        void shouldFallBackForNoMatch() {
            assertEquals(OIDC_SCOPES, resolver.resolve("/unrouted"));
        }

        @Test
        @DisplayName("Should fall back when no return URL is supplied")
        void shouldFallBackForAbsent() {
            assertEquals(OIDC_SCOPES, resolver.resolve(null));
        }

        @ParameterizedTest(name = "\"{0}\" is not a same-origin target")
        @ValueSource(strings = {"https://evil.example.com/app/orders", "//evil.example.com/app", "/\\evil.example.com",
                "", " ", "javascript:alert(1)"})
        @DisplayName("Should fall back for a cross-origin, schema-relative, blank or unparseable target")
        void shouldFallBackForCrossOrigin(String returnUrl) {
            assertEquals(OIDC_SCOPES, resolver.resolve(returnUrl));
        }

        @ParameterizedTest(name = "\"{0}\" is not a canonical path")
        @ValueSource(strings = {"/app%2forders", "/app%5Corders", "/app;jsessionid=1", "/../app/orders", "/app/a b"})
        @DisplayName("Should fall back for a non-canonical or unparseable path")
        void shouldFallBackForNonCanonicalPath(String returnUrl) {
            assertEquals(OIDC_SCOPES, resolver.resolve(returnUrl));
        }

        @Test
        @DisplayName("Should treat the bare gateway origin as the root path, which no route here covers")
        void shouldResolveBareOrigin() {
            assertEquals(OIDC_SCOPES, resolver.resolve(ORIGIN),
                    "the bare origin is the root path, which no route in this table covers");
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Should resolve everything to oidc.scopes over an empty route table")
        void shouldFallBackOverEmptyTable() {
            ReturnTargetScopes empty = new ReturnTargetScopes(new RouteTable(List.of()), ORIGIN, OIDC_SCOPES);

            assertEquals(OIDC_SCOPES, empty.resolve("/app/orders"));
        }

        @Test
        @DisplayName("Should reject absent constructor arguments")
        void shouldRejectNulls() {
            RouteTable table = new RouteTable(List.of());
            assertThrows(NullPointerException.class, () -> new ReturnTargetScopes(null, ORIGIN, OIDC_SCOPES));
            assertThrows(NullPointerException.class, () -> new ReturnTargetScopes(table, null, OIDC_SCOPES));
            assertThrows(NullPointerException.class, () -> new ReturnTargetScopes(table, ORIGIN, null));
        }
    }
}
