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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins {@link EventType}'s category, HTTP-status and WebSocket-close mappings.
 * <p>
 * Every constant is claimed by exactly one of four explicitly enumerated contract lists — success /
 * informational, boot-only configuration, WebSocket-close, and the request-time error contract. The
 * expected statuses and categories are stated here as literals rather than re-read from the enum
 * under test, so the lists carry independent information; the drift guards in
 * {@link ContractCoverage} then assert that the lists remain <em>exhaustive</em>, which is what a
 * hand-maintained mirror of an authoritative source cannot guarantee on its own.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("EventType — category and HTTP-status mapping")
class EventTypeTest {

    /** Success / informational events: no category, no HTTP mapping, no WebSocket-close mapping. */
    private static final Set<EventType> SUCCESS_EVENTS = EnumSet.of(
            EventType.REQUEST_FORWARDED,
            EventType.TOKEN_REFRESHED,
            EventType.CONFIG_LOADED,
            EventType.SESSION_CREATED,
            EventType.SESSION_DESTROYED,
            EventType.SESSION_REFRESH_FAILED,
            EventType.BACKCHANNEL_LOGOUT);

    /** Boot-only configuration failures: a category, but never an HTTP response. */
    private static final Set<EventType> CONFIGURATION_EVENTS = EnumSet.of(
            EventType.CONFIG_INVALID,
            EventType.AUTH_WEAKENED);

    /**
     * Events that terminate an <em>established</em> WebSocket relay: no HTTP mapping (they occur
     * after the {@code 101} upgrade) but a non-zero close code.
     */
    private static final Set<EventType> WEBSOCKET_CLOSE_EVENTS = EnumSet.of(
            EventType.WEBSOCKET_IDLE_TIMEOUT);

    /**
     * The request-time error contract: one row per event the edge renders as an HTTP status, with the
     * status and category the contract in {@code architecture.adoc} promises.
     */
    private static final List<Arguments> ERROR_CONTRACT = List.of(
            arguments(EventType.SECURITY_FILTER_VIOLATION, 400, EventCategory.INPUT_VALIDATION),
            arguments(EventType.PATH_NOT_ALLOWED, 400, EventCategory.INPUT_VALIDATION),
            arguments(EventType.PARAMETER_LIMIT_EXCEEDED, 400, EventCategory.INPUT_VALIDATION),
            arguments(EventType.NO_ROUTE_MATCHED, 404, EventCategory.INPUT_VALIDATION),
            arguments(EventType.PASSTHROUGH_HOST_SMUGGLED, 404, EventCategory.INPUT_VALIDATION),
            arguments(EventType.METHOD_NOT_ALLOWED, 405, EventCategory.INPUT_VALIDATION),
            arguments(EventType.RESERVED_BODY_TOO_LARGE, 413, EventCategory.INPUT_VALIDATION),
            arguments(EventType.CONTENT_TOO_LARGE, 413, EventCategory.INPUT_VALIDATION),
            arguments(EventType.TOKEN_MISSING, 401, EventCategory.AUTHENTICATION),
            arguments(EventType.TOKEN_INVALID, 401, EventCategory.AUTHENTICATION),
            arguments(EventType.LOGOUT_TOKEN_INVALID, 400, EventCategory.AUTHENTICATION),
            arguments(EventType.SCOPE_MISSING, 403, EventCategory.AUTHORIZATION),
            arguments(EventType.CSRF_REJECTED, 403, EventCategory.AUTHORIZATION),
            arguments(EventType.WEBSOCKET_ORIGIN_REJECTED, 403, EventCategory.AUTHORIZATION),
            arguments(EventType.UPSTREAM_ERROR, 502, EventCategory.UPSTREAM),
            arguments(EventType.UPSTREAM_CIRCUIT_OPEN, 503, EventCategory.UPSTREAM),
            arguments(EventType.UPSTREAM_TIMEOUT, 504, EventCategory.UPSTREAM));

    static Stream<EventType> successEvents() {
        return SUCCESS_EVENTS.stream();
    }

    static Stream<EventType> configurationEvents() {
        return CONFIGURATION_EVENTS.stream();
    }

    static Stream<Arguments> errorContract() {
        return ERROR_CONTRACT.stream();
    }

    private static Set<EventType> errorContractKeys() {
        return ERROR_CONTRACT.stream()
                .map(row -> (EventType) row.get()[0])
                .collect(() -> EnumSet.noneOf(EventType.class), Set::add, Set::addAll);
    }

    @Nested
    @DisplayName("Success / informational events")
    class SuccessEvents {

        @ParameterizedTest
        @MethodSource("de.cuioss.sheriff.gateway.events.EventTypeTest#successEvents")
        @DisplayName("Should carry no category and no HTTP mapping")
        void shouldCarryNoCategoryAndNoHttpMapping(EventType eventType) {
            assertAll("success event " + eventType,
                    () -> assertNull(eventType.category(), "Success events carry a null category"),
                    () -> assertFalse(eventType.isFailure(), "Success events are not failures"),
                    () -> assertEquals(0, eventType.httpStatus(), "Success events have no HTTP status"),
                    () -> assertFalse(eventType.hasHttpMapping(), "Success events have no HTTP mapping"),
                    () -> assertEquals(0, eventType.wsCloseCode(), "Success events have no WebSocket-close mapping"));
        }
    }

    @Nested
    @DisplayName("Boot-only configuration events")
    class ConfigurationEvents {

        @ParameterizedTest
        @MethodSource("de.cuioss.sheriff.gateway.events.EventTypeTest#configurationEvents")
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
        @MethodSource("de.cuioss.sheriff.gateway.events.EventTypeTest#errorContract")
        @DisplayName("Should map each error-contract row to its status and category")
        void shouldMapEachErrorContractRow(EventType eventType, int expectedStatus, EventCategory expectedCategory) {
            assertAll("failure event " + eventType,
                    () -> assertEquals(expectedCategory, eventType.category(), "Category must match the contract"),
                    () -> assertEquals(expectedStatus, eventType.httpStatus(), "Status must match the contract"),
                    () -> assertTrue(eventType.isFailure(), "Request-time rejections are failures"),
                    () -> assertTrue(eventType.hasHttpMapping(), "Request-time rejections map to a status"));
        }
    }

    @Nested
    @DisplayName("Established-relay WebSocket-close events")
    class WebSocketCloseEvents {

        @Test
        @DisplayName("Should close the relay with 1001 Going Away and carry no HTTP mapping")
        void shouldCloseIdleRelayWithGoingAway() {
            EventType eventType = EventType.WEBSOCKET_IDLE_TIMEOUT;
            assertAll("websocket-close event " + eventType,
                    () -> assertNull(eventType.category(),
                            "A reclaimed relay is not a request-time rejection, so it carries no category"),
                    () -> assertFalse(eventType.isFailure(), "WebSocket-close events are not failures"),
                    () -> assertEquals(0, eventType.httpStatus(),
                            "The close happens after the 101 upgrade, so there is no HTTP status to render"),
                    () -> assertFalse(eventType.hasHttpMapping(), "WebSocket-close events have no HTTP mapping"),
                    () -> assertEquals(1001, eventType.wsCloseCode(), "An idle relay is closed with 1001 Going Away"));
        }
    }

    @Nested
    @DisplayName("Contract coverage (drift guards)")
    class ContractCoverage {

        @Test
        @DisplayName("Should cover exactly the HTTP-mapped constants in the error-contract table")
        void shouldCoverExactlyTheHttpMappedConstants() {
            Set<EventType> mapped = EnumSet.allOf(EventType.class).stream()
                    .filter(EventType::hasHttpMapping)
                    .collect(() -> EnumSet.noneOf(EventType.class), Set::add, Set::addAll);

            assertEquals(mapped, errorContractKeys(),
                    "Every EventType with hasHttpMapping() must have an error-contract row, and no row may"
                            + " name an unmapped constant — add the row in the same change as the constant");
        }

        @Test
        @DisplayName("Should cover exactly the constants carrying a WebSocket close code")
        void shouldCoverExactlyTheWebSocketCloseConstants() {
            Set<EventType> closing = EnumSet.allOf(EventType.class).stream()
                    .filter(eventType -> eventType.wsCloseCode() > 0)
                    .collect(() -> EnumSet.noneOf(EventType.class), Set::add, Set::addAll);

            assertEquals(WEBSOCKET_CLOSE_EVENTS, closing,
                    "Every EventType with a non-zero wsCloseCode must be asserted as a WebSocket-close event");
        }

        @Test
        @DisplayName("Should claim every EventType constant in exactly one contract list")
        void shouldClaimEveryConstantExactlyOnce() {
            List<EventType> claimed = Stream
                    .of(SUCCESS_EVENTS, CONFIGURATION_EVENTS, WEBSOCKET_CLOSE_EVENTS, errorContractKeys())
                    .flatMap(Collection::stream)
                    .toList();

            assertAll("contract-list partition",
                    () -> assertEquals(EnumSet.allOf(EventType.class), EnumSet.copyOf(claimed),
                            "Every EventType constant must be asserted by one of the contract lists"),
                    () -> assertEquals(claimed.size(), EnumSet.copyOf(claimed).size(),
                            "No EventType constant may appear in more than one contract list"));
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
