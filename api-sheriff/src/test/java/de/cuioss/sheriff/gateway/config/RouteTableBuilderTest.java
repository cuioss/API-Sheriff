/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Tests for {@link RouteTableBuilder}: enabled-only merge, longest-prefix
 * ordering over normalized prefixes (same-prefix disjointness now lives in
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

    private static GatewayConfig.GatewayConfigBuilder gateway() {
        return GatewayConfig.builder().version(1);
    }

    private static ResolvedTopology topologyWith(String... aliases) {
        Map<String, ResolvedUpstream> map = new HashMap<>();
        for (String alias : aliases) {
            map.put(alias, new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, ""));
        }
        return new ResolvedTopology(map);
    }

    private static MatchConfig match(String pathPrefix, HttpMethod... methods) {
        return MatchConfig.builder().pathPrefix(pathPrefix).methods(List.of(methods)).build();
    }

    private static RouteConfig route(String id, HttpMethod... methods) {
        return RouteConfig.builder().id(id).match(match("/" + id, methods)).build();
    }

    private static RouteConfig routeWithPrefix(String id, String pathPrefix, HttpMethod... methods) {
        return RouteConfig.builder().id(id).match(match(pathPrefix, methods)).build();
    }

    private static RouteConfig routeWithPrefixAndHost(String id, String pathPrefix, String host,
            HttpMethod... methods) {
        MatchConfig match = MatchConfig.builder()
                .pathPrefix(pathPrefix)
                .methods(List.of(methods))
                .host(Optional.of(host))
                .build();
        return RouteConfig.builder().id(id).match(match).build();
    }

    private static RouteConfig routeWithHeader(String id, String pathPrefix, HeaderMatcher header) {
        MatchConfig match = MatchConfig.builder().pathPrefix(pathPrefix).headers(List.of(header)).build();
        return RouteConfig.builder().id(id).match(match).build();
    }

    private static RouteConfig routeWithToggles(String id, Boolean retry, Boolean notModified) {
        UpstreamConfig.UpstreamConfigBuilder upstream = UpstreamConfig.builder();
        if (retry != null) {
            upstream.retry(Optional.of(UpstreamConfig.Retry.builder().enabled(Optional.of(retry)).build()));
        }
        if (notModified != null) {
            upstream.notModified(Optional.of(new UpstreamConfig.NotModified(Optional.of(notModified))));
        }
        return RouteConfig.builder().id(id).match(match("/" + id)).upstream(Optional.of(upstream.build())).build();
    }

    private static RouteConfig routeWithUpstreamPath(String id, String pathPrefix, String upstreamPath) {
        UpstreamConfig upstream = UpstreamConfig.builder().path(Optional.of(upstreamPath)).build();
        return RouteConfig.builder().id(id).match(match(pathPrefix)).upstream(Optional.of(upstream)).build();
    }

    private static ResolvedTopology topologyWithBasePath(String alias, String basePath) {
        return new ResolvedTopology(Map.of(alias,
                new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, basePath)));
    }

    private static EndpointConfig.EndpointConfigBuilder endpoint(String id, String alias) {
        return EndpointConfig.builder().id(id).enabled(true).baseUrl(alias)
                .auth(Optional.of(new AuthConfig("none", List.of())));
    }

    private static EndpointConfig.EndpointConfigBuilder anchoredEndpoint(String id, String alias, String anchorName) {
        return EndpointConfig.builder().id(id).enabled(true).baseUrl(alias).anchor(Optional.of(anchorName))
                .auth(Optional.empty());
    }

    private static AnchorConfig anchor(String name, String prefix, AuthConfig auth, SecurityFilterConfig filter,
            List<HttpMethod> methods, SecurityHeadersConfig headers) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(AnchorType.PROXY)
                .access(AccessLevel.AUTHENTICATED)
                .auth(Optional.ofNullable(auth))
                .securityFilter(Optional.ofNullable(filter))
                .securityHeaders(Optional.ofNullable(headers))
                .allowedMethods(methods == null ? List.of() : methods)
                .build();
    }

    private static SecurityFilterConfig filter(String profile) {
        return SecurityFilterConfig.builder().profile(Optional.of(profile)).build();
    }

    private static SecurityHeadersConfig headers() {
        return SecurityHeadersConfig.builder().frameDeny(Optional.of(true)).build();
    }

    private static ResolvedRoute find(RouteTable table, String id) {
        return table.routes().stream().filter(route -> route.id().equals(id)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("Route-level upstream.path materialization")
    class UpstreamPathMaterialization {

        @Test
        @DisplayName("Should materialize a non-blank route upstream.path as the effective base path")
        void shouldMaterializeRouteUpstreamPath() {
            EndpointConfig endpoint = endpoint("grpc", "GRPC")
                    .routes(List.of(routeWithUpstreamPath("grpc-echo",
                            "/de.cuioss.sheriff.api.integration.grpc.Echo",
                            "/de.cuioss.sheriff.api.integration.grpc.Echo")))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("GRPC"));

            ResolvedUpstream upstream = find(table, "grpc-echo").upstream().orElseThrow();
            assertAll("the route upstream.path becomes the effective base path so the service segment survives",
                    () -> assertEquals("/de.cuioss.sheriff.api.integration.grpc.Echo", upstream.basePath(),
                            "the bare-service route path is materialized as the upstream base path"),
                    () -> assertEquals("grpc.internal", upstream.host(),
                            "the alias host is carried through unchanged"),
                    () -> assertEquals(443, upstream.port(), "the alias port is carried through unchanged"));
        }

        @Test
        @DisplayName("Should replace a non-empty alias base path with the route upstream.path (not append)")
        void shouldReplaceAliasBasePathWithRouteUpstreamPath() {
            EndpointConfig endpoint = endpoint("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithUpstreamPath("httpbin-graphql", "/graphql", "/anything/graphql")))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything/graphql", find(table, "httpbin-graphql").upstream().orElseThrow().basePath(),
                    "the route upstream.path replaces the alias base path wholesale — it must not be doubled to "
                            + "/anything/anything/graphql");
        }

        @Test
        @DisplayName("Should keep the alias base path when a route declares no upstream.path")
        void shouldKeepAliasBasePathWithoutRouteUpstreamPath() {
            EndpointConfig endpoint = endpoint("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithPrefix("httpbin-proxy", "/proxy", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything", find(table, "httpbin-proxy").upstream().orElseThrow().basePath(),
                    "a route without upstream.path keeps the alias-derived base path — the default proxy behavior");
        }

        @Test
        @DisplayName("Should keep the alias base path when the route upstream.path is blank")
        void shouldKeepAliasBasePathWhenRouteUpstreamPathBlank() {
            EndpointConfig endpoint = endpoint("httpbin", "UPSTREAM")
                    .routes(List.of(routeWithUpstreamPath("httpbin-blank", "/proxy", "   ")))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint),
                    topologyWithBasePath("UPSTREAM", "/anything"));

            assertEquals("/anything", find(table, "httpbin-blank").upstream().orElseThrow().basePath(),
                    "a blank upstream.path is treated as absent, keeping the alias base path");
        }
    }

    @Nested
    @DisplayName("Merge, ordering, and disjointness")
    class MergeOrderDisjointness {

        @Test
        @DisplayName("Should merge enabled endpoints only, skipping disabled ones")
        void shouldMergeEnabledEndpointsOnly() {
            EndpointConfig enabled = endpoint("orders", "ORDERS")
                    .routes(List.of(route("orders-get", HttpMethod.GET))).build();
            EndpointConfig disabled = endpoint("legacy", "LEGACY").enabled(false)
                    .routes(List.of(route("legacy-get", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(enabled, disabled), topologyWith("ORDERS"));

            assertEquals(1, table.routes().size(), "the disabled endpoint should contribute no rows");
            assertEquals("orders-get", table.routes().getFirst().id());
        }

        @Test
        @DisplayName("Should order routes by descending path_prefix length")
        void shouldOrderByLongestPrefixFirst() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("short", "/a", HttpMethod.GET),
                            routeWithPrefix("long", "/a/b/c", HttpMethod.GET),
                            routeWithPrefix("mid", "/a/b", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of("long", "mid", "short"),
                    table.routes().stream().map(ResolvedRoute::id).toList());
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
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("first", "/x", HttpMethod.GET),
                            routeWithPrefix("second", "/x", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(),
                    "the builder no longer enforces disjointness — both same-prefix routes survive assembly");
        }

        @Test
        @DisplayName("Should accept two same-prefix routes disjoint by method")
        void shouldAcceptSamePrefixRoutesDisjointByMethod() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("reader", "/x", HttpMethod.GET),
                            routeWithPrefix("writer", "/x", HttpMethod.POST)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(), "method-disjoint same-prefix routes should both survive");
        }

        @Test
        @DisplayName("Should reject an unresolved alias for an enabled endpoint")
        void shouldRejectUnresolvedAliasForEnabledEndpoint() {
            EndpointConfig endpoint = endpoint("orders", "MISSING")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            GatewayConfig config = gateway().build();
            ResolvedTopology topology = topologyWith("ORDERS");
            List<EndpointConfig> endpoints = List.of(endpoint);
            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, endpoints, topology));
        }

        @Test
        @DisplayName("Should resolve each route's upstream from the topology")
        void shouldResolveUpstreamTarget() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("orders.internal", find(table, "r").upstream().orElseThrow().host());
        }

        @Test
        @DisplayName("Should look up the most specific prefix match")
        void shouldLookUpMostSpecificPrefix() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("broad", "/a", HttpMethod.GET),
                            routeWithPrefix("narrow", "/a/b", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("narrow", table.lookup("/a/b/item").orElseThrow().id());
            assertEquals("broad", table.lookup("/a/other").orElseThrow().id());
            assertTrue(table.lookup("/unmatched").isEmpty());
        }

        @Test
        @DisplayName("Should not match a prefix that only shares a leading substring with the path")
        void shouldMatchOnSegmentBoundaryOnly() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("proxy", "/proxy", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(table.lookup("/proxy-helper").isEmpty(),
                    "/proxy-helper must not match the prefix /proxy across a segment boundary");
            assertEquals("proxy", table.lookup("/proxy").orElseThrow().id(),
                    "an exact prefix match must resolve the route");
            assertEquals("proxy", table.lookup("/proxy/items").orElseThrow().id(),
                    "a child path must match the prefix on the segment boundary");
        }

        @Test
        @DisplayName("Should treat a prefix already ending in a slash as a segment boundary")
        void shouldMatchTrailingSlashPrefix() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("api", "/api/", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("api", table.lookup("/api/orders").orElseThrow().id());
            assertTrue(table.lookup("/api-internal").isEmpty(),
                    "/api-internal must not match the prefix /api/");
        }

        @Test
        @DisplayName("Should accept same-prefix routes made disjoint by mutually exclusive header presence")
        void shouldAcceptSamePrefixRoutesDisjointByHeaderPresence() {
            RouteConfig present = routeWithHeader("present", "/x",
                    HeaderMatcher.builder().name("X-Debug").present(Optional.of(true)).build());
            RouteConfig absent = routeWithHeader("absent", "/x",
                    HeaderMatcher.builder().name("X-Debug").present(Optional.of(false)).build());
            EndpointConfig endpoint = endpoint("orders", "ORDERS").routes(List.of(present, absent)).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(),
                    "presence-disjoint same-prefix routes should both survive");
        }

    }

    @Nested
    @DisplayName("Effective auth materialization")
    class EffectiveAuth {

        @Test
        @DisplayName("Should apply a route-level auth override wholesale")
        void shouldApplyRouteAuthOverride() {
            RouteConfig secured = RouteConfig.builder().id("secured").match(match("/secured", HttpMethod.GET))
                    .auth(Optional.of(new AuthConfig("bearer", List.of("read")))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS").routes(List.of(secured)).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("bearer", find(table, "secured").effectiveAuth().require());
        }

        @Test
        @DisplayName("Should inherit the endpoint default auth when the route omits it")
        void shouldInheritEndpointAuth() {
            EndpointConfig endpoint = EndpointConfig.builder().id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(Optional.of(new AuthConfig("session", List.of())))
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("session", find(table, "r").effectiveAuth().require());
        }
    }

    @Nested
    @DisplayName("Effective allowed_methods resolution")
    class EffectiveAllowedMethods {

        @Test
        @DisplayName("Should let the endpoint list replace the global list wholesale, even for a verb the global omits")
        void shouldLetEndpointReplaceGlobalWholesale() {
            GatewayConfig config = gateway().allowedMethods(List.of(HttpMethod.GET)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS").allowedMethods(List.of(HttpMethod.PUT))
                    .routes(List.of(route("r", HttpMethod.PUT))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of(HttpMethod.PUT), find(table, "r").effectiveAllowedMethods());
        }

        @Test
        @DisplayName("Should fall back to the global list when the endpoint declares none")
        void shouldFallBackToGlobalList() {
            GatewayConfig config = gateway().allowedMethods(List.of(HttpMethod.GET, HttpMethod.POST)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), find(table, "r").effectiveAllowedMethods());
        }

        @Test
        @DisplayName("Should fall back to the standard set when neither level declares a list")
        void shouldFallBackToStandardSet() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            List<HttpMethod> effective = find(table, "r").effectiveAllowedMethods();
            assertEquals(EnumSet.allOf(HttpMethod.class).size(), effective.size());
            assertTrue(effective.containsAll(EnumSet.allOf(HttpMethod.class)),
                    "the standard set should contain every representable verb");
        }
    }

    @Nested
    @DisplayName("Anchor chain resolution (gateway → anchor → endpoint → route)")
    class AnchorResolution {

        @Test
        @DisplayName("Should materialize the anchor auth floor when endpoint and route both omit auth")
        void shouldMaterializeAnchorAuthFloor() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("api", anchor("api", "/api", new AuthConfig("bearer", List.of()), null, null, null)))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api")
                    .routes(List.of(routeWithPrefix("r", "/api/orders", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertEquals("bearer", resolved.effectiveAuth().require(), "the anchor auth floor should materialize");
            assertEquals(Optional.of("api"), resolved.anchor(), "the resolving anchor name should be retained");
        }

        @Test
        @DisplayName("Should let a route auth override replace the anchor floor wholesale between non-none postures")
        void shouldLetRouteAuthReplaceAnchorFloor() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("api", anchor("api", "/api", new AuthConfig("bearer", List.of()), null, null, null)))
                    .build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET))
                    .auth(Optional.of(new AuthConfig("session", List.of()))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api").routes(List.of(route)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("session", find(table, "r").effectiveAuth().require());
        }

        @Test
        @DisplayName("Should throw when no route, endpoint, or anchor auth resolves")
        void shouldThrowWhenNoEffectiveAuthResolves() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("api", anchor("api", "/api", null, null, null, null))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api")
                    .routes(List.of(routeWithPrefix("r", "/api/orders", HttpMethod.GET))).build();
            ResolvedTopology topology = topologyWith("ORDERS");
            List<EndpointConfig> endpoints = List.of(endpoint);

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, endpoints, topology));
        }

        @Test
        @DisplayName("Should materialize the anchor security_filter when the route omits it")
        void shouldMaterializeAnchorSecurityFilter() {
            GatewayConfig config = gateway().anchors(Map.of("api",
                    anchor("api", "/api", new AuthConfig("bearer", List.of()), filter("strict"), null, null))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api")
                    .routes(List.of(routeWithPrefix("r", "/api/orders", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertEquals(Optional.of("strict"),
                    resolved.effectiveSecurityFilter().flatMap(SecurityFilterConfig::profile));
        }

        @Test
        @DisplayName("Should let the route security_filter replace the anchor block wholesale")
        void shouldLetRouteSecurityFilterReplaceAnchor() {
            GatewayConfig config = gateway().anchors(Map.of("api",
                    anchor("api", "/api", new AuthConfig("bearer", List.of()), filter("strict"), null, null))).build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET))
                    .securityFilter(Optional.of(filter("lenient"))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api").routes(List.of(route)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(Optional.of("lenient"),
                    find(table, "r").effectiveSecurityFilter().flatMap(SecurityFilterConfig::profile),
                    "the route security_filter should replace the anchor block wholesale");
        }

        @Test
        @DisplayName("Should materialize the anchor allowed_methods when the endpoint declares none")
        void shouldMaterializeAnchorAllowedMethods() {
            GatewayConfig config = gateway().anchors(Map.of("api", anchor("api", "/api",
                    new AuthConfig("bearer", List.of()), null, List.of(HttpMethod.GET), null))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api")
                    .routes(List.of(routeWithPrefix("r", "/api/orders", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of(HttpMethod.GET), find(table, "r").effectiveAllowedMethods(),
                    "the anchor allowed_methods should materialize when the endpoint declares none");
        }

        @Test
        @DisplayName("Should let the endpoint allowed_methods replace the anchor list wholesale")
        void shouldLetEndpointReplaceAnchorAllowedMethods() {
            GatewayConfig config = gateway().anchors(Map.of("api", anchor("api", "/api",
                    new AuthConfig("bearer", List.of()), null, List.of(HttpMethod.GET), null))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api")
                    .allowedMethods(List.of(HttpMethod.POST))
                    .routes(List.of(routeWithPrefix("r", "/api/orders", HttpMethod.POST))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of(HttpMethod.POST), find(table, "r").effectiveAllowedMethods());
        }

        @Test
        @DisplayName("Should materialize the anchor security_headers, else fall back to the gateway block")
        void shouldMaterializeAnchorSecurityHeadersElseGateway() {
            SecurityHeadersConfig anchorHeaders = headers();
            SecurityHeadersConfig gatewayHeaders = SecurityHeadersConfig.builder()
                    .contentTypeNosniff(Optional.of(true)).build();
            GatewayConfig config = gateway().securityHeaders(Optional.of(gatewayHeaders)).anchors(Map.of("api",
                    anchor("api", "/api", new AuthConfig("bearer", List.of()), null, null, anchorHeaders))).build();
            EndpointConfig anchored = anchoredEndpoint("orders", "ORDERS", "api")
                    .routes(List.of(routeWithPrefix("anchored", "/api/orders", HttpMethod.GET))).build();
            EndpointConfig plain = endpoint("public", "PUBLIC")
                    .routes(List.of(routeWithPrefix("plain", "/public", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(anchored, plain), topologyWith("ORDERS", "PUBLIC"));

            assertAll("security_headers resolves at gateway → anchor level only",
                    () -> assertEquals(Optional.of(anchorHeaders), find(table, "anchored").effectiveSecurityHeaders(),
                            "an anchored route should carry the anchor security_headers"),
                    () -> assertEquals(Optional.of(gatewayHeaders), find(table, "plain").effectiveSecurityHeaders(),
                            "an unanchored route should fall back to the gateway security_headers"));
        }

        @Test
        @DisplayName("Should let a per-route anchor override the endpoint default membership")
        void shouldLetRouteAnchorOverrideEndpointAnchor() {
            GatewayConfig config = gateway().anchors(Map.of(
                    "api", anchor("api", "/api", new AuthConfig("bearer", List.of()), null, null, null),
                    "bff", anchor("bff", "/bff", new AuthConfig("session", List.of()), null, null, null))).build();
            RouteConfig routeOnBff = RouteConfig.builder().id("r").anchor(Optional.of("bff"))
                    .match(match("/bff/home", HttpMethod.GET)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api").routes(List.of(routeOnBff)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertEquals(Optional.of("bff"), resolved.anchor(), "the per-route anchor override should win");
            assertEquals("session", resolved.effectiveAuth().require());
        }

        @Test
        @DisplayName("Should leave an unanchored route's anchor empty and behave exactly as before")
        void shouldLeaveUnanchoredRouteEmpty() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertTrue(resolved.anchor().isEmpty(), "a route without any anchor ref carries no resolving anchor");
            assertTrue(resolved.effectiveSecurityFilter().isEmpty());
        }
    }

    @Nested
    @DisplayName("Per-route effective-posture logging")
    class PostureLogging {

        @Test
        @DisplayName("Should emit a per-route effective-posture INFO line during assembly")
        void shouldEmitEffectivePostureInfo() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("api", anchor("api", "/api", new AuthConfig("bearer", List.of()), filter("strict"),
                            null, null)))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api")
                    .routes(List.of(routeWithPrefix("orders-read", "/api/orders", HttpMethod.GET))).build();

            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "effective posture");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "orders-read");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "anchor='api'");
        }

        @Test
        @DisplayName("Should WARN when a route replaces an anchor-provided security_filter wholesale")
        void shouldWarnOnWeakeningSecurityFilterOverride() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("api", anchor("api", "/api", new AuthConfig("bearer", List.of()), filter("strict"),
                            null, null)))
                    .build();
            RouteConfig route = RouteConfig.builder().id("r").match(match("/api/orders", HttpMethod.GET))
                    .securityFilter(Optional.of(filter("lenient"))).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api").routes(List.of(route)).build();

            builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "overrides anchor");
        }
    }

    @Nested
    @DisplayName("Effective retry / not-modified resolution")
    class EffectiveUpstreamDefaults {

        @Test
        @DisplayName("Should default both toggles to true when nothing is declared")
        void shouldDefaultBothTogglesTrue() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertTrue(resolved.retryEnabled(), "retry should default to true");
            assertTrue(resolved.notModifiedEnabled(), "not-modified should default to true");
        }

        @Test
        @DisplayName("Should let the endpoint upstream_defaults replace the global block wholesale")
        void shouldLetEndpointDefaultsReplaceGlobal() {
            GatewayConfig config = gateway()
                    .upstreamDefaults(Optional.of(new UpstreamDefaultsConfig(true, true))).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .upstreamDefaults(Optional.of(new UpstreamDefaultsConfig(false, false)))
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertFalse(resolved.retryEnabled(), "endpoint block should replace the global retry toggle");
            assertFalse(resolved.notModifiedEnabled(), "endpoint block should replace the global not-modified toggle");
        }

        @Test
        @DisplayName("Should let a per-route toggle override the resolved endpoint value")
        void shouldLetPerRouteToggleOverride() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .upstreamDefaults(Optional.of(new UpstreamDefaultsConfig(false, false)))
                    .routes(List.of(routeWithToggles("r", true, null))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertTrue(resolved.retryEnabled(), "the per-route retry override should win");
            assertFalse(resolved.notModifiedEnabled(), "the absent per-route toggle should inherit the endpoint value");
        }

        @Test
        @DisplayName("Should inherit the resolved value when a per-route toggle is absent")
        void shouldInheritWhenPerRouteToggleAbsent() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .upstreamDefaults(Optional.of(new UpstreamDefaultsConfig(false, true)))
                    .routes(List.of(routeWithToggles("r", null, null))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
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
            ForwardConfig forward = new ForwardConfig(List.of("Accept"), List.of("page"),
                    Map.of("X-Gateway", "api-sheriff"));
            RouteConfig route = RouteConfig.builder().id("r").match(match("/r", HttpMethod.GET))
                    .forward(Optional.of(forward)).build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS").routes(List.of(route)).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertEquals(forward, resolved.effectiveForward(), "the route forward block flows through unchanged");
        }

        @Test
        @DisplayName("Should default an absent forward block to a deny-by-default empty allowlist")
        void shouldDefaultAbsentForwardToEmpty() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertTrue(resolved.effectiveForward().headersAllow().isEmpty(),
                    "an unforwarded route resolves to an empty, deny-by-default allowlist");
            assertTrue(resolved.effectiveForward().queryAllow().isEmpty());
            assertTrue(resolved.effectiveForward().setHeaders().isEmpty());
        }
    }

    @Nested
    @DisplayName("Asset terminal-action materialization (ADR-0014)")
    class AssetTerminalAction {

        private AnchorConfig assetAnchor(String name, String prefix, AccessLevel access, String require) {
            return AnchorConfig.builder()
                    .name(name)
                    .pathPrefix(prefix)
                    .type(AnchorType.ASSET)
                    .access(access)
                    .auth(require == null ? Optional.empty() : Optional.of(new AuthConfig(require, List.of())))
                    .build();
        }

        private RouteConfig assetRoute(String id, String prefix, String anchorName, AssetConfig asset) {
            return RouteConfig.builder()
                    .id(id)
                    .anchor(Optional.of(anchorName))
                    .match(match(prefix, HttpMethod.GET))
                    .asset(Optional.of(asset))
                    .build();
        }

        @Test
        @DisplayName("Should materialize a directory asset action and leave the proxy upstream empty")
        void shouldMaterializeDirectoryAsset() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("assets", assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                    .directory(Optional.of("/srv/assets")).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor(Optional.of("assets")).auth(Optional.of(new AuthConfig("none", List.of())))
                    .routes(List.of(assetRoute("bundle", "/assets", "assets", asset))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB"));

            ResolvedRoute resolved = find(table, "bundle");
            assertTrue(resolved.upstream().isEmpty(), "an asset route carries no proxy upstream");
            ResolvedAsset resolvedAsset = resolved.asset().orElseThrow();
            assertAll("directory asset materialization",
                    () -> assertEquals(AssetConfig.Source.DIRECTORY, resolvedAsset.source()),
                    () -> assertEquals(Optional.of("/srv/assets"), resolvedAsset.directory()),
                    () -> assertTrue(resolvedAsset.upstream().isEmpty(), "a directory action carries no upstream"),
                    () -> assertEquals(AccessLevel.PUBLIC, resolvedAsset.access(),
                            "the access level is inherited from the resolving anchor"));
        }

        @Test
        @DisplayName("Should materialize an upstream asset action resolving its alias through the topology")
        void shouldMaterializeUpstreamAsset() {
            GatewayConfig config = gateway().anchors(Map.of("assets",
                    assetAnchor("assets", "/assets", AccessLevel.AUTHENTICATED, "bearer"))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                    .upstream(Optional.of("SECONDARY")).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor(Optional.of("assets")).auth(Optional.empty())
                    .routes(List.of(assetRoute("cdn", "/assets", "assets", asset))).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB", "SECONDARY"));

            ResolvedRoute resolved = find(table, "cdn");
            ResolvedAsset resolvedAsset = resolved.asset().orElseThrow();
            assertAll("upstream asset materialization",
                    () -> assertEquals(AssetConfig.Source.UPSTREAM, resolvedAsset.source()),
                    () -> assertEquals("secondary.internal", resolvedAsset.upstream().orElseThrow().host(),
                            "the upstream alias resolves through the same topology the proxy action uses"),
                    () -> assertTrue(resolvedAsset.directory().isEmpty(), "an upstream action carries no directory"),
                    () -> assertEquals(AccessLevel.AUTHENTICATED, resolvedAsset.access()),
                    () -> assertTrue(resolved.upstream().isEmpty(), "an asset route carries no proxy upstream"));
        }

        @Test
        @DisplayName("Should force AUTHENTICATED access when a route auth override strengthens a public-access anchor")
        void shouldTreatRouteAuthOverrideAsAuthenticatedUnderPublicAccessAnchor() {
            GatewayConfig config = gateway()
                    .anchors(Map.of("assets", assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.DIRECTORY)
                    .directory(Optional.of("/srv/assets")).build();
            RouteConfig route = RouteConfig.builder().id("bundle").anchor(Optional.of("assets"))
                    .match(match("/assets", HttpMethod.GET))
                    .auth(Optional.of(new AuthConfig("bearer", List.of())))
                    .asset(Optional.of(asset)).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor(Optional.of("assets")).auth(Optional.empty()).routes(List.of(route)).build();

            RouteTable table = builder.build(config, List.of(endpoint), topologyWith("WEB"));

            ResolvedRoute resolved = find(table, "bundle");
            assertEquals("bearer", resolved.effectiveAuth().require(),
                    "the route-level override should strengthen the public anchor's absent auth floor");
            assertEquals(AccessLevel.AUTHENTICATED, resolved.asset().orElseThrow().access(),
                    "a route whose effective auth requires bearer must be governed AUTHENTICATED "
                            + "for caching purposes even though its anchor stays access: public");
        }

        @Test
        @DisplayName("Should reject an upstream asset action whose alias does not resolve")
        void shouldRejectUnresolvableUpstreamAssetAlias() {
            GatewayConfig config = gateway().anchors(Map.of("assets",
                    assetAnchor("assets", "/assets", AccessLevel.PUBLIC, null))).build();
            AssetConfig asset = AssetConfig.builder().source(AssetConfig.Source.UPSTREAM)
                    .upstream(Optional.of("MISSING")).build();
            EndpointConfig endpoint = EndpointConfig.builder().id("web").enabled(true).baseUrl("WEB")
                    .anchor(Optional.of("assets")).auth(Optional.of(new AuthConfig("none", List.of())))
                    .routes(List.of(assetRoute("cdn", "/assets", "assets", asset))).build();
            ResolvedTopology topology = topologyWith("WEB");
            List<EndpointConfig> endpoints = List.of(endpoint);

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, endpoints, topology));
        }

        @Test
        @DisplayName("Should keep a proxy route's upstream present and its asset action empty (exclusivity)")
        void shouldKeepProxyRouteExclusive() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            ResolvedRoute resolved = find(table, "r");
            assertTrue(resolved.upstream().isPresent(), "a proxy route resolves an upstream terminal action");
            assertTrue(resolved.asset().isEmpty(), "a proxy route carries no asset terminal action");
        }

        @Test
        @DisplayName("Should resolve the empty-Optional (no-asset) path to the upstream action without throwing (S3655 guard)")
        void shouldResolveNoAssetPathWithoutThrowing() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("proxy-only", HttpMethod.GET))).build();

            RouteTable table = assertDoesNotThrow(
                    () -> builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS")),
                    "the empty-Optional asset branch must resolve the upstream action without throwing");

            ResolvedRoute resolved = find(table, "proxy-only");
            assertAll("empty-Optional asset branch drives the upstream terminal action",
                    () -> assertTrue(resolved.asset().isEmpty(), "the no-asset path leaves the asset action empty"),
                    () -> assertTrue(resolved.upstream().isPresent(),
                            "the guarded empty-asset branch resolves the upstream terminal action"),
                    () -> assertEquals("orders.internal", resolved.upstream().orElseThrow().host(),
                            "the resolved upstream host is driven from the guarded empty-asset branch"));
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
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("short", shortPrefix, HttpMethod.GET),
                            routeWithPrefix("long", longPrefix, HttpMethod.GET),
                            routeWithPrefix("mid", midPrefix, HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(List.of("long", "mid", "short"),
                    table.routes().stream().map(ResolvedRoute::id).toList(),
                    () -> "routes must be ordered by descending prefix length for segment: " + segment);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should match any generated prefix only on a segment boundary")
        void shouldMatchOnSegmentBoundaryOnlyForAnyGeneratedSegment(String segment) {
            String prefix = "/" + segment;
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("target", prefix, HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertAll("segment-boundary matching for prefix " + prefix,
                    () -> assertEquals("target", table.lookup(prefix).orElseThrow().id(),
                            "an exact prefix match must resolve the route"),
                    () -> assertEquals("target", table.lookup(prefix + "/child").orElseThrow().id(),
                            "a child path must match on the segment boundary"),
                    () -> assertTrue(table.lookup(prefix + "-suffix").isEmpty(),
                            "a mere leading-substring match must not resolve the route"));
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept any generated same-prefix pair made disjoint by method")
        void shouldAcceptSamePrefixRoutesDisjointByMethodForAnyGeneratedSegment(String segment) {
            String prefix = "/" + segment;
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("reader", prefix, HttpMethod.GET),
                            routeWithPrefix("writer", prefix, HttpMethod.POST)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(),
                    () -> "method-disjoint same-prefix routes must both survive for prefix: " + prefix);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept any generated same-prefix pair made disjoint by host")
        void shouldAcceptSamePrefixRoutesDisjointByHostForAnyGeneratedSegment(String segment) {
            String prefix = "/" + segment;
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(
                            routeWithPrefixAndHost("alpha", prefix, segment + "-alpha.example.com", HttpMethod.GET),
                            routeWithPrefixAndHost("beta", prefix, segment + "-beta.example.com", HttpMethod.GET)))
                    .build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(),
                    () -> "host-disjoint same-prefix routes must both survive for prefix: " + prefix);
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Should accept any generated same-prefix pair made disjoint by header presence")
        void shouldAcceptSamePrefixRoutesDisjointByHeaderForAnyGeneratedSegment(String segment) {
            String prefix = "/" + segment;
            String headerName = "X-" + segment;
            RouteConfig present = routeWithHeader("present", prefix,
                    HeaderMatcher.builder().name(headerName).present(Optional.of(true)).build());
            RouteConfig absent = routeWithHeader("absent", prefix,
                    HeaderMatcher.builder().name(headerName).present(Optional.of(false)).build());
            EndpointConfig endpoint = endpoint("orders", "ORDERS").routes(List.of(present, absent)).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals(2, table.routes().size(),
                    () -> "presence-disjoint same-prefix routes must both survive for prefix: " + prefix);
        }
    }
}
