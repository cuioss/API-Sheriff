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
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract of the fail-closed WebSocket Origin gate (GW-09 / CSWSH). The allowlist is applied
 * exact-match, case-insensitive on host (the allowlist is lower-cased at boot; the inbound
 * {@code Origin} is lower-cased here). An empty allowlist means no enforcement; a non-empty allowlist
 * rejects an absent or foreign origin with {@link EventType#WEBSOCKET_ORIGIN_REJECTED}.
 */
@EnableTestLogger
@DisplayName("OriginValidationStage — fail-closed WebSocket Origin allowlist")
class OriginValidationStageTest {

    private static final String ROUTE_ID = "chat";

    private final OriginValidationStage stage = new OriginValidationStage();

    private static PipelineRequest requestWithOrigin(String origin) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/ws")
                .headers(Map.of("Origin", List.of(origin)))
                .build();
    }

    private static PipelineRequest requestWithoutOrigin() {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/ws")
                .build();
    }

    @Test
    @DisplayName("an empty allowlist enforces nothing — the upgrade proceeds even for a foreign origin")
    void emptyAllowlistEnforcesNothing() {
        PipelineRequest request = requestWithOrigin("https://evil.example.com");

        assertDoesNotThrow(() -> stage.validate(request, ROUTE_ID, Set.of()),
                "an empty allowlist declares no enforcement (a non-bearer route)");
    }

    @Test
    @DisplayName("an allowlisted origin proceeds")
    void allowlistedOriginProceeds() {
        PipelineRequest request = requestWithOrigin("https://app.example.com");

        assertDoesNotThrow(() -> stage.validate(request, ROUTE_ID, Set.of("https://app.example.com")));
    }

    @Test
    @DisplayName("matching is case-insensitive on host (the allowlist is pre-lower-cased at boot)")
    void matchingIsCaseInsensitiveOnHost() {
        PipelineRequest request = requestWithOrigin("https://App.Example.COM");

        assertDoesNotThrow(() -> stage.validate(request, ROUTE_ID, Set.of("https://app.example.com")),
                "the inbound Origin is lower-cased before comparison against the pre-lower-cased allowlist");
    }

    @Test
    @DisplayName("a foreign origin is rejected fail-closed")
    void foreignOriginRejected() {
        PipelineRequest request = requestWithOrigin("https://evil.example.com");
        Set<String> allowlist = Set.of("https://app.example.com");

        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.validate(request, ROUTE_ID, allowlist));
        assertEquals(EventType.WEBSOCKET_ORIGIN_REJECTED, thrown.getEventType());
    }

    @Test
    @DisplayName("an absent Origin against a non-empty allowlist is rejected fail-closed")
    void absentOriginRejected() {
        PipelineRequest request = requestWithoutOrigin();
        Set<String> allowlist = Set.of("https://app.example.com");

        GatewayException thrown = assertThrows(GatewayException.class,
                () -> stage.validate(request, ROUTE_ID, allowlist));
        assertEquals(EventType.WEBSOCKET_ORIGIN_REJECTED, thrown.getEventType());
    }
}
