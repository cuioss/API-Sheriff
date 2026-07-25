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

import java.util.Collection;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A minimal, dependency-free JSON serializer for the curated user-info disclosure the
 * {@link BffRuntime} renders (D11). It serializes only the value shapes that disclosure produces —
 * an insertion-ordered {@link Map} of allowlisted claims and session metadata whose leaves are
 * {@link String}, {@link Number}, {@link Boolean}, {@link Collection}, nested {@link Map}, or
 * {@code null} — with RFC 8259 string escaping. It is deliberately not a general-purpose JSON
 * library: the response body is a small, gateway-controlled structure, and keeping the writer here
 * avoids a runtime JSON dependency on the data-plane edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * Serializes a gateway-controlled value graph to a compact JSON string.
     *
     * @param value the value to serialize (a {@link Map}, {@link Collection}, {@link String},
     *              {@link Number}, {@link Boolean}, or {@code null})
     * @return the compact JSON rendering
     */
    static String toJson(@Nullable Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, @Nullable Object value) {
        switch (value) {
            case null -> out.append("null");
            case String string -> writeString(out, string);
            case Boolean bool -> out.append(bool.booleanValue());
            case Number number -> out.append(number);
            case Map<?, ?> map -> writeObject(out, map);
            case Collection<?> collection -> writeArray(out, collection);
            default -> writeString(out, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            write(out, entry.getValue());
            first = false;
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Collection<?> collection) {
        out.append('[');
        boolean first = true;
        for (Object element : collection) {
            if (!first) {
                out.append(',');
            }
            write(out, element);
            first = false;
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) {
                        out.append("\\u%04x".formatted((int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }
}
