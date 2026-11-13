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
package de.cuioss.sheriff.gateway.config;

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


import de.cuioss.sheriff.gateway.config.PolicyBlock.Fallback;
import de.cuioss.sheriff.gateway.config.PolicyBlock.Scope;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder.RouteTableException;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Tests for {@link PolicyBlock}: the scope set every descriptor declares, the one resolution rule
 * (nearest declared scope wins, wholesale), the shared "is declared" test (an empty list is not a
 * declaration), each of the three fallback kinds, that a scope a block is not declarable at is never
 * read, and the anchor resolution of {@link ScopeChain#of}.
 */
@EnableGeneratorController
class PolicyBlockTest {

    private static String name() {
        return Generators.letterStrings(3, 12).next();
    }

    private static GatewayConfig gatewayWith(AnchorConfig anchor) {
        return validGateway().anchors(Map.of(anchor.name(), anchor)).build();
    }

    private static EndpointConfig.EndpointConfigBuilder endpoint() {
        return endpointSeed(name());
    }

    private static RouteConfig.RouteConfigBuilder route() {
        return routeConfigSeed(name());
    }

    @Nested
    @DisplayName("Descriptor declarations")
    class Declarations {

        @Test
        @DisplayName("ALL enumerates the eight policy blocks, each under its own yaml key")
        void shouldEnumerateTheEightBlocks() {
            List<String> keys = PolicyBlock.ALL.stream().map(PolicyBlock::yamlKey).toList();

            assertEquals(List.of("auth", "allowed_methods", "security_filter", "security_headers",
                    "upstream_defaults", "forward", "websocket", "cookies"), keys);
        }

        @Test
        @DisplayName("Every descriptor declares exactly its decided scope set")
        void shouldDeclareTheDecidedScopeSets() {
            Map<String, Set<Scope>> scopes = PolicyBlock.ALL.stream()
                    .collect(Collectors.toMap(PolicyBlock::yamlKey, PolicyBlock::scopes));

            assertAll("scope sets",
                    () -> assertEquals(EnumSet.of(Scope.ANCHOR, Scope.ENDPOINT, Scope.ROUTE), scopes.get("auth")),
                    () -> assertEquals(EnumSet.of(Scope.GATEWAY, Scope.ANCHOR, Scope.ENDPOINT),
                            scopes.get("allowed_methods")),
                    () -> assertEquals(EnumSet.of(Scope.ANCHOR, Scope.ENDPOINT, Scope.ROUTE),
                            scopes.get("security_filter")),
                    () -> assertEquals(EnumSet.of(Scope.GATEWAY, Scope.ANCHOR), scopes.get("security_headers")),
                    () -> assertEquals(EnumSet.of(Scope.GATEWAY, Scope.ENDPOINT), scopes.get("upstream_defaults")),
                    () -> assertEquals(EnumSet.of(Scope.ENDPOINT, Scope.ROUTE), scopes.get("forward")),
                    () -> assertEquals(EnumSet.of(Scope.ROUTE), scopes.get("websocket")),
                    () -> assertEquals(EnumSet.allOf(Scope.class), scopes.get("cookies")));
        }

        @Test
        @DisplayName("Neither widened block is declarable at the gateway scope")
        void shouldNotReadTheWidenedBlocksAtTheGateway() {
            assertAll("no gateway scope",
                    () -> assertFalse(PolicyBlock.SECURITY_FILTER.scopes().contains(Scope.GATEWAY),
                            "security_defaults owns the gateway-wide profile"),
                    () -> assertFalse(PolicyBlock.FORWARD.scopes().contains(Scope.GATEWAY),
                            "forward filtering is upstream-specific"),
                    () -> assertFalse(PolicyBlock.FORWARD.scopes().contains(Scope.ANCHOR),
                            "forward filtering is upstream-specific"));
        }

        @Test
        @DisplayName("Exactly allowed_methods, security_filter and cookies opt into the override WARN")
        void shouldOptExactlyTheDecidedBlocksIntoTheOverrideWarn() {
            Set<String> optedIn = PolicyBlock.ALL.stream().filter(PolicyBlock::overrideWarn)
                    .map(PolicyBlock::yamlKey).collect(Collectors.toSet());

            assertEquals(Set.of("allowed_methods", "security_filter", "cookies"), optedIn);
        }

        @Test
        @DisplayName("cookies resolves allow: none when no scope declares it")
        void shouldFallBackToNoCookies() {
            CookiesConfig resolved = PolicyBlock.COOKIES.resolve(
                    ScopeChain.of(validGateway().build(), endpoint().build(), route().build()));

            assertAll("nothing-declared cookies fallback",
                    () -> assertSame(CookiesConfig.NONE, resolved),
                    () -> assertNull(PolicyBlock.COOKIES.declared(
                                    ScopeChain.of(validGateway().build(), endpoint().build(), route().build())),
                            "the fallback is not a declaration"),
                    () -> assertTrue(PolicyBlock.ALL.contains(PolicyBlock.COOKIES)));
        }

        @Test
        @DisplayName("cookies: route over endpoint over anchor over gateway, whole block")
        void shouldResolveCookiesNearestFirst() {
            CookiesConfig gatewayCookies = cookies(name());
            CookiesConfig anchorCookies = cookies(name());
            CookiesConfig endpointCookies = cookies(name());
            CookiesConfig routeCookies = new CookiesConfig(CookieAllow.ALL, null, null);
            AnchorConfig anchor = anchorSeed(name()).cookies(anchorCookies).build();
            GatewayConfig gateway = validGateway().cookies(gatewayCookies).anchors(Map.of(anchor.name(), anchor)).build();

            CookiesConfig routeWins = PolicyBlock.COOKIES.resolve(ScopeChain.of(gateway,
                    endpoint().anchor(anchor.name()).cookies(endpointCookies).build(),
                    route().cookies(routeCookies).build()));
            CookiesConfig endpointWins = PolicyBlock.COOKIES.resolve(ScopeChain.of(gateway,
                    endpoint().anchor(anchor.name()).cookies(endpointCookies).build(), route().build()));
            CookiesConfig anchorWins = PolicyBlock.COOKIES.resolve(ScopeChain.of(gateway,
                    endpoint().anchor(anchor.name()).build(), route().build()));
            CookiesConfig gatewayWins = PolicyBlock.COOKIES.resolve(ScopeChain.of(gateway, endpoint().build(),
                    route().build()));

            assertAll("cookies chain",
                    () -> assertSame(routeCookies, routeWins, "the route block replaces the broader ones whole"),
                    () -> assertSame(endpointCookies, endpointWins),
                    () -> assertSame(anchorCookies, anchorWins),
                    () -> assertSame(gatewayCookies, gatewayWins),
                    () -> assertEquals(EnumSet.allOf(Scope.class), PolicyBlock.COOKIES.declaringScopes(
                            ScopeChain.of(gateway, endpoint().anchor(anchor.name()).cookies(endpointCookies).build(),
                                    route().cookies(routeCookies).build()))));
        }

        @Test
        @DisplayName("A descriptor without any declarable scope is refused")
        void shouldRefuseADescriptorWithoutScopes() {
            String yamlKey = name();
            Map<Scope, Function<ScopeChain, Object>> noScopes = Map.of();
            Fallback<Object> fallback = new Fallback.None<>();

            assertThrows(IllegalArgumentException.class, () -> new PolicyBlock<>(yamlKey, noScopes, fallback, false));
        }
    }

    @Nested
    @DisplayName("Nearest declared scope wins")
    class NearestDeclaredScope {

        @Test
        @DisplayName("auth: route over endpoint over anchor")
        void shouldResolveAuthNearestFirst() {
            AuthConfig anchorAuth = auth(Require.BEARER);
            AuthConfig endpointAuth = auth(Require.SESSION);
            AuthConfig routeAuth = auth(Require.NONE);
            AnchorConfig anchor = anchorSeed(name()).auth(anchorAuth).build();
            GatewayConfig gateway = gatewayWith(anchor);
            EndpointConfig.EndpointConfigBuilder endpoint = endpoint().anchor(anchor.name());

            AuthConfig all = PolicyBlock.AUTH.resolve(ScopeChain.of(gateway, endpoint.auth(endpointAuth).build(),
                    route().auth(routeAuth).build()));
            AuthConfig endpointAndAnchor = PolicyBlock.AUTH.resolve(
                    ScopeChain.of(gateway, endpoint.auth(endpointAuth).build(), route().build()));
            AuthConfig anchorOnly = PolicyBlock.AUTH.resolve(
                    ScopeChain.of(gateway, endpoint.auth(null).build(), route().build()));

            assertAll("auth chain",
                    () -> assertSame(routeAuth, all),
                    () -> assertSame(endpointAuth, endpointAndAnchor),
                    () -> assertSame(anchorAuth, anchorOnly));
        }

        @Test
        @DisplayName("allowed_methods: endpoint over anchor over gateway")
        void shouldResolveAllowedMethodsNearestFirst() {
            AnchorConfig anchor = anchorSeed(name()).allowedMethods(List.of(HttpMethod.POST)).build();
            GatewayConfig gateway = validGateway().allowedMethods(List.of(HttpMethod.GET))
                    .anchors(Map.of(anchor.name(), anchor)).build();
            EndpointConfig anchored = endpoint().anchor(anchor.name()).build();

            List<HttpMethod> endpointWins = PolicyBlock.ALLOWED_METHODS.resolve(ScopeChain.of(gateway,
                    endpoint().anchor(anchor.name()).allowedMethods(List.of(HttpMethod.PUT)).build(), route().build()));
            List<HttpMethod> anchorWins = PolicyBlock.ALLOWED_METHODS.resolve(
                    ScopeChain.of(gateway, anchored, route().build()));
            List<HttpMethod> gatewayWins = PolicyBlock.ALLOWED_METHODS.resolve(
                    ScopeChain.of(gateway, endpoint().build(), route().build()));

            assertAll("allowed_methods chain",
                    () -> assertEquals(List.of(HttpMethod.PUT), endpointWins),
                    () -> assertEquals(List.of(HttpMethod.POST), anchorWins),
                    () -> assertEquals(List.of(HttpMethod.GET), gatewayWins));
        }

        @Test
        @DisplayName("allowed_methods: an empty list is not a declaration and falls through")
        void shouldTreatAnEmptyListAsAbsent() {
            AnchorConfig anchor = anchorSeed(name()).allowedMethods(List.of(HttpMethod.DELETE)).build();
            GatewayConfig gateway = gatewayWith(anchor);
            ScopeChain chain = ScopeChain.of(gateway,
                    endpoint().anchor(anchor.name()).allowedMethods(List.of()).build(), route().build());

            List<HttpMethod> resolved = PolicyBlock.ALLOWED_METHODS.resolve(chain);

            assertAll("empty endpoint list",
                    () -> assertEquals(List.of(HttpMethod.DELETE), resolved),
                    () -> assertEquals(EnumSet.of(Scope.ANCHOR), PolicyBlock.ALLOWED_METHODS.declaringScopes(chain)));
        }

        @Test
        @DisplayName("security_filter: route over endpoint over anchor, each replacing wholesale")
        void shouldResolveSecurityFilterNearestFirst() {
            SecurityFilterConfig anchorFilter = filter("strict");
            SecurityFilterConfig endpointFilter = SecurityFilterConfig.builder().profile("lenient").build();
            SecurityFilterConfig routeFilter = SecurityFilterConfig.builder().build();
            AnchorConfig anchor = anchorSeed(name()).securityFilter(anchorFilter).build();
            GatewayConfig gateway = gatewayWith(anchor);
            EndpointConfig endpoint = endpoint().anchor(anchor.name()).build();
            EndpointConfig filteringEndpoint = endpoint().anchor(anchor.name()).securityFilter(endpointFilter).build();

            SecurityFilterConfig routeWins = PolicyBlock.SECURITY_FILTER.resolve(
                    ScopeChain.of(gateway, filteringEndpoint, route().securityFilter(routeFilter).build()));
            SecurityFilterConfig endpointWins = PolicyBlock.SECURITY_FILTER.resolve(
                    ScopeChain.of(gateway, filteringEndpoint, route().build()));
            SecurityFilterConfig anchorWins = PolicyBlock.SECURITY_FILTER.resolve(
                    ScopeChain.of(gateway, endpoint, route().build()));

            assertAll("security_filter chain",
                    () -> assertSame(routeFilter, routeWins),
                    () -> assertSame(endpointFilter, endpointWins),
                    () -> assertSame(anchorFilter, anchorWins));
        }

        @Test
        @DisplayName("forward: a route block replaces the endpoint block wholesale")
        void shouldResolveForwardNearestFirst() {
            ForwardConfig endpointForward = ForwardConfig.builder().queryDeny(List.of(name())).build();
            ForwardConfig routeForward = ForwardConfig.builder().headersAllow(List.of(name())).build();
            EndpointConfig endpoint = endpoint().forward(endpointForward).build();

            ForwardConfig routeWins = PolicyBlock.FORWARD.resolve(
                    ScopeChain.of(validGateway().build(), endpoint, route().forward(routeForward).build()));
            ForwardConfig endpointWins = PolicyBlock.FORWARD.resolve(
                    ScopeChain.of(validGateway().build(), endpoint, route().build()));

            assertAll("forward chain",
                    () -> assertSame(routeForward, routeWins),
                    () -> assertNull(routeWins.queryDeny(), "the endpoint query_deny is not merged"),
                    () -> assertSame(endpointForward, endpointWins));
        }

        @Test
        @DisplayName("security_headers: anchor over gateway")
        void shouldResolveSecurityHeadersNearestFirst() {
            SecurityHeadersConfig gatewayHeaders = headers();
            SecurityHeadersConfig anchorHeaders = SecurityHeadersConfig.builder().contentTypeNosniff(true).build();
            AnchorConfig anchor = anchorSeed(name()).securityHeaders(anchorHeaders).build();
            GatewayConfig gateway = validGateway().securityHeaders(gatewayHeaders)
                    .anchors(Map.of(anchor.name(), anchor)).build();

            SecurityHeadersConfig anchorWins = PolicyBlock.SECURITY_HEADERS.resolve(
                    ScopeChain.of(gateway, endpoint().anchor(anchor.name()).build(), route().build()));
            SecurityHeadersConfig gatewayWins = PolicyBlock.SECURITY_HEADERS.resolve(
                    ScopeChain.of(gateway, endpoint().build(), route().build()));

            assertAll("security_headers chain",
                    () -> assertSame(anchorHeaders, anchorWins),
                    () -> assertSame(gatewayHeaders, gatewayWins));
        }

        @Test
        @DisplayName("upstream_defaults: endpoint over gateway")
        void shouldResolveUpstreamDefaultsNearestFirst() {
            UpstreamDefaultsConfig gatewayDefaults = upstreamDefaults(false, false);
            UpstreamDefaultsConfig endpointDefaults = UpstreamDefaultsConfig.builder().retryEnabled(true).build();
            GatewayConfig gateway = validGateway().upstreamDefaults(gatewayDefaults).build();

            UpstreamDefaultsConfig endpointWins = PolicyBlock.UPSTREAM_DEFAULTS.resolve(
                    ScopeChain.of(gateway, endpoint().upstreamDefaults(endpointDefaults).build(), route().build()));
            UpstreamDefaultsConfig gatewayWins = PolicyBlock.UPSTREAM_DEFAULTS.resolve(
                    ScopeChain.of(gateway, endpoint().build(), route().build()));

            assertAll("upstream_defaults chain",
                    () -> assertSame(endpointDefaults, endpointWins),
                    () -> assertSame(gatewayDefaults, gatewayWins));
        }

        @Test
        @DisplayName("forward and websocket: the route's declared block on an endpoint declaring none")
        void shouldResolveRouteLevelBlocksFromTheRoute() {
            ForwardConfig forward = ForwardConfig.builder().headersAllow(List.of(name())).build();
            WebSocketConfig websocket = WebSocketConfig.builder().idleTimeoutSeconds(42).build();
            ScopeChain chain = ScopeChain.of(validGateway().build(), endpoint().build(),
                    route().forward(forward).websocket(websocket).build());

            assertAll("route-only blocks",
                    () -> assertSame(forward, PolicyBlock.FORWARD.resolve(chain)),
                    () -> assertSame(websocket, PolicyBlock.WEBSOCKET.resolve(chain)));
        }
    }

    @Nested
    @DisplayName("Fallback when no scope declares the block")
    class Fallbacks {

        @Test
        @DisplayName("auth: boot error carrying the route id")
        void shouldRefuseAnUndeclaredAuth() {
            RouteConfig route = route().build();
            ScopeChain chain = ScopeChain.of(validGateway().build(), endpoint().build(), route);

            RouteTableException error = assertThrows(RouteTableException.class, () -> PolicyBlock.AUTH.resolve(chain));

            assertAll("auth boot error",
                    () -> assertEquals("route '" + route.id()
                            + "' has no effective auth (no route, endpoint, or anchor auth block)", error.getMessage()),
                    () -> assertNull(PolicyBlock.AUTH.declared(chain)));
        }

        @Test
        @DisplayName("Value fallbacks: every standard method, both upstream toggles on, a forward-all block")
        void shouldApplyTheValueFallbacks() {
            ScopeChain chain = ScopeChain.of(validGateway().build(), endpoint().build(), route().build());

            List<HttpMethod> methods = PolicyBlock.ALLOWED_METHODS.resolve(chain);
            UpstreamDefaultsConfig defaults = PolicyBlock.UPSTREAM_DEFAULTS.resolve(chain);
            ForwardConfig forward = PolicyBlock.FORWARD.resolve(chain);

            assertAll("value fallbacks",
                    () -> assertEquals(EnumSet.allOf(HttpMethod.class), EnumSet.copyOf(methods)),
                    () -> assertEquals(UpstreamDefaultsConfig.defaults(), defaults),
                    () -> assertNull(forward.headersAllow()),
                    () -> assertNull(forward.headersDeny()),
                    () -> assertNull(forward.queryAllow()),
                    () -> assertNull(forward.queryDeny()),
                    () -> assertTrue(forward.setHeaders().isEmpty()));
        }

        @Test
        @DisplayName("None fallbacks: security_filter, security_headers and websocket resolve to null")
        void shouldApplyTheNoneFallbacks() {
            ScopeChain chain = ScopeChain.of(validGateway().build(), endpoint().build(), route().build());

            assertAll("none fallbacks",
                    () -> assertNull(PolicyBlock.SECURITY_FILTER.resolve(chain)),
                    () -> assertNull(PolicyBlock.SECURITY_HEADERS.resolve(chain)),
                    () -> assertNull(PolicyBlock.WEBSOCKET.resolve(chain)));
        }

        @Test
        @DisplayName("Each fallback kind applies as declared")
        void shouldApplyEachFallbackKind() {
            String value = name();
            RouteConfig route = route().build();
            ScopeChain chain = ScopeChain.of(validGateway().build(), endpoint().build(), route);
            Fallback<String> bootError = new Fallback.BootError<>("route '%s' refused");

            RouteTableException error = assertThrows(RouteTableException.class, () -> bootError.apply(chain));

            assertAll("fallback kinds",
                    () -> assertEquals(value, new Fallback.Value<>(value).apply(chain)),
                    () -> assertNull(new Fallback.None<String>().apply(chain)),
                    () -> assertEquals("route '" + route.id() + "' refused", error.getMessage()));
        }
    }

    @Nested
    @DisplayName("Scope reading")
    class ScopeReading {

        @Test
        @DisplayName("Every descriptor reads exactly its own scopes on a chain declaring everything")
        void shouldReadOnlyDeclarableScopes() {
            AnchorConfig anchor = anchorSeed(name()).auth(auth(Require.BEARER)).securityFilter(filter("strict"))
                    .securityHeaders(headers()).allowedMethods(List.of(HttpMethod.GET)).cookies(cookies(name()))
                    .build();
            GatewayConfig gateway = validGateway().allowedMethods(List.of(HttpMethod.GET)).securityHeaders(headers())
                    .upstreamDefaults(upstreamDefaults(false, false)).cookies(cookies(name()))
                    .anchors(Map.of(anchor.name(), anchor)).build();
            EndpointConfig endpoint = endpoint().anchor(anchor.name()).auth(auth(Require.BEARER))
                    .allowedMethods(List.of(HttpMethod.GET)).upstreamDefaults(upstreamDefaults(false, false))
                    .securityFilter(filter("strict")).forward(ForwardConfig.builder().build()).cookies(cookies(name()))
                    .build();
            RouteConfig route = route().auth(auth(Require.BEARER)).securityFilter(filter("strict"))
                    .forward(ForwardConfig.builder().build()).websocket(WebSocketConfig.builder().build())
                    .cookies(cookies(name())).build();
            ScopeChain chain = ScopeChain.of(gateway, endpoint, route);

            assertAll("declaring scopes", PolicyBlock.ALL.stream().map(block -> (Executable) () -> assertEquals(
                    block.scopes(), block.declaringScopes(chain), block.yamlKey())));
        }

        @Test
        @DisplayName("An unanchored route never reads the anchor scope")
        void shouldNotReadTheAnchorScopeOfAnUnanchoredRoute() {
            AnchorConfig anchor = anchorSeed(name()).auth(auth(Require.BEARER)).build();
            ScopeChain chain = ScopeChain.of(gatewayWith(anchor), endpoint().build(), route().build());

            assertAll("unanchored route",
                    () -> assertTrue(PolicyBlock.AUTH.declaringScopes(chain).isEmpty()),
                    () -> assertNull(PolicyBlock.AUTH.declared(chain)));
        }

        @Test
        @DisplayName("The is-declared test: null and an empty collection are absent, anything else declared")
        void shouldApplyTheIsDeclaredTest() {
            assertAll("is declared",
                    () -> assertFalse(PolicyBlock.isDeclared(null)),
                    () -> assertFalse(PolicyBlock.isDeclared(List.of())),
                    () -> assertTrue(PolicyBlock.isDeclared(List.of(HttpMethod.GET))),
                    () -> assertTrue(PolicyBlock.isDeclared(filter("strict"))));
        }
    }

    @Nested
    @DisplayName("ScopeChain.of anchor resolution")
    class AnchorResolution {

        @Test
        @DisplayName("The route's anchor wins over the endpoint's")
        void shouldPreferTheRouteAnchor() {
            AnchorConfig endpointAnchor = anchorSeed(name() + "e").build();
            AnchorConfig routeAnchor = anchorSeed(name() + "r").build();
            GatewayConfig gateway = validGateway().anchors(Map.of(endpointAnchor.name(), endpointAnchor,
                    routeAnchor.name(), routeAnchor)).build();
            EndpointConfig endpoint = endpoint().anchor(endpointAnchor.name()).build();

            ScopeChain routeAnchored = ScopeChain.of(gateway, endpoint, route().anchor(routeAnchor.name()).build());
            ScopeChain endpointAnchored = ScopeChain.of(gateway, endpoint, route().build());

            assertAll("anchor preference",
                    () -> assertSame(routeAnchor, routeAnchored.anchor()),
                    () -> assertSame(endpointAnchor, endpointAnchored.anchor()));
        }

        @Test
        @DisplayName("An unknown or absent anchor name resolves to no anchor")
        void shouldResolveNoAnchorForAnUnknownOrAbsentName() {
            AnchorConfig known = anchorSeed(name()).build();
            GatewayConfig gateway = gatewayWith(known);

            ScopeChain unknown = ScopeChain.of(gateway, endpoint().anchor(known.name() + "x").build(), route().build());
            ScopeChain absent = ScopeChain.of(gateway, endpoint().build(), route().build());

            assertAll("no anchor",
                    () -> assertNull(unknown.anchor()),
                    () -> assertNull(absent.anchor()));
        }
    }
}
