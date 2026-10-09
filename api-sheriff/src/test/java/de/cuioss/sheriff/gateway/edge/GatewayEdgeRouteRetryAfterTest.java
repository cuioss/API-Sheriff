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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The {@code Retry-After} header of an open circuit's {@code 503}: {@link GatewayEdgeRoute#seedRetryAfter}
 * seeds it onto the response-header map every gateway-authored answer is written from, for that one
 * event and no other, with the window the breaker itself applies.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — Retry-After on the 503 of an open circuit")
class GatewayEdgeRouteRetryAfterTest {

    @Test
    @DisplayName("an open circuit is given Retry-After: 5, the seconds the breaker stays open")
    void openCircuitCarriesTheBreakerWindow() {
        PipelineRequest request = anyRequest();

        GatewayEdgeRoute.seedRetryAfter(request, EventType.UPSTREAM_CIRCUIT_OPEN);

        Map<String, String> authored = request.gatewayAuthoredResponseHeaders();
        assertAll("Retry-After of an open circuit",
                () -> assertEquals("5", authored.get("Retry-After"),
                        "the header is a delay in whole seconds on the map every gateway-authored answer carries"),
                () -> assertEquals(Integer.toString(GatewayEdgeRoute.BREAKER_RESET_SECONDS),
                        authored.get(GatewayEdgeRoute.RETRY_AFTER_HEADER),
                        "the hint is the window the breaker's own delay is built from"));
    }

    @ParameterizedTest(name = "{0} gets no Retry-After")
    @EnumSource(value = EventType.class, mode = EnumSource.Mode.EXCLUDE, names = "UPSTREAM_CIRCUIT_OPEN")
    @DisplayName("control: no other event is given the header")
    void everyOtherEventIsLeftAlone(EventType eventType) {
        PipelineRequest request = anyRequest();

        GatewayEdgeRoute.seedRetryAfter(request, eventType);

        assertFalse(request.gatewayAuthoredResponseHeaders().containsKey("Retry-After"),
                "only the open circuit's 503 names a time to retry at");
    }

    @Test
    @DisplayName("a rejection answered before a request was built is left without the header")
    void absentRequestIsTolerated() {
        assertDoesNotThrow(() -> GatewayEdgeRoute.seedRetryAfter(null, EventType.UPSTREAM_CIRCUIT_OPEN));
    }

    private static PipelineRequest anyRequest() {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/" + Generators.letterStrings(1, 12).next())
                .build();
    }
}
