/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FramingGate — D3b GW-02 anti-smuggling framing gate")
class FramingGateTest {

    private final FramingGate gate = new FramingGate();

    @Test
    @DisplayName("rejects Content-Length and Transfer-Encoding both present")
    void rejectsConflictingFraming() {
        // Arrange
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("10"),
                "transfer-encoding", List.of("chunked")), 10L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a request carrying multiple Content-Length headers (RFC 7230 §3.3.2)")
    void rejectsMultipleContentLength() {
        // Arrange — two Content-Length values is an ambiguous framing / request-smuggling vector
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("10", "20")), 10L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a single Content-Length header carrying a comma-separated value list")
    void rejectsCommaSeparatedContentLength() {
        // Arrange — "5, 6" in one Content-Length field is equally a smuggling vector per RFC 7230
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("5, 6")), 5L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a body on a bodyless GET")
    void rejectsBodyOnGet() {
        // Arrange
        PipelineRequest request = request(HttpMethod.GET, Map.of("content-length", List.of("5")), 5L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a HEAD request carrying a declared body")
    void rejectsBodyOnHead() {
        // Arrange
        PipelineRequest request = request(HttpMethod.HEAD, Map.of("content-length", List.of("3")), 3L, true);

        // Act + Assert
        assertThrows(GatewayException.class, () -> gate.process(request));
    }

    @Test
    @DisplayName("rejects a Connection token that would strip a framing header")
    void rejectsFramingHeaderStrip() {
        // Arrange
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("4"),
                "connection", List.of("keep-alive, content-length")), 4L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a Connection token that would strip the trust header Authorization")
    void rejectsTrustHeaderStrip() {
        // Arrange
        PipelineRequest request = request(HttpMethod.GET, Map.of(
                "connection", List.of("authorization")), -1L, false);

        // Act + Assert
        assertThrows(GatewayException.class, () -> gate.process(request));
    }

    @Test
    @DisplayName("accepts a well-framed POST carrying only Content-Length")
    void acceptsWellFramedPost() {
        // Arrange
        PipelineRequest request = request(HttpMethod.POST, Map.of("content-length", List.of("12")), 12L, true);

        // Act + Assert
        assertDoesNotThrow(() -> gate.process(request));
    }

    private static PipelineRequest request(HttpMethod method, Map<String, List<String>> headers,
            long contentLength, boolean bodyPresent) {
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/orders")
                .queryParameters(Map.of())
                .headers(headers)
                .declaredContentLength(contentLength)
                .bodyPresent(bodyPresent)
                .build();
    }
}
