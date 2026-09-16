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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link RedirectStage}: the configured status passes through verbatim, the configured
 * {@code location} is written verbatim (never context-path-prefixed), the raw query is appended
 * only under {@code keep_query} joined with {@code ?} or {@code &} as the location requires, and the
 * answer is marked uncacheable exactly when the route is effectively authenticated or the response
 * carries a {@code Set-Cookie}.
 */
@EnableGeneratorController
@DisplayName("RedirectStage")
class RedirectStageTest {

    /** The posture of a public redirect route: no authentication requirement. */
    private static final AuthConfig PUBLIC_AUTH = AuthConfig.builder().require(Require.NONE).build();

    private final RedirectStage stage = new RedirectStage();

    private static String gatewayPath() {
        return "/" + Generators.letterStrings(3, 12).next();
    }

    private static String rawQuery() {
        return Generators.letterStrings(2, 8).next() + "=" + Generators.letterStrings(1, 8).next();
    }

    private static String setCookie() {
        return "sid=" + Generators.letterStrings(8, 24).next() + "; HttpOnly; Secure; SameSite=Lax";
    }

    private static RedirectConfig redirect(String location, int status, boolean keepQuery) {
        return RedirectConfig.builder().location(location).status(status).keepQuery(keepQuery).build();
    }

    /**
     * The status/location half of the contract, asserted against a public route with no accumulated
     * cookie so the cacheability verdict never colours these cases.
     */
    private RedirectStage.Answer answer(RedirectConfig redirect, @Nullable String rawQuery) {
        return stage.answer(redirect, rawQuery, PUBLIC_AUTH, List.of());
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    @DisplayName("Should pass every admitted redirect status through verbatim")
    void shouldPassStatusThroughVerbatim(int status) {
        String location = gatewayPath();

        RedirectStage.Answer answer = answer(redirect(location, status, false), null);

        assertAll(
                () -> assertEquals(status, answer.status(), "status should be the configured one"),
                () -> assertEquals(location, answer.location(), "location should be written verbatim"));
    }

    @Test
    @DisplayName("Should refuse a missing redirect action, auth posture or Set-Cookie list")
    void shouldRefuseMissingArguments() {
        String query = rawQuery();
        RedirectConfig redirect = redirect(gatewayPath(), 302, false);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> stage.answer(null, query, PUBLIC_AUTH, List.of()),
                        "a null redirect action should be refused"),
                () -> assertThrows(NullPointerException.class,
                        () -> stage.answer(redirect, query, null, List.of()),
                        "a null effective auth posture should be refused"),
                () -> assertThrows(NullPointerException.class,
                        () -> stage.answer(redirect, query, PUBLIC_AUTH, null),
                        "a null Set-Cookie list should be refused"));
    }

    @Nested
    @DisplayName("keep_query")
    class KeepQuery {

        @Test
        @DisplayName("Should drop the request query when keep_query is off")
        void shouldDropQueryWhenKeepQueryOff() {
            String location = gatewayPath();

            RedirectStage.Answer answer = answer(redirect(location, 302, false), rawQuery());

            assertEquals(location, answer.location(), "the query should not be appended without keep_query");
        }

        @Test
        @DisplayName("Should append the raw query with '?' when keep_query is on")
        void shouldAppendQueryWithQuestionMark() {
            String location = gatewayPath();
            String query = rawQuery();

            RedirectStage.Answer answer = answer(redirect(location, 307, true), query);

            assertEquals(location + "?" + query, answer.location(), "the raw query should follow a '?'");
        }

        @Test
        @DisplayName("Should join the raw query with '&' when the location already carries a query")
        void shouldJoinQueryWithAmpersandOnExistingQuery() {
            String location = gatewayPath() + "?" + rawQuery();
            String query = rawQuery();

            RedirectStage.Answer answer = answer(redirect(location, 308, true), query);

            assertEquals(location + "&" + query, answer.location(), "the raw query should follow a '&'");
        }

        @Test
        @DisplayName("Should append directly when the location already ends with a query delimiter")
        void shouldAppendDirectlyAfterTrailingDelimiter() {
            String bareQuery = gatewayPath() + "?";
            String openParameter = gatewayPath() + "?" + rawQuery() + "&";
            String query = rawQuery();

            RedirectStage.Answer afterQuestionMark = answer(redirect(bareQuery, 303, true), query);
            RedirectStage.Answer afterAmpersand = answer(redirect(openParameter, 303, true), query);

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

            RedirectStage.Answer absent = answer(redirect(location, 301, true), null);
            RedirectStage.Answer empty = answer(redirect(location, 301, true), "");

            assertAll(
                    () -> assertEquals(location, absent.location(), "an absent query should add nothing"),
                    () -> assertEquals(location, empty.location(), "an empty query should add no bare '?'"));
        }
    }

    @Nested
    @DisplayName("Cacheability of the answer (CWE-524 / CWE-525)")
    class Cacheability {

        @Test
        @DisplayName("Should leave a public redirect with no Set-Cookie cacheable")
        void shouldLeavePublicCookielessAnswerCacheable() {
            RedirectStage.Answer answer = stage.answer(redirect(gatewayPath(), 308, false), null,
                    PUBLIC_AUTH, List.of());

            assertFalse(answer.noStore(),
                    "a public redirect carrying no cookie is ordinary cacheable configuration");
        }

        @ParameterizedTest(name = "require {0}")
        @EnumSource(value = Require.class, names = {"BEARER", "SESSION"})
        @DisplayName("Should force no-store on every authenticated posture, cookie or not")
        void shouldForceNoStoreOnAuthenticatedRoute(Require require) {
            AuthConfig authenticated = AuthConfig.builder().require(require).build();

            RedirectStage.Answer answer = stage.answer(redirect(gatewayPath(), 301, false), null,
                    authenticated, List.of());

            assertTrue(answer.noStore(),
                    "an authenticated redirect answer must never be written to a shared cache");
        }

        @Test
        @DisplayName("Should force no-store on a public route once the response carries a Set-Cookie")
        void shouldForceNoStoreOnAccumulatedSetCookie() {
            RedirectStage.Answer answer = stage.answer(redirect(gatewayPath(), 301, false), null,
                    PUBLIC_AUTH, List.of(setCookie()));

            assertTrue(answer.noStore(),
                    "a cached redirect would replay one client's session cookie to the next");
        }

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {301, 302, 303, 307, 308})
        @DisplayName("Should decide cacheability from the posture alone, never from the status")
        void shouldDecideIndependentlyOfStatus(int status) {
            AuthConfig authenticated = AuthConfig.builder().require(Require.BEARER).build();

            RedirectStage.Answer authenticatedAnswer =
                    stage.answer(redirect(gatewayPath(), status, false), null, authenticated, List.of());
            RedirectStage.Answer publicAnswer =
                    stage.answer(redirect(gatewayPath(), status, false), null, PUBLIC_AUTH, List.of());

            assertAll(
                    () -> assertTrue(authenticatedAnswer.noStore(),
                            "the non-heuristically-cacheable statuses are governed too: a status is a"
                                    + " client hint, not a cache guarantee"),
                    () -> assertFalse(publicAnswer.noStore(),
                            "and a public cookieless answer stays cacheable at every status"));
        }
    }
}
