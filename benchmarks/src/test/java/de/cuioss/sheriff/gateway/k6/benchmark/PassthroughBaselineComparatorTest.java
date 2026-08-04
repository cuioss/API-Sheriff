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
package de.cuioss.sheriff.gateway.k6.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cuioss.sheriff.gateway.k6.benchmark.PassthroughBaselineComparator.ComparisonResult;
import de.cuioss.sheriff.gateway.k6.benchmark.PassthroughBaselineComparator.Verdict;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PassthroughBaselineComparator}'s noise-band verdict logic.
 * <p>
 * The three acceptance behaviours the empty-{@code passthrough_sni} no-regression gate turns on are
 * asserted directly: a delta within the noise band passes, a drop (throughput) or rise (latency)
 * beyond the band is flagged as a regression, and an absent metric renders {@code n/a} — classified
 * {@link Verdict#NOT_MEASURED} — rather than collapsing to a {@code 0} that would read as a measured
 * failure. The band tests are property-style: CUI Test Generator supplies fresh baselines and
 * within-/beyond-band deltas each repetition, so the verdict is exercised across a range of
 * magnitudes rather than a single hand-picked pair.
 * <p>
 * The measurement-window precondition is asserted on the same terms, against the field shape of CI run
 * 30872335137: two arms that served the same request count over windows differing by ~1.56x, whose
 * counter-derived rates therefore differed by the same factor with nothing having slowed down. That
 * pair must be classified {@link Verdict#WINDOW_MISMATCH} and must fail the run, while an equal-window
 * collapse must still be attributed to throughput and an absent window pair must stay
 * {@link Verdict#NOT_MEASURED}.
 */
@EnableGeneratorController
class PassthroughBaselineComparatorTest {

    /** The comparator's own default band, so the tests track it if the production default changes. */
    private static final double TOLERANCE = PassthroughBaselineComparator.DEFAULT_TOLERANCE;

    /** The comparator's own window band, for the same reason — never a hand-copied literal. */
    private static final double WINDOW_TOLERANCE = PassthroughBaselineComparator.DEFAULT_WINDOW_TOLERANCE;

    /** The label the comparator gives the measurement-window precondition row. */
    private static final String WINDOW_LABEL = "measurement window";

    /** The label the comparator gives the throughput row. */
    private static final String THROUGHPUT_LABEL = "throughput";

    /** The nominal 60s k6 load phase both arms declare. */
    private static final double BASELINE_WINDOW_MILLIS = 60_000.0;

    /**
     * The request count both arms of CI run 30872335137 carried — the same work on both sides, which
     * is what makes that run's rate difference an artifact of the window rather than of throughput.
     */
    private static final double FIELD_SHAPE_REQUESTS = 480_000.0;

    /**
     * The window inflation the candidate arm of run 30872335137 carried: its measured window ran about
     * 1.56x the baseline's, which deflated its counter-derived rate by the same factor and produced the
     * false ~35.8% throughput verdict PLAN-46 diagnosed.
     */
    private static final double FIELD_SHAPE_WINDOW_RATIO = 1.56;

    /** An arbitrary but fixed window start; only the two windows' lengths are under test. */
    private static final Instant WINDOW_START = Instant.parse("2026-08-04T10:00:00Z");

    /** Builds a k6-shaped summary carrying a top-level throughput and both latency percentiles. */
    private static JsonObject summary(double rps, double p50, double p99) {
        return JsonParser.parseString("""
                {
                  "requests_per_second": %s,
                  "error_rate": 0.0,
                  "latency_ms": { "avg": 4.0, "p50": %s, "p99": %s }
                }
                """.formatted(rps, p50, p99)).getAsJsonObject();
    }

    /**
     * The same shape as {@link #summary(double, double, double)} plus the {@code start_time} /
     * {@code end_time} pair k6 emits, so the measurement window is derivable the way it is in the field.
     */
    private static JsonObject summary(double rps, double p50, double p99, double windowMillis) {
        return JsonParser.parseString("""
                {
                  "requests_per_second": %s,
                  "error_rate": 0.0,
                  "latency_ms": { "avg": 4.0, "p50": %s, "p99": %s },
                  "start_time": "%s",
                  "end_time": "%s"
                }
                """.formatted(rps, p50, p99, WINDOW_START,
                WINDOW_START.plusMillis((long) windowMillis))).getAsJsonObject();
    }

    /**
     * The counter-derived rate k6 reports: a request count divided by the whole measured window. This
     * is the arithmetic that makes a stretched window read as a throughput collapse.
     */
    private static double ratePerSecond(double requests, double windowMillis) {
        return requests / (windowMillis / 1000.0);
    }

    /** The verdict on one labelled row of a comparison, failing loudly when the row is absent. */
    private static Verdict verdictOf(ComparisonResult result, String label) {
        return result.metrics().stream()
                .filter(metric -> label.equals(metric.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no metric labelled '" + label + "' in " + result.metrics()))
                .verdict();
    }

    // ---- Throughput (higher-is-better) band ------------------------------------------------------

    @RepeatedTest(25)
    void throughputStayingWithinTheNoiseBandPasses() {
        // Arrange -- a candidate whose throughput drop stays strictly inside the tolerance.
        double baseline = Generators.doubles(50.0, 10_000.0).next();
        double dropFraction = Generators.doubles(0.0, TOLERANCE * 0.95).next();
        double candidate = baseline * (1.0 - dropFraction);

        // Act
        Verdict verdict = PassthroughBaselineComparator.throughputVerdict(
                Optional.of(candidate), Optional.of(baseline), TOLERANCE);

        // Assert -- a within-band throughput drop is noise, not a regression.
        assertEquals(Verdict.PASS, verdict,
                () -> "candidate %.4f vs baseline %.4f (drop %.4f) is within the %.2f band"
                        .formatted(candidate, baseline, dropFraction, TOLERANCE));
    }

    @RepeatedTest(25)
    void throughputDroppingBeyondTheNoiseBandRegresses() {
        // Arrange -- a candidate whose throughput drop exceeds the tolerance.
        double baseline = Generators.doubles(50.0, 10_000.0).next();
        double dropFraction = Generators.doubles(TOLERANCE * 1.05, 0.9).next();
        double candidate = baseline * (1.0 - dropFraction);

        // Act
        Verdict verdict = PassthroughBaselineComparator.throughputVerdict(
                Optional.of(candidate), Optional.of(baseline), TOLERANCE);

        // Assert -- a beyond-band throughput drop is a real regression.
        assertEquals(Verdict.REGRESSION, verdict,
                () -> "candidate %.4f vs baseline %.4f (drop %.4f) exceeds the %.2f band"
                        .formatted(candidate, baseline, dropFraction, TOLERANCE));
    }

    // ---- Latency (lower-is-better) band ----------------------------------------------------------

    @RepeatedTest(25)
    void latencyRisingWithinTheNoiseBandPasses() {
        // Arrange -- a candidate whose latency rise stays strictly inside the tolerance.
        double baseline = Generators.doubles(0.5, 500.0).next();
        double riseFraction = Generators.doubles(0.0, TOLERANCE * 0.95).next();
        double candidate = baseline * (1.0 + riseFraction);

        // Act
        Verdict verdict = PassthroughBaselineComparator.latencyVerdict(
                Optional.of(candidate), Optional.of(baseline), TOLERANCE);

        // Assert -- a within-band latency rise is noise, not a regression.
        assertEquals(Verdict.PASS, verdict,
                () -> "candidate %.4f vs baseline %.4f (rise %.4f) is within the %.2f band"
                        .formatted(candidate, baseline, riseFraction, TOLERANCE));
    }

    @RepeatedTest(25)
    void latencyRisingBeyondTheNoiseBandRegresses() {
        // Arrange -- a candidate whose latency rise exceeds the tolerance.
        double baseline = Generators.doubles(0.5, 500.0).next();
        double riseFraction = Generators.doubles(TOLERANCE * 1.05, 2.0).next();
        double candidate = baseline * (1.0 + riseFraction);

        // Act
        Verdict verdict = PassthroughBaselineComparator.latencyVerdict(
                Optional.of(candidate), Optional.of(baseline), TOLERANCE);

        // Assert -- a beyond-band latency rise is a real regression.
        assertEquals(Verdict.REGRESSION, verdict,
                () -> "candidate %.4f vs baseline %.4f (rise %.4f) exceeds the %.2f band"
                        .formatted(candidate, baseline, riseFraction, TOLERANCE));
    }

    // ---- Absent metric: NOT_MEASURED, never a false collapse -------------------------------------

    @Test
    void anAbsentMetricIsNotMeasuredOnEitherSideForBothDirections() {
        // Arrange -- one measured value; the other side is absent.
        double measured = Generators.doubles(1.0, 1_000.0).next();

        // Act + Assert -- a missing candidate or baseline is never a regression, only NOT_MEASURED.
        assertEquals(Verdict.NOT_MEASURED, PassthroughBaselineComparator.throughputVerdict(
                Optional.empty(), Optional.of(measured), TOLERANCE));
        assertEquals(Verdict.NOT_MEASURED, PassthroughBaselineComparator.throughputVerdict(
                Optional.of(measured), Optional.empty(), TOLERANCE));
        assertEquals(Verdict.NOT_MEASURED, PassthroughBaselineComparator.latencyVerdict(
                Optional.empty(), Optional.of(measured), TOLERANCE));
        assertEquals(Verdict.NOT_MEASURED, PassthroughBaselineComparator.latencyVerdict(
                Optional.of(measured), Optional.empty(), TOLERANCE));
    }

    @Test
    void anAbsentValueRendersNotAvailableNeverZero() {
        // Arrange
        double value = Generators.doubles(0.01, 9_999.0).next();

        // Act
        String absent = PassthroughBaselineComparator.render(Optional.empty());
        String measured = PassthroughBaselineComparator.render(Optional.of(value));

        // Assert -- absent is the n/a sentinel, never a 0.00 that reads as a measured collapse.
        assertEquals("n/a", absent);
        assertNotEquals("n/a", measured);
        assertNotEquals("0.00", measured);
        assertEquals(String.format(Locale.ROOT, "%.2f", value), measured);
    }

    // ---- End-to-end compare + render over k6-shaped summaries -------------------------------------

    @Test
    void aWithinBandCandidateDoesNotRegressAcrossAllMetrics() {
        // Arrange -- every metric moves half a band in the worse direction (throughput down,
        // latencies up), all still inside the tolerance.
        JsonObject baseline = summary(8_000.0, 2.0, 30.0);
        JsonObject candidate = summary(
                8_000.0 * (1.0 - TOLERANCE * 0.5),
                2.0 * (1.0 + TOLERANCE * 0.5),
                30.0 * (1.0 + TOLERANCE * 0.5));

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);

        // Assert
        assertFalse(result.regressed(), "a within-band candidate must not be flagged as a regression");
    }

    @Test
    void aThroughputCollapseRegressesTheComparison() {
        // Arrange -- a 50% throughput drop, latencies unchanged.
        JsonObject baseline = summary(8_000.0, 2.0, 30.0);
        JsonObject candidate = summary(8_000.0 * 0.5, 2.0, 30.0);

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);

        // Assert
        assertTrue(result.regressed(), "a throughput collapse must be flagged as a regression");
    }

    @Test
    void anUnmeasuredLatencyRendersNotAvailableInTheMarkdownWithoutRegressing() {
        // Arrange -- the candidate summary omits latency_ms entirely (an unmeasured run), while its
        // throughput matches the baseline so throughput alone stays within band.
        JsonObject candidate = JsonParser.parseString(
                "{ \"requests_per_second\": 8000.0 }").getAsJsonObject();
        JsonObject baseline = summary(8_000.0, 2.0, 30.0);

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);
        String rendered = PassthroughBaselineComparator.render(result, TOLERANCE);

        // Assert -- an absent latency metric is NOT_MEASURED, never a regression, and reads n/a — not 0.
        assertFalse(result.regressed(), "an absent latency metric must not read as a regression");
        assertTrue(rendered.contains("n/a"), "an unmeasured metric must render n/a:\n" + rendered);
        assertFalse(rendered.contains("| 0.00 |"),
                "an absent measurement must never render as 0:\n" + rendered);
    }

    // ---- Metric extraction from the summary shape ------------------------------------------------

    @Test
    void metricExtractionTreatsAbsentFieldsAsNotMeasured() {
        // Arrange
        JsonObject full = summary(1234.5, 2.0, 30.0);
        JsonObject noLatency = JsonParser.parseString(
                "{ \"requests_per_second\": 1.0 }").getAsJsonObject();

        // Act + Assert -- present fields are read; absent top-level and nested fields are empty.
        assertEquals(Optional.of(1234.5),
                PassthroughBaselineComparator.topLevelMetric(full, "requests_per_second"));
        assertEquals(Optional.of(2.0), PassthroughBaselineComparator.latencyMetric(full, "p50"));
        assertTrue(PassthroughBaselineComparator.topLevelMetric(noLatency, "missing").isEmpty());
        assertTrue(PassthroughBaselineComparator.latencyMetric(noLatency, "p50").isEmpty());
    }

    // ---- Tolerance resolution from the system property -------------------------------------------

    @Test
    void resolveToleranceDefaultsWhenThePropertyIsUnset() {
        // Arrange
        String prior = System.getProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY);
        System.clearProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY);
        try {
            // Act + Assert
            assertEquals(PassthroughBaselineComparator.DEFAULT_TOLERANCE,
                    PassthroughBaselineComparator.resolveTolerance());
        } finally {
            restoreProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, prior);
        }
    }

    @Test
    void resolveToleranceReadsAValidOverride() {
        // Arrange
        String prior = System.getProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY);
        try {
            System.setProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, "0.25");

            // Act + Assert
            assertEquals(0.25, PassthroughBaselineComparator.resolveTolerance());
        } finally {
            restoreProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, prior);
        }
    }

    @Test
    void resolveToleranceRejectsAnOutOfRangeOrMalformedOverride() {
        // Arrange
        String prior = System.getProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY);
        try {
            System.setProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, "1.5");
            assertThrows(IllegalArgumentException.class,
                    PassthroughBaselineComparator::resolveTolerance,
                    "a value above 1 would disable the gate and must be rejected");

            System.setProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, "-0.1");
            assertThrows(IllegalArgumentException.class,
                    PassthroughBaselineComparator::resolveTolerance,
                    "a negative value would invert the gate and must be rejected");

            System.setProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, "not-a-number");
            assertThrows(IllegalArgumentException.class,
                    PassthroughBaselineComparator::resolveTolerance,
                    "a malformed value must be rejected rather than silently defaulted");
        } finally {
            restoreProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, prior);
        }
    }

    private static void restoreProperty(String property, String prior) {
        if (prior == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, prior);
        }
    }

    // ---- Measurement window: the precondition, not a metric --------------------------------------

    @Test
    void sameWorkOverAnInflatedWindowIsClassifiedWindowMismatchRatherThanRegression() {
        // Arrange -- the field shape from CI run 30872335137: both arms served the SAME request count,
        // but the candidate's measured window ran ~1.56x the baseline's, so its counter-derived rate
        // came out proportionally lower without a single request being served more slowly.
        double candidateWindow = BASELINE_WINDOW_MILLIS * FIELD_SHAPE_WINDOW_RATIO;
        JsonObject baseline = summary(ratePerSecond(FIELD_SHAPE_REQUESTS, BASELINE_WINDOW_MILLIS),
                2.0, 30.0, BASELINE_WINDOW_MILLIS);
        JsonObject candidate = summary(ratePerSecond(FIELD_SHAPE_REQUESTS, candidateWindow),
                2.0, 30.0, candidateWindow);

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);

        // Assert -- the pair is refused as incomparable, and it is the WINDOW row that says so.
        assertEquals(Verdict.WINDOW_MISMATCH, verdictOf(result, WINDOW_LABEL),
                "a %.2fx window inflation must exceed the %.2f window band".formatted(
                        FIELD_SHAPE_WINDOW_RATIO, WINDOW_TOLERANCE));
        assertTrue(result.windowMismatched(),
                "an incomparable window pair must be reported as a window mismatch");
    }

    @Test
    void anIncomparableWindowPairFailsTheRun() {
        // Arrange -- the same field shape as above.
        double candidateWindow = BASELINE_WINDOW_MILLIS * FIELD_SHAPE_WINDOW_RATIO;
        JsonObject baseline = summary(ratePerSecond(FIELD_SHAPE_REQUESTS, BASELINE_WINDOW_MILLIS),
                2.0, 30.0, BASELINE_WINDOW_MILLIS);
        JsonObject candidate = summary(ratePerSecond(FIELD_SHAPE_REQUESTS, candidateWindow),
                2.0, 30.0, candidateWindow);

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);

        // Assert -- pins the operator's ruling as executable behaviour: an incomparable pair FAILS the
        // run. It is deliberately not rescaled to a common window and not downgraded to a warning.
        assertTrue(result.regressed(), "a window mismatch must fail the run, not merely warn");
    }

    @Test
    void theInflatedWindowPairReadsAsAThroughputRegressionWithoutTheWindowPrecondition() {
        // Arrange -- the same two rates, compared the way the pre-window comparator did it: throughput
        // alone, with no window row to refuse the pair first.
        double candidateWindow = BASELINE_WINDOW_MILLIS * FIELD_SHAPE_WINDOW_RATIO;
        double baselineRate = ratePerSecond(FIELD_SHAPE_REQUESTS, BASELINE_WINDOW_MILLIS);
        double candidateRate = ratePerSecond(FIELD_SHAPE_REQUESTS, candidateWindow);

        // Act
        Verdict throughputOnly = PassthroughBaselineComparator.throughputVerdict(
                Optional.of(candidateRate), Optional.of(baselineRate), TOLERANCE);

        // Assert -- fail-without-fix, made explicit: on exactly this pair the throughput row alone says
        // REGRESSION (the ~35.8% drop that no request ever experienced), which is the false verdict the
        // lane shipped. The fix does not change this arithmetic -- it adds the window row that runs
        // first, so the same pair is now refused as incomparable instead of blamed on throughput.
        assertEquals(Verdict.REGRESSION, throughputOnly,
                () -> "candidate %.2f vs baseline %.2f RPS is a %.1f%% drop on paper"
                        .formatted(candidateRate, baselineRate,
                                (1.0 - candidateRate / baselineRate) * 100.0));
    }

    @Test
    void anEqualWindowThroughputCollapseStillRegressesOnThroughput() {
        // Arrange -- the matched negative control for the case above: windows identical, so the window
        // precondition holds and the throughput row is believable. Half the work in the same window.
        JsonObject baseline = summary(ratePerSecond(FIELD_SHAPE_REQUESTS, BASELINE_WINDOW_MILLIS),
                2.0, 30.0, BASELINE_WINDOW_MILLIS);
        JsonObject candidate = summary(ratePerSecond(FIELD_SHAPE_REQUESTS * 0.5, BASELINE_WINDOW_MILLIS),
                2.0, 30.0, BASELINE_WINDOW_MILLIS);

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);

        // Assert -- the window guard must not swallow a genuine collapse: this one is still a
        // REGRESSION, attributed to throughput rather than to the measurement.
        assertEquals(Verdict.PASS, verdictOf(result, WINDOW_LABEL),
                "identical windows must be comparable");
        assertEquals(Verdict.REGRESSION, verdictOf(result, THROUGHPUT_LABEL),
                "a genuine equal-window throughput collapse must still regress");
        assertFalse(result.windowMismatched(),
                "a genuine collapse must not be misreported as a window mismatch");
        assertTrue(result.regressed(), "a genuine throughput collapse must fail the run");
    }

    @Test
    void anAbsentWindowPairIsNotMeasuredAndNeverFailsTheRun() {
        // Arrange -- summaries in the pre-window shape, carrying no start_time / end_time at all, with
        // every other metric identical so nothing else could fail the run.
        JsonObject baseline = summary(8_000.0, 2.0, 30.0);
        JsonObject candidate = summary(8_000.0, 2.0, 30.0);

        // Act
        ComparisonResult result = PassthroughBaselineComparator.compare(candidate, baseline, TOLERANCE);

        // Assert -- an older summary shape is not retroactively broken: absent is NOT_MEASURED, never a
        // zero-length window that would read as maximally mismatched.
        assertEquals(Verdict.NOT_MEASURED, verdictOf(result, WINDOW_LABEL),
                "an absent window pair must be NOT_MEASURED");
        assertFalse(result.windowMismatched(), "an absent window pair must not report a mismatch");
        assertFalse(result.regressed(), "an absent window pair must never fail the run");
    }

    @Test
    void aWindowMissingOnEitherSideAloneIsNotMeasured() {
        // Arrange
        double measured = Generators.doubles(1_000.0, 120_000.0).next();

        // Act + Assert -- one-sided absence is still absence, in both directions.
        assertEquals(Verdict.NOT_MEASURED, PassthroughBaselineComparator.windowVerdict(
                Optional.empty(), Optional.of(measured), WINDOW_TOLERANCE));
        assertEquals(Verdict.NOT_MEASURED, PassthroughBaselineComparator.windowVerdict(
                Optional.of(measured), Optional.empty(), WINDOW_TOLERANCE));
    }

    @RepeatedTest(25)
    void windowsAgreeingInsideTheBandAreComparable() {
        // Arrange -- a drift strictly inside the window band, in either direction.
        double baseline = Generators.doubles(10_000.0, 300_000.0).next();
        double driftFraction = Generators.doubles(-WINDOW_TOLERANCE * 0.95, WINDOW_TOLERANCE * 0.95).next();
        double candidate = baseline * (1.0 + driftFraction);

        // Act
        Verdict verdict = PassthroughBaselineComparator.windowVerdict(
                Optional.of(candidate), Optional.of(baseline), WINDOW_TOLERANCE);

        // Assert
        assertEquals(Verdict.PASS, verdict,
                () -> "candidate window %.2f vs baseline %.2f (drift %.4f) is within the %.2f band"
                        .formatted(candidate, baseline, driftFraction, WINDOW_TOLERANCE));
    }

    @RepeatedTest(25)
    void windowsDisagreeingBeyondTheBandAreIncomparable() {
        // Arrange -- a drift beyond the window band, in either direction: a window that ran short is as
        // incomparable as one that ran long, because the rate is a quotient either way.
        double baseline = Generators.doubles(10_000.0, 300_000.0).next();
        double magnitude = Generators.doubles(WINDOW_TOLERANCE * 1.05, 0.9).next();
        double driftFraction = Generators.booleans().next() ? magnitude : -magnitude;
        double candidate = baseline * (1.0 + driftFraction);

        // Act
        Verdict verdict = PassthroughBaselineComparator.windowVerdict(
                Optional.of(candidate), Optional.of(baseline), WINDOW_TOLERANCE);

        // Assert
        assertEquals(Verdict.WINDOW_MISMATCH, verdict,
                () -> "candidate window %.2f vs baseline %.2f (drift %.4f) exceeds the %.2f band"
                        .formatted(candidate, baseline, driftFraction, WINDOW_TOLERANCE));
    }

    @Test
    void theBoundedGracefulStopTailStaysInsideTheWindowBandWhileTheObservedInflationDoesNot() {
        // Arrange -- the two magnitudes the band was chosen between: the worst case the bounded 5s
        // gracefulStop can add to a 60s load phase, and the inflation run 30872335137 actually carried.
        double healthyWorstCase = BASELINE_WINDOW_MILLIS + 5_000.0;
        double observedInflation = BASELINE_WINDOW_MILLIS * FIELD_SHAPE_WINDOW_RATIO;

        // Act
        Verdict healthy = PassthroughBaselineComparator.windowVerdict(
                Optional.of(healthyWorstCase), Optional.of(BASELINE_WINDOW_MILLIS), WINDOW_TOLERANCE);
        Verdict inflated = PassthroughBaselineComparator.windowVerdict(
                Optional.of(observedInflation), Optional.of(BASELINE_WINDOW_MILLIS), WINDOW_TOLERANCE);

        // Assert -- the band must separate the two, or it is either useless or permanently red.
        assertEquals(Verdict.PASS, healthy,
                "a bounded 5s tail on a 60s run must never trip the window band");
        assertEquals(Verdict.WINDOW_MISMATCH, inflated,
                "the observed window inflation must trip the window band");
    }

    @Test
    void windowMillisReadsTheIsoPairAndTreatsAnAbsentOrMalformedPairAsNotMeasured() {
        // Arrange
        JsonObject windowed = summary(8_000.0, 2.0, 30.0, BASELINE_WINDOW_MILLIS);
        JsonObject withoutWindow = summary(8_000.0, 2.0, 30.0);
        JsonObject malformed = JsonParser.parseString("""
                { "start_time": "not-a-timestamp", "end_time": "2026-08-04T10:01:00Z" }
                """).getAsJsonObject();

        // Act + Assert -- a measured pair yields the window length in millis; an absent or unparseable
        // pair yields empty rather than a 0 that would read as a maximally mismatched window.
        assertEquals(Optional.of(BASELINE_WINDOW_MILLIS),
                PassthroughBaselineComparator.windowMillis(windowed));
        assertTrue(PassthroughBaselineComparator.windowMillis(withoutWindow).isEmpty(),
                "an absent start/end pair must be empty");
        assertTrue(PassthroughBaselineComparator.windowMillis(malformed).isEmpty(),
                "an unparseable timestamp must be empty, never a zero-length window");
    }

    // ---- Window-tolerance resolution from the system property -------------------------------------

    @Test
    void resolveWindowToleranceDefaultsWhenThePropertyIsUnset() {
        // Arrange
        String prior = System.getProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY);
        System.clearProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY);
        try {
            // Act + Assert
            assertEquals(PassthroughBaselineComparator.DEFAULT_WINDOW_TOLERANCE,
                    PassthroughBaselineComparator.resolveWindowTolerance());
        } finally {
            restoreProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, prior);
        }
    }

    @Test
    void resolveWindowToleranceReadsAValidOverride() {
        // Arrange
        String prior = System.getProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY);
        try {
            System.setProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, "0.03");

            // Act + Assert
            assertEquals(0.03, PassthroughBaselineComparator.resolveWindowTolerance());
        } finally {
            restoreProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, prior);
        }
    }

    @Test
    void resolveWindowToleranceRejectsAnOutOfRangeOrMalformedOverride() {
        // Arrange
        String prior = System.getProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY);
        try {
            System.setProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, "1.5");
            assertThrows(IllegalArgumentException.class,
                    PassthroughBaselineComparator::resolveWindowTolerance,
                    "a value above 1 would disable the window precondition and must be rejected");

            System.setProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, "-0.1");
            assertThrows(IllegalArgumentException.class,
                    PassthroughBaselineComparator::resolveWindowTolerance,
                    "a negative value would invert the window precondition and must be rejected");

            System.setProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, "not-a-number");
            assertThrows(IllegalArgumentException.class,
                    PassthroughBaselineComparator::resolveWindowTolerance,
                    "a malformed value must be rejected rather than silently defaulted");
        } finally {
            restoreProperty(PassthroughBaselineComparator.WINDOW_TOLERANCE_PROPERTY, prior);
        }
    }
}
