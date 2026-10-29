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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The score tier of threat classification over a controllable monotonic clock, observed through the
 * ALERT tier's meters and records: a burst crossing the threshold raises one ALERT, further traffic of
 * the same episode raises none, a drained source raises a second ALERT for a second episode, one
 * malformed request raises none, a recon trip adds {@code recon_trip} without an ALERT of its own, a
 * request without a stashed source admits nothing, and an untracked source reaches the overflow
 * reporter. The classifier never writes to the request's context, so it can neither hold nor change an
 * answer.
 */
@EnableTestLogger
@DisplayName("ThreatClassifier — the weighted threat score on its own ledger")
class ThreatClassifierTest {

    private static final SourceKey SOURCE = new SourceKey("203.0.113.7", SourceKey.UNDECLARED_HOST);
    /** One malformed request at the default input-validation weight scores a tenth of the default threshold. */
    private static final int BURST_TO_THRESHOLD = ThreatClassificationConfig.DEFAULT_THRESHOLD
            / ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION;

    private final AtomicLong nanos = new AtomicLong(1_000L);
    private SimpleMeterRegistry meterRegistry;
    private ThreatClassifier classifier;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ThreatAlerts alerts = new ThreatAlerts(new ThreatAlertDocument(new GatewayJson(new ObjectMapper())),
                new SheriffMetrics(meterRegistry), Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC));
        classifier = new ThreatClassifier(ThreatClassificationConfig.defaults(), alerts, nanos::get);
    }

    @Test
    @DisplayName("the threat ledger is its own, observe-only, tuned by the threat_classification block")
    void ledgerIsTunedByTheBlock() {
        SourceLedger.Settings settings = classifier.ledger().settings();

        assertAll("the threat ledger's settings",
                () -> assertEquals(ThreatClassificationConfig.DEFAULT_THRESHOLD, settings.threshold()),
                () -> assertEquals(Duration.ofSeconds(ThreatClassificationConfig.DEFAULT_WINDOW_SECONDS),
                        settings.window()),
                () -> assertEquals(SourceLedger.Action.OBSERVE, settings.action(), "the action is fixed"),
                () -> assertEquals(settings.window(), settings.blockDuration(), "never used under observe"));
    }

    @Test
    @DisplayName("a burst crossing the threshold raises exactly one ALERT")
    void burstCrossingTheThresholdAlertsOnce() {
        Map<String, Object> data = stashed();

        admit(data, EventType.SECURITY_FILTER_VIOLATION, BURST_TO_THRESHOLD);

        assertEquals(1.0, alertCount());
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.THREAT_ALERT.resolveIdentifierString());
    }

    @Test
    @DisplayName("continued traffic within the same episode raises no second ALERT")
    void continuedTrafficInTheEpisodeDoesNotAlertAgain() {
        Map<String, Object> data = stashed();

        admit(data, EventType.SECURITY_FILTER_VIOLATION, BURST_TO_THRESHOLD * 5);

        assertEquals(1.0, alertCount());
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.THREAT_ALERT.resolveIdentifierString());
    }

    @Test
    @DisplayName("after the score drained to zero, a new burst raises the second episode's ALERT")
    void drainedSourceAlertsAgainInANewEpisode() {
        Map<String, Object> data = stashed();
        admit(data, EventType.SECURITY_FILTER_VIOLATION, BURST_TO_THRESHOLD * 2);

        nanos.addAndGet(TimeUnit.HOURS.toNanos(2));
        boolean drained = classifier.ledger().state(SOURCE).map(state -> state.level() <= 0.0).orElse(true);
        admit(data, EventType.SECURITY_FILTER_VIOLATION, BURST_TO_THRESHOLD);

        assertAll("two episodes, two ALERTs",
                () -> assertTrue(drained, "precondition: the score drained to zero"),
                () -> assertEquals(2.0, alertCount()));
    }

    @Test
    @DisplayName("one malformed request scores a tenth of the threshold and raises no ALERT")
    void oneMalformedRequestDoesNotAlert() {
        admit(stashed(), EventType.SECURITY_FILTER_VIOLATION, 1);

        SourceState state = classifier.ledger().state(SOURCE).orElseThrow();
        assertAll("one malformed request",
                () -> assertEquals(ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION, state.level(), 0.001),
                () -> assertEquals(SourceState.Status.CLEAR, state.status()),
                () -> assertEquals(0.0, alertCount()));
    }

    @Test
    @DisplayName("a recon trip adds recon_trip under SOURCE_TRIPPED and raises no ALERT by itself")
    void reconTripAddsItsWeightWithoutAnAlert() {
        classifier.admitReconTrip(reconTrip());

        SourceState state = classifier.ledger().state(SOURCE).orElseThrow();
        assertAll("the recon trip is an input",
                () -> assertEquals(ThreatClassificationConfig.Weights.DEFAULT_RECON_TRIP, state.level(), 0.001),
                () -> assertEquals(Map.of(EventType.SOURCE_TRIPPED,
                        (long) ThreatClassificationConfig.Weights.DEFAULT_RECON_TRIP), state.contributions()),
                () -> assertEquals(0.0, alertCount(), "never an ALERT of its own"));
    }

    @Test
    @DisplayName("a recon trip plus the rest of the threshold in rejections raises one ALERT")
    void reconTripCountsTowardsTheThreshold() {
        int remaining = (ThreatClassificationConfig.DEFAULT_THRESHOLD - ThreatClassificationConfig.Weights.DEFAULT_RECON_TRIP)
                / ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION;
        classifier.admitReconTrip(reconTrip());
        admit(stashed(), EventType.SECURITY_FILTER_VIOLATION, remaining - 1);
        double before = alertCount();

        admit(stashed(), EventType.SECURITY_FILTER_VIOLATION, 1);

        assertAll("the recon trip is half of the default threshold",
                () -> assertEquals(0.0, before, "control: one rejection short of the threshold"),
                () -> assertEquals(1.0, alertCount()));
    }

    @Test
    @DisplayName("a request without a stashed source admits nothing")
    void missingStashedKeyAdmitsNothing() {
        admit(new HashMap<>(), EventType.SECURITY_FILTER_VIOLATION, BURST_TO_THRESHOLD);

        assertAll("nothing is scored",
                () -> assertEquals(0, classifier.ledger().trackedSources()),
                () -> assertEquals(0.0, alertCount()));
    }

    @Test
    @DisplayName("a rejection weighed at 0 is not admitted and does not start tracking the source")
    void zeroWeightIsNotAdmitted() {
        admit(stashed(), EventType.INBOUND_BODY_ABORTED, BURST_TO_THRESHOLD);
        admit(stashed(), EventType.UPSTREAM_ERROR, BURST_TO_THRESHOLD);

        assertEquals(0, classifier.ledger().trackedSources());
    }

    @Test
    @DisplayName("a source the full ledger cannot track reaches the overflow reporter")
    void untrackedAdmissionReachesTheOverflowReporter() {
        SourceLedger ledger = classifier.ledger();
        for (int index = 0; ledger.trackedSources() < SourceLedger.MAX_TRACKED_SOURCES; index++) {
            ledger.admit(new SourceKey("filler-" + index, SourceKey.UNDECLARED_HOST), 1, EventType.NO_ROUTE_MATCHED);
        }

        admit(stashed(), EventType.SECURITY_FILTER_VIOLATION, 1);

        Counter untracked = meterRegistry.find(SheriffMetrics.THREAT_UNTRACKED_TOTAL).counter();
        assertAll("the overflow is never silent",
                () -> assertTrue(ledger.state(SOURCE).isEmpty(), "precondition: the source was not tracked"),
                () -> assertEquals(1.0, untracked == null ? 0.0 : untracked.count()));
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.THREAT_UNTRACKED.resolveIdentifierString());
    }

    private double alertCount() {
        Counter alerts = meterRegistry.find(SheriffMetrics.THREAT_ALERTS_TOTAL).counter();
        return alerts == null ? 0.0 : alerts.count();
    }

    /** Admits the same rejection {@code times} times for the context's stashed source. */
    private void admit(Map<String, Object> data, EventType eventType, int times) {
        for (int count = 0; count < times; count++) {
            classifier.admit(context(data), null, eventType);
        }
    }

    private static Map<String, Object> stashed() {
        return new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, SOURCE));
    }

    private static SourceTrip reconTrip() {
        return new SourceTrip(SOURCE, 20.0, 20, Duration.ofSeconds(60), Map.of(EventType.NO_ROUTE_MATCHED, 20L),
                SourceLedger.Action.OBSERVE, 0L);
    }

    /**
     * A context that answers {@code get} only: the classifier reads the stashed source and must never
     * write to the request, so a {@code put} — a hold, a changed answer — fails the test.
     */
    private static RoutingContext context(Map<String, Object> data) {
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return data.get(args[0]);
                    }
                    throw new AssertionError("the threat feed must not call " + method.getName());
                });
    }
}
