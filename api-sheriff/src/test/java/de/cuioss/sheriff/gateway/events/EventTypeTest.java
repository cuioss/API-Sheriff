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
package de.cuioss.sheriff.gateway.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("EventType — category and HTTP-status mapping")
class EventTypeTest {

    @Nested
    @DisplayName("Success / informational events")
    class SuccessEvents {

        @ParameterizedTest
        @EnumSource(names = {"REQUEST_FORWARDED", "TOKEN_REFRESHED", "CONFIG_LOADED"})
        @DisplayName("Should carry no category and no HTTP mapping")
        void shouldCarryNoCategoryAndNoHttpMapping(EventType eventType) {
            assertAll("success event " + eventType,
                    () -> assertNull(eventType.category(), "Success events carry a null category"),
                    () -> assertFalse(eventType.isFailure(), "Success events are not failures"),
                    () -> assertEquals(0, eventType.httpStatus(), "Success events have no HTTP status"),
                    () -> assertFalse(eventType.hasHttpMapping(), "Success events have no HTTP mapping"));
        }
    }

    @Nested
    @DisplayName("Boot-only configuration events")
    class ConfigurationEvents {

        @ParameterizedTest
        @EnumSource(names = {"CONFIG_INVALID", "AUTH_WEAKENED"})
        @DisplayName("Should be failures in the CONFIGURATION category with no HTTP mapping")
        void shouldBeConfigurationFailuresWithoutHttpMapping(EventType eventType) {
            assertAll("configuration event " + eventType,
                    () -> assertEquals(EventCategory.CONFIGURATION, eventType.category(),
                            "Configuration events carry the CONFIGURATION category"),
                    () -> assertTrue(eventType.isFailure(), "Configuration events are failures"),
                    () -> assertEquals(0, eventType.httpStatus(), "Configuration events never surface as HTTP"),
                    () -> assertFalse(eventType.hasHttpMapping(), "Configuration events have no HTTP mapping"));
        }
    }

    @Nested
    @DisplayName("Request-time failure events (error contract)")
    class FailureEvents {

        @ParameterizedTest(name = "{0} -> {1} ({2})")
        @CsvSource({
                "SECURITY_FILTER_VIOLATION, 400, INPUT_VALIDATION",
                "PATH_NOT_ALLOWED,          400, INPUT_VALIDATION",
                "PARAMETER_LIMIT_EXCEEDED,  400, INPUT_VALIDATION",
                "NO_ROUTE_MATCHED,          404, INPUT_VALIDATION",
                "METHOD_NOT_ALLOWED,        405, INPUT_VALIDATION",
                "TOKEN_MISSING,             401, AUTHENTICATION",
                "TOKEN_INVALID,             401, AUTHENTICATION",
                "SCOPE_MISSING,             403, AUTHORIZATION",
                "CSRF_REJECTED,             403, AUTHORIZATION",
                "UPSTREAM_ERROR,            502, UPSTREAM",
                "UPSTREAM_CIRCUIT_OPEN,     503, UPSTREAM",
                "UPSTREAM_TIMEOUT,          504, UPSTREAM"
        })
        @DisplayName("Should map each error-contract row to its status and category")
        void shouldMapEachErrorContractRow(EventType eventType, int expectedStatus, EventCategory expectedCategory) {
            assertAll("failure event " + eventType,
                    () -> assertEquals(expectedCategory, eventType.category(), "Category must match the contract"),
                    () -> assertEquals(expectedStatus, eventType.httpStatus(), "Status must match the contract"),
                    () -> assertTrue(eventType.isFailure(), "Request-time rejections are failures"),
                    () -> assertTrue(eventType.hasHttpMapping(), "Request-time rejections map to a status"));
        }
    }

    @Test
    @DisplayName("Should derive the RFC 9457 problem type from the category slug")
    void shouldDeriveProblemTypeFromCategorySlug() {
        assertAll("problem type",
                () -> assertEquals("urn:api-sheriff:problem:input-validation",
                        EventCategory.INPUT_VALIDATION.problemType()),
                () -> assertEquals("urn:api-sheriff:problem:upstream",
                        EventCategory.UPSTREAM.problemType()),
                () -> assertEquals("Input Validation", EventCategory.INPUT_VALIDATION.title()));
    }
}
