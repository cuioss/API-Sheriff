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
package de.cuioss.sheriff.gateway.config.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CookieAllow}: the YAML shape decides the mode — the scalars {@code none} and
 * {@code all} are the two modes, a sequence is always a list of names — and {@link CookieAllow#admits}
 * answers per variant. The values are bound through a YAML mapper configured the way
 * {@code ConfigLoader} configures its own (snake-case property names, case-insensitive enums), so the
 * delegating creator is exercised on the path a boot takes.
 */
@EnableGeneratorController
@DisplayName("CookieAllow — the cookies.allow mode")
class CookieAllowTest {

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static CookieAllow bind(String allowYaml) throws JsonProcessingException {
        return MAPPER.readValue("allow: " + allowYaml + "\n", CookiesConfig.class).allow();
    }

    private static String cookieName() {
        return Generators.letterStrings(3, 12).next();
    }

    @Nested
    @DisplayName("Binding")
    class Binding {

        @Test
        @DisplayName("Should bind the scalar none to the none mode")
        void shouldBindNone() throws Exception {
            assertSame(CookieAllow.NONE, bind("none"));
        }

        @Test
        @DisplayName("Should bind the scalar all to the all mode")
        void shouldBindAll() throws Exception {
            assertSame(CookieAllow.ALL, bind("all"));
        }

        @Test
        @DisplayName("Should bind a sequence to the listed names, in order")
        void shouldBindSequenceInOrder() throws Exception {
            String first = cookieName();
            String second = cookieName() + "_2";

            CookieAllow allow = bind("[" + second + ", " + first + "]");

            assertEquals(new CookieAllow.Named(List.of(second, first)), allow);
        }

        @Test
        @DisplayName("Should bind [all] and [none] to the cookies named all and none, not to the modes")
        void shouldBindKeywordListsAsNames() {
            assertAll("a sequence is always names",
                    () -> assertEquals(new CookieAllow.Named(List.of("all")), bind("[all]")),
                    () -> assertEquals(new CookieAllow.Named(List.of("none")), bind("[none]")));
        }

        @Test
        @DisplayName("Should bind the full block with its snake-case keys")
        void shouldBindTheFullBlock() throws Exception {
            CookiesConfig cookies = MAPPER.readValue("""
                    allow: [theme]
                    same_origin_check: false
                    additional_origins: [https://app.example.com]
                    """, CookiesConfig.class);

            assertEquals(new CookiesConfig(new CookieAllow.Named(List.of("theme")), false,
                    List.of("https://app.example.com")), cookies);
        }

        @ParameterizedTest(name = "allow: {0}")
        @ValueSource(strings = {"some", "NONE", "All", "''", "1", "true", "{a: b}", "[1]", "[[a]]"})
        @DisplayName("Should refuse any other scalar, and a sequence holding a non-name")
        void shouldRefuseOtherValues(String value) {
            assertThrows(JsonProcessingException.class, () -> bind(value));
        }
    }

    @Nested
    @DisplayName("admits")
    class Admits {

        @Test
        @DisplayName("none admits no cookie")
        void noneAdmitsNothing() {
            assertFalse(CookieAllow.NONE.admits(cookieName()));
        }

        @Test
        @DisplayName("all admits every cookie")
        void allAdmitsEverything() {
            assertTrue(CookieAllow.ALL.admits(cookieName()));
        }

        @Test
        @DisplayName("a list admits exactly its names, case-sensitively")
        void namedAdmitsExactlyItsNames() {
            String listed = cookieName().toLowerCase(Locale.ROOT);
            CookieAllow allow = new CookieAllow.Named(List.of(listed));

            assertAll("exact, case-sensitive membership",
                    () -> assertTrue(allow.admits(listed)),
                    () -> assertFalse(allow.admits(listed.toUpperCase(Locale.ROOT))),
                    () -> assertFalse(allow.admits(listed + "_other")),
                    () -> assertFalse(allow.admits("")));
        }

        @Test
        @DisplayName("a list is copied and unmodifiable")
        void namedCopiesItsList() {
            List<String> source = new ArrayList<>(List.of(cookieName()));
            CookieAllow.Named allow = new CookieAllow.Named(source);
            source.add(cookieName());
            List<String> names = allow.names();

            assertAll(
                    () -> assertEquals(1, names.size(), "a later change to the source does not reach it"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> names.add("x")));
        }
    }
}
