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

import java.util.EnumSet;
import java.util.Map;

import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VerbGateStage — stage 2b verb gate (405 + Allow)")
class VerbGateStageTest {

    private final VerbGateStage stage = new VerbGateStage();

    @Test
    @DisplayName("rejects a disallowed verb with 405 and a sorted Allow header")
    void rejectsDisallowedVerb() {
        // Arrange
        PipelineRequest request = requestFor(HttpMethod.DELETE);
        request.selectedRoute(routeAllowing(EnumSet.of(HttpMethod.GET, HttpMethod.POST)));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> stage.process(request));

        // Assert
        assertEquals(EventType.METHOD_NOT_ALLOWED, thrown.getEventType());
        assertEquals("GET, POST", request.responseHeaders().get("Allow"));
    }

    @Test
    @DisplayName("passes an allowed verb through untouched")
    void passesAllowedVerb() {
        // Arrange
        PipelineRequest request = requestFor(HttpMethod.GET);
        request.selectedRoute(routeAllowing(EnumSet.of(HttpMethod.GET, HttpMethod.POST)));

        // Act + Assert
        assertDoesNotThrow(() -> stage.process(request));
    }

    private static RouteRuntime routeAllowing(EnumSet<HttpMethod> methods) {
        return RouteRuntime.builder()
                .id("orders")
                .effectiveAllowedMethods(methods)
                .build();
    }

    private static PipelineRequest requestFor(HttpMethod method) {
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/orders")
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
    }
}
