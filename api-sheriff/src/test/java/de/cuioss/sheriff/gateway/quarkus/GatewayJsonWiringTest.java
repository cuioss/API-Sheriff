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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts the {@link GatewayJson} corpora against the bean the running application injects, so the
 * byte-level contract holds for the mapper Quarkus configures — with every module and customizer the
 * platform registers on it — and not only for a mapper a unit test builds itself.
 * <p>
 * The corpora are those of {@code GatewayJsonTest}; they are referenced, not copied, so the two
 * tests cannot drift apart.
 */
@QuarkusTest
@DisplayName("GatewayJson — the injected bean renders the corpus byte for byte")
class GatewayJsonWiringTest {

    private static final String CORPUS_OWNER = "de.cuioss.sheriff.gateway.bff.runtime.GatewayJsonTest";

    @Inject
    GatewayJson gatewayJson;

    @Inject
    ObjectMapper platformMapper;

    @ParameterizedTest(name = "{0} renders as {2}")
    @MethodSource(CORPUS_OWNER + "#parityCorpus")
    @DisplayName("Should render every corpus value to exactly its expected bytes")
    void shouldRenderCorpusValue(String label, @Nullable Object value, String expectedJson) {
        assertEquals(expectedJson, gatewayJson.toJson(value), () -> "Rendering of: " + label);
    }

    @ParameterizedTest(name = "control character {0} renders as {1}")
    @MethodSource(CORPUS_OWNER + "#controlCharacterCorpus")
    @DisplayName("Should escape a control character without a short escape in lower-case hex")
    void shouldEscapeControlCharacterInLowerCaseHex(int character, String expectedJson) {
        assertEquals(expectedJson, gatewayJson.toJson(Character.toString(character)));
    }

    @Test
    @DisplayName("Should leave the platform mapper rendering a control character its own way")
    void shouldLeavePlatformMapperUnchanged() throws Exception {
        String controlCharacter = Character.toString(0x1f);

        String gatewayRendering = gatewayJson.toJson(controlCharacter);
        String platformRendering = platformMapper.writeValueAsString(controlCharacter);

        assertEquals("\"\\u001f\"", gatewayRendering);
        assertNotEquals(gatewayRendering, platformRendering,
                "the platform mapper keeps its own escape form, so the gateway setting did not reach it");
    }
}
