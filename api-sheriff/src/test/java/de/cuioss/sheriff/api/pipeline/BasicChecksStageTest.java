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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.database.AttackTestCase;
import de.cuioss.http.security.database.OWASPTop10AttackDatabase;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("BasicChecksStage — stage 1 baseline filter and collection caps")
class BasicChecksStageTest {

    private final SecurityEventCounter counter = new SecurityEventCounter();
    private final BasicChecksStage strictStage = new BasicChecksStage(SecurityConfiguration.strict(), counter);
    private final BasicChecksStage defaultStage = new BasicChecksStage(SecurityConfiguration.defaults(), counter);

    static Stream<AttackTestCase> owaspTop10() {
        return StreamSupport.stream(new OWASPTop10AttackDatabase().getAttackTestCases().spliterator(), false);
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("owaspTop10")
    @DisplayName("rejects every OWASP Top 10 attack-database path as a security-filter violation")
    void rejectsOwaspTop10(AttackTestCase attack) {
        // Arrange
        PipelineRequest request = requestWithPath(attack.attackString());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> strictStage.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("accepts a legitimate path and records the single canonical path")
    void acceptsLegitimatePath() {
        // Arrange
        PipelineRequest request = requestWithPath("/api/v1/users");

        // Act
        defaultStage.process(request);

        // Assert
        assertNotNull(request.canonicalPath());
    }

    @Test
    @DisplayName("rejects a query-parameter count beyond the configured cap")
    void rejectsExcessiveParameterCount() {
        // Arrange
        int cap = SecurityConfiguration.defaults().maxParameterCount();
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (int i = 0; i <= cap; i++) {
            parameters.put("p" + i, List.of("1"));
        }
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api")
                .queryParameters(parameters)
                .build();

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> defaultStage.process(request));

        // Assert
        assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a header count beyond the configured cap")
    void rejectsExcessiveHeaderCount() {
        // Arrange
        int cap = SecurityConfiguration.defaults().maxHeaderCount();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i <= cap; i++) {
            headers.put("x-h" + i, List.of("v"));
        }
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api")
                .headers(headers)
                .build();

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> defaultStage.process(request));

        // Assert
        assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, thrown.getEventType());
    }

    private static PipelineRequest requestWithPath(String path) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(path)
                .queryParameters(Map.of())
                .headers(Map.of())
                .build();
    }
}
