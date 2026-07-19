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
package de.cuioss.sheriff.api.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.database.AttackTestCase;
import de.cuioss.http.security.database.OWASPTop10AttackDatabase;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.RouteMatcher;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ThoroughChecksStage — stage 3 per-route divergent filters, allowed_paths, and body cap")
class ThoroughChecksStageTest {

    private final SecurityConfiguration defaultConfiguration = SecurityConfiguration.defaults();
    private final SecurityEventCounter counter = new SecurityEventCounter();
    private final ThoroughChecksStage stage = new ThoroughChecksStage(defaultConfiguration, counter);

    static Stream<AttackTestCase> owaspTop10() {
        return StreamSupport.stream(new OWASPTop10AttackDatabase().getAttackTestCases().spliterator(), false);
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("owaspTop10")
    @DisplayName("re-runs the divergent route filter and rejects an attack path as a security violation")
    void rejectsDivergentFilterViolation(AttackTestCase attack) {
        // Arrange — a route whose strict config diverges from the stage-1 default forces a re-run
        PipelineRequest request = requestFor(attack.attackString(),
                routeWithConfig(Optional.of(SecurityConfiguration.strict())));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, List.of()));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("accepts a legitimate request under a divergent strict route filter")
    void acceptsLegitimateRequestUnderDivergentFilter() {
        // Arrange
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .queryParameters(Map.of("page", List.of("2")))
                .headers(Map.of("x-trace", List.of("abc123")))
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(routeWithConfig(Optional.of(SecurityConfiguration.strict())));

        // Act + Assert — every pipeline (path, params, headers) passes for a benign request
        assertDoesNotThrow(() -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("skips the pipeline re-run when the route config equals the stage-1 default")
    void skipsReRunWhenRouteConfigEqualsDefault() {
        // Arrange — a route whose config equals the default was already covered by stage 1
        PipelineRequest request = requestFor("/api/orders",
                routeWithConfig(Optional.of(defaultConfiguration)));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("skips per-route enforcement entirely when the route declares no security config")
    void skipsWhenRouteDeclaresNoConfig() {
        // Arrange
        PipelineRequest request = requestFor("/api/orders", routeWithConfig(Optional.empty()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("fast-rejects a declared body already exceeding the route cap before the body is read")
    void rejectsBodyExceedingRouteCap() {
        // Arrange
        long cap = defaultConfiguration.maxBodySize();
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.POST)
                .requestPath("/api/orders")
                .declaredContentLength(cap + 1)
                .build();
        request.canonicalPath("/api/orders");
        request.selectedRoute(routeWithConfig(Optional.of(defaultConfiguration)));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, List.of()));

        // Assert
        assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a canonical path outside the route's allowed_paths whitelist")
    void rejectsPathOutsideAllowedPaths() {
        // Arrange
        PipelineRequest request = requestFor("/api/other", routeWithConfig(Optional.empty()));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, List.of("/api/orders")));

        // Assert
        assertEquals(EventType.PATH_NOT_ALLOWED, thrown.getEventType());
    }

    @Test
    @DisplayName("admits a path matching an allowed_paths pattern with a single-segment wildcard")
    void admitsWildcardWhitelistMatch() {
        // Arrange — {id} matches exactly one non-empty path segment
        PipelineRequest request = requestFor("/api/42/detail", routeWithConfig(Optional.empty()));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request, List.of("/api/{id}/detail")));
    }

    @Test
    @DisplayName("rejects a whitelist pattern whose segment count differs from the path")
    void rejectsWildcardSegmentCountMismatch() {
        // Arrange — the path has one segment too few to match the pattern
        PipelineRequest request = requestFor("/api/42", routeWithConfig(Optional.empty()));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.process(request, List.of("/api/{id}/detail")));

        // Assert
        assertEquals(EventType.PATH_NOT_ALLOWED, thrown.getEventType());
    }

    @Test
    @DisplayName("fails loud when the route was not selected at stage 2")
    void failsLoudWhenRouteNotSelected() {
        // Arrange — no selected route recorded
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .build();

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> stage.process(request, List.of()));
    }

    @Test
    @DisplayName("fails loud when the canonical path was not resolved at stage 1")
    void failsLoudWhenCanonicalPathMissing() {
        // Arrange — a selected route but no canonical path recorded
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .build();
        request.selectedRoute(routeWithConfig(Optional.empty()));

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> stage.process(request, List.of()));
    }

    private static PipelineRequest requestFor(String canonicalPath, RouteRuntime route) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(canonicalPath)
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
        request.canonicalPath(canonicalPath);
        request.selectedRoute(route);
        return request;
    }

    private static RouteRuntime routeWithConfig(Optional<SecurityConfiguration> config) {
        return RouteRuntime.builder()
                .id("r")
                .matcher(RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").build()))
                .securityConfiguration(config)
                .build();
    }
}
