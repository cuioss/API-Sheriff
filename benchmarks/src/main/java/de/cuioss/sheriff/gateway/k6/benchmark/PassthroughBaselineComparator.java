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
import de.cuioss.tools.logging.CuiLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies that the empty-{@code passthrough_sni} proxied route does not regress beyond the run's
 * noise band against the stored PLAN-04 plain-proxy baseline.
 * <p>
 * When {@code tls.passthrough_sni} is empty the accept-time front listener is never created (D1's
 * zero-overhead default) and the terminated Quarkus HTTPS listener owns the public port directly, so
 * the empty-mode run measures exactly the same proxied static route as the PLAN-04 {@code unauth}
 * ({@code proxiedStatic}) aspect. This comparator reads the {@code passthroughRelayEmpty} summary
 * against the {@code proxiedStatic} summary produced in the same lane and asserts no regression
 * beyond a percentile-band tolerance.
 * <p>
 * <strong>Noise band, not a point comparison.</strong> These are single-node, containerized,
 * local-network measurements, so a small run-to-run delta is noise rather than signal. The band is a
 * fixed fractional tolerance (default {@value #DEFAULT_TOLERANCE}, overridable via the
 * {@code passthrough.baseline.tolerance} system property) rather than a standard-deviation gate,
 * because k6 omits {@code latency_ms.stdev} (see the benchmark README): throughput (higher is
 * better) may not fall below {@code baseline * (1 - tolerance)}, and each latency percentile (lower
 * is better) may not exceed {@code baseline * (1 + tolerance)}.
 * <p>
 * <strong>Absent metric is {@code n/a}, never {@code 0}.</strong> A metric a run did not measure is
 * rendered {@code n/a} and classified {@link Verdict#NOT_MEASURED} — never a {@code 0} that would
 * read as a measured collapse and never a false regression — mirroring {@link ComparisonSummaryWriter}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PassthroughBaselineComparator {

    private static final CuiLogger LOGGER = new CuiLogger(PassthroughBaselineComparator.class);

    /** The empty-mode candidate summary, produced by {@code passthrough_relay.js} with {@code PASSTHROUGH_SNI=empty}. */
    static final String CANDIDATE_SUMMARY = "passthroughRelayEmpty-summary.json";

    /** The stored PLAN-04 plain-proxy baseline summary the candidate is read against. */
    static final String BASELINE_SUMMARY = "proxiedStatic-summary.json";

    /** Name of the rendered artifact, written directly under the results directory. */
    static final String OUTPUT_FILE_NAME = "passthrough-baseline-comparison.md";

    /** The fractional noise band applied when the {@code passthrough.baseline.tolerance} property is unset. */
    static final double DEFAULT_TOLERANCE = 0.15;

    /** System property overriding {@link #DEFAULT_TOLERANCE}. */
    static final String TOLERANCE_PROPERTY = "passthrough.baseline.tolerance";

    private static final String FIELD_REQUESTS_PER_SECOND = "requests_per_second";
    private static final String FIELD_LATENCY_MS = "latency_ms";
    private static final String FIELD_P50 = "p50";
    private static final String FIELD_P99 = "p99";

    private static final String NOT_AVAILABLE = "n/a";

    private PassthroughBaselineComparator() {
        // utility class
    }

    /** Whether a single metric stayed within the band, regressed, or was not measured on either side. */
    enum Verdict {
        PASS, REGRESSION, NOT_MEASURED
    }

    /** One metric's candidate-vs-baseline comparison. */
    record MetricComparison(String label, String unit, Optional<Double> candidate,
    Optional<Double> baseline, Verdict verdict) {
    }

    /** The full comparison across the throughput and latency metrics. */
    record ComparisonResult(List<MetricComparison> metrics) {

        /** A regression on any measured metric fails the run; a not-measured metric never does. */
        boolean regressed() {
            return metrics.stream().anyMatch(metric -> metric.verdict() == Verdict.REGRESSION);
        }
    }

    /**
     * Renders the passthrough baseline comparison, writes it beside the summaries, and exits non-zero
     * when the empty-mode run regressed beyond the noise band.
     *
     * @param args {@code <k6-results-dir>} — the directory holding the k6 {@code *-summary.json} exports
     * @throws IOException when a summary cannot be read or the artifact cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.PASSTHROUGH_BASELINE_USAGE_ERROR);
            throw new IllegalArgumentException("expected <k6-results-dir>, got " + args.length + " argument(s)");
        }
        Path resultsDir = Path.of(args[0]);
        Path candidatePath = resultsDir.resolve(CANDIDATE_SUMMARY);
        Path baselinePath = resultsDir.resolve(BASELINE_SUMMARY);

        Optional<JsonObject> candidate = readSummary(candidatePath, resultsDir);
        Optional<JsonObject> baseline = readSummary(baselinePath, resultsDir);
        if (candidate.isEmpty() || baseline.isEmpty()) {
            throw new IllegalStateException("missing a summary the passthrough baseline comparison requires under "
                    + resultsDir);
        }

        double tolerance = resolveTolerance();
        LOGGER.info(K6BenchmarkLogMessages.INFO.PASSTHROUGH_BASELINE_START, CANDIDATE_SUMMARY, BASELINE_SUMMARY);

        ComparisonResult result = compare(candidate.get(), baseline.get(), tolerance);
        String rendered = render(result, tolerance);
        Files.writeString(resultsDir.resolve(OUTPUT_FILE_NAME), rendered);

        if (result.regressed()) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.PASSTHROUGH_BASELINE_REGRESSION,
                    formatTolerance(tolerance), regressionDetail(result));
            throw new IllegalStateException("empty-passthrough_sni run regressed beyond the noise band");
        }
        LOGGER.info(K6BenchmarkLogMessages.INFO.PASSTHROUGH_BASELINE_OK, formatTolerance(tolerance));
    }

    /**
     * Reads one summary, logging and returning empty when it is absent rather than throwing here.
     *
     * @param summaryPath the summary file
     * @param resultsDir  the directory it was expected under, for the diagnostic
     * @return the parsed summary, or empty when the file does not exist
     * @throws IOException when the present file cannot be read
     */
    private static Optional<JsonObject> readSummary(Path summaryPath, Path resultsDir) throws IOException {
        if (!Files.isRegularFile(summaryPath)) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.PASSTHROUGH_BASELINE_SUMMARY_MISSING,
                    summaryPath.getFileName(), resultsDir);
            return Optional.empty();
        }
        return Optional.of(JsonParser.parseString(Files.readString(summaryPath)).getAsJsonObject());
    }

    /**
     * Compares the throughput and both latency percentiles of the empty-mode candidate against the
     * baseline.
     *
     * @param candidate the empty-passthrough_sni summary
     * @param baseline  the PLAN-04 plain-proxy baseline summary
     * @param tolerance the fractional noise band
     * @return the per-metric comparison
     */
    static ComparisonResult compare(JsonObject candidate, JsonObject baseline, double tolerance) {
        MetricComparison rps = new MetricComparison("throughput", "RPS",
                topLevelMetric(candidate, FIELD_REQUESTS_PER_SECOND),
                topLevelMetric(baseline, FIELD_REQUESTS_PER_SECOND),
                throughputVerdict(topLevelMetric(candidate, FIELD_REQUESTS_PER_SECOND),
                        topLevelMetric(baseline, FIELD_REQUESTS_PER_SECOND), tolerance));
        MetricComparison p50 = latencyComparison("latency p50", candidate, baseline, FIELD_P50, tolerance);
        MetricComparison p99 = latencyComparison("latency p99", candidate, baseline, FIELD_P99, tolerance);
        return new ComparisonResult(List.of(rps, p50, p99));
    }

    private static MetricComparison latencyComparison(String label, JsonObject candidate, JsonObject baseline,
            String percentileField, double tolerance) {
        Optional<Double> candidateValue = latencyMetric(candidate, percentileField);
        Optional<Double> baselineValue = latencyMetric(baseline, percentileField);
        return new MetricComparison(label, "ms", candidateValue, baselineValue,
                latencyVerdict(candidateValue, baselineValue, tolerance));
    }

    /**
     * The no-regression verdict for a higher-is-better metric (throughput): the candidate must stay at
     * or above {@code baseline * (1 - tolerance)}.
     *
     * @param candidate the candidate value, when measured
     * @param baseline  the baseline value, when measured
     * @param tolerance the fractional noise band
     * @return {@link Verdict#NOT_MEASURED} when either side is absent, else PASS / REGRESSION
     */
    static Verdict throughputVerdict(Optional<Double> candidate, Optional<Double> baseline, double tolerance) {
        if (candidate.isEmpty() || baseline.isEmpty()) {
            return Verdict.NOT_MEASURED;
        }
        double floor = baseline.get() * (1.0 - tolerance);
        return candidate.get() >= floor ? Verdict.PASS : Verdict.REGRESSION;
    }

    /**
     * The no-regression verdict for a lower-is-better metric (a latency percentile): the candidate must
     * stay at or below {@code baseline * (1 + tolerance)}.
     *
     * @param candidate the candidate value, when measured
     * @param baseline  the baseline value, when measured
     * @param tolerance the fractional noise band
     * @return {@link Verdict#NOT_MEASURED} when either side is absent, else PASS / REGRESSION
     */
    static Verdict latencyVerdict(Optional<Double> candidate, Optional<Double> baseline, double tolerance) {
        if (candidate.isEmpty() || baseline.isEmpty()) {
            return Verdict.NOT_MEASURED;
        }
        double ceiling = baseline.get() * (1.0 + tolerance);
        return candidate.get() <= ceiling ? Verdict.PASS : Verdict.REGRESSION;
    }

    /**
     * Reads a top-level numeric field, treating an absent or non-numeric field as not measured.
     *
     * @param summary the parsed summary
     * @param field   the field name
     * @return the value, or empty when absent / non-numeric
     */
    static Optional<Double> topLevelMetric(JsonObject summary, String field) {
        return summary.has(field) && summary.get(field).isJsonPrimitive()
                ? Optional.of(summary.get(field).getAsDouble()) : Optional.empty();
    }

    /**
     * Reads a percentile from the nested {@code latency_ms} object, treating an absent object or field
     * as not measured.
     *
     * @param summary         the parsed summary
     * @param percentileField the percentile field name (e.g. {@code p50})
     * @return the value, or empty when absent / non-numeric
     */
    static Optional<Double> latencyMetric(JsonObject summary, String percentileField) {
        if (!summary.has(FIELD_LATENCY_MS) || !summary.get(FIELD_LATENCY_MS).isJsonObject()) {
            return Optional.empty();
        }
        return topLevelMetric(summary.getAsJsonObject(FIELD_LATENCY_MS), percentileField);
    }

    /**
     * Renders a measured value to two decimals, or {@code n/a} when not measured.
     *
     * @param value the metric value, when measured
     * @return the rendered value, or {@code n/a}
     */
    static String render(Optional<Double> value) {
        return value.map(measured -> String.format(Locale.ROOT, "%.2f", measured)).orElse(NOT_AVAILABLE);
    }

    /**
     * Renders the comparison as a markdown table.
     *
     * @param result    the per-metric comparison
     * @param tolerance the fractional noise band applied
     * @return the rendered markdown document
     */
    static String render(ComparisonResult result, double tolerance) {
        StringBuilder out = new StringBuilder(512);
        out.append("# Passthrough empty-mode baseline comparison\n\n")
                .append("Empty-`passthrough_sni` proxied route (`").append(CANDIDATE_SUMMARY)
                .append("`) vs the PLAN-04 plain-proxy baseline (`").append(BASELINE_SUMMARY).append("`). ")
                .append("Noise band: ").append(formatTolerance(tolerance))
                .append(". `n/a` means the run did not measure the metric.\n\n")
                .append("| Metric | Unit | Empty-mode | Baseline | Verdict |\n")
                .append("|---|---|---|---|---|\n");
        for (MetricComparison metric : result.metrics()) {
            out.append("| ").append(metric.label())
                    .append(" | ").append(metric.unit())
                    .append(" | ").append(render(metric.candidate()))
                    .append(" | ").append(render(metric.baseline()))
                    .append(" | ").append(metric.verdict())
                    .append(" |\n");
        }
        return out.toString();
    }

    /** Renders the regressed metrics for the failure diagnostic. */
    private static String regressionDetail(ComparisonResult result) {
        return result.metrics().stream()
                .filter(metric -> metric.verdict() == Verdict.REGRESSION)
                .map(metric -> "%s (empty-mode %s vs baseline %s %s)".formatted(metric.label(),
                        render(metric.candidate()), render(metric.baseline()), metric.unit()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static String formatTolerance(double tolerance) {
        return String.format(Locale.ROOT, "%.0f%%", tolerance * 100.0);
    }

    /**
     * Resolves the noise band from the {@code passthrough.baseline.tolerance} system property, falling
     * back to {@link #DEFAULT_TOLERANCE}. An invalid override is fatal rather than silently defaulted:
     * a negative value would invert the gate and a value above 1 would disable it.
     *
     * @return the fractional noise band in {@code [0, 1]}
     */
    static double resolveTolerance() {
        String raw = System.getProperty(TOLERANCE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOLERANCE;
        }
        double value;
        try {
            value = Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    TOLERANCE_PROPERTY + " must be a fraction in [0, 1], got \"" + raw + "\"", e);
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    TOLERANCE_PROPERTY + " must be a fraction in [0, 1], got \"" + raw + "\"");
        }
        return value;
    }
}
