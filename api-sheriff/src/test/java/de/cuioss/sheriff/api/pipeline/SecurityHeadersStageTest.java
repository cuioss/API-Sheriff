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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig.Cors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityHeadersStage — stage 0 response headers and CORS origin handling")
class SecurityHeadersStageTest {

    private static final String ACAO = "Access-Control-Allow-Origin";

    @Test
    @DisplayName("reflects any presented origin when a wildcard origin is configured")
    void wildcardReflectsAnyOrigin() {
        // Arrange — a configured "*" is a real wildcard (credentials disabled); the request Origin is
        // never literally "*", so the stage must accept and reflect an arbitrary presented origin.
        SecurityHeadersStage stage = corsStage(List.of("*"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://any.example", false);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://any.example", request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("emits CORS headers for an explicitly allow-listed origin")
    void exactOriginMatch() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://ok.example", request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("emits no CORS header for an origin that is neither listed nor wildcarded")
    void disallowedOriginEmitsNoCorsHeader() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://evil.example", false);

        // Act
        stage.process(request);

        // Assert
        assertNull(request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("short-circuits a wildcard-origin preflight with 204 and the reflected origin")
    void wildcardPreflightShortCircuits() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("*"), false);
        PipelineRequest request = corsRequest(HttpMethod.OPTIONS, "https://any.example", true);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://any.example", request.responseHeaders().get(ACAO));
        assertTrue(request.shortCircuitStatus().isPresent(), "a CORS preflight must short-circuit");
        assertEquals(204, request.shortCircuitStatus().orElseThrow().intValue());
    }

    private static SecurityHeadersStage corsStage(List<String> allowedOrigins, boolean allowCredentials) {
        Cors cors = Cors.builder()
                .enabled(Optional.of(Boolean.TRUE))
                .allowedOrigins(allowedOrigins)
                .allowedMethods(List.of("GET", "POST"))
                .allowCredentials(Optional.of(allowCredentials))
                .build();
        return new SecurityHeadersStage(Optional.of(
                SecurityHeadersConfig.builder().cors(Optional.of(cors)).build()));
    }

    private static PipelineRequest corsRequest(HttpMethod method, String origin, boolean preflight) {
        Map<String, List<String>> headers = preflight
                ? Map.of("origin", List.of(origin), "access-control-request-method", List.of("GET"))
                : Map.of("origin", List.of(origin));
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/api")
                .queryParameters(Map.of())
                .headers(headers)
                .build();
    }
}
