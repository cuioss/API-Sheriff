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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link RedirectStage}: the configured status passes through verbatim, the configured
 * {@code location} is written verbatim (never context-path-prefixed), and the raw query is appended
 * only under {@code keep_query}, joined with {@code ?} or {@code &} as the location requires.
 */
@EnableGeneratorController
@DisplayName("RedirectStage")
class RedirectStageTest {

    private final RedirectStage stage = new RedirectStage();

    private static String gatewayPath() {
        return "/" + Generators.letterStrings(3, 12).next();
    }

    private static String rawQuery() {
        return Generators.letterStrings(2, 8).next() + "=" + Generators.letterStrings(1, 8).next();
    }

    private static RedirectConfig redirect(String location, int status, boolean keepQuery) {
        return RedirectConfig.builder().location(location).status(status).keepQuery(keepQuery).build();
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    @DisplayName("Should pass every admitted redirect status through verbatim")
    void shouldPassStatusThroughVerbatim(int status) {
        String location = gatewayPath();

        RedirectStage.Answer answer = stage.answer(redirect(location, status, false), null);

        assertAll(
                () -> assertEquals(status, answer.status(), "status should be the configured one"),
                () -> assertEquals(location, answer.location(), "location should be written verbatim"));
    }

    @Test
    @DisplayName("Should refuse a missing redirect action")
    void shouldRefuseMissingRedirect() {
        String query = rawQuery();

        assertThrows(NullPointerException.class, () -> stage.answer(null, query),
                "a null redirect action should be refused");
    }

    @Nested
    @DisplayName("keep_query")
    class KeepQuery {

        @Test
        @DisplayName("Should drop the request query when keep_query is off")
        void shouldDropQueryWhenKeepQueryOff() {
            String location = gatewayPath();

            RedirectStage.Answer answer = stage.answer(redirect(location, 302, false), rawQuery());

            assertEquals(location, answer.location(), "the query should not be appended without keep_query");
        }

        @Test
        @DisplayName("Should append the raw query with '?' when keep_query is on")
        void shouldAppendQueryWithQuestionMark() {
            String location = gatewayPath();
            String query = rawQuery();

            RedirectStage.Answer answer = stage.answer(redirect(location, 307, true), query);

            assertEquals(location + "?" + query, answer.location(), "the raw query should follow a '?'");
        }

        @Test
        @DisplayName("Should join the raw query with '&' when the location already carries a query")
        void shouldJoinQueryWithAmpersandOnExistingQuery() {
            String location = gatewayPath() + "?" + rawQuery();
            String query = rawQuery();

            RedirectStage.Answer answer = stage.answer(redirect(location, 308, true), query);

            assertEquals(location + "&" + query, answer.location(), "the raw query should follow a '&'");
        }

        @Test
        @DisplayName("Should append directly when the location already ends with a query delimiter")
        void shouldAppendDirectlyAfterTrailingDelimiter() {
            String bareQuery = gatewayPath() + "?";
            String openParameter = gatewayPath() + "?" + rawQuery() + "&";
            String query = rawQuery();

            RedirectStage.Answer afterQuestionMark = stage.answer(redirect(bareQuery, 303, true), query);
            RedirectStage.Answer afterAmpersand = stage.answer(redirect(openParameter, 303, true), query);

            assertAll(
                    () -> assertEquals(bareQuery + query, afterQuestionMark.location(),
                            "no second '?' should be inserted"),
                    () -> assertEquals(openParameter + query, afterAmpersand.location(),
                            "no second '&' should be inserted"));
        }

        @Test
        @DisplayName("Should leave the location untouched when keep_query is on but the request has no query")
        void shouldLeaveLocationWithoutRequestQuery() {
            String location = gatewayPath();

            RedirectStage.Answer absent = stage.answer(redirect(location, 301, true), null);
            RedirectStage.Answer empty = stage.answer(redirect(location, 301, true), "");

            assertAll(
                    () -> assertEquals(location, absent.location(), "an absent query should add nothing"),
                    () -> assertEquals(location, empty.location(), "an empty query should add no bare '?'"));
        }
    }
}
