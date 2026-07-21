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
package de.cuioss.sheriff.api.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import de.cuioss.sheriff.api.config.model.HttpMethod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contract of {@link WebSocketProtocolProcessor}: it identifies as {@code websocket} and serves only
 * the WebSocket upgrade verb ({@code GET}), since a WebSocket route is entered by the HTTP upgrade
 * handshake and every other verb is outside its scope.
 */
@DisplayName("WebSocketProtocolProcessor — WebSocket upgrade verb semantics")
class WebSocketProtocolProcessorTest {

    private final WebSocketProtocolProcessor processor = new WebSocketProtocolProcessor();

    @Test
    @DisplayName("identifies as 'websocket'")
    void identifiesAsWebsocket() {
        assertEquals("websocket", processor.id());
    }

    @Test
    @DisplayName("serves exactly the GET upgrade verb")
    void servesOnlyGet() {
        assertEquals(Set.of(HttpMethod.GET), processor.standardMethods(),
                "a WebSocket route is entered by the GET upgrade handshake only");
    }

    @Test
    @DisplayName("supports GET")
    void supportsGet() {
        assertTrue(processor.supports(HttpMethod.GET));
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = "GET", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("rejects every non-GET verb")
    void rejectsNonGetVerbs(HttpMethod method) {
        assertFalse(processor.supports(method),
                () -> "the WebSocket processor must not serve " + method);
    }
}
