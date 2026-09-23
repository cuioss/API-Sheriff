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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.RouteMatcher;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnableGeneratorController
@DisplayName("RouteSelectionStage — stage 2 deny-by-default exact-first, longest-prefix selection")
class RouteSelectionStageTest {

    private final RouteSelectionStage stage = new RouteSelectionStage(List.of(
            route("admin", "/orders/admin"),
            route("orders", "/orders")));

    @Test
    @DisplayName("selects the most specific route when both prefixes match")
    void selectsLongestPrefix() {
        // Arrange
        PipelineRequest request = requestFor("/orders/admin/reports");

        // Act
        stage.process(request);

        // Assert
        assertNotNull(request.selectedRoute());
        assertEquals("admin", request.selectedRoute().getId());
    }

    @Test
    @DisplayName("selects the general route when only its prefix matches")
    void selectsGeneralRoute() {
        // Arrange
        PipelineRequest request = requestFor("/orders/123");

        // Act
        stage.process(request);

        // Assert
        assertEquals("orders", request.selectedRoute().getId());
    }

    @Test
    @DisplayName("rejects an unmatched path 404 (deny by default)")
    void rejectsUnmatchedPath() {
        // Arrange
        PipelineRequest request = requestFor("/catalog");

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.NO_ROUTE_MATCHED, thrown.getEventType());
    }

    @Test
    @DisplayName("selects the exact route over a prefix route for the same address")
    void selectsExactRouteOverPrefixForSameAddress() {
        // The list is exact-first, as the route-table builder orders it.
        RouteSelectionStage exactFirst = new RouteSelectionStage(List.of(
                exactRoute("orders-root", "/orders"),
                route("orders", "/orders")));
        PipelineRequest request = requestFor("/orders");

        exactFirst.process(request);

        assertEquals("orders-root", request.selectedRoute().getId());
    }

    @Test
    @DisplayName("falls through to the prefix route below an exact route's address")
    void fallsThroughToPrefixBelowExactAddress() {
        RouteSelectionStage exactFirst = new RouteSelectionStage(List.of(
                exactRoute("orders-root", "/orders"),
                route("orders", "/orders")));
        PipelineRequest request = requestFor("/orders/123");

        exactFirst.process(request);

        assertEquals("orders", request.selectedRoute().getId());
    }

    @Test
    @DisplayName("does not match an exact route against its trailing-slash variant")
    void exactRouteDoesNotMatchTrailingSlashVariant() {
        RouteSelectionStage exactOnly = new RouteSelectionStage(List.of(exactRoute("a", "/a")));
        PipelineRequest request = requestFor("/a/");

        GatewayException thrown = assertThrows(GatewayException.class, () -> exactOnly.process(request));

        assertEquals(EventType.NO_ROUTE_MATCHED, thrown.getEventType(),
                "/a and /a/ are distinct addresses for an exact route");
    }

    @Test
    @DisplayName("fails loud when the canonical path was not resolved at stage 1")
    void requiresCanonicalPath() {
        // Arrange — no canonical path recorded
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/orders/123")
                .build();

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> stage.process(request));
    }

    @Nested
    @DisplayName("header-discriminated same-prefix route pair (present: true vs present: false)")
    class HeaderDiscriminatedPair {

        private static final String WITH_HEADER = "with-header";
        private static final String WITHOUT_HEADER = "without-header";
        private static final String CONFIGURED_NAME = "X-Variant";

        @ParameterizedTest(name = "present: true route declared first = {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("selects the present: true route for a request carrying the header, in either declaration order")
        void selectsWithHeaderRouteWhenHeaderPresent(boolean presentFirst) {
            PipelineRequest request = requestWithHeaders("/variants/x",
                    Map.of("x-VARIANT", List.of(Generators.letterStrings(3, 12).next())));

            pairStage(presentFirst).process(request);

            assertEquals(WITH_HEADER, request.selectedRoute().getId(),
                    "The header-carrying request must reach the present: true route whatever the declaration order");
        }

        @ParameterizedTest(name = "present: true route declared first = {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("selects the present: false route for a request without the header, in either declaration order")
        void selectsWithoutHeaderRouteWhenHeaderAbsent(boolean presentFirst) {
            PipelineRequest request = requestWithHeaders("/variants/x", Map.of());

            pairStage(presentFirst).process(request);

            assertEquals(WITHOUT_HEADER, request.selectedRoute().getId(),
                    "The header-less request must reach the present: false route whatever the declaration order");
        }

        @Test
        @DisplayName("selects a mixed-case-named matcher when the request spells the header differently")
        void selectsMixedCaseMatcherForDifferentlySpelledHeader() {
            RouteSelectionStage single = new RouteSelectionStage(List.of(
                    headerRoute(WITH_HEADER, CONFIGURED_NAME, true)));
            PipelineRequest request = requestWithHeaders("/variants",
                    Map.of("X-VARIANT", List.of(Generators.letterStrings(3, 12).next())));

            single.process(request);

            assertEquals(WITH_HEADER, request.selectedRoute().getId(),
                    "A matcher declared as X-Variant must match a request spelling it X-VARIANT");
        }

        private RouteSelectionStage pairStage(boolean presentFirst) {
            RouteRuntime withHeader = headerRoute(WITH_HEADER, CONFIGURED_NAME, true);
            RouteRuntime withoutHeader = headerRoute(WITHOUT_HEADER, CONFIGURED_NAME, false);
            return new RouteSelectionStage(presentFirst
                    ? List.of(withHeader, withoutHeader)
                    : List.of(withoutHeader, withHeader));
        }
    }

    private static RouteRuntime headerRoute(String id, String headerName, boolean present) {
        MatchConfig match = MatchConfig.builder()
                .pathPrefix("/variants")
                .headers(List.of(HeaderMatcher.builder().name(headerName).present(present).build()))
                .build();
        return RouteRuntime.builder()
                .id(id)
                .matcher(RouteMatcher.from(match))
                .build();
    }

    private static PipelineRequest requestWithHeaders(String canonicalPath, Map<String, List<String>> headers) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(canonicalPath)
                .queryParameters(List.of())
                .headers(headers)
                .build();
        request.canonicalPath(canonicalPath);
        return request;
    }

    private static RouteRuntime route(String id, String pathPrefix) {
        MatchConfig match = MatchConfig.builder().pathPrefix(pathPrefix).build();
        return RouteRuntime.builder()
                .id(id)
                .matcher(RouteMatcher.from(match))
                .build();
    }

    private static RouteRuntime exactRoute(String id, String path) {
        MatchConfig match = MatchConfig.builder().path(path).build();
        return RouteRuntime.builder()
                .id(id)
                .matcher(RouteMatcher.from(match))
                .build();
    }

    private static PipelineRequest requestFor(String canonicalPath) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(canonicalPath)
                .queryParameters(List.of())
                .headers(Map.of())
                .build();
        request.canonicalPath(canonicalPath);
        return request;
    }
}
