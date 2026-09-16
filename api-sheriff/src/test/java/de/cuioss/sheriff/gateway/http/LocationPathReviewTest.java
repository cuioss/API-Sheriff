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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The path literals below ARE the contract under test — a percent-encoding, a segment boundary and a
 * leading slash run decide whether a {@code Location} path is emitted — so they are spelled out
 * rather than generated.
 */
@DisplayName("LocationPathReview — the emitted-Location path review (GW-13)")
class LocationPathReviewTest {

    @Nested
    @DisplayName("admitted paths")
    class Admitted {

        @ParameterizedTest(name = "admits {0}")
        @ValueSource(strings = {
                "/",
                "/login",
                "/api/v2/orders/4711",
                "/static/js/main.4f3a2b1c.js",
                "/.well-known/openid-configuration",
                "/a...b/..c/.d",
                "/a/%2ex",
                "/a/%2Dx",
                "/a/%5Bx%5D",
                "/files/report%202024.pdf",
                "/search/caf%C3%A9",
                "/etc/config",
                "/users/profile"})
        @DisplayName("admits an ordinary gateway path, including encodings that are not separators")
        void admitsOrdinaryPaths(String path) {
            assertTrue(LocationPathReview.refusalReason(path).isEmpty(),
                    () -> "'" + path + "' should be admitted, got: " + LocationPathReview.refusalReason(path));
        }
    }

    /**
     * The four tests {@code refusalReason} keeps as its own, each named by the message it reports.
     * They are the tests the {@code cui-http} pipeline provably does not carry — see
     * {@link CuiHttpCoverageBoundary}, which is the control proving these are not redundant.
     */
    @Nested
    @DisplayName("locally decided refusals")
    class LocalRefusals {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"//evil.example", "//evil.example/path", "//"})
        @DisplayName("refuses a scheme-relative leading '//'")
        void refusesSchemeRelative(String path) {
            assertTrue(LocationPathReview.refusalReason(path).orElse("").contains("scheme-relative"),
                    () -> "expected a scheme-relative refusal for '" + path + "'");
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "/%5Cattacker.com", "/%5cattacker.com", "/%2f%2fattacker.com", "/%2F%2Fattacker.com",
                "/a/x%2fy", "/a/%5c%2Fx", "/svc/v1/a%2f..%2f..%2fadmin"})
        @DisplayName("refuses a percent-encoded '/' or '\\' in either case spelling")
        void refusesEncodedSeparator(String path) {
            assertTrue(LocationPathReview.refusalReason(path).orElse("").contains("percent-encoded"),
                    () -> "expected an encoded-separator refusal for '" + path + "'");
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "/a/../b", "/a/./b", "/a/..", "/a/.", "/%2e%2e/b", "/%2E/b", "/a/.%2e/b", "/svc/v1/items/.."})
        @DisplayName("refuses a dot segment in either its literal or its %2e spelling")
        void refusesDotSegment(String path) {
            assertTrue(LocationPathReview.refusalReason(path).orElse("").contains("dot-segment"),
                    () -> "expected a dot-segment refusal for '" + path + "'");
        }

        @Test
        @DisplayName("treats only '.' and '..' as dot segments, not every segment containing a dot")
        void doesNotRefuseSegmentsThatMerelyContainDots() {
            assertAll(
                    () -> assertTrue(LocationPathReview.refusalReason("/a/...").isEmpty(), "'...' is not a dot segment"),
                    () -> assertTrue(LocationPathReview.refusalReason("/a/..b").isEmpty(), "'..b' is not a dot segment"),
                    () -> assertTrue(LocationPathReview.refusalReason("/a/b..").isEmpty(), "'b..' is not a dot segment"),
                    () -> assertTrue(LocationPathReview.refusalReason("/a/%2eb").isEmpty(),
                            "'%2eb' decodes to '.b', which is not a dot segment"));
        }

        @ParameterizedTest(name = "detects an encoded separator in {0}")
        @ValueSource(strings = {"?next=%2Fhome", "#frag%5Cx", "/a%2Fb", "%5c"})
        @DisplayName("exposes the encoded-separator test on its own, for a caller scoping it wider than a path")
        void exposesEncodedSeparatorTest(String value) {
            assertTrue(LocationPathReview.carriesEncodedSeparator(value));
        }

        @ParameterizedTest(name = "finds no encoded separator in {0}")
        @ValueSource(strings = {"?next=/home", "/a/%2ex", "/a/%5Bx%5D", "/a/%2Dx", "/a/%252fb"})
        @DisplayName("does not read a neighbouring encoding as a separator")
        void doesNotMistakeNeighbouringEncodings(String value) {
            assertFalse(LocationPathReview.carriesEncodedSeparator(value),
                    () -> "'" + value + "' carries no %2f or %5c — the test must be on the separator, not on a"
                            + " '%2' or '%5' prefix, and not on a double-encoded octet");
        }
    }

    /**
     * The classes delegated to {@code cui-http}. Every literal here passes all four locally decided
     * tests above — {@code %252f} is not the {@code %2f} substring the separator test looks for, and
     * {@code %252e%252e} is not the {@code %2e} spelling the dot-segment test recognizes — so each row
     * is refused by the library and by nothing else. Before the delegation they were all admitted.
     */
    @Nested
    @DisplayName("refusals delegated to the cui-http URL_PATH pipeline")
    class DelegatedRefusals {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "/%252F%252Fevil.example",
                "/%252f%252fevil.example",
                "/a/%252f..%252fadmin",
                "/svc/v1/%252e%252e/login",
                "/a%00b",
                "/a%01b",
                "/a%c0%afb",
                "/a%ef%bc%8fb"})
        @DisplayName("refuses a class the local tests cannot see — double encoding above all")
        void refusesDelegatedClasses(String path) {
            assertAll(
                    () -> assertFalse(LocationPathReview.carriesEncodedSeparator(path),
                            () -> "'" + path + "' must not be caught by the local separator test, or the row"
                                    + " would not prove the delegation"),
                    () -> assertTrue(LocationPathReview.refusalReason(path).orElse("").contains("cui-http"),
                            () -> "expected a delegated refusal for '" + path + "', got: "
                                    + LocationPathReview.refusalReason(path)));
        }

        @Test
        @DisplayName("refuses a path above the policy's 1024-character cap and admits one at it")
        void refusesOverlongPath() {
            String atCap = "/" + "a".repeat(1023);
            String overCap = "/" + "a".repeat(1024);

            assertAll(
                    () -> assertTrue(LocationPathReview.refusalReason(atCap).isEmpty(),
                            "a 1024-character path is within the strict policy's cap"),
                    () -> assertTrue(LocationPathReview.refusalReason(overCap).orElse("").contains("cui-http"),
                            "a 1025-character path is refused by the length stage"));
        }
    }

    /**
     * The control that makes {@link LocalRefusals} non-vacuous, and the executable record of why this
     * class does not simply call the library and stop.
     * <p>
     * The {@code cui-http} {@code URL_PATH} pipeline is a <em>canonicalizer</em>: for each spelling
     * below it returns a fixed value rather than throwing. A caller that judges an <strong>emitted</strong>
     * value cannot use that fixed value — the browser receives the original — so delegating these would
     * admit them. Should a future {@code cui-http} start refusing one, this test goes red and the
     * matching local test can be retired deliberately instead of drifting into redundancy unnoticed.
     */
    @Nested
    @DisplayName("cui-http coverage boundary")
    class CuiHttpCoverageBoundary {

        private final HttpSecurityValidator pipeline = PipelineFactory.createUrlPathPipeline(
                SecurityConfiguration.strict(), new SecurityEventCounter());

        @ParameterizedTest(name = "library normalizes {0}, review refuses it")
        @ValueSource(strings = {
                "/%5Cattacker.com",
                "/%2f%2fattacker.com",
                "/a/x%2fy",
                "/a/./b",
                "/%2E/b",
                "/svc/v1/..",
                "//evil.example"})
        @DisplayName("the library admits every spelling the local tests refuse")
        void libraryAdmitsWhatTheLocalTestsRefuse(String path) {
            assertAll(
                    () -> assertDoesNotThrow(() -> pipeline.validate(path),
                            () -> "the cui-http URL_PATH pipeline normalizes '" + path + "' instead of refusing"
                                    + " it — that is why this review keeps its own test for the spelling"),
                    () -> assertTrue(LocationPathReview.refusalReason(path).isPresent(),
                            () -> "'" + path + "' must still be refused by the review"));
        }

        @Test
        @DisplayName("the library's canonical output is never what the review would emit")
        void libraryOutputDiffersFromTheReviewedValue() {
            Optional<String> canonical = assertDoesNotThrow(() -> pipeline.validate("/%5Cattacker.com"));

            assertAll(
                    () -> assertTrue(canonical.isPresent(), "the pipeline returns a canonical value"),
                    () -> assertTrue(canonical.orElseThrow().contains("\\"),
                            () -> "the canonical form decodes %5C to a literal backslash (" + canonical
                                    + "); emitting it would hand the browser the very value the review refuses"));
        }
    }
}
