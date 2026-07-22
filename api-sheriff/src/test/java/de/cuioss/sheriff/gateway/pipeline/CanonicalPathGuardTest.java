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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CanonicalPathGuard — D3b GW-01 single-canonical-path guard")
class CanonicalPathGuardTest {

    private final CanonicalPathGuard guard = new CanonicalPathGuard();

    @ParameterizedTest(name = "rejects non-canonical path [{0}]")
    @ValueSource(strings = {
            "/orders/1%2f2",
            "/orders/1%2F2",
            "/orders/1%5cadmin",
            "/orders/1%5Cadmin",
            "/orders;jsessionid=abc",
            "/orders/../admin;x=1"
    })
    @DisplayName("rejects an encoded separator or a matrix parameter as a security-filter violation")
    void rejectsNonCanonicalPaths(String rawPath) {
        // Arrange
        PipelineRequest request = requestWith(rawPath, "/orders/canonical");

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> guard.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("accepts a clean path once the canonical path has been recorded")
    void acceptsCleanPath() {
        // Arrange
        PipelineRequest request = requestWith("/orders/123", "/orders/123");

        // Act + Assert
        assertDoesNotThrow(() -> guard.process(request));
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
        assertThrows(IllegalStateException.class, () -> guard.process(request));
    }

    private static PipelineRequest requestWith(String rawPath, String canonicalPath) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(rawPath)
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
        request.canonicalPath(canonicalPath);
        return request;
    }
}
