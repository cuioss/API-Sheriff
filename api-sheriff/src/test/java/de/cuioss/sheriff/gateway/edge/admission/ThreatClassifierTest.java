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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.edge.error.ReconEvents;
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
 * malformed request raises none, a recon trip adds {@code recon_trip} without an ALERT of its own and
 * lands on the request's own source even when the recon ledger counted it on its {@code /48}, a
 * request without a stashed source admits nothing, and an untracked source reaches the overflow
 * reporter. The classifier never writes to the request's context, so it can neither hold nor change an
 * answer.
 */
@EnableTestLogger
@DisplayName("ThreatClassifier — the weighted threat score on its own ledger")
class ThreatClassifierTest {

    private static final SourceKey SOURCE = new SourceKey("203.0.113.7", SourceKey.UNDECLARED_HOST);
    private static final SourceKey SLASH_64 = new SourceKey("2001:db8:5:7::/64", SourceKey.UNDECLARED_HOST);
    private static final SourceKey SLASH_48 = new SourceKey("2001:db8:5::/48", SourceKey.UNDECLARED_HOST);
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

    /**
     * SEC2-03-C: under recon-ledger pressure a new {@code /64} is counted on its {@code /48}, so the recon
     * trip's tracked key is the {@code /48}. The trip must still be scored where the threat ledger already
     * counts the request's own {@code /64}, not on a second {@code /48} entry nothing ever combines with it.
     */
    @Test
    @DisplayName("a recon trip counted on a /48 is scored on the request's own threat-tracked /64")
    void reconTripUnderAggregationScoresTheRequestsOwnSource() {
        List<SourceTrip> reconTrips = new ArrayList<>();
        SourceLedger recon = reconLedger(reconTrips);
        Map<String, Object> slash64 = new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, SLASH_64));
        admit(slash64, EventType.SECURITY_FILTER_VIOLATION, 1);
        fillToHighWaterMark(recon);

        for (int count = 0; count < EnumerationDetectionConfig.defaults().effectiveThreshold(); count++) {
            recon.admit(SLASH_64, 1, EventType.NO_ROUTE_MATCHED);
        }

        SourceState state = classifier.ledger().state(SLASH_64).orElseThrow();
        assertAll("one threat entry for the /64",
                () -> assertEquals(1, reconTrips.size(), "precondition: the recon ledger tripped once"),
                () -> assertEquals(SLASH_48, reconTrips.getFirst().key(), "precondition: counted on the /48"),
                () -> assertEquals(SLASH_64, reconTrips.getFirst().requestKey()),
                () -> assertEquals(SLASH_64, state.key(), "the /64's own entry answers"),
                () -> assertEquals(ThreatClassificationConfig.Weights.DEFAULT_INPUT_VALIDATION
                        + ThreatClassificationConfig.Weights.DEFAULT_RECON_TRIP, state.level(), 0.001),
                () -> assertEquals(1, classifier.ledger().trackedSources(), "no second /48 entry"));
    }

    /**
     * Matched control for the test above: at the default tuning every recon event is threat-scored too,
     * so a single {@code /64} probing at full speed alerts at the recon threshold's request whether or not
     * the recon ledger aggregates it — moving the trip's weight to the {@code /64} does not move the alert.
     */
    @Test
    @DisplayName("at the default tuning a probing /64 alerts at the same request, with and without pressure")
    void defaultTuningAlertsAtTheSameRequestWithAndWithoutPressure() {
        int unpressured = requestThatAlerts(false);
        int pressured = requestThatAlerts(true);

        int expected = EnumerationDetectionConfig.defaults().effectiveThreshold();
        assertAll("the alert's request number",
                () -> assertEquals(expected, unpressured),
                () -> assertEquals(expected, pressured));
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
        return alertCount(meterRegistry);
    }

    private static double alertCount(SimpleMeterRegistry registry) {
        Counter alerts = registry.find(SheriffMetrics.THREAT_ALERTS_TOTAL).counter();
        return alerts == null ? 0.0 : alerts.count();
    }

    /** A recon ledger tuned as the edge's at the defaults, wired to this test's classifier as the edge wires it. */
    private SourceLedger reconLedger(List<SourceTrip> trips) {
        return reconLedger(classifier, trips);
    }

    private SourceLedger reconLedger(ThreatClassifier target, List<SourceTrip> trips) {
        EnumerationDetectionConfig config = EnumerationDetectionConfig.defaults();
        SourceLedger.Settings settings = new SourceLedger.Settings(config.effectiveThreshold(),
                Duration.ofSeconds(config.effectiveWindowSeconds()), SourceLedger.Action.OBSERVE,
                Duration.ofSeconds(config.effectiveBlockSeconds()));
        return new SourceLedger(settings, nanos::get, trip -> {
            trips.add(trip);
            target.admitReconTrip(trip);
        });
    }

    /** Fills a ledger with distinct IPv4 sources up to its high-water mark, from which a new /64 is aggregated. */
    private static void fillToHighWaterMark(SourceLedger ledger) {
        int mark = SourceLedger.MAX_TRACKED_SOURCES * SourceLedger.HIGH_WATER_PERCENT / 100;
        for (int index = 0; ledger.trackedSources() < mark; index++) {
            ledger.admit(new SourceKey("10.%d.%d.%d".formatted((index >> 16) & 0xff, (index >> 8) & 0xff, index & 0xff),
                    SourceKey.UNDECLARED_HOST), 1, EventType.NO_ROUTE_MATCHED);
        }
    }

    /**
     * Feeds one {@code /64} unmatched-route rejections in a burst, each to the recon ledger and then to a
     * fresh classifier in the order the rejection writer feeds them, and answers the request number at
     * which the first ALERT is raised, or {@code -1} when none is raised.
     */
    private int requestThatAlerts(boolean reconPressure) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreatClassifier target = new ThreatClassifier(ThreatClassificationConfig.defaults(),
                new ThreatAlerts(new ThreatAlertDocument(new GatewayJson(new ObjectMapper())), new SheriffMetrics(registry),
                        Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)),
                nanos::get);
        SourceLedger recon = reconLedger(target, new ArrayList<>());
        if (reconPressure) {
            fillToHighWaterMark(recon);
        }
        Map<String, Object> slash64 = new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, SLASH_64));
        for (int request = 1; request <= 4 * ThreatClassificationConfig.DEFAULT_THRESHOLD; request++) {
            recon.admit(SLASH_64, ReconEvents.weightOf(null, EventType.NO_ROUTE_MATCHED), EventType.NO_ROUTE_MATCHED);
            target.admit(context(slash64), null, EventType.NO_ROUTE_MATCHED);
            if (alertCount(registry) > 0.0) {
                return request;
            }
        }
        return -1;
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
        return new SourceTrip(SOURCE, SOURCE, 20.0, 20, Duration.ofSeconds(60),
                Map.of(EventType.NO_ROUTE_MATCHED, 20L), SourceLedger.Action.OBSERVE, 0L);
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
