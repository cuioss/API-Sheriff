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
package de.cuioss.sheriff.gateway.routing;

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;


import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Tests for {@link RouteTable#lookup(String)} over a table assembled by {@link RouteTableBuilder}: the first
 * match in table order is the most specific route, an exact route wins over a prefix route for its own
 * address, and a prefix matches on a segment boundary only.
 * <p>
 * The concrete segment-boundary example belongs to the matcher itself
 * ({@code RouteTest.RouteMatcherContract.shouldMatchPrefixOnlyOnSegmentBoundaries}); the case here
 * re-asserts it through the lookup over generated prefixes.
 */
@EnableGeneratorController
@DisplayName("RouteTable — the most-specific-first lookup")
class RouteTableTest {

    private static RouteTable tableOf(RouteConfig... routes) {
        EndpointConfig endpoint = openEndpointSeed("orders", "ORDERS").routes(List.of(routes)).build();
        return new RouteTableBuilder().build(validGateway().build(), List.of(endpoint), topologyWith("ORDERS"));
    }

    @Test
    @DisplayName("Should look up the most specific prefix match")
    void shouldLookUpMostSpecificPrefix() {
        RouteTable table = tableOf(prefixRoute("broad", "/a", HttpMethod.GET),
                prefixRoute("narrow", "/a/b", HttpMethod.GET));

        assertEquals("narrow", table.lookup("/a/b/item").orElseThrow().id());
        assertEquals("broad", table.lookup("/a/other").orElseThrow().id());
        assertTrue(table.lookup("/unmatched").isEmpty());
    }

    @Test
    @DisplayName("Should treat a prefix already ending in a slash as a segment boundary")
    void shouldMatchTrailingSlashPrefix() {
        RouteTable table = tableOf(prefixRoute("api", "/api/", HttpMethod.GET));

        assertEquals("api", table.lookup("/api/orders").orElseThrow().id());
        assertTrue(table.lookup("/api-internal").isEmpty(),
                "/api-internal must not match the prefix /api/");
    }

    @Test
    @DisplayName("Should look up the exact route for its own address and the prefix route for every other")
    void shouldLookUpExactBeforePrefixForTheSameAddress() {
        RouteConfig exact = RouteConfig.builder().id("exact")
                .match(MatchConfig.builder().path("/a").methods(List.of(HttpMethod.GET)).build())
                .build();
        RouteTable table = tableOf(prefixRoute("prefix", "/a", HttpMethod.GET), exact);

        assertAll("exact-first lookup",
                () -> assertEquals("exact", table.lookup("/a").orElseThrow().id(),
                        "the exact route wins for its own address"),
                () -> assertEquals("prefix", table.lookup("/a/").orElseThrow().id(),
                        "an exact path is un-normalized: /a does not match /a/"),
                () -> assertEquals("prefix", table.lookup("/a/child").orElseThrow().id(),
                        "the prefix route keeps serving the paths below the exact address"));
    }

    @ParameterizedTest
    @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
    @DisplayName("Should match any generated prefix only on a segment boundary")
    void shouldMatchOnSegmentBoundaryOnlyForAnyGeneratedSegment(String segment) {
        String prefix = "/" + segment;
        RouteTable table = tableOf(prefixRoute("target", prefix, HttpMethod.GET));

        assertAll("segment-boundary matching for prefix " + prefix,
                () -> assertEquals("target", table.lookup(prefix).orElseThrow().id(),
                        "an exact prefix match must resolve the route"),
                () -> assertEquals("target", table.lookup(prefix + "/child").orElseThrow().id(),
                        "a child path must match on the segment boundary"),
                () -> assertTrue(table.lookup(prefix + "-suffix").isEmpty(),
                        "a mere leading-substring match must not resolve the route"));
    }
}
