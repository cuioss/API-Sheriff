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
package de.cuioss.sheriff.gateway.portal;

import static de.cuioss.sheriff.gateway.portal.ErrorPageClassifier.Classification.HTML_ELIGIBLE;
import static de.cuioss.sheriff.gateway.portal.ErrorPageClassifier.Classification.KEEP_SHAPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;


import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier.Classification;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier.Exit;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ErrorPageClassifier}: the full classification table over every {@link EventType}
 * constant and every non-event {@link Exit}, the explicit {@code text/html} negotiation rule, and the
 * fixed per-status titles.
 * <p>
 * The classification tables are literal on purpose — the table <em>is</em> the contract, so any
 * reclassification shows up as a visible diff here, and a new constant without a row fails the
 * coverage assertion.
 */
@EnableGeneratorController
@DisplayName("ErrorPageClassifier")
class ErrorPageClassifierTest {

    private static Map<EventType, Classification> expectedEventTable() {
        Map<EventType, Classification> table = new EnumMap<>(EventType.class);
        table.put(EventType.REQUEST_FORWARDED, KEEP_SHAPE);
        table.put(EventType.TOKEN_REFRESHED, KEEP_SHAPE);
        table.put(EventType.CONFIG_LOADED, KEEP_SHAPE);
        table.put(EventType.CONFIG_INVALID, KEEP_SHAPE);
        table.put(EventType.AUTH_WEAKENED, KEEP_SHAPE);
        table.put(EventType.SECURITY_FILTER_VIOLATION, KEEP_SHAPE);
        table.put(EventType.PATH_NOT_ALLOWED, KEEP_SHAPE);
        table.put(EventType.PARAMETER_LIMIT_EXCEEDED, KEEP_SHAPE);
        table.put(EventType.NO_ROUTE_MATCHED, HTML_ELIGIBLE);
        table.put(EventType.PASSTHROUGH_HOST_SMUGGLED, KEEP_SHAPE);
        table.put(EventType.METHOD_NOT_ALLOWED, KEEP_SHAPE);
        table.put(EventType.RESERVED_BODY_TOO_LARGE, KEEP_SHAPE);
        table.put(EventType.CONTENT_TOO_LARGE, HTML_ELIGIBLE);
        table.put(EventType.TOKEN_MISSING, KEEP_SHAPE);
        table.put(EventType.TOKEN_INVALID, KEEP_SHAPE);
        table.put(EventType.SCOPE_MISSING, HTML_ELIGIBLE);
        table.put(EventType.CSRF_REJECTED, HTML_ELIGIBLE);
        table.put(EventType.UPSTREAM_ERROR, HTML_ELIGIBLE);
        table.put(EventType.UPSTREAM_CIRCUIT_OPEN, HTML_ELIGIBLE);
        table.put(EventType.UPSTREAM_TIMEOUT, HTML_ELIGIBLE);
        table.put(EventType.SESSION_CREATED, KEEP_SHAPE);
        table.put(EventType.SESSION_DESTROYED, KEEP_SHAPE);
        table.put(EventType.SESSION_REFRESH_FAILED, KEEP_SHAPE);
        table.put(EventType.BACKCHANNEL_LOGOUT, KEEP_SHAPE);
        table.put(EventType.LOGOUT_TOKEN_INVALID, KEEP_SHAPE);
        table.put(EventType.WEBSOCKET_ORIGIN_REJECTED, KEEP_SHAPE);
        table.put(EventType.WEBSOCKET_IDLE_TIMEOUT, KEEP_SHAPE);
        return table;
    }

    private static Map<Exit, Classification> expectedExitTable() {
        Map<Exit, Classification> table = new EnumMap<>(Exit.class);
        table.put(Exit.CALLBACK_FAILURE, HTML_ELIGIBLE);
        table.put(Exit.DIRECTORY_ASSET_NOT_FOUND, HTML_ELIGIBLE);
        table.put(Exit.ORIGIN_RELAY, KEEP_SHAPE);
        table.put(Exit.UPSTREAM_ASSET, KEEP_SHAPE);
        table.put(Exit.GRPC_REJECTION, KEEP_SHAPE);
        table.put(Exit.ADMISSION_REJECT, KEEP_SHAPE);
        table.put(Exit.RELAY_FAILURE_AFTER_HEAD, KEEP_SHAPE);
        table.put(Exit.UNEXPECTED_INTERNAL, KEEP_SHAPE);
        return table;
    }

    @Nested
    @DisplayName("Classification table")
    class ClassificationTable {

        @Test
        @DisplayName("The event table covers every EventType constant")
        void eventTableCoversEveryConstant() {
            assertEquals(EnumSet.allOf(EventType.class), expectedEventTable().keySet(),
                    "a new EventType constant needs an explicit row in the documented classification table");
        }

        @Test
        @DisplayName("Every EventType constant is classified as documented")
        void everyEventTypeIsClassifiedAsDocumented() {
            Map<EventType, Classification> expected = expectedEventTable();
            for (EventType event : EventType.values()) {
                assertEquals(expected.get(event), ErrorPageClassifier.classify(event), event.name());
            }
        }

        @Test
        @DisplayName("Every eligible event is a request-time failure with an HTTP status")
        void everyEligibleEventHasAnHttpMapping() {
            for (EventType event : EventType.values()) {
                if (ErrorPageClassifier.classify(event) == HTML_ELIGIBLE) {
                    assertTrue(event.hasHttpMapping(), event.name());
                }
            }
        }

        @Test
        @DisplayName("Every non-event exit is classified as documented")
        void everyExitIsClassifiedAsDocumented() {
            Map<Exit, Classification> expected = expectedExitTable();
            assertEquals(EnumSet.allOf(Exit.class), expected.keySet());
            for (Exit exit : Exit.values()) {
                assertEquals(expected.get(exit), ErrorPageClassifier.classify(exit), exit.name());
            }
        }

        @ParameterizedTest(name = "{0} is never eligible")
        @ValueSource(strings = {"ORIGIN_RELAY", "UPSTREAM_ASSET"})
        @DisplayName("A response relayed from an origin is never replaced")
        void relayedResponsesAreNeverEligible(String exit) {
            assertEquals(KEEP_SHAPE, ErrorPageClassifier.classify(Exit.valueOf(exit)));
        }
    }

    @Nested
    @DisplayName("Accept negotiation")
    class AcceptNegotiation {

        @ParameterizedTest(name = "offers HTML for \"{0}\"")
        @ValueSource(strings = {"text/html", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "TEXT/HTML", " text/html ; q=0.5", "application/json, text/html;q=0.001", "text/html;level=1",
                "text/html;q=1", "text/html;q=1.000"})
        void offersHtmlForExplicitTextHtml(String accept) {
            assertTrue(ErrorPageClassifier.offersHtml(accept));
        }

        @ParameterizedTest(name = "keeps the problem body for \"{0}\"")
        @ValueSource(strings = {"text/html;q=0", "text/html;q=0.000", "*/*", "text/*", "application/json",
                "application/problem+json", "text/htmlx", "text/html;q=abc", "text/html;q=1.5", "text/html;q=2",
                "text/html;q", "text/html;=1", "garbage", ",,,", ";"})
        void doesNotOfferHtmlOtherwise(String accept) {
            assertFalse(ErrorPageClassifier.offersHtml(accept));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("An absent or blank header never offers HTML")
        void absentHeaderDoesNotOfferHtml(String accept) {
            assertFalse(ErrorPageClassifier.offersHtml(accept));
        }

        @Test
        @DisplayName("Arbitrary letter strings never offer HTML")
        void arbitraryStringsDoNotOfferHtml() {
            for (int round = 0; round < 50; round++) {
                String accept = Generators.letterStrings(1, 40).next();
                assertFalse(ErrorPageClassifier.offersHtml(accept), accept);
            }
        }
    }

    @Nested
    @DisplayName("Fixed titles")
    class Titles {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"400,Bad Request", "401,Unauthorized", "403,Forbidden", "404,Not Found",
                "413,Content Too Large", "502,Bad Gateway", "503,Service Unavailable", "504,Gateway Timeout"})
        void titleForListedStatus(int status, String title) {
            assertEquals(title, ErrorPageClassifier.titleFor(status));
        }

        @ParameterizedTest(name = "{0} -> fallback")
        @ValueSource(ints = {0, 200, 405, 418, 500, 599})
        void titleForUnlistedStatusFallsBack(int status) {
            assertEquals(ErrorPageClassifier.FALLBACK_TITLE, ErrorPageClassifier.titleFor(status));
            assertEquals("Error", ErrorPageClassifier.titleFor(status));
        }
    }
}
