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
package de.cuioss.sheriff.gateway.bff.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JsonWriter}, the minimal dependency-free serializer for the curated user-info
 * disclosure. The focus is that a non-finite floating-point leaf ({@link Double#NaN},
 * {@code ±Infinity}) is normalized to {@code null} so the gateway-controlled body is always valid
 * JSON (RFC 8259 defines no {@code NaN} / {@code Infinity} token), alongside the ordinary leaf shapes.
 */
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

        List<Object> array = new ArrayList<>();
        array.add(1);
        array.add(Double.POSITIVE_INFINITY);
        assertEquals("[1,null]", JsonWriter.toJson(array));
    }
}
