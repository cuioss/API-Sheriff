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
package de.cuioss.sheriff.gateway.bff.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link GatewayJson}: every gateway-authored JSON body is rendered to exactly the bytes
 * its contract names, whatever the mapper it is derived from is configured to do.
 * <p>
 * The expectations are literals because the literal is the contract: a body that differs by one
 * byte is a different response. {@link #parityCorpus()} and {@link #controlCharacterCorpus()} are
 * the two corpora; {@code GatewayJsonWiringTest} asserts the same two against the bean the running
 * application injects.
 */
@EnableGeneratorController
class GatewayJsonTest {

    private static final String LOWER_CASE_HEX = "0123456789abcdef";

    private final GatewayJson gatewayJson = new GatewayJson(new ObjectMapper());

    /**
     * The literal corpus: one value graph and the exact JSON it renders to.
     *
     * @return {@code label, value, expectedJson} triples
     */
    static Stream<Arguments> parityCorpus() {
        return Stream.of(
                Arguments.of("double NaN", Double.NaN, "null"),
                Arguments.of("double positive infinity", Double.POSITIVE_INFINITY, "null"),
                Arguments.of("double negative infinity", Double.NEGATIVE_INFINITY, "null"),
                Arguments.of("float NaN", Float.NaN, "null"),
                Arguments.of("float positive infinity", Float.POSITIVE_INFINITY, "null"),
                Arguments.of("finite double", 1.5d, "1.5"),
                Arguments.of("double in exponent form", 1.0E10d, "1.0E10"),
                Arguments.of("integer", 42, "42"),
                Arguments.of("boolean", Boolean.TRUE, "true"),
                Arguments.of("null", null, "null"),
                Arguments.of("string", "hi", "\"hi\""),
                Arguments.of("non-finite member keeps insertion order", finiteThenBroken(),
                        "{\"finite\":1,\"broken\":null}"),
                Arguments.of("non-finite array element", new ArrayList<>(List.of(1, Double.POSITIVE_INFINITY)),
                        "[1,null]"),
                Arguments.of("value of no JSON-native type", DayOfWeek.MONDAY, "\"MONDAY\""),
                Arguments.of("optional", Optional.of("x"), "\"Optional[x]\""),
                Arguments.of("instant", Instant.parse("2026-01-01T00:00:00Z"), "\"2026-01-01T00:00:00Z\""),
                Arguments.of("quotation mark", "a\"b", "\"a\\\"b\""),
                Arguments.of("reverse solidus", "a\\b", "\"a\\\\b\""),
                Arguments.of("line feed", "a\nb", "\"a\\nb\""),
                Arguments.of("carriage return", "a\rb", "\"a\\rb\""),
                Arguments.of("tab", "a\tb", "\"a\\tb\""),
                Arguments.of("backspace", "a\bb", "\"a\\bb\""),
                Arguments.of("form feed", "a\fb", "\"a\\fb\""),
                Arguments.of("control character 0x01", "a" + (char) 0x01 + "b", "\"a\\u0001b\""),
                Arguments.of("control character 0x1f", "a" + (char) 0x1f + "b", "\"a\\u001fb\""),
                Arguments.of("solidus", "a/b", "\"a/b\""),
                Arguments.of("non-ASCII characters", "Grüße €", "\"Grüße €\""),
                Arguments.of("delete character", "a" + (char) 0x7f + "b", "\"a" + (char) 0x7f + "b\""),
                Arguments.of("line separator", "a" + (char) 0x2028 + "b", "\"a" + (char) 0x2028 + "b\""),
                Arguments.of("supplementary character", Character.toString(0x1F600),
                        "\"" + Character.toString(0x1F600) + "\""),
                Arguments.of("escaped object key", Map.of("quo\"ted", 1), "{\"quo\\\"ted\":1}"),
                Arguments.of("integer object key", Map.of(7, 1), "{\"7\":1}"),
                Arguments.of("enum object key", Map.of(DayOfWeek.MONDAY, 1), "{\"MONDAY\":1}"),
                Arguments.of("absent object key", absentKey(), "{\"null\":1}"),
                Arguments.of("null member", nullThenValue(), "{\"a\":null,\"b\":1}"),
                Arguments.of("long", Long.MAX_VALUE, "9223372036854775807"),
                Arguments.of("big decimal in exponent form", new BigDecimal("1E+3"), "1E+3"),
                Arguments.of("big decimal with trailing zero", new BigDecimal("12.50"), "12.50"),
                Arguments.of("big integer", new BigInteger("123456789012345678901234567890"),
                        "123456789012345678901234567890"),
                Arguments.of("empty object", Map.of(), "{}"),
                Arguments.of("empty array", List.of(), "[]"),
                Arguments.of("single-element array", List.of("x"), "[\"x\"]"),
                Arguments.of("nested structures", Map.of("keys", List.of(Map.of("kty", "EC"))),
                        "{\"keys\":[{\"kty\":\"EC\"}]}"));
    }

    /**
     * Every control character that has no two-character escape, with its six-character escape in
     * lower-case hex digits.
     *
     * @return {@code codePoint, expectedJson} pairs
     */
    static Stream<Arguments> controlCharacterCorpus() {
        return IntStream.range(0, 0x20)
                .filter(character -> "\b\t\n\f\r".indexOf(character) < 0)
                .mapToObj(character -> Arguments.of(character, "\"\\u00"
                        + LOWER_CASE_HEX.charAt(character >> 4) + LOWER_CASE_HEX.charAt(character & 0xf) + "\""));
    }

    private static Map<String, Object> finiteThenBroken() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("finite", 1);
        object.put("broken", Double.NaN);
        return object;
    }

    private static Map<String, @Nullable Object> nullThenValue() {
        Map<String, @Nullable Object> object = new LinkedHashMap<>();
        object.put("a", null);
        object.put("b", 1);
        return object;
    }

    private static Map<@Nullable Object, Object> absentKey() {
        Map<@Nullable Object, Object> object = new HashMap<>();
        object.put(null, 1);
        return object;
    }

    @ParameterizedTest(name = "{0} renders as {2}")
    @MethodSource("parityCorpus")
    @DisplayName("Should render every corpus value to exactly its expected bytes")
    void shouldRenderCorpusValue(String label, @Nullable Object value, String expectedJson) {
        assertEquals(expectedJson, gatewayJson.toJson(value), () -> "Rendering of: " + label);
    }

    @ParameterizedTest(name = "control character {0} renders as {1}")
    @MethodSource("controlCharacterCorpus")
    @DisplayName("Should escape a control character without a short escape in lower-case hex")
    void shouldEscapeControlCharacterInLowerCaseHex(int character, String expectedJson) {
        assertEquals(expectedJson, gatewayJson.toJson(Character.toString(character)));
    }

    @ParameterizedTest
    @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 1, maxSize = 20, count = 5)
    @DisplayName("Should pass any letter-only value through unescaped between quotes")
    void shouldPassLettersThroughUnescaped(String letters) {
        assertEquals("\"" + letters + "\"", gatewayJson.toJson(letters),
                "A value needing no escaping should be quoted but otherwise unchanged");
    }

    @Nested
    @DisplayName("Derived from a mapper configured otherwise")
    class DerivedFromConfiguredMapper {

        private final ObjectMapper configured = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT, SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        private final GatewayJson derived = new GatewayJson(configured);

        @ParameterizedTest(name = "{0} renders as {2}")
        @MethodSource("de.cuioss.sheriff.gateway.bff.runtime.GatewayJsonTest#parityCorpus")
        @DisplayName("Should render every corpus value to the same bytes")
        void shouldRenderCorpusValue(String label, @Nullable Object value, String expectedJson) {
            assertEquals(expectedJson, derived.toJson(value), () -> "Rendering of: " + label);
        }

        @Test
        @DisplayName("Should leave the mapper it is derived from unchanged")
        void shouldLeaveSourceMapperUnchanged() {
            derived.toJson(nullThenValue());

            assertAll("the source mapper keeps its own configuration",
                    () -> assertTrue(configured.isEnabled(SerializationFeature.INDENT_OUTPUT)),
                    () -> assertTrue(configured.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)),
                    () -> assertEquals("{" + System.lineSeparator() + "  \"b\" : 1" + System.lineSeparator() + "}",
                            configured.writeValueAsString(nullThenValue()),
                            "indented, and without the null member its inclusion rule drops"));
        }
    }
}
