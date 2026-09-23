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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard over {@link RootPaths#normalize(String)} — the single
 * Java-side owner of the context-path normalisation rule.
 * <p>
 * Every value below is written as an exact literal rather than generated: each one is a
 * spec-defined boundary of the rule ({@code "/"} collapsing to the empty string, a trailing run
 * being removed whole), and the one value that matters is precisely the one a generator would
 * replace with an arbitrary string.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Root-path normalisation")
class RootPathsTest {

    /** The endpoint callers append beneath a normalised root, used by the composition guards. */
    private static final String ENDPOINT = "/health/ready";

    @Nested
    @DisplayName("Root-mounted interfaces")
    class RootMountedTests {

        @Test
        @DisplayName("'/' collapses to the empty string")
        void slashCollapsesToEmptyString() {
            assertEquals("", RootPaths.normalize("/"),
                    "A root-mounted path should normalise to the empty string");
        }

        @Test
        @DisplayName("a run of slashes collapses to the empty string too")
        void repeatedSlashesCollapseToEmptyString() {
            assertAll("Repeated-slash roots",
                    () -> assertEquals("", RootPaths.normalize("//"),
                            "Two slashes should normalise to the empty string"),
                    () -> assertEquals("", RootPaths.normalize("///"),
                            "Three slashes should normalise to the empty string"));
        }

        @Test
        @DisplayName("composing onto a normalised root yields no doubled separator")
        void composedEndpointHasNoDoubledSeparator() {
            assertEquals(ENDPOINT, RootPaths.normalize("/") + ENDPOINT,
                    "A root-mounted gateway should serve the endpoint unprefixed");
        }
    }

    @Nested
    @DisplayName("Paths beneath a context root")
    class ContextRootTests {

        @Test
        @DisplayName("a path with no trailing slash is returned unchanged")
        void pathWithoutTrailingSlashIsUnchanged() {
            assertEquals("/q", RootPaths.normalize("/q"),
                    "A path with no trailing slash should be left alone");
        }

        @Test
        @DisplayName("a trailing slash is removed")
        void trailingSlashIsRemoved() {
            assertAll("Trailing slash",
                    () -> assertEquals("/q", RootPaths.normalize("/q/"),
                            "The trailing slash should be removed"),
                    () -> assertEquals("/q" + ENDPOINT, RootPaths.normalize("/q/") + ENDPOINT,
                            "Composing should yield a single separator"));
        }

        @Test
        @DisplayName("a multi-segment path keeps its interior separators")
        void multiSegmentPathKeepsItsInteriorSeparators() {
            assertAll("Multi-segment paths",
                    () -> assertEquals("/ops/manage", RootPaths.normalize("/ops/manage"),
                            "Interior separators should be preserved"),
                    () -> assertEquals("/ops/manage", RootPaths.normalize("/ops/manage/"),
                            "Only the trailing separator should be removed"));
        }
    }

    @Nested
    @DisplayName("Corner cases")
    class CornerCaseTests {

        @Test
        @DisplayName("the empty string is returned unchanged")
        void emptyStringIsUnchanged() {
            assertEquals("", RootPaths.normalize(""),
                    "An already-empty path should be left alone");
        }

        @Test
        @DisplayName("the ENTIRE trailing run is removed, not just the last separator")
        void entireTrailingRunIsRemoved() {
            assertEquals("/ops", RootPaths.normalize("/ops//"),
                    "Every trailing separator should be removed");
        }

        @Test
        @DisplayName("normalising an already-normalised path changes nothing")
        void normalizeIsIdempotent() {
            String once = RootPaths.normalize("/q/");

            assertEquals(once, RootPaths.normalize(once),
                    "A second pass should produce the same value");
        }
    }
}
