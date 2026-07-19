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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.RouteMatcher;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteSelectionStage — stage 2 deny-by-default longest-prefix selection")
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

    private static RouteRuntime route(String id, String pathPrefix) {
        MatchConfig match = MatchConfig.builder().pathPrefix(pathPrefix).build();
        return RouteRuntime.builder()
                .id(id)
                .matcher(RouteMatcher.from(match))
                .build();
    }

    private static PipelineRequest requestFor(String canonicalPath) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(canonicalPath)
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
        request.canonicalPath(canonicalPath);
        return request;
    }
}
