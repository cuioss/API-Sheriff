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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link RouteTableBuilder}: enabled-only merge, exact-first then longest-prefix
 * ordering over normalized prefixes, the three-way terminal action (upstream / asset /
 * redirect), the conditional {@code base_url} resolution (same-prefix disjointness now lives in
 * {@code ConfigValidator}, ADR-0009), the materialization of effective
 * auth, effective {@code allowed_methods}, and the three-level retry / not-modified
 * override chain, and the D1/D2 anchor resolution (gateway → anchor → endpoint →
 * route with wholesale replacement, effective security filter/headers, the
 * per-route effective-posture INFO log, and the weakening-override WARN).
 * <p>
 * The example-based cases above pin each invariant with one concrete path; the
 * {@link PropertyBasedInvariants} nested class complements — never replaces — them by
 * re-asserting the same invariants over generated prefix / host / method
 * permutations, so a rule that happens to hold only for the chosen literal is caught.
 */
@EnableGeneratorController
@EnableTestLogger
class RouteTableBuilderTest {

    private final RouteTableBuilder builder = new RouteTableBuilder();

    // --- fixture helpers -------------------------------------------------

    private static RouteConfig routeWithPrefixAndHost(String id, String pathPrefix, String host,
            HttpMethod... methods) {
        MatchConfig match = MatchConfig.builder()
                .pathPrefix(pathPrefix)
                .methods(List.of(methods))
                .host(host)
                .build();
        return RouteConfig.builder().id(id).match(match).build();
    }

    private static RouteConfig routeWithToggles(String id, Boolean retry, Boolean notModified) {
        UpstreamConfig.UpstreamConfigBuilder upstream = UpstreamConfig.builder();
        if (retry != null) {
            upstream.retry(UpstreamConfig.Retry.builder().enabled(retry).build());
        }
        if (notModified != null) {
            upstream.notModified(UpstreamConfig.NotModified.builder().enabled(notModified).build());
        }
        return RouteConfig.builder().id(id).match(match("/" + id)).upstream(upstream.build()).build();
    }

    private static RouteConfig routeWithUpstreamPath(String id, String pathPrefix, String upstreamPath) {
        UpstreamConfig upstream = UpstreamConfig.builder().path(upstreamPath).build();
        return RouteConfig.builder().id(id).match(match(pathPrefix)).upstream(upstream).build();
    }

    private static ResolvedTopology topologyWithBasePath(String alias, String basePath) {
        return new ResolvedTopology(Map.of(alias,
                new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, basePath)));
    }

    private static AnchorConfig anchor(String name, String prefix, AuthConfig auth, SecurityFilterConfig filter,
            List<HttpMethod> methods, SecurityHeadersConfig headers) {
        return anchorSeed(name)
                .pathPrefix(prefix)
                .auth(auth)
                .securityFilter(filter)
                .securityHeaders(headers)
                .allowedMethods(methods == null ? List.of() : methods)
                .build();
    }

    /** The {@code api} anchor at {@code /api} with a bearer floor and the given {@code cookies} block. */
    private static AnchorConfig cookieAnchor(CookiesConfig cookies) {
        return anchorSeed("api").auth(auth(Require.BEARER)).cookies(cookies).build();
    }

    private static Route find(RouteTable table, String id) {
        return table.routes().stream().filter(route -> route.id().equals(id)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("Route-level upstream.path materialization")
    class UpstreamPathMaterialization {

        @Test
        @DisplayName("Should use the route upstream.path as the whole base path when the alias has no base path")
        void shouldAppendRouteUpstreamPathToEmptyAliasBase() {
            EndpointConfig endpoint = openEndpointSeed("grpc", "GRPC")
                    .routes(List.of(routeWithUpstreamPath("grpc-echo",
                            "/de.cuioss.sheriff.api.integration.grpc.Echo",
                            "/de.cuioss.sheriff.api.integration.grpc.Echo")))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("GRPC"));

            ResolvedUpstream upstream = find(table, "grpc-echo").upstream();
            assertAll("an empty alias base plus the route upstream.path keeps the gRPC service segment",
                    () -> assertEquals("/de.cuioss.sheriff.api.integration.grpc.Echo", upstream.basePath(),
                            "the bare-service route path is the whole effective base path"),
                    () -> assertEquals("grpc.internal", upstream.host(),
                            "the alias host is carried through unchanged"),
                    () -> assertEquals(443, upstream.port(), "the alias port is carried through unchanged"),
                    () -> assertEquals("https", upstream.scheme(), "the alias scheme is carried through unchanged"));
        }

        @Test
        @DisplayName("Should append the route upstream.path to a non-empty alias base path")
        void shouldAppendRouteUpstreamPathToAliasBasePath() {
            EndpointConfig endpoint = openEndpointSeed("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithUpstreamPath("httpbin-graphql", "/graphql", "/graphql")))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything/graphql", find(table, "httpbin-graphql").upstream().basePath(),
                    "the alias carries the environment base and the route carries its own path");
        }

        @ParameterizedTest(name = "alias base ''{0}'' + upstream.path ''{1}''")
        @CsvSource({
                "/anything, graphql",
                "/anything/, /graphql",
                "/anything/, graphql",
                "/anything//, //graphql",
                "/, /graphql"
        })
        @DisplayName("Should join the alias base path and upstream.path on exactly one slash")
        void shouldJoinOnExactlyOneSlash(String aliasBasePath, String upstreamPath) {
            String expected = "/".equals(aliasBasePath) ? "/graphql" : "/anything/graphql";
            EndpointConfig endpoint = openEndpointSeed("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithUpstreamPath("httpbin-graphql", "/graphql", upstreamPath)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", aliasBasePath));

            assertEquals(expected, find(table, "httpbin-graphql").upstream().basePath(),
                    "no doubled and no missing slash may survive the join");
        }

        @Test
        @DisplayName("Should keep a trailing slash the route upstream.path declares")
        void shouldKeepDeclaredTrailingSlashOfUpstreamPath() {
            EndpointConfig endpoint = openEndpointSeed("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithUpstreamPath("httpbin-upload", "/upload", "/upload/")))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything/upload/", find(table, "httpbin-upload").upstream().basePath(),
                    "the join normalizes only the seam, the dispatch strips the trailing slash later");
        }

        @Test
        @DisplayName("Should keep the alias base path when a route declares no upstream.path")
        void shouldKeepAliasBasePathWithoutRouteUpstreamPath() {
            EndpointConfig endpoint = openEndpointSeed("httpbin", "UPSTREAM")
                    .routes(List.of(prefixRoute("httpbin-proxy", "/proxy", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything", find(table, "httpbin-proxy").upstream().basePath(),
                    "a route without upstream.path keeps the alias-derived base path — the default proxy behavior");
        }

        @Test
        @DisplayName("Should keep the alias base path when the route upstream.path is blank")
        void shouldKeepAliasBasePathWhenRouteUpstreamPathBlank() {
            EndpointConfig endpoint = openEndpointSeed("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithUpstreamPath("httpbin-blank", "/proxy", "   ")))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything", find(table, "httpbin-blank").upstream().basePath(),
                    "a blank upstream.path is treated as absent, keeping the alias base path");
        }
    }

    @Nested
    @DisplayName("Route-level keep_identity_headers materialization")
    class KeepIdentityHeadersMaterialization {

        private Route buildRoute(@Nullable Boolean keepIdentityHeaders) {
            RouteConfig route = RouteConfig.builder().id("r").match(match("/r"))
                    .keepIdentityHeaders(keepIdentityHeaders).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();
            return find(builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS")), "r");
        }

        @Test
        @DisplayName("Should materialize an absent key as false, the strip default")
        void shouldMaterializeAbsentAsFalse() {
            assertFalse(buildRoute(null).keepIdentityHeaders(), "an absent key strips the identity headers");
        }

        @Test
        @DisplayName("Should materialize a declared false as false")
        void shouldMaterializeDeclaredFalse() {
            assertFalse(buildRoute(false).keepIdentityHeaders(), "a declared false strips the identity headers");
        }

        @Test
        @DisplayName("Should materialize a declared true as true")
        void shouldMaterializeDeclaredTrue() {
            assertTrue(buildRoute(true).keepIdentityHeaders(), "a declared true must reach the resolved route");
        }
    }

    @Nested
    @DisplayName("Route-level upstream.rewrite_location materialization")
    class RewriteLocationMaterialization {

        private RouteConfig routeWithRewriteLocation(String id, Boolean rewriteLocation) {
            UpstreamConfig upstream = UpstreamConfig.builder().rewriteLocation(rewriteLocation).build();
            return RouteConfig.builder().id(id).match(match("/" + id)).upstream(upstream).build();
        }

        @Test
        @DisplayName("Should build a location rewriter for a declared rewrite_location: true")
        void shouldMaterializeDeclaredTrue() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeWithRewriteLocation("r", true))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertNotNull(find(table, "r").locationRewriter(), "a declared true must reach the resolved route");
        }

        @Test
        @DisplayName("Should build the rewriter from the effective upstream base path and the match key")
        void shouldBuildRewriterFromEffectiveUpstreamAndMatchKey() {
            UpstreamConfig upstream = UpstreamConfig.builder().path("/v1").rewriteLocation(true).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(RouteConfig.builder().id("r").match(match("/api")).upstream(upstream).build()))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint),
                    topologyWithBasePath("ORDERS", "/svc"));

            assertEquals("/api/items?id=7",
                    find(table, "r").locationRewriter().rewrite("https://orders.internal/svc/v1/items?id=7"),
                    "the alias base plus upstream.path maps back onto the route's match key");
        }

        @Test
        @DisplayName("Should build no location rewriter for a declared rewrite_location: false")
        void shouldMaterializeDeclaredFalse() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeWithRewriteLocation("r", false))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertNull(find(table, "r").locationRewriter(), "a declared false must stay off");
        }

        @Test
        @DisplayName("Should resolve an absent rewrite_location key, or an absent upstream block, to off")
        void shouldResolveAbsentToOff() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeWithRewriteLocation("declared-upstream", null),
                            routeConfig("no-upstream", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertAll("rewrite_location defaults to off with no inheritance",
                    () -> assertNull(find(table, "declared-upstream").locationRewriter(),
                            "an upstream block without the key resolves to off"),
                    () -> assertNull(find(table, "no-upstream").locationRewriter(),
                            "a route without an upstream block resolves to off"));
        }

        @Test
        @DisplayName("Should keep the toggle per route rather than leaking it to a sibling route")
        void shouldKeepTogglePerRoute() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeWithRewriteLocation("on", true), routeWithRewriteLocation("off", null)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertAll("the flag is a route-local materialization",
                    () -> assertNotNull(find(table, "on").locationRewriter()),
                    () -> assertNull(find(table, "off").locationRewriter()));
        }
    }

    @Nested
    @DisplayName("Pure derivations set on the pre-boot route")
    class PureDerivations {

        @Test
        @DisplayName("Should compile the route's matcher from its match block")
        void shouldCompileMatcherFromMatchBlock() {
            RouteConfig route = routeWithPrefixAndHost("r", "/orders", "api.example.com", HttpMethod.GET);
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertAll("compiled matcher",
                    () -> assertEquals("/orders", resolved.matcher().matchKey(), "the match key is compiled"),
                    () -> assertFalse(resolved.matcher().isExact(), "a path_prefix compiles to a prefix matcher"),
                    () -> assertTrue(resolved.matcher().matches("/orders/1", HttpMethod.GET, "api.example.com",
                            Map.of()), "the compiled matcher applies path, method and host"),
                    () -> assertFalse(resolved.matcher().matches("/orders/1", HttpMethod.GET, "other.example.com",
                            Map.of()), "the compiled matcher rejects another host"));
        }

        @Test
        @DisplayName("Should derive the allowed_paths whitelist from the effective security_filter")
        void shouldDeriveAllowedPathsFromEffectiveSecurityFilter() {
            SecurityFilterConfig whitelist = SecurityFilterConfig.builder()
                    .allowedPaths(List.of("/orders/list", "/orders/item")).build();
            RouteConfig declaring = RouteConfig.builder().id("declaring").match(match("/orders", HttpMethod.GET))
                    .securityFilter(whitelist).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(declaring, routeConfig("open", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertAll("allowed_paths",
                    () -> assertEquals(List.of("/orders/list", "/orders/item"),
                            find(table, "declaring").effectiveAllowedPaths(),
                            "the whitelist of the effective security_filter is carried in declaration order"),
                    () -> assertTrue(find(table, "open").effectiveAllowedPaths().isEmpty(),
                            "a route resolving no security_filter is unrestricted"));
        }

        @Test
        @DisplayName("Should leave every boot-attached component unset")
        void shouldLeaveBootAttachedComponentsUnset() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertAll("the pre-boot pass starts nothing",
                    () -> assertNull(resolved.protocolProcessor(), "no processor before boot"),
                    () -> assertNull(resolved.securityConfiguration(), "no security configuration before boot"),
                    () -> assertNull(resolved.assetSource(), "no asset source before boot"),
                    () -> assertEquals(SecurityProfile.STRICT, resolved.securityProfile(),
                            "the fail-closed profile until the boot pass resolves it"));
        }
    }

    @Nested
    @DisplayName("Merge, ordering, and disjointness")
    class MergeOrderDisjointness {

        @Test
        @DisplayName("Should merge enabled endpoints only, skipping disabled ones")
        void shouldMergeEnabledEndpointsOnly() {
            EndpointConfig enabled = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("orders-get", HttpMethod.GET))).build();
            EndpointConfig disabled = openEndpointSeed("legacy", "LEGACY").enabled(false)
                    .routes(List.of(routeConfig("legacy-get", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(enabled, disabled), topologyWith("ORDERS"));

            assertEquals(1, table.routes().size(), "the disabled endpoint should contribute no rows");
            assertEquals("orders-get", table.routes().getFirst().id());
        }

        @Test
        @DisplayName("Should order routes by descending path_prefix length")
        void shouldOrderByLongestPrefixFirst() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(prefixRoute("short", "/a", HttpMethod.GET),
                            prefixRoute("long", "/a/b/c", HttpMethod.GET),
                            prefixRoute("mid", "/a/b", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of("long", "mid", "short"),
                    table.routes().stream().map(Route::id).toList());
        }

        @Test
        @DisplayName("Should normalize '/api' and '/api/' to the same prefix via the shared helper")
        void shouldNormalizeTrailingSlashIdentically() {
            assertEquals(RouteTableBuilder.normalizePrefix("/api"), RouteTableBuilder.normalizePrefix("/api/"),
                    "'/api' and '/api/' must normalize identically");
            assertEquals("/api", RouteTableBuilder.normalizePrefix("api/"),
                    "normalization adds a leading slash and strips the trailing one");
        }

        @Test
        @DisplayName("Should keep both same-prefix routes — same-prefix disjointness now lives in ConfigValidator")
        void shouldKeepBothSamePrefixRoutes() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(prefixRoute("first", "/x", HttpMethod.GET),
                            prefixRoute("second", "/x", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(),
                    "the builder no longer enforces disjointness — both same-prefix routes survive assembly");
        }

        @Test
        @DisplayName("Should reject an unresolved alias for an enabled endpoint")
        void shouldRejectUnresolvedAliasForEnabledEndpoint() {
            EndpointConfig endpoint = openEndpointSeed("orders", "MISSING")
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            GatewayConfig config = validGateway().build();
            ResolvedTopology topology = topologyWith("ORDERS");
            List<EndpointConfig> endpoints = List.of(endpoint);
            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, endpoints, topology));
        }

        @Test
        @DisplayName("Should resolve each route's upstream from the topology")
        void shouldResolveUpstreamTarget() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("orders.internal", find(table, "r").upstream().host());
        }
    }

    @Nested
    @DisplayName("session_fallback rides the wholesale auth cascade")
    class SessionFallbackCascade {

        private static final AuthConfig FALLBACK_AUTH = AuthConfig.builder()
                .require(Require.BEARER).sessionFallback(true).build();

        @Test
        @DisplayName("Should materialize session_fallback declared on a route-level auth block")
        void shouldMaterializeRouteLevelSessionFallback() {
            RouteConfig route = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .auth(FALLBACK_AUTH).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(find(table, "r").effectiveAuth().effectiveSessionFallback(),
                    "a route-level session_fallback must reach the resolved route");
        }

        @Test
        @DisplayName("Should materialize session_fallback declared on the endpoint default auth block")
        void shouldMaterializeEndpointLevelSessionFallback() {
            EndpointConfig endpoint = EndpointConfig.builder().id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(FALLBACK_AUTH)
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(find(table, "r").effectiveAuth().effectiveSessionFallback(),
                    "an endpoint-level session_fallback must reach a route that declares no auth block");
        }

        @Test
        @DisplayName("Should materialize session_fallback declared on the anchor auth floor")
        void shouldMaterializeAnchorLevelSessionFallback() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", FALLBACK_AUTH, null, null, null)))
                    .build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("r", "/api/orders", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(find(table, "r").effectiveAuth().effectiveSessionFallback(),
                    "an anchor-level session_fallback must reach a route when neither endpoint nor route declares auth");
        }

        @Test
        @DisplayName("Should drop session_fallback when a route-level block omits it under an anchor that declares it")
        void shouldDropSessionFallbackOnWholesaleRouteOverride() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", FALLBACK_AUTH, null, null, null)))
                    .build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET))
                    .auth(auth(Require.BEARER)).build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api").routes(List.of(route)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            AuthConfig effective = find(table, "r").effectiveAuth();
            assertAll("the route-level block replaces the anchor block wholesale",
                    () -> assertNull(effective.sessionFallback(), "the key is not inherited from the anchor"),
                    () -> assertFalse(effective.effectiveSessionFallback(),
                            "an omitted key resolves to false, not to the anchor's declared true"));
        }
    }

    @Nested
    @DisplayName("Needed scopes materialization")
    class NeededScopes {

        private GatewayConfig gatewayWithOidcScopes(List<String> scopes) {
            return validGateway().oidc(OidcConfig.builder().scopes(scopes).build()).build();
        }

        @Test
        @DisplayName("Should unite oidc.scopes with the owning endpoint's scopes")
        void shouldUniteOidcAndEndpointScopes() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").scopes(List.of("orders.read"))
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gatewayWithOidcScopes(List.of("openid", "profile")), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertEquals(Set.of("openid", "profile", "orders.read"), find(table, "r").neededScopes());
        }

        @Test
        @DisplayName("Should carry the endpoint scopes alone when no oidc block is configured")
        void shouldCarryEndpointScopesWithoutOidcBlock() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").scopes(List.of("orders.read"))
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(Set.of("orders.read"), find(table, "r").neededScopes());
        }

        @Test
        @DisplayName("Should carry oidc.scopes alone for an endpoint declaring no scopes")
        void shouldCarryOidcScopesForEndpointWithoutScopes() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gatewayWithOidcScopes(List.of("openid")), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertEquals(Set.of("openid"), find(table, "r").neededScopes());
        }

        @Test
        @DisplayName("Should materialize the same union on a require: none endpoint")
        void shouldMaterializeUnionOnUnauthenticatedEndpoint() {
            EndpointConfig endpoint = openEndpointSeed("public", "ORDERS").routes(List.of(routeConfig("r", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gatewayWithOidcScopes(List.of("openid")), List.of(endpoint),
                    topologyWith("ORDERS"));

            Route route = find(table, "r");
            assertAll(
                    () -> assertEquals(Require.NONE, route.effectiveAuth().require()),
                    () -> assertEquals(Set.of("openid"), route.neededScopes(),
                            "the derivation is uniform; only the consumers skip a require: none route"));
        }

        @Test
        @DisplayName("Should never let an endpoint remove an oidc.scopes member")
        void shouldNotLetEndpointRemoveOidcScope() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").scopes(List.of("orders.read"))
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gatewayWithOidcScopes(List.of("openid", "email")), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(find(table, "r").neededScopes().containsAll(List.of("openid", "email")),
                    "endpoint scopes are additive: every oidc.scopes member stays needed");
        }

        @Test
        @DisplayName("Should resolve an empty set when neither level declares a scope")
        void shouldResolveEmptySetWithoutScopes() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(find(table, "r").neededScopes().isEmpty());
        }
    }

    @Nested
    @DisplayName("Effective allowed_methods resolution")
    class EffectiveAllowedMethods {

        @Test
        @DisplayName("Should let the endpoint list replace the global list wholesale, even for a verb the global omits")
        void shouldLetEndpointReplaceGlobalWholesale() {
            GatewayConfig config = validGateway().allowedMethods(List.of(HttpMethod.GET)).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").allowedMethods(List.of(HttpMethod.PUT))
                    .routes(List.of(routeConfig("r", HttpMethod.PUT))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(Set.of(HttpMethod.PUT), find(table, "r").effectiveAllowedMethods());
        }

        @Test
        @DisplayName("Should fall back to the global list when the endpoint declares none")
        void shouldFallBackToGlobalList() {
            GatewayConfig config = validGateway().allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST)).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(Set.of(HttpMethod.GET, HttpMethod.POST), find(table, "r").effectiveAllowedMethods());
        }
    }

    @Nested
    @DisplayName("Anchor chain resolution (gateway → anchor → endpoint → route)")
    class AnchorResolution {

        @Test
        @DisplayName("Should materialize the anchor auth floor when endpoint and route both omit auth")
        void shouldMaterializeAnchorAuthFloor() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", auth(Require.BEARER), null, null, null)))
                    .build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("r", "/api/orders", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertEquals(Require.BEARER, resolved.effectiveAuth().require(), "the anchor auth floor should materialize");
            assertEquals("api", resolved.anchor(), "the resolving anchor name should be retained");
        }

        @Test
        @DisplayName("Should materialize the anchor allowed_methods when the endpoint declares none")
        void shouldMaterializeAnchorAllowedMethods() {
            GatewayConfig config = validGateway().anchors(Map.of("api", anchor("api", "/api",
                    auth(Require.BEARER), null, List.of(HttpMethod.GET), null))).build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("r", "/api/orders", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(Set.of(HttpMethod.GET), find(table, "r").effectiveAllowedMethods(),
                    "the anchor allowed_methods should materialize when the endpoint declares none");
        }

        @Test
        @DisplayName("Should let a route anchor override the endpoint anchor")
        void shouldLetRouteAnchorOverrideEndpointAnchor() {
            GatewayConfig config = validGateway().anchors(Map.of(
                    "api", anchor("api", "/api", auth(Require.BEARER), null, null, null),
                    "bff", anchor("bff", "/bff", auth(Require.SESSION), null, null, null))).build();
            RouteConfig routeOnBff = RouteConfig.builder().id("r").anchor("bff").match(match("/bff/home")).build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(routeOnBff)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertEquals("bff", resolved.anchor(), "the per-route anchor override should win");
            assertEquals(Require.SESSION, resolved.effectiveAuth().require());
        }

        @Test
        @DisplayName("Should throw when no route, endpoint, or anchor auth resolves")
        void shouldThrowWhenNoEffectiveAuthResolves() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", null, null, null, null))).build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("r", "/api/orders", HttpMethod.GET))).build();
            ResolvedTopology topology = topologyWith("ORDERS");
            List<EndpointConfig> endpoints = List.of(endpoint);

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, endpoints, topology));
        }

        @Test
        @DisplayName("Should materialize the anchor security_headers, else fall back to the gateway block")
        void shouldMaterializeAnchorSecurityHeadersElseGateway() {
            SecurityHeadersConfig anchorHeaders = headers();
            SecurityHeadersConfig gatewayHeaders = SecurityHeadersConfig.builder()
                    .contentTypeNosniff(true).build();
            GatewayConfig config = validGateway().securityHeaders(gatewayHeaders).anchors(Map.of("api",
                    anchor("api", "/api", auth(Require.BEARER), null, null, anchorHeaders))).build();
            EndpointConfig anchored = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("anchored", "/api/orders", HttpMethod.GET))).build();
            EndpointConfig plain = openEndpointSeed("public", "PUBLIC")
                    .routes(List.of(prefixRoute("plain", "/public", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(anchored, plain), topologyWith("ORDERS", "PUBLIC"));

            assertAll("security_headers resolves at gateway → anchor level only",
                    () -> assertEquals(anchorHeaders, find(table, "anchored").securityHeaders(),
                            "an anchored route should carry the anchor security_headers"),
                    () -> assertEquals(gatewayHeaders, find(table, "plain").securityHeaders(),
                            "an unanchored route should fall back to the gateway security_headers"));
        }

        @Test
        @DisplayName("Should carry content_security_policy through resolution verbatim, and drop the gateway one under an anchor block without it")
        void shouldCarryContentSecurityPolicyThroughResolution() {
            String gatewayPolicy = "default-src 'self'; frame-ancestors 'none'";
            String anchorPolicy = "default-src 'none'; script-src 'self'";
            GatewayConfig config = validGateway()
                    .securityHeaders(SecurityHeadersConfig.builder().contentSecurityPolicy(gatewayPolicy).build())
                    .anchors(Map.of(
                            "api", anchor("api", "/api", auth(Require.BEARER), null, null,
                            SecurityHeadersConfig.builder().contentSecurityPolicy(anchorPolicy).build()),
                            "bff", anchor("bff", "/bff", auth(Require.BEARER), null, null,
                            headers())))
                    .build();
            EndpointConfig withPolicy = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("anchor-policy", "/api/orders", HttpMethod.GET))).build();
            EndpointConfig withoutPolicy = anchoredEndpointSeed("frontend", "FRONTEND", "bff")
                    .routes(List.of(prefixRoute("anchor-no-policy", "/bff/home", HttpMethod.GET))).build();
            EndpointConfig plain = openEndpointSeed("public", "PUBLIC")
                    .routes(List.of(prefixRoute("gateway-policy", "/public", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(withPolicy, withoutPolicy, plain),
                    topologyWith("ORDERS", "FRONTEND", "PUBLIC"));

            assertAll("content_security_policy follows the wholesale gateway → anchor resolution",
                    () -> assertEquals(anchorPolicy,
                            find(table, "anchor-policy").securityHeaders().contentSecurityPolicy(),
                            "an anchor block's own policy reaches its routes verbatim"),
                    () -> assertNull(find(table, "anchor-no-policy").securityHeaders().contentSecurityPolicy(),
                            "an anchor block omitting the policy does not inherit the gateway one"),
                    () -> assertEquals(gatewayPolicy,
                            find(table, "gateway-policy").securityHeaders().contentSecurityPolicy(),
                            "an unanchored route carries the gateway policy verbatim"));
        }

        @Test
        @DisplayName("Should leave an unanchored route's anchor absent and behave exactly as before")
        void shouldLeaveUnanchoredRouteAnchorAbsent() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertNull(resolved.anchor(), "a route without any anchor ref carries no resolving anchor");
            assertNull(resolved.effectiveSecurityFilter());
        }
    }

    @Nested
    @DisplayName("Per-route effective-posture logging")
    class PostureLogging {

        @Test
        @DisplayName("Should emit a per-route effective-posture INFO line during assembly")
        void shouldEmitEffectivePostureInfo() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", auth(Require.BEARER), filter("strict"),
                            null, null)))
                    .build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(prefixRoute("orders-read", "/api/orders", HttpMethod.GET))).build();

            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "effective posture");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "orders-read");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "anchor='api'");
        }

        @Test
        @DisplayName("Should report auth.session_fallback=true on the posture line of a session_fallback route")
        void shouldReportSessionFallbackOnPostureLine() {
            RouteConfig route = RouteConfig.builder().id("orders-read").match(match("/orders", HttpMethod.GET))
                    .auth(AuthConfig.builder().require(Require.BEARER).sessionFallback(true).build()).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();

            builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            // Require renders its lowercase configuration spelling, so the line reads 'bearer'.
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    "auth.require='bearer', auth.session_fallback=true");
        }

        @Test
        @DisplayName("Should report auth.session_fallback=false on the posture line of a plain bearer route")
        void shouldReportSessionFallbackFalseOnPlainBearerRoute() {
            RouteConfig route = RouteConfig.builder().id("orders-read").match(match("/orders", HttpMethod.GET))
                    .auth(auth(Require.BEARER)).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();

            builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    "auth.require='bearer', auth.session_fallback=false");
        }

        @Test
        @DisplayName("Should log the resolved effective profile, not a placeholder, for a route omitting the knob")
        void shouldLogResolvedProfileForRouteOmittingTheKnob() {
            // Arrange — no security_filter anywhere, so the route inherits the gateway-wide profile
            GatewayConfig config = validGateway()
                    .securityDefaults(SecurityDefaultsConfig.builder().profile("lenient").build())
                    .build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(prefixRoute("orders-read", "/orders", HttpMethod.GET))).build();

            // Act
            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            // Assert — the old logPosture printed the literal "none" as a placeholder for unset, which
            // would misreport every knob-less route as the real partial-disable mode ('minimal').
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "filter='lenient'");
        }

        @Test
        @DisplayName("Should log strict for a knob-less route under an omitted security_defaults block")
        void shouldLogStrictWhenSecurityDefaultsOmitted() {
            // Arrange
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(prefixRoute("orders-read", "/orders", HttpMethod.GET))).build();

            // Act
            builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            // Assert
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "filter='strict'");
        }

        @Test
        @DisplayName("Should log a route's own declared profile ahead of the gateway-wide fallback")
        void shouldLogDeclaredRouteProfile() {
            // Arrange
            GatewayConfig config = validGateway()
                    .securityDefaults(SecurityDefaultsConfig.builder().profile("strict").build())
                    .build();
            RouteConfig declared = RouteConfig.builder().id("orders-read").match(match("/orders", HttpMethod.GET))
                    .securityFilter(filter("minimal")).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(declared)).build();

            // Act
            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            // Assert
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "filter='minimal'");
        }

        @Test
        @DisplayName("Should WARN when a route replaces an anchor-provided security_filter wholesale")
        void shouldWarnOnWeakeningSecurityFilterOverride() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", auth(Require.BEARER), filter("strict"),
                            null, null)))
                    .build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET))
                    .securityFilter(filter("lenient")).build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api").routes(List.of(route)).build();

            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "overrides anchor");
        }

        @Test
        @DisplayName("Should let a route-level security_filter replace the endpoint block wholesale")
        void shouldReplaceEndpointSecurityFilterWithRouteBlockWholesale() {
            SecurityFilterConfig endpointFilter = SecurityFilterConfig.builder().profile("lenient")
                    .maxBodyBytes(2048).build();
            SecurityFilterConfig routeFilter = SecurityFilterConfig.builder().maxHeaderCount(10).build();
            RouteConfig declaring = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .securityFilter(routeFilter).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").securityFilter(endpointFilter)
                    .routes(List.of(declaring)).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            SecurityFilterConfig resolved = find(table, "r").effectiveSecurityFilter();
            assertAll("the route block replaces the endpoint block, no field is merged",
                    () -> assertEquals(routeFilter, resolved),
                    () -> assertNull(resolved.profile(), "the endpoint profile does not survive the route block"),
                    () -> assertNull(resolved.maxBodyBytes(), "the endpoint cap does not survive the route block"));
        }

        @Test
        @DisplayName("Should let an endpoint-level security_filter replace the anchor block wholesale")
        void shouldReplaceAnchorSecurityFilterWithEndpointBlockWholesale() {
            SecurityFilterConfig anchorFilter = SecurityFilterConfig.builder().profile("paranoid")
                    .maxBodyBytes(1024).build();
            SecurityFilterConfig endpointFilter = SecurityFilterConfig.builder().maxHeaderCount(20).build();
            GatewayConfig config = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", auth(Require.BEARER), anchorFilter, null, null)))
                    .build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET)).build();
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api").securityFilter(endpointFilter)
                    .routes(List.of(route)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            SecurityFilterConfig resolved = find(table, "r").effectiveSecurityFilter();
            assertAll("the endpoint block replaces the anchor block, no field is merged",
                    () -> assertEquals(endpointFilter, resolved),
                    () -> assertNull(resolved.profile(), "the anchor profile does not survive the endpoint block"),
                    () -> assertNull(resolved.maxBodyBytes(), "the anchor cap does not survive the endpoint block"));
        }
    }

    /**
     * The override WARN is derived from the descriptors: a block whose descriptor opts in is announced
     * when the route's anchor declares it and a nearer scope declares it too, once per route and
     * block; nothing else is announced.
     */
    @Nested
    @DisplayName("Anchor-override WARN derived from the descriptor opt-in")
    class AnchorOverrideWarn {

        /** The identifier every override WARN carries; absent from the log when none was emitted. */
        private static final String OVERRIDE_WARN_ID = "ApiSheriff-101";

        private GatewayConfig gatewayWithAnchor(AuthConfig auth, @Nullable SecurityFilterConfig filter,
                @Nullable List<HttpMethod> methods) {
            return validGateway().anchors(Map.of("api", anchor("api", "/api", auth, filter, methods, null))).build();
        }

        private RouteConfig.RouteConfigBuilder apiRoute() {
            return RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET));
        }

        private void build(GatewayConfig config, EndpointConfig endpoint) {
            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));
        }

        /**
         * The complete rendered override WARN for route {@code r} of endpoint {@code orders} under
         * anchor {@code api} — the template is the contract, so the whole line is asserted, not a
         * fragment of it.
         */
        private static String overrideOf(String block, String scope) {
            return "Route 'r' of endpoint 'orders' overrides anchor 'api' " + block + " policy at " + scope
                    + " scope (wholesale replacement)";
        }

        @Test
        @DisplayName("Should WARN at endpoint scope when an endpoint allowed_methods list replaces the anchor's")
        void shouldWarnOnEndpointAllowedMethodsOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .allowedMethods(List.of(HttpMethod.GET)).routes(List.of(apiRoute().build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), null, List.of(HttpMethod.GET, HttpMethod.POST)), endpoint);

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    overrideOf("allowed_methods", "endpoint"));
        }

        @Test
        @DisplayName("Should WARN at route scope when a route security_filter replaces the anchor's")
        void shouldWarnOnRouteSecurityFilterOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(apiRoute().securityFilter(filter("lenient")).build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), filter("strict"), null), endpoint);

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    overrideOf("security_filter", "route"));
        }

        @Test
        @DisplayName("Should WARN at endpoint scope when an endpoint security_filter replaces the anchor's")
        void shouldWarnOnEndpointSecurityFilterOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api").securityFilter(filter("lenient"))
                    .routes(List.of(apiRoute().build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), filter("strict"), null), endpoint);

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    overrideOf("security_filter", "endpoint"));
        }

        @Test
        @DisplayName("Should WARN exactly once, at route scope, when both the endpoint and the route replace the anchor security_filter")
        void shouldWarnOnceWhenEndpointAndRouteBothOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api").securityFilter(filter("lenient"))
                    .routes(List.of(apiRoute().securityFilter(filter("paranoid")).build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), filter("strict"), null), endpoint);

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, OVERRIDE_WARN_ID);
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    overrideOf("security_filter", "route"));
        }

        @Test
        @DisplayName("Should not WARN when only the anchor declares the opted-in blocks")
        void shouldNotWarnWhenOnlyTheAnchorDeclares() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(apiRoute().build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), filter("strict"), List.of(HttpMethod.GET)), endpoint);

            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, OVERRIDE_WARN_ID);
        }

        @Test
        @DisplayName("Should not WARN when only a nearer scope declares the opted-in blocks")
        void shouldNotWarnWhenOnlyANearerScopeDeclares() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api").securityFilter(filter("lenient"))
                    .allowedMethods(List.of(HttpMethod.GET))
                    .routes(List.of(apiRoute().securityFilter(filter("paranoid")).build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), null, null), endpoint);

            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, OVERRIDE_WARN_ID);
        }

        @Test
        @DisplayName("Should not WARN for auth, which does not opt in, declared at the anchor and the route")
        void shouldNotWarnForAuthOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(apiRoute().auth(auth(Require.BEARER)).build())).build();

            build(gatewayWithAnchor(auth(Require.BEARER), null, null), endpoint);

            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, OVERRIDE_WARN_ID);
        }

        private GatewayConfig gatewayWithAnchorCookies(CookiesConfig cookies) {
            return validGateway().anchors(Map.of("api", cookieAnchor(cookies))).build();
        }

        @Test
        @DisplayName("Should WARN at route scope when a route cookies block replaces the anchor's, even all under none")
        void shouldWarnOnRouteCookiesOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(apiRoute().cookies(new CookiesConfig(CookieAllow.ALL, null, null)).build()))
                    .build();

            build(gatewayWithAnchorCookies(CookiesConfig.NONE), endpoint);

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, overrideOf("cookies", "route"));
        }

        @Test
        @DisplayName("Should WARN at endpoint scope when an endpoint cookies block replaces the anchor's")
        void shouldWarnOnEndpointCookiesOverride() {
            EndpointConfig endpoint = anchoredEndpointSeed("orders", "ORDERS", "api")
                    .cookies(new CookiesConfig(new CookieAllow.Named(List.of("theme")), null, null))
                    .routes(List.of(apiRoute().build())).build();

            build(gatewayWithAnchorCookies(new CookiesConfig(new CookieAllow.Named(List.of("app")), null, null)),
                    endpoint);

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, overrideOf("cookies", "endpoint"));
        }

        @Test
        @DisplayName("Should not WARN for a cookies block on an unanchored route")
        void shouldNotWarnForUnanchoredCookies() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .cookies(new CookiesConfig(CookieAllow.ALL, null, null))
                    .routes(List.of(RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                            .cookies(new CookiesConfig(new CookieAllow.Named(List.of("theme")), null, null)).build()))
                    .build();

            builder.build(validGateway().cookies(new CookiesConfig(new CookieAllow.Named(List.of("app")), null, null))
                    .build(), List.of(endpoint), topologyWith("ORDERS"));

            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, OVERRIDE_WARN_ID);
        }
    }

    /**
     * The {@code cookies} block resolves through {@link PolicyBlock#COOKIES}: route, else endpoint,
     * else anchor, else gateway, else {@code allow: none} — the nearest block whole, never merged —
     * and an asset or redirect route always resolves {@code allow: none}.
     */
    @Nested
    @DisplayName("Effective cookies resolution")
    class EffectiveCookies {

        private final CookiesConfig gatewayCookies = named("gw");
        private final CookiesConfig anchorCookies = named("anchor");
        private final CookiesConfig endpointCookies = named("endpoint");
        private final CookiesConfig routeCookies = new CookiesConfig(CookieAllow.ALL, false, null);

        private static CookiesConfig named(String cookieName) {
            return new CookiesConfig(new CookieAllow.Named(List.of(cookieName)), null,
                    List.of("https://" + cookieName + ".example.com"));
        }

        private GatewayConfig gatewayWithCookies() {
            return validGateway().cookies(gatewayCookies).anchors(Map.of("api", cookieAnchor(anchorCookies))).build();
        }

        private Route resolve(GatewayConfig gateway, EndpointConfig endpoint) {
            return find(builder.build(gateway, List.of(endpoint), topologyWith("ORDERS")), "r");
        }

        private static RouteConfig.RouteConfigBuilder apiRoute() {
            return RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET));
        }

        @Test
        @DisplayName("Should resolve each scope as the nearest declaration, route over endpoint over anchor over gateway")
        void shouldResolveNearestScope() {
            GatewayConfig gateway = gatewayWithCookies();

            Route routeWins = resolve(gateway, anchoredEndpointSeed("orders", "ORDERS", "api").cookies(endpointCookies)
                    .routes(List.of(apiRoute().cookies(routeCookies).build())).build());
            Route endpointWins = resolve(gateway, anchoredEndpointSeed("orders", "ORDERS", "api").cookies(endpointCookies)
                    .routes(List.of(apiRoute().build())).build());
            Route anchorWins = resolve(gateway, anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(apiRoute().build())).build());
            Route gatewayWins = resolve(gateway, openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build());

            assertAll("nearest declared scope wins",
                    () -> assertEquals(routeCookies, routeWins.effectiveCookies()),
                    () -> assertEquals(endpointCookies, endpointWins.effectiveCookies()),
                    () -> assertEquals(anchorCookies, anchorWins.effectiveCookies()),
                    () -> assertEquals(gatewayCookies, gatewayWins.effectiveCookies()));
        }

        @Test
        @DisplayName("Should replace a broader cookies list whole rather than extend it")
        void shouldReplaceWholeBlock() {
            CookiesConfig routeList = new CookiesConfig(new CookieAllow.Named(List.of("theme")), null, null);

            Route resolved = resolve(gatewayWithCookies(), anchoredEndpointSeed("orders", "ORDERS", "api")
                    .routes(List.of(apiRoute().cookies(routeList).build())).build());

            assertAll("the route list replaces the anchor list, no name and no key is merged",
                    () -> assertEquals(routeList, resolved.effectiveCookies()),
                    () -> assertFalse(resolved.effectiveCookies().allow().admits("anchor"),
                            "the anchor's listed cookie does not survive the route list"),
                    () -> assertNull(resolved.effectiveCookies().additionalOrigins(),
                            "the anchor's additional_origins do not survive the route block"));
        }

        @Test
        @DisplayName("Should resolve allow: none on an asset or redirect route whatever a broader scope declares")
        void shouldForceNoneOnTerminalRoutes() {
            RouteConfig redirect = RouteConfig.builder().id("r").match(match("/moved", HttpMethod.GET))
                    .redirect(RedirectConfig.builder().location("/home").status(302).build()).build();
            RouteConfig asset = RouteConfig.builder().id("a").match(match("/static", HttpMethod.GET))
                    .asset(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv/www", null, null, null)).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").cookies(new CookiesConfig(CookieAllow.ALL, null,
                    null)).routes(List.of(redirect, asset)).build();

            RouteTable table = builder.build(validGateway().cookies(gatewayCookies).build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertAll("no upstream, no cookie",
                    () -> assertEquals(CookiesConfig.NONE, find(table, "r").effectiveCookies()),
                    () -> assertEquals(CookiesConfig.NONE, find(table, "a").effectiveCookies()));
        }
    }

    /**
     * A route that forwards browser cookies gets one same-origin check at assembly, trusting the
     * request's received origin plus its block's {@code additional_origins}. A route forwarding no
     * cookie gets none, a WebSocket route gets none (its upgrade is gated by
     * {@code websocket.allowed_origins}), and a forwarding route whose block turns the check off gets
     * none and is named by a WARN.
     */
    @Nested
    @DisplayName("Cookie same-origin check assembly")
    class CookieOriginDefenceAssembly {

        /** The identifier of the check-disabled WARN; absent from the log when none was emitted. */
        private static final String CHECK_DISABLED_WARN_ID = "ApiSheriff-139";
        private static final String CHECK_DISABLED_TEXT =
                "Route 'r' forwards browser cookies with cookies.same_origin_check: false";
        private static final String ADDITIONAL_ORIGIN = "https://partner.example.com";
        private static final String FOREIGN_ORIGIN = "https://evil.example.com";

        private Route resolve(RouteConfig route) {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();
            return find(builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS")), route.id());
        }

        private static RouteConfig.RouteConfigBuilder cookieRoute(CookiesConfig cookies) {
            return RouteConfig.builder().id("r").match(match("/orders", HttpMethod.POST)).cookies(cookies);
        }

        private static PipelineRequest postFrom(String origin) {
            return PipelineRequest.builder().method(HttpMethod.POST).requestPath("/orders")
                    .headers(Map.of("Origin", List.of(origin))).build();
        }

        @Test
        @DisplayName("Should build a check for a list route, trusting the block's additional origins")
        void shouldBuildCheckForListRoute() {
            Route resolved = resolve(cookieRoute(new CookiesConfig(new CookieAllow.Named(List.of("theme")), null,
                    List.of(ADDITIONAL_ORIGIN))).build());

            CsrfDefence defence = resolved.cookieOriginDefence();
            assertNotNull(defence, "a route forwarding a listed cookie carries the check");
            PipelineRequest fromAdditionalOrigin = postFrom(ADDITIONAL_ORIGIN);
            PipelineRequest fromForeignOrigin = postFrom(FOREIGN_ORIGIN);
            assertAll("the check trusts exactly what the block declares",
                    () -> assertDoesNotThrow(() -> defence.enforce(fromAdditionalOrigin),
                            "the block's additional origin is trusted"),
                    () -> assertThrows(GatewayException.class, () -> defence.enforce(fromForeignOrigin),
                            "an origin the block does not declare is refused"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, CHECK_DISABLED_WARN_ID);
        }

        @Test
        @DisplayName("Should build a check for an all route, the check defaulting to on")
        void shouldBuildCheckForAllRoute() {
            Route resolved = resolve(cookieRoute(new CookiesConfig(CookieAllow.ALL, null, null)).build());

            CsrfDefence defence = resolved.cookieOriginDefence();
            assertNotNull(defence, "a route forwarding every cookie carries the check");
            PipelineRequest fromUndeclaredOrigin = postFrom(ADDITIONAL_ORIGIN);
            assertThrows(GatewayException.class, () -> defence.enforce(fromUndeclaredOrigin),
                    "a route declaring no additional origin trusts none");
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, CHECK_DISABLED_WARN_ID);
        }

        @Test
        @DisplayName("Should build no check for a route forwarding no cookie")
        void shouldBuildNoCheckForNoneRoute() {
            Route declared = resolve(cookieRoute(CookiesConfig.NONE).build());
            Route defaulted = resolve(routeConfig("r", HttpMethod.POST));

            assertAll("nothing forwarded, nothing to guard",
                    () -> assertNull(declared.cookieOriginDefence(), "a declared allow: none carries no check"),
                    () -> assertNull(defaulted.cookieOriginDefence(), "the default allow: none carries no check"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, CHECK_DISABLED_WARN_ID);
        }

        @Test
        @DisplayName("Should build no check for a WebSocket route, whose upgrade allowed_origins gates instead")
        void shouldBuildNoCheckForWebSocketRoute() {
            WebSocketConfig websocket = WebSocketConfig.builder().allowedOrigins(List.of(ADDITIONAL_ORIGIN)).build();
            Route checkOn = resolve(cookieRoute(new CookiesConfig(CookieAllow.ALL, null, null))
                    .match(match("/ws", HttpMethod.GET)).protocol(Protocol.WEBSOCKET).websocket(websocket).build());
            Route checkOff = resolve(cookieRoute(new CookiesConfig(CookieAllow.ALL, false, null)).id("ws-off")
                    .match(match("/ws", HttpMethod.GET)).protocol(Protocol.WEBSOCKET).websocket(websocket).build());

            assertAll("a WebSocket route carries no per-route same-origin check",
                    () -> assertNull(checkOn.cookieOriginDefence()),
                    () -> assertNull(checkOff.cookieOriginDefence()));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, CHECK_DISABLED_WARN_ID);
        }

        @Test
        @DisplayName("Should build no check and WARN with the route id when a forwarding route turns the check off")
        void shouldWarnWhenCheckIsOff() {
            Route resolved = resolve(cookieRoute(new CookiesConfig(CookieAllow.ALL, false, null)).build());

            assertNull(resolved.cookieOriginDefence(), "same_origin_check: false removes the check");
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, CHECK_DISABLED_WARN_ID);
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, CHECK_DISABLED_TEXT);
        }

        @Test
        @DisplayName("Should not WARN for an asset route under a broader block that turns the check off")
        void shouldNotWarnForTerminalRoute() {
            RouteConfig asset = RouteConfig.builder().id("a").match(match("/static", HttpMethod.GET))
                    .asset(new AssetConfig(AssetConfig.Source.DIRECTORY, "/srv/www", null, null, null)).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .cookies(new CookiesConfig(CookieAllow.ALL, false, null)).routes(List.of(asset)).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertNull(find(table, "a").cookieOriginDefence(), "an asset route forwards no cookie");
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, CHECK_DISABLED_WARN_ID);
        }
    }

    @Nested
    @DisplayName("Effective retry / not-modified resolution")
    class EffectiveUpstreamDefaults {

        @Test
        @DisplayName("Should let the endpoint upstream_defaults replace the global block wholesale")
        void shouldLetEndpointDefaultsReplaceGlobal() {
            GatewayConfig config = validGateway()
                    .upstreamDefaults(upstreamDefaults(true, true)).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .upstreamDefaults(upstreamDefaults(false, false))
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertFalse(resolved.retryEnabled(), "endpoint block should replace the global retry toggle");
            assertFalse(resolved.notModifiedEnabled(), "endpoint block should replace the global not-modified toggle");
        }

        @Test
        @DisplayName("Should let a per-route toggle override the resolved endpoint value")
        void shouldLetPerRouteToggleOverride() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .upstreamDefaults(upstreamDefaults(false, false))
                    .routes(List.of(routeWithToggles("r", true, null))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertTrue(resolved.retryEnabled(), "the per-route retry override should win");
            assertFalse(resolved.notModifiedEnabled(), "the absent per-route toggle should inherit the endpoint value");
        }

        @Test
        @DisplayName("Should inherit the resolved value when a per-route toggle is absent")
        void shouldInheritWhenPerRouteToggleAbsent() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .upstreamDefaults(upstreamDefaults(false, true))
                    .routes(List.of(routeWithToggles("r", null, null))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertFalse(resolved.retryEnabled(), "absent retry toggle should inherit the endpoint value");
            assertTrue(resolved.notModifiedEnabled(), "absent not-modified toggle should inherit the endpoint value");
        }
    }

    @Nested
    @DisplayName("Effective forward-policy resolution")
    class EffectiveForward {

        @Test
        @DisplayName("Should carry the route-level forward block onto the resolved route")
        void shouldCarryRouteForward() {
            ForwardConfig forward = ForwardConfig.builder().headersAllow(List.of("Accept"))
                    .queryAllow(List.of("page")).setHeaders(Map.of("X-Gateway", "api-sheriff")).build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .forward(forward).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertEquals(forward, resolved.effectiveForward(), "the route forward block flows through unchanged");
        }

        @Test
        @DisplayName("Should carry a route-level negative-list forward block through unchanged")
        void shouldCarryRouteDenyLists() {
            ForwardConfig forward = ForwardConfig.builder().headersDeny(List.of("X-Internal"))
                    .queryDeny(List.of("debug")).setHeaders(Map.of()).build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .forward(forward).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertAll("the deny lists ride the same wholesale carry-through as the allow lists",
                    () -> assertEquals(forward, resolved.effectiveForward()),
                    () -> assertEquals(List.of("X-Internal"), resolved.effectiveForward().headersDeny()),
                    () -> assertEquals(List.of("debug"), resolved.effectiveForward().queryDeny()));
        }

        @Test
        @DisplayName("Should keep a declared-empty positive-list distinguishable from an absent one")
        void shouldKeepDeclaredEmptyDistinctFromAbsent() {
            ForwardConfig declaredEmpty = ForwardConfig.builder().headersAllow(List.of())
                    .queryAllow(List.of()).setHeaders(Map.of()).build();
            RouteConfig declaring = RouteConfig.builder().id("declared").match(match("/declared", HttpMethod.GET))
                    .forward(declaredEmpty).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(declaring, routeConfig("absent", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertAll("the two routes resolve to different postures, not to the same empty one",
                    () -> assertTrue(find(table, "declared").effectiveForward().headersAllow().isEmpty(),
                            "a declared-empty positive-list stays present and empty"),
                    () -> assertNull(find(table, "absent").effectiveForward().headersAllow(),
                            "an absent list stays absent"),
                    () -> assertNotEquals(find(table, "declared").effectiveForward(),
                            find(table, "absent").effectiveForward()));
        }

        @Test
        @DisplayName("Should let an endpoint-level forward block govern a route declaring none")
        void shouldApplyEndpointForwardToRouteDeclaringNone() {
            ForwardConfig endpointForward = ForwardConfig.builder().queryDeny(List.of("debug"))
                    .headersDeny(List.of("X-Internal")).build();
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").forward(endpointForward)
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(endpointForward, find(table, "r").effectiveForward(),
                    "the endpoint block is the nearest declaration for a route declaring none");
        }
    }

    @Nested
    @DisplayName("Asset terminal-action materialization (ADR-0014)")
    class AssetTerminalAction {

        private AnchorConfig assetAnchor(String name, String prefix, AccessLevel access, Require require) {
            return matrixAnchor(name, prefix, AnchorType.ASSET, access, require);
        }

        @Test
        @DisplayName("Should materialize a directory asset action and leave the proxy upstream empty")
        void shouldMaterializeDirectoryAsset() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("assets", assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                    .directory("/srv/assets").build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor("assets").auth(auth(Require.NONE))
                    .routes(List.of(assetRoute("bundle", "/assets", "assets", asset))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB"));

            Route resolved = find(table, "bundle");
            assertNull(resolved.upstream(), "an asset route carries no proxy upstream");
            ResolvedAsset resolvedAsset = resolved.asset();
            assertAll("directory asset materialization",
                    () -> assertEquals(AssetConfig.Source.DIRECTORY, resolvedAsset.source()),
                    () -> assertEquals("/srv/assets", resolvedAsset.directory()),
                    () -> assertNull(resolvedAsset.upstream(), "a directory action carries no upstream"),
                    () -> assertEquals(AccessLevel.PUBLIC, resolvedAsset.access(),
                            "the access level is inherited from the resolving anchor"));
        }

        @Test
        @DisplayName("Should carry a directory asset's index and fallback onto the resolved action (AS-12)")
        void shouldMaterializeDirectoryAssetIndexAndFallback() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("assets", assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig spa = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                    .directory("/srv/spa").index("index.html").fallback("shell.html").build();
            AssetConfig plain = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                    .directory("/srv/plain").build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor("assets").auth(auth(Require.NONE))
                    .routes(List.of(assetRoute("spa", "/assets/spa", "assets", spa),
                            assetRoute("plain", "/assets/plain", "assets", plain)))
                    .build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB"));

            ResolvedAsset spaAsset = find(table, "spa").asset();
            ResolvedAsset plainAsset = find(table, "plain").asset();
            assertAll("index and fallback flow from the asset block to the resolved action",
                    () -> assertEquals("index.html", spaAsset.index(), "the declared index is carried"),
                    () -> assertEquals("shell.html", spaAsset.fallback(), "the declared fallback is carried"),
                    () -> assertNull(plainAsset.index(), "an undeclared index stays absent"),
                    () -> assertNull(plainAsset.fallback(), "an undeclared fallback stays absent"));
        }

        @Test
        @DisplayName("Should materialize an upstream asset action resolving its alias through the topology")
        void shouldMaterializeUpstreamAsset() {
            GatewayConfig config = validGateway().anchors(Map.of("assets",
                    assetAnchor("assets", "/assets", AccessLevel.AUTHENTICATED, Require.BEARER))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                    .upstream("SECONDARY").build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor("assets").auth(null)
                    .routes(List.of(assetRoute("cdn", "/assets", "assets", asset))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB", "SECONDARY"));

            Route resolved = find(table, "cdn");
            ResolvedAsset resolvedAsset = resolved.asset();
            assertAll("upstream asset materialization",
                    () -> assertEquals(AssetConfig.Source.UPSTREAM, resolvedAsset.source()),
                    () -> assertEquals("secondary.internal", resolvedAsset.upstream().host(),
                            "the upstream alias resolves through the same topology the proxy action uses"),
                    () -> assertNull(resolvedAsset.directory(), "an upstream action carries no directory"),
                    () -> assertEquals(AccessLevel.AUTHENTICATED, resolvedAsset.access()),
                    () -> assertNull(resolved.upstream(), "an asset route carries no proxy upstream"));
        }

        @Test
        @DisplayName("Should force AUTHENTICATED access when a route auth override strengthens a public-access anchor")
        void shouldTreatRouteAuthOverrideAsAuthenticatedUnderPublicAccessAnchor() {
            GatewayConfig config = validGateway()
                    .anchors(Map.of("assets", assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                    .directory("/srv/assets").build();
            RouteConfig route = RouteConfig.builder().id("bundle").anchor("assets")
                    .match(match("/assets", HttpMethod.GET))
                    .auth(auth(Require.BEARER))
                    .asset(asset).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor("assets").auth(null).routes(List.of(route)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB"));

            Route resolved = find(table, "bundle");
            assertEquals(Require.BEARER, resolved.effectiveAuth().require(),
                    "the route-level override should strengthen the public anchor's absent auth floor");
            assertEquals(AccessLevel.AUTHENTICATED, resolved.asset().access(),
                    "a route whose effective auth requires bearer must be governed AUTHENTICATED "
                            + "for caching purposes even though its anchor stays access: public");
        }

        @Test
        @DisplayName("Should reject an upstream asset action whose alias does not resolve")
        void shouldRejectUnresolvableUpstreamAssetAlias() {
            GatewayConfig config = validGateway().anchors(Map.of("assets",
                    assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                    .upstream("MISSING").build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor("assets").auth(auth(Require.NONE))
                    .routes(List.of(assetRoute("cdn", "/assets", "assets", asset))).build();
            ResolvedTopology topology = topologyWith("WEB");
            List<EndpointConfig> endpoints = List.of(endpoint);

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, endpoints, topology));
        }

        @Test
        @DisplayName("Should keep a proxy route's upstream present and its asset action empty (exclusivity)")
        void shouldKeepProxyRouteExclusive() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            Route resolved = find(table, "r");
            assertNotNull(resolved.upstream(), "a proxy route resolves an upstream terminal action");
            assertNull(resolved.asset(), "a proxy route carries no asset terminal action");
        }

        @Test
        @DisplayName("Should resolve the null (no-asset) path to the upstream action without throwing (S3655 guard)")
        void shouldResolveNoAssetPathWithoutThrowing() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(routeConfig("proxy-only", HttpMethod.GET))).build();

            RouteTable table = assertDoesNotThrow(
                    () -> builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS")),
                    "the null asset branch must resolve the upstream action without throwing");

            Route resolved = find(table, "proxy-only");
            assertAll("null asset branch drives the upstream terminal action",
                    () -> assertNull(resolved.asset(), "the no-asset path leaves the asset action absent"),
                    () -> assertNotNull(resolved.upstream(),
                            "the guarded absent-asset branch resolves the upstream terminal action"),
                    () -> assertEquals("orders.internal", resolved.upstream().host(),
                            "the resolved upstream host is driven from the guarded absent-asset branch"));
        }
    }

    @Nested
    @DisplayName("Exact routes, redirect terminal action and conditional base_url (AS-3/AS-4)")
    class ExactRoutesRedirectAndConditionalBaseUrl {

        private RouteConfig exactRoute(String id, String path) {
            return RouteConfig.builder().id(id)
                    .match(MatchConfig.builder().path(path).methods(List.of(HttpMethod.GET)).build())
                    .build();
        }

        private RouteConfig redirectRoute(String id, String path, RedirectConfig redirect) {
            return RouteConfig.builder().id(id)
                    .match(MatchConfig.builder().path(path).build())
                    .redirect(redirect)
                    .build();
        }

        private RouteConfig directoryAssetRoute(String id, String prefix) {
            return RouteConfig.builder().id(id)
                    .match(match(prefix, HttpMethod.GET))
                    .asset(AssetConfig.builder().source(AssetConfig.Source.DIRECTORY).directory("/srv/site").build())
                    .build();
        }

        @Test
        @DisplayName("Should materialize a redirect route as the third terminal action with no upstream or asset")
        void shouldMaterializeRedirectAction() {
            RedirectConfig redirect = new RedirectConfig("/new-home", 308, true, false);
            EndpointConfig endpoint = openEndpointSeed("site", null)
                    .routes(List.of(redirectRoute("moved", "/old-home", redirect))).build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith());

            Route resolved = find(table, "moved");
            assertAll("the redirect arm of the three-way terminal action",
                    () -> assertEquals(redirect, resolved.redirect(), "the redirect block is carried verbatim"),
                    () -> assertNull(resolved.upstream(), "a redirect route resolves no proxy upstream"),
                    () -> assertNull(resolved.asset(), "a redirect route resolves no asset action"));
        }

        @Test
        @DisplayName("Should order every exact route before every prefix route, each group longest-first")
        void shouldOrderExactRoutesBeforePrefixRoutes() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(prefixRoute("prefix-short", "/a", HttpMethod.GET),
                            exactRoute("exact-short", "/a"),
                            prefixRoute("prefix-long", "/a/b/c", HttpMethod.GET),
                            exactRoute("exact-long", "/a/b/longer")))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of("exact-long", "exact-short", "prefix-long", "prefix-short"),
                    table.routes().stream().map(Route::id).toList(),
                    "exact routes precede prefix routes regardless of length; each group orders longest-first");
        }

        @Test
        @DisplayName("Should order exact routes of equal length lexically for a deterministic table")
        void shouldOrderEqualLengthExactRoutesLexically() {
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(exactRoute("second", "/bb"), exactRoute("first", "/aa")))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of("first", "second"), table.routes().stream().map(Route::id).toList());
        }

        @Test
        @DisplayName("Should build an endpoint without base_url that carries only asset and redirect routes")
        void shouldBuildEndpointWithoutBaseUrlAndWithoutProxyRoutes() {
            EndpointConfig endpoint = openEndpointSeed("site", null)
                    .routes(List.of(directoryAssetRoute("site-assets", "/static"),
                            redirectRoute("site-root", "/", new RedirectConfig("/static/", 302, false, false))))
                    .build();

            RouteTable table = assertDoesNotThrow(
                    () -> builder.build(validGateway().build(), List.of(endpoint), topologyWith()),
                    "an endpoint without a proxy route needs no base_url");

            assertEquals(2, table.routes().size());
        }

        @Test
        @DisplayName("Should not resolve a declared alias of an endpoint that carries no proxy route")
        void shouldNotResolveDeclaredAliasWithoutProxyRoutes() {
            EndpointConfig endpoint = openEndpointSeed("site", "UNRESOLVED")
                    .routes(List.of(redirectRoute("site-root", "/", new RedirectConfig("/home", 307, false, false))))
                    .build();

            RouteTable table = assertDoesNotThrow(
                    () -> builder.build(validGateway().build(), List.of(endpoint), topologyWith()),
                    "the builder resolves base_url only for an endpoint with a proxy route; the validator owns the"
                            + " declared-alias resolvability rule");

            assertNull(find(table, "site-root").upstream());
        }

        @Test
        @DisplayName("Should refuse an endpoint that carries a proxy route but declares no base_url")
        void shouldRefuseProxyRouteEndpointWithoutBaseUrl() {
            EndpointConfig endpoint = openEndpointSeed("orders", null)
                    .routes(List.of(routeConfig("orders-read", HttpMethod.GET),
                            redirectRoute("orders-moved", "/old", new RedirectConfig("/orders-read", 301, false, false))))
                    .build();
            GatewayConfig config = validGateway().build();
            List<EndpointConfig> endpoints = List.of(endpoint);
            ResolvedTopology topology = topologyWith("ORDERS");

            RouteTableBuilder.RouteTableException exception = assertThrows(
                    RouteTableBuilder.RouteTableException.class, () -> builder.build(config, endpoints, topology));

            assertEquals("enabled endpoint 'orders' declares proxy route(s) but no base_url", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Invariants over generated prefix / host / method permutations")
    class PropertyBasedInvariants {

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should order any generated prefix family longest-first, regardless of declaration order")
        void shouldOrderByLongestPrefixFirstForAnyGeneratedSegment(String segment) {
            String shortPrefix = "/" + segment;
            String midPrefix = shortPrefix + "/mid";
            String longPrefix = midPrefix + "/leaf";
            EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS")
                    .routes(List.of(prefixRoute("short", shortPrefix, HttpMethod.GET),
                            prefixRoute("long", longPrefix, HttpMethod.GET),
                            prefixRoute("mid", midPrefix, HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of("long", "mid", "short"),
                    table.routes().stream().map(Route::id).toList(),
                    () -> "routes must be ordered by descending prefix length for segment: " + segment);
        }
    }

    @Nested
    @DisplayName("The shared effectiveAccessLevel seam")
    class EffectiveAccessLevelSeam {

        private static AnchorConfig accessAnchor(AccessLevel access) {
            return AnchorConfig.builder()
                    .name("surface")
                    .pathPrefix("/surface")
                    .type(AnchorType.PROXY)
                    .access(access)
                    .build();
        }

        @ParameterizedTest(name = "require ''{0}'' resolves to AUTHENTICATED even under a public anchor")
        @EnumSource(value = Require.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
        @DisplayName("Should treat any non-'none' effective auth as authenticated regardless of anchor access")
        void shouldTreatNonNoneRequireAsAuthenticated(Require require) {
            // The strengthened-floor case: the anchor stays access: public, but the route's own floor
            // must still govern the surface as authenticated (ADR-0007 permits strengthening).
            AccessLevel access = RouteTableBuilder.effectiveAccessLevel(
                    accessAnchor(AccessLevel.PUBLIC), auth(require));

            assertEquals(AccessLevel.AUTHENTICATED, access);
        }

        @ParameterizedTest(name = "an effectively-unauthenticated route inherits the anchor access {0}")
        @EnumSource(AccessLevel.class)
        @DisplayName("Should fall back to the anchor's declared access for an effectively-unauthenticated route")
        void shouldFallBackToAnchorAccessWhenUnauthenticated(AccessLevel declared) {
            AccessLevel access = RouteTableBuilder.effectiveAccessLevel(
                    accessAnchor(declared), auth(Require.NONE));

            assertEquals(declared, access);
        }

        @Test
        @DisplayName("Should default to PUBLIC for an unanchored, effectively-unauthenticated route")
        void shouldDefaultToPublicWhenUnanchoredAndUnauthenticated() {
            AccessLevel access = RouteTableBuilder.effectiveAccessLevel(
                    null, auth(Require.NONE));

            assertEquals(AccessLevel.PUBLIC, access);
        }
    }

    @Nested
    @DisplayName("The shared globalProfile seam")
    class GlobalProfileSeam {

        @Test
        @DisplayName("Should resolve a declared security_defaults profile")
        void shouldResolveDeclaredGlobalProfile() {
            GatewayConfig config = validGateway()
                    .securityDefaults(SecurityDefaultsConfig.builder().profile("lenient").build())
                    .build();

            assertEquals(SecurityProfile.LENIENT, RouteTableBuilder.globalProfile(config));
        }

        @Test
        @DisplayName("Should resolve an omitted security_defaults block to the fail-closed default")
        void shouldResolveOmittedBlockToDefault() {
            assertEquals(SecurityProfile.DEFAULT_PROFILE, RouteTableBuilder.globalProfile(validGateway().build()));
        }

        @Test
        @DisplayName("Should resolve a security_defaults block without a profile to the fail-closed default")
        void shouldResolveBlockWithoutProfileToDefault() {
            GatewayConfig config = validGateway()
                    .securityDefaults(SecurityDefaultsConfig.builder().build())
                    .build();

            assertEquals(SecurityProfile.DEFAULT_PROFILE, RouteTableBuilder.globalProfile(config));
        }

        @Test
        @DisplayName("Should resolve the retired 'none' mode value to the fail-closed default")
        void shouldResolveRetiredModeValueToDefault() {
            // Arrange — 'none' is the mode value this release renamed to 'minimal'. The schema
            // refuses it at boot, so this seam is not the primary guard; it is the fail-closed
            // backstop, and proving it lands on STRICT rather than throwing or failing OPEN is what
            // makes an un-migrated descriptor reaching the model layer safe.
            GatewayConfig config = validGateway()
                    .securityDefaults(SecurityDefaultsConfig.builder().profile("none").build())
                    .build();

            // Act
            SecurityProfile resolved = RouteTableBuilder.globalProfile(config);

            // Assert
            assertEquals(SecurityProfile.DEFAULT_PROFILE, resolved,
                    "an unrecognised profile value must fall back to the fail-closed default, never"
                            + " to a looser mode and never by throwing");
            assertEquals(SecurityProfile.STRICT, resolved,
                    "the fail-closed default is STRICT — the retired 'none' value must not resolve to"
                            + " its 'minimal' successor by accident");
        }
    }
}
