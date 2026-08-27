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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link JsonWriter}, the minimal dependency-free serializer for the curated user-info
 * disclosure. The focus is that a non-finite floating-point leaf ({@link Double#NaN},
 * {@code ±Infinity}) is normalized to {@code null} so the gateway-controlled body is always valid
 * JSON (RFC 8259 defines no {@code NaN} / {@code Infinity} token), alongside the ordinary leaf shapes
 * and the RFC 8259 string-escaping contract every disclosed claim name and value passes through.
 */
@EnableGeneratorController
class JsonWriterTest {

    @Test
    @DisplayName("Should normalize a non-finite double to null so the body stays valid JSON")
    void shouldNormalizeNonFiniteDouble() {
        assertEquals("null", JsonWriter.toJson(Double.NaN));
        assertEquals("null", JsonWriter.toJson(Double.POSITIVE_INFINITY));
        assertEquals("null", JsonWriter.toJson(Double.NEGATIVE_INFINITY));
        assertEquals("null", JsonWriter.toJson(Float.NaN));
        assertEquals("null", JsonWriter.toJson(Float.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("Should serialize finite numbers, strings, booleans, and null unchanged")
    void shouldSerializeScalars() {
        assertEquals("1.5", JsonWriter.toJson(1.5d));
        assertEquals("42", JsonWriter.toJson(42));
        assertEquals("true", JsonWriter.toJson(Boolean.TRUE));
        assertEquals("null", JsonWriter.toJson(null));
        assertEquals("\"hi\"", JsonWriter.toJson("hi"));
    }

    @Test
    @DisplayName("Should normalize a non-finite double nested inside an object and array")
    void shouldNormalizeNonFiniteNested() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("finite", 1);
        object.put("broken", Double.NaN);

        assertEquals("{\"finite\":1,\"broken\":null}", JsonWriter.toJson(object));

        List<Object> array = new ArrayList<>(List.of(
                1,
                Double.POSITIVE_INFINITY));
        assertEquals("[1,null]", JsonWriter.toJson(array));
    }

    @Test
    @DisplayName("Should render a value of no JSON-native shape through its string form")
    void shouldRenderUnmodelledValueAsString() {
        assertEquals("\"MONDAY\"", JsonWriter.toJson(DayOfWeek.MONDAY),
                "A leaf outside the modelled shapes should disclose as its quoted string form");
    }

    @Nested
    @DisplayName("RFC 8259 string escaping")
    class StringEscaping {

        /**
         * The escape table is fixed by RFC 8259 section 7, so each raw character and its
         * two-character escape are spec-defined literals rather than generated data.
         */
        static Stream<Arguments> escapeTable() {
            return Stream.of(
                    Arguments.of("quotation mark", "a\"b", "\"a\\\"b\""),
                    Arguments.of("reverse solidus", "a\\b", "\"a\\\\b\""),
                    Arguments.of("line feed", "a\nb", "\"a\\nb\""),
                    Arguments.of("carriage return", "a\rb", "\"a\\rb\""),
                    Arguments.of("tab", "a\tb", "\"a\\tb\""),
                    Arguments.of("backspace", "a\bb", "\"a\\bb\""),
                    Arguments.of("form feed", "a\fb", "\"a\\fb\""));
        }

        @ParameterizedTest(name = "{0} is emitted as its two-character escape")
        @MethodSource("escapeTable")
        @DisplayName("Should emit the RFC 8259 two-character escape for every escapable character")
        void shouldEmitTwoCharacterEscape(String label, String raw, String expectedJson) {
            assertEquals(expectedJson, JsonWriter.toJson(raw),
                    () -> "The " + label + " should be emitted as its RFC 8259 escape");
        }

        @Test
        @DisplayName("Should emit a \\u escape for a control character with no dedicated escape")
        void shouldEmitUnicodeEscapeForControlCharacter() {
            String withControlCharacter = "a" + (char) 0x01 + "b";

            assertEquals("\"a\\u0001b\"", JsonWriter.toJson(withControlCharacter),
                    "A control character below 0x20 should be emitted as a \\u escape");
        }

        @Test
        @DisplayName("Should escape a claim name used as an object key")
        void shouldEscapeObjectKey() {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("quo\"ted", 1);

            assertEquals("{\"quo\\\"ted\":1}", JsonWriter.toJson(claims),
                    "An object key should pass through the same escaping as a value");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 1, maxSize = 20, count = 5)
        @DisplayName("Should pass any letter-only value through unescaped between quotes")
        void shouldPassLettersThroughUnescaped(String letters) {
            assertEquals("\"" + letters + "\"", JsonWriter.toJson(letters),
                    "A value needing no escaping should be quoted but otherwise unchanged");
        }
    }
}
