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
package de.cuioss.sheriff.api.config;

import static org.junit.jupiter.api.Assertions.assertAll;
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


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.UpstreamConfig;
import de.cuioss.sheriff.api.config.model.UpstreamDefaultsConfig;

/**
 * Tests for {@link RouteTableBuilder}: enabled-only merge, longest-prefix
 * ordering, same-prefix disjointness enforcement, and the materialization of
 * effective auth, effective {@code allowed_methods}, and the three-level
 * retry / not-modified override chain.
 * <p>
 * The example-based cases above pin each invariant with one concrete path; the
 * {@link PropertyBasedInvariants} nested class complements — never replaces — them by
 * re-asserting the same invariants over generated prefix / host / method
 * permutations, so a rule that happens to hold only for the chosen literal is caught.
 */
@EnableGeneratorController
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

    private static EndpointConfig.EndpointConfigBuilder endpoint(String id, String alias) {
        return EndpointConfig.builder().id(id).enabled(true).baseUrl(alias).auth(new AuthConfig("none", List.of()));
    }

    private static ResolvedRoute find(RouteTable table, String id) {
        return table.routes().stream().filter(route -> route.id().equals(id)).findFirst().orElseThrow();
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
        @DisplayName("Should reject two same-prefix routes that overlap on method")
        void shouldRejectNonDisjointSamePrefixRoutes() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("first", "/x", HttpMethod.GET),
                            routeWithPrefix("second", "/x", HttpMethod.GET)))
                    .build();

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS")));
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

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS")));
        }

        @Test
        @DisplayName("Should resolve each route's upstream from the topology")
        void shouldResolveUpstreamTarget() {
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(route("r", HttpMethod.GET))).build();

            RouteTable table = builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS"));

            assertEquals("orders.internal", find(table, "r").upstream().host());
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

        @Test
        @DisplayName("Should reject same-prefix routes whose header matchers only agree on presence")
        void shouldRejectSamePrefixRoutesWithMatchingHeaderPresence() {
            RouteConfig first = routeWithHeader("first", "/x",
                    HeaderMatcher.builder().name("X-Debug").present(Optional.of(true)).build());
            RouteConfig second = routeWithHeader("second", "/x",
                    HeaderMatcher.builder().name("X-Debug").present(Optional.of(true)).build());
            EndpointConfig endpoint = endpoint("orders", "ORDERS").routes(List.of(first, second)).build();

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(gateway().build(), List.of(endpoint), topologyWith("ORDERS")));
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
                    .auth(new AuthConfig("session", List.of())).routes(List.of(route("r", HttpMethod.GET))).build();

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
        @DisplayName("Should reject any generated same-prefix pair overlapping on method")
        void shouldRejectSamePrefixRoutesOverlappingOnMethodForAnyGeneratedSegment(String segment) {
            String prefix = "/" + segment;
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefix("first", prefix, HttpMethod.GET),
                            routeWithPrefix("second", prefix, HttpMethod.GET)))
                    .build();
            GatewayConfig config = gateway().build();
            ResolvedTopology topology = topologyWith("ORDERS");

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, List.of(endpoint), topology),
                    () -> "method-overlapping same-prefix routes must be rejected for prefix: " + prefix);
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
        @DisplayName("Should reject any generated same-prefix pair sharing one host and method")
        void shouldRejectSamePrefixRoutesSharingHostForAnyGeneratedSegment(String segment) {
            String prefix = "/" + segment;
            String host = segment + ".example.com";
            EndpointConfig endpoint = endpoint("orders", "ORDERS")
                    .routes(List.of(routeWithPrefixAndHost("first", prefix, host, HttpMethod.GET),
                            routeWithPrefixAndHost("second", prefix, host, HttpMethod.GET)))
                    .build();
            GatewayConfig config = gateway().build();
            ResolvedTopology topology = topologyWith("ORDERS");

            assertThrows(RouteTableBuilder.RouteTableException.class,
                    () -> builder.build(config, List.of(endpoint), topology),
                    () -> "same-prefix routes sharing host and method must be rejected for prefix: " + prefix);
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
