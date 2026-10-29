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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.events.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ThreatAlertDocument}: the rendered ALERT document is parsed back as a JSON tree and
 * its ECS fields, the {@code source.ip} trap, the {@code url.domain} rule and the
 * {@code api_sheriff.threat} breakdown are asserted against the trip it was rendered from.
 */
@DisplayName("ThreatAlertDocument")
class ThreatAlertDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String DECLARED_HOST = "api.example.com";
    private static final double LEVEL_AT_TRIP = 104.5d;
    private static final int THRESHOLD = 100;
    private static final Duration WINDOW = Duration.ofSeconds(300);

    private final ThreatAlertDocument renderer = new ThreatAlertDocument(new GatewayJson(new ObjectMapper()));

    @Nested
    @DisplayName("ECS event and threat fields")
    class EcsFields {

        @Test
        @DisplayName("carries the fixed ECS event classification")
        void carriesEventClassification() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode document = render(trip);

            // Assert
            JsonNode event = document.path("event");
            assertAll(
                    () -> assertEquals("alert", event.path("kind").asText()),
                    () -> assertEquals(List.of("intrusion_detection", "web"), texts(event.path("category"))),
                    () -> assertEquals(List.of("info"), texts(event.path("type"))),
                    () -> assertEquals("threat-classification-threshold-reached", event.path("action").asText()),
                    () -> assertEquals("api_sheriff.threat", event.path("dataset").asText()),
                    () -> assertEquals("api_sheriff", event.path("module").asText()),
                    () -> assertTrue(event.path("severity").isIntegralNumber()),
                    () -> assertEquals(47, event.path("severity").asInt()));
        }

        @Test
        @DisplayName("carries the MITRE ATT&CK reconnaissance tactic and active-scanning technique")
        void carriesThreatFramework() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode threat = render(trip).path("threat");

            // Assert
            assertAll(
                    () -> assertEquals("MITRE ATT&CK", threat.path("framework").asText()),
                    () -> assertEquals(List.of("TA0043"), texts(threat.path("tactic").path("id"))),
                    () -> assertEquals(List.of("Reconnaissance"), texts(threat.path("tactic").path("name"))),
                    () -> assertEquals(List.of("T1595"), texts(threat.path("technique").path("id"))),
                    () -> assertEquals(List.of("Active Scanning"), texts(threat.path("technique").path("name"))));
        }

        @Test
        @DisplayName("carries the timestamp, ECS version, log level and fixed message")
        void carriesBaseFields() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode document = render(trip);

            // Assert
            assertAll(
                    () -> assertEquals(NOW.toString(), document.path("@timestamp").asText()),
                    () -> assertEquals(ThreatAlertDocument.ECS_VERSION, document.path("ecs").path("version").asText()),
                    () -> assertEquals("warn", document.path("log").path("level").asText()),
                    () -> assertEquals(ThreatAlertDocument.MESSAGE, document.path("message").asText()));
        }
    }

    @Nested
    @DisplayName("source and url fields")
    class SourceFields {

        @Test
        @DisplayName("an IPv4 key is written as both source.address and source.ip")
        void ipv4KeyCarriesSourceIp() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("203.0.113.7", DECLARED_HOST));

            // Act
            JsonNode source = render(trip).path("source");

            // Assert
            assertAll(
                    () -> assertEquals("203.0.113.7", source.path("address").asText()),
                    () -> assertEquals("203.0.113.7", source.path("ip").asText()));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"2001:db8:1:2::/64", SourceKey.UNKNOWN_SOURCE})
        @DisplayName("an IPv6 /64 prefix or the unknown source is written as source.address only")
        void nonIpv4KeyCarriesNoSourceIp(String sourceText) throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey(sourceText, DECLARED_HOST));

            // Act
            JsonNode source = render(trip).path("source");

            // Assert
            assertAll(
                    () -> assertEquals(sourceText, source.path("address").asText()),
                    () -> assertFalse(source.has("ip"), "source.ip must be absent for " + sourceText));
        }

        @Test
        @DisplayName("a declared host is written as url.domain")
        void declaredHostCarriesUrlDomain() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode document = render(trip);

            // Assert
            assertEquals(DECLARED_HOST, document.path("url").path("domain").asText());
        }

        @Test
        @DisplayName("the undeclared-host sentinel writes no url member")
        void undeclaredHostCarriesNoUrl() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", SourceKey.UNDECLARED_HOST));

            // Act
            JsonNode document = render(trip);

            // Assert
            assertAll(
                    () -> assertFalse(document.has("url")),
                    () -> assertEquals(SourceKey.UNDECLARED_HOST,
                            document.path("api_sheriff").path("threat").path("host").asText()));
        }
    }

    @Nested
    @DisplayName("api_sheriff.threat breakdown")
    class Breakdown {

        @Test
        @DisplayName("the per-event breakdown equals the trip's contributions")
        void contributionsEqualTrip() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode contributions = render(trip).path("api_sheriff").path("threat").path("contributions");

            // Assert
            Map<String, Long> expected = new TreeMap<>();
            trip.contributions().forEach((tag, weight) -> expected.put(tag.name(), weight));
            assertEquals(expected, longs(contributions));
        }

        @Test
        @DisplayName("the per-category breakdown folds the contributions by category, recon trip apart")
        void categoriesFoldContributions() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode categories = render(trip).path("api_sheriff").path("threat").path("categories");

            // Assert
            assertEquals(Map.of("input-validation", 60L, "routing", 4L, "authentication", 2L, "recon_trip", 50L),
                    longs(categories));
        }

        @Test
        @DisplayName("the score and risk score are the level at the trip, with threshold and window")
        void scoreIsLevelAtTrip() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("192.0.2.10", DECLARED_HOST));

            // Act
            JsonNode document = render(trip);

            // Assert
            JsonNode threat = document.path("api_sheriff").path("threat");
            assertAll(
                    () -> assertEquals(LEVEL_AT_TRIP, threat.path("score").asDouble()),
                    () -> assertEquals(LEVEL_AT_TRIP, document.path("event").path("risk_score").asDouble()),
                    () -> assertEquals(THRESHOLD, threat.path("threshold").asInt()),
                    () -> assertEquals(WINDOW.toSeconds(), threat.path("window_seconds").asLong()),
                    () -> assertEquals(DECLARED_HOST, threat.path("host").asText()));
        }
    }

    @Nested
    @DisplayName("output form")
    class OutputForm {

        @Test
        @DisplayName("the output is a single valid JSON object without control characters")
        void outputIsValidJsonWithoutControlCharacters() throws JsonProcessingException {
            // Arrange
            SourceTrip trip = trip(new SourceKey("2001:db8:1:2::/64", SourceKey.UNDECLARED_HOST));

            // Act
            String json = renderer.render(trip, NOW);

            // Assert
            JsonNode document = MAPPER.readTree(json);
            assertAll(
                    () -> assertTrue(document.isObject()),
                    () -> assertTrue(json.chars().noneMatch(character -> character < 0x20),
                            "rendered document carries a control character"));
        }

        @Test
        @DisplayName("an absent serializer is rejected")
        void rejectsAbsentSerializer() {
            assertThrows(NullPointerException.class, () -> new ThreatAlertDocument(null));
        }
    }

    @Nested
    @DisplayName("IPv4 literal check")
    class Ipv4Literal {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"0.0.0.0", "192.0.2.10", "255.255.255.255"})
        @DisplayName("accepts a dotted-decimal IPv4 address")
        void acceptsIpv4(String text) {
            assertTrue(ThreatAlertDocument.isIpv4Literal(text));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"256.0.0.1", "1.2.3", "1.2.3.4.5", "1..2.3", "1.2.3.", "a.b.c.d", "1234.1.1.1",
                "", "unknown", "2001:db8:1:2::/64", "-1.2.3.4"})
        @DisplayName("rejects anything that is not a whole IPv4 address")
        void rejectsNonIpv4(String text) {
            assertFalse(ThreatAlertDocument.isIpv4Literal(text));
        }
    }

    @Nested
    @DisplayName("category fold")
    class CategoryFold {

        @Test
        @DisplayName("the recon trip, a categorized event and an uncategorized tag fold to their keys")
        void foldsTags() {
            assertAll(
                    () -> assertEquals("recon_trip", ThreatAlertDocument.categoryOf(EventType.SOURCE_TRIPPED)),
                    () -> assertEquals("input-validation",
                            ThreatAlertDocument.categoryOf(EventType.SECURITY_FILTER_VIOLATION)),
                    () -> assertEquals(ThreatAlertDocument.UNCATEGORIZED,
                            ThreatAlertDocument.categoryOf(EventType.REQUEST_FORWARDED)),
                    () -> assertEquals(ThreatAlertDocument.UNCATEGORIZED,
                            ThreatAlertDocument.categoryOf(SourceLedger.Action.OBSERVE)));
        }
    }

    private JsonNode render(SourceTrip trip) throws JsonProcessingException {
        return MAPPER.readTree(renderer.render(trip, NOW));
    }

    private static SourceTrip trip(SourceKey key) {
        Map<Enum<?>, Long> contributions = Map.of(
                EventType.SECURITY_FILTER_VIOLATION, 40L,
                EventType.PATH_NOT_ALLOWED, 20L,
                EventType.NO_ROUTE_MATCHED, 4L,
                EventType.TOKEN_MISSING, 2L,
                EventType.SOURCE_TRIPPED, 50L);
        return new SourceTrip(key, LEVEL_AT_TRIP, THRESHOLD, WINDOW, contributions, SourceLedger.Action.OBSERVE,
                0L);
    }

    private static List<String> texts(JsonNode array) {
        assertTrue(array.isArray(), "expected a JSON array");
        return array.valueStream().map(JsonNode::asText).toList();
    }

    private static Map<String, Long> longs(JsonNode object) {
        assertTrue(object.isObject(), "expected a JSON object");
        Map<String, Long> values = new TreeMap<>();
        object.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asLong()));
        return values;
    }
}
