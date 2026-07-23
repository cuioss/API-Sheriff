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

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 */
@EnableGeneratorController
class PassthroughBaselineComparatorTest {

    /** The comparator's own default band, so the tests track it if the production default changes. */
    private static final double TOLERANCE = PassthroughBaselineComparator.DEFAULT_TOLERANCE;

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
            restoreToleranceProperty(prior);
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
            restoreToleranceProperty(prior);
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
            restoreToleranceProperty(prior);
        }
    }

    private static void restoreToleranceProperty(String prior) {
        if (prior == null) {
            System.clearProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY);
        } else {
            System.setProperty(PassthroughBaselineComparator.TOLERANCE_PROPERTY, prior);
        }
    }
}
