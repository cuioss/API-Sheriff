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
package de.cuioss.sheriff.gateway.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The slash runs below ARE the contract under test — how many slashes a trim removes decides which
 * path a join or a split yields — so they are spelled out rather than generated. Every table covers
 * the same boundary cases: the empty string, {@code /}, {@code //}, a value without a slash, and
 * interior slashes, which no operation touches.
 */
@DisplayName("PathSlashes — the shared slash trimming of path joins and splits (#327)")
class PathSlashesTest {

    @Nested
    @DisplayName("stripLeading")
    class StripLeading {

        @ParameterizedTest(name = "''{0}'' -> ''{1}''")
        @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
                "\"\"|\"\"",
                "/|\"\"",
                "//|\"\"",
                "abc|abc",
                "/abc|abc",
                "///abc|abc",
                "/a//b/|a//b/",
                "a/b|a/b"})
        @DisplayName("removes the whole leading run and nothing else")
        void stripsWholeLeadingRun(String value, String expected) {
            // Act
            String stripped = PathSlashes.stripLeading(value);

            // Assert
            assertEquals(expected, stripped);
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> PathSlashes.stripLeading(null));
        }
    }

    @Nested
    @DisplayName("stripTrailing")
    class StripTrailing {

        @ParameterizedTest(name = "''{0}'' -> ''{1}''")
        @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
                "\"\"|\"\"",
                "/|\"\"",
                "//|\"\"",
                "abc|abc",
                "/abc/|/abc",
                "/a//|/a",
                "/a//b//|/a//b",
                "a/b|a/b"})
        @DisplayName("removes the whole trailing run and nothing else")
        void stripsWholeTrailingRun(String value, String expected) {
            // Act
            String stripped = PathSlashes.stripTrailing(value);

            // Assert
            assertEquals(expected, stripped);
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> PathSlashes.stripTrailing(null));
        }
    }

    @Nested
    @DisplayName("stripOneTrailing")
    class StripOneTrailing {

        @ParameterizedTest(name = "''{0}'' -> ''{1}''")
        @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
                "\"\"|\"\"",
                "/|\"\"",
                "//|/",
                "abc|abc",
                "/abc/|/abc",
                "/a//|/a/",
                "/a//b//|/a//b/",
                "a/b|a/b"})
        @DisplayName("removes at most one trailing slash and nothing else")
        void stripsAtMostOneTrailingSlash(String value, String expected) {
            // Act
            String stripped = PathSlashes.stripOneTrailing(value);

            // Assert
            assertEquals(expected, stripped);
        }

        @Test
        @DisplayName("differs from stripTrailing on a trailing run: /a// keeps one slash")
        void differsFromStripTrailingOnRun() {
            // Arrange
            String value = "/a//";

            // Act
            String one = PathSlashes.stripOneTrailing(value);
            String all = PathSlashes.stripTrailing(value);

            // Assert
            assertEquals("/a/", one);
            assertEquals("/a", all);
            assertNotEquals(all, one, "the at-most-one variant must not collapse into strip-all");
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> PathSlashes.stripOneTrailing(null));
        }
    }
}
