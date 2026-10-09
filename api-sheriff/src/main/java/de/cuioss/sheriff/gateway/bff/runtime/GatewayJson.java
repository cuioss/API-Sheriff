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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerFactory;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.CollectionLikeType;
import com.fasterxml.jackson.databind.type.MapLikeType;
import com.fasterxml.jackson.databind.type.ReferenceType;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * Renders the small JSON bodies the gateway authors itself — the user-info view, the step-up
 * {@code 401} problem, the client JWKS document and the RFC 9457 problem body of the edge — through
 * the Jackson 2 serializer the platform already ships (ADR-0062).
 * <p>
 * Every one of those bodies is an insertion-ordered {@link Map} whose leaves are {@link String},
 * {@link Number}, {@link Boolean}, {@link Collection}, nested {@link Map} or {@code null}, so the
 * platform serializer over maps is the whole answer and no further JSON library is needed.
 * <p>
 * <strong>The injected mapper is never changed.</strong> The Quarkus-managed {@link ObjectMapper}
 * also serves the JAX-RS layer, so a setting applied to it would change response bodies this class
 * does not own. Every setting below is applied to a private copy of that mapper and to the one
 * {@link ObjectWriter} derived from it, which all callers share.
 * <p>
 * <strong>The rendering is fixed, not inherited.</strong> A body rendered here is the same whatever
 * the deployment configures on the shared mapper:
 * <ul>
 *   <li>compact output, members in the map's own iteration order, {@code null} members written as
 *       {@code null};</li>
 *   <li>a non-finite {@link Double} or {@link Float} is written as {@code null}, because RFC 8259
 *       defines no token for it; every other {@link Number} is written as its {@code toString()};</li>
 *   <li>a value that is none of the shapes above is written as its quoted
 *       {@link String#valueOf(Object) string form}, and a map key likewise;</li>
 *   <li>a control character without a short escape is written as {@code \}{@code u00xx} with
 *       lower-case hex digits; every character from {@code U+0020} on other than {@code "} and
 *       {@code \} is written as it is.</li>
 * </ul>
 * Usage:
 * <pre>{@code
 * String body = gatewayJson.toJson(Map.of("status", 401));
 * }</pre>
 * <p>
 * <strong>Thread safety.</strong> Immutable after construction; safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
// @Singleton and not @ApplicationScoped: the class is final and immutable, so it needs no client proxy.
@Singleton
public final class GatewayJson {

    private static final int FIRST_UNESCAPED_CHARACTER = 0x20;

    private final ObjectWriter writer;

    /**
     * Derives the shared writer from a private copy of the given mapper.
     *
     * @param objectMapper the Quarkus-managed mapper; it is copied and left unchanged
     */
    public GatewayJson(ObjectMapper objectMapper) {
        ObjectMapper derived = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        // No annotation, inclusion rule, default typing or module-registered serializer configured on
        // the shared mapper may reach a gateway-authored body. The serializer factory therefore
        // starts from the library's own instance, not from the shared mapper's.
        derived.setAnnotationIntrospector(AnnotationIntrospector.nopInstance());
        derived.setDefaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS));
        derived.deactivateDefaultTyping();
        // Every serialization feature starts from the library default, so a feature switched on the
        // shared mapper — omitting null map values, for one — cannot reach a gateway-authored body.
        for (SerializationFeature feature : SerializationFeature.values()) {
            derived.configure(feature, feature.enabledByDefault());
        }
        derived.setSerializerFactory(BeanSerializerFactory.instance
                .withAdditionalSerializers(new ValueSerializers())
                .withAdditionalKeySerializers(new KeySerializers()));
        derived.getSerializerProvider().setNullKeySerializer(KeySerializer.INSTANCE);
        this.writer = derived.writer()
                .without(SerializationFeature.INDENT_OUTPUT,
                        SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                        SerializationFeature.WRAP_ROOT_VALUE,
                        SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED)
                .with(JsonWriteFeature.QUOTE_FIELD_NAMES)
                .without(JsonWriteFeature.ESCAPE_NON_ASCII)
                .with(new LowerCaseControlEscapes());
    }

    /**
     * Renders a gateway-authored value graph to a compact JSON string.
     *
     * @param value the value to render (a {@link Map}, {@link Collection}, {@link String},
     *              {@link Number}, {@link Boolean}, or {@code null})
     * @return the compact JSON rendering
     * @throws UncheckedIOException when the serializer refuses the value graph
     */
    public String toJson(@Nullable Object value) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Fixed text: the value graph may carry identity claims and must not reach a message.
            throw new UncheckedIOException("Rendering a gateway-authored JSON body failed", e);
        }
    }

    /**
     * Selects the serializer of every value that is not a {@link Map} or a {@link Collection}: the
     * platform default for {@link String} and {@link Boolean}, {@link NumberSerializer} for a
     * {@link Number}, and {@link StringFormSerializer} for everything else.
     */
    private static final class ValueSerializers extends Serializers.Base {

        @Override
        public @Nullable JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type,
                BeanDescription beanDesc) {
            Class<?> raw = type.getRawClass();
            if (raw == String.class || raw == Boolean.class) {
                return null;
            }
            if (Number.class.isAssignableFrom(raw)) {
                return NumberSerializer.INSTANCE;
            }
            return StringFormSerializer.INSTANCE;
        }

        @Override
        public JsonSerializer<?> findReferenceSerializer(SerializationConfig config, ReferenceType type,
                BeanDescription beanDesc, TypeSerializer contentTypeSerializer,
                JsonSerializer<Object> contentValueSerializer) {
            return StringFormSerializer.INSTANCE;
        }

        @Override
        public JsonSerializer<?> findArraySerializer(SerializationConfig config, ArrayType type,
                BeanDescription beanDesc, TypeSerializer elementTypeSerializer,
                JsonSerializer<Object> elementValueSerializer) {
            return StringFormSerializer.INSTANCE;
        }

        @Override
        public JsonSerializer<?> findCollectionLikeSerializer(SerializationConfig config, CollectionLikeType type,
                BeanDescription beanDesc, TypeSerializer elementTypeSerializer,
                JsonSerializer<Object> elementValueSerializer) {
            return StringFormSerializer.INSTANCE;
        }

        @Override
        public JsonSerializer<?> findMapLikeSerializer(SerializationConfig config, MapLikeType type,
                BeanDescription beanDesc, JsonSerializer<Object> keySerializer,
                TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) {
            return StringFormSerializer.INSTANCE;
        }
    }

    /** Selects {@link KeySerializer} for every map key, whatever its type. */
    private static final class KeySerializers extends Serializers.Base {

        @Override
        public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type,
                BeanDescription beanDesc) {
            return KeySerializer.INSTANCE;
        }
    }

    /** Writes a map key — an absent one included — as its string form. */
    private static final class KeySerializer extends JsonSerializer<Object> {

        private static final KeySerializer INSTANCE = new KeySerializer();

        @Override
        public void serialize(@Nullable Object value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeFieldName(String.valueOf(value));
        }
    }

    /** Writes a value of a type the gateway-authored bodies do not carry as its quoted string form. */
    private static final class StringFormSerializer extends JsonSerializer<Object> {

        private static final StringFormSerializer INSTANCE = new StringFormSerializer();

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeString(String.valueOf(value));
        }
    }

    /**
     * Writes a number as its {@code toString()}, and a non-finite floating-point value as
     * {@code null}: RFC 8259 defines no NaN or Infinity token, so writing one would make the body
     * invalid JSON.
     */
    private static final class NumberSerializer extends JsonSerializer<Number> {

        private static final NumberSerializer INSTANCE = new NumberSerializer();

        @Override
        public void serialize(Number value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            if ((value instanceof Double || value instanceof Float) && !Double.isFinite(value.doubleValue())) {
                generator.writeNull();
                return;
            }
            generator.writeRawValue(value.toString());
        }
    }

    /**
     * The JSON escapes of the platform serializer, with one difference: a control character that has
     * no short escape is written with lower-case hex digits.
     */
    private static final class LowerCaseControlEscapes extends CharacterEscapes {

        private static final long serialVersionUID = 1L;

        private final int[] asciiEscapes;
        private final SerializedString[] controlSequences;

        LowerCaseControlEscapes() {
            asciiEscapes = CharacterEscapes.standardAsciiEscapesForJSON();
            controlSequences = new SerializedString[FIRST_UNESCAPED_CHARACTER];
            for (int character = 0; character < FIRST_UNESCAPED_CHARACTER; character++) {
                if (asciiEscapes[character] == CharacterEscapes.ESCAPE_STANDARD) {
                    asciiEscapes[character] = CharacterEscapes.ESCAPE_CUSTOM;
                    controlSequences[character] = new SerializedString("\\u%04x".formatted(character));
                }
            }
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes.clone();
        }

        @Override
        public @Nullable SerializableString getEscapeSequence(int character) {
            return character >= 0 && character < FIRST_UNESCAPED_CHARACTER ? controlSequences[character] : null;
        }
    }
}
