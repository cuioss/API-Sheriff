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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ThreatAlerts}, the ALERT tier of threat classification: each threat-ledger trip is
 * one {@code ApiSheriff-143} WARN carrying its ECS document and one count on
 * {@code sheriff_threat_alerts_total}; the overflow is counted every time and logged as WARN once per
 * process, then at {@code DEBUG}.
 */
@EnableTestLogger(debug = ThreatAlerts.class)
@DisplayName("ThreatAlerts — one ALERT per threat episode")
class ThreatAlertsTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String SOURCE = "198.51.100.23";
    private static final String DECLARED_HOST = "api.example.com";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ThreatAlerts alerts = new ThreatAlerts(
            new ThreatAlertDocument(new GatewayJson(new ObjectMapper())), new SheriffMetrics(registry),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("one trip logs exactly one ApiSheriff-143 WARN and counts one ALERT")
    void oneTripIsOneAlert() {
        // Act
        alerts.onTrip(trip(new SourceKey(SOURCE, DECLARED_HOST)));

        // Assert
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, alertIdentifier());
        assertEquals(1.0, alertCount(), 0.0);
    }

    @Test
    @DisplayName("two trips — two episodes — log two ALERTs and count two")
    void twoTripsAreTwoAlerts() {
        // Act
        alerts.onTrip(trip(new SourceKey(SOURCE, DECLARED_HOST)));
        alerts.onTrip(trip(new SourceKey(SOURCE, DECLARED_HOST)));

        // Assert
        assertAll(
                () -> assertEquals(2, alertRecords().size(), "one WARN per episode"),
                () -> assertEquals(2.0, alertCount(), 0.0));
    }

    @Test
    @DisplayName("the ALERT names the canonical source and folded host and carries the parseable ECS document")
    void alertCarriesSourceHostAndDocument() throws Exception {
        // Act
        alerts.onTrip(trip(new SourceKey(SOURCE, DECLARED_HOST)));

        // Assert
        String message = alertRecords().getFirst().getMessage();
        String marker = "ECS alert: ";
        JsonNode document = new ObjectMapper().readTree(message.substring(message.indexOf(marker) + marker.length()));
        assertAll(
                () -> assertTrue(message.contains("Source '" + SOURCE + "' on host '" + DECLARED_HOST + "'"),
                        () -> "the ALERT names the source and host: " + message),
                () -> assertEquals("alert", document.path("event").path("kind").asText()),
                () -> assertEquals(SOURCE, document.path("source").path("ip").asText()),
                () -> assertEquals(NOW.toString(), document.path("@timestamp").asText(),
                        "the timestamp is read from the injected clock"));
    }

    @Test
    @DisplayName("the ALERT carries neither a request path nor a raw Host")
    void alertCarriesNoRequestDerivedText() {
        // Arrange — the key a request with a raw Host of a foreign, mixed-case authority folds to
        SourceTrip trip = trip(new SourceKey(SOURCE, SourceKey.UNDECLARED_HOST));

        // Act
        alerts.onTrip(trip);

        // Assert
        String message = alertRecords().getFirst().getMessage();
        assertAll(
                () -> assertTrue(message.contains("on host '" + SourceKey.UNDECLARED_HOST + "'"),
                        "an undeclared host is logged as the fold sentinel"),
                () -> assertFalse(message.contains("Evil.Example.COM"), "no raw Host reaches the record"),
                () -> assertFalse(message.contains("/admin/.env"), "no request path reaches the record"),
                () -> assertFalse(message.contains("\"url\""), "the undeclared host writes no url.domain"));
    }

    @Test
    @DisplayName("the first overflow is a WARN, later ones are DEBUG, and every one is counted")
    void overflowIsLatchedAndCounted() {
        // Act
        alerts.reportUntracked();
        alerts.reportUntracked();
        alerts.reportUntracked();

        // Assert
        String untracked = ApiSheriffLogMessages.WARN.THREAT_UNTRACKED.resolveIdentifierString();
        Counter counter = registry.get(SheriffMetrics.THREAT_UNTRACKED_TOTAL).counter();
        assertAll(
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, untracked),
                () -> assertEquals(2, TestLoggerFactory.getTestHandler()
                                .resolveLogMessagesContaining(TestLogLevel.DEBUG, "left a further source untracked").size(),
                        "every later overflow is a DEBUG diagnostic"),
                () -> assertEquals(3.0, counter.count(), 0.0, "every overflow is counted"),
                () -> assertTrue(alertRecords().isEmpty(), "an overflow raises no ALERT"));
    }

    @Test
    @DisplayName("the overflow WARN names the bound and no source")
    void overflowWarnNamesTheBoundOnly() {
        // Act
        alerts.reportUntracked();

        // Assert
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                "tracking its maximum of " + SourceLedger.MAX_TRACKED_SOURCES + " sources");
    }

    @Test
    @DisplayName("an absent collaborator or trip is rejected")
    void rejectsAbsentArguments() {
        ThreatAlertDocument document = new ThreatAlertDocument(new GatewayJson(new ObjectMapper()));
        SheriffMetrics metrics = new SheriffMetrics(registry);
        Clock clock = Clock.systemUTC();
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new ThreatAlerts(null, metrics, clock)),
                () -> assertThrows(NullPointerException.class, () -> new ThreatAlerts(document, null, clock)),
                () -> assertThrows(NullPointerException.class, () -> new ThreatAlerts(document, metrics, null)),
                () -> assertThrows(NullPointerException.class, () -> alerts.onTrip(null)));
    }

    private static SourceTrip trip(SourceKey key) {
        Map<Enum<?>, Long> contributions = Map.of(EventType.SECURITY_FILTER_VIOLATION, 100L);
        return new SourceTrip(key, 100.0d, 100, Duration.ofSeconds(300), contributions, SourceLedger.Action.OBSERVE,
                0L);
    }

    private static String alertIdentifier() {
        return ApiSheriffLogMessages.WARN.THREAT_ALERT.resolveIdentifierString();
    }

    private static List<LogRecord> alertRecords() {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN, alertIdentifier());
    }

    private double alertCount() {
        return registry.get(SheriffMetrics.THREAT_ALERTS_TOTAL).counter().count();
    }
}
