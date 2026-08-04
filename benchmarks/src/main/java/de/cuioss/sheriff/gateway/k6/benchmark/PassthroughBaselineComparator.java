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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies that a gateway configured with no {@code tls.passthrough_sni} does not serve the plain
 * proxied route measurably worse than the primary gateway, which declares one.
 * <p>
 * The two summaries come from two <em>different gateway instances</em> running concurrently in the
 * same lane, not from two modes of one process: {@code passthrough_sni} is a property of the whole
 * gateway — declaring it non-empty is what starts the accept-time SNI front listener at boot — so
 * emptiness cannot be selected per request. The {@code passthroughRelayEmpty} candidate is measured
 * against the dedicated {@code api-sheriff-passthrough-empty} instance, whose overlaid
 * {@code gateway.yaml} declares no {@code passthrough_sni} and where the front listener is therefore
 * never created (D1's zero-overhead default, terminated Quarkus HTTPS listener owning the public
 * port directly). The {@code proxiedStatic} baseline is measured against the primary instance, which
 * declares two {@code passthrough_sni} entries for its whole lifetime. Both arms drive the same
 * {@code /proxy/static} route to the same {@code nginx-static} upstream on the same image under the
 * same resource limits, so the two runs differ in exactly one thing: the presence of the accept-time
 * front listener.
 * <p>
 * <strong>The bound is one-sided, and deliberately so.</strong> On the baseline instance the front
 * listener owns the public port, so every connection — including the plain proxied ones this route
 * carries — pays an accept-time ClientHello peek and an L4 relay hop to the internal terminated
 * listener. The candidate instance pays neither. The expected result is therefore that the candidate
 * measures <em>at or above</em> the baseline, and the gate asserts only
 * {@code candidate >= baseline * (1 - tolerance)}: it exists to catch the front listener's absence
 * somehow costing throughput, never to cap the headroom its absence yields.
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
 * <p>
 * <strong>Window comparability is a precondition, not a metric.</strong> {@code requests_per_second}
 * is a counter rate over the run's whole measured window — load phase plus graceful-stop tail — so two
 * arms measured over windows of different length do not produce comparable rates at all. A run in which
 * one arm's window was stretched by a stalled VU reports a proportionally deflated rate that reads as a
 * throughput collapse which never happened; this is exactly the false 35.8% verdict PLAN-46 diagnosed.
 * The comparator therefore reads {@code start_time} / {@code end_time} from both summaries and compares
 * the two windows first, against {@link #DEFAULT_WINDOW_TOLERANCE} (overridable via
 * {@value #WINDOW_TOLERANCE_PROPERTY}).
 * <p>
 * <strong>Operator ruling — refuse and fail; do not normalise.</strong> An incomparable window pair
 * FAILS the run. It is deliberately NOT rescaled to a common window, and deliberately NOT downgraded to
 * a warning. Rescaling would silently republish a number the run never measured, and a warning would let
 * a lane whose measurement window is broken keep producing baseline history that looks authoritative.
 * The failure names the two windows so the operator fixes the measurement rather than the threshold. An
 * ABSENT window pair is the one exception: it reads {@code n/a} / {@link Verdict#NOT_MEASURED} and never
 * fails, so a summary shape that predates the window fields is not retroactively broken.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PassthroughBaselineComparator {

    private static final CuiLogger LOGGER = new CuiLogger(PassthroughBaselineComparator.class);

    /**
     * The candidate summary, produced by {@code passthrough_relay.js} with
     * {@code PASSTHROUGH_SNI=empty} against the no-{@code passthrough_sni} gateway instance.
     */
    static final String CANDIDATE_SUMMARY = "passthroughRelayEmpty-summary.json";

    /** The plain-proxy baseline summary from the primary instance, taken in the same lane. */
    static final String BASELINE_SUMMARY = "proxiedStatic-summary.json";

    /** Name of the rendered artifact, written directly under the results directory. */
    static final String OUTPUT_FILE_NAME = "passthrough-baseline-comparison.md";

    /** The fractional noise band applied when the {@code passthrough.baseline.tolerance} property is unset. */
    static final double DEFAULT_TOLERANCE = 0.15;

    /** System property overriding {@link #DEFAULT_TOLERANCE}. */
    static final String TOLERANCE_PROPERTY = "passthrough.baseline.tolerance";

    /**
     * The fractional band the two arms' measured windows must agree within for their rates to be
     * comparable at all, applied when {@value #WINDOW_TOLERANCE_PROPERTY} is unset.
     * <p>
     * Tighter than {@link #DEFAULT_TOLERANCE} on purpose: the window is a precondition of the
     * comparison rather than one of the compared metrics, so it must catch a drift small enough to
     * still move the throughput verdict. It is also set above the worst case the k6 lane can now
     * produce — the bounded 5s graceful stop caps window inflation at 5/60 = 8.3% of a 60s run — so a
     * healthy run never trips it while a reverted or unbounded tail always does.
     */
    static final double DEFAULT_WINDOW_TOLERANCE = 0.10;

    /** System property overriding {@link #DEFAULT_WINDOW_TOLERANCE}. */
    static final String WINDOW_TOLERANCE_PROPERTY = "passthrough.baseline.window.tolerance";

    private static final String FIELD_REQUESTS_PER_SECOND = "requests_per_second";
    private static final String FIELD_LATENCY_MS = "latency_ms";
    private static final String FIELD_P50 = "p50";
    private static final String FIELD_P99 = "p99";
    private static final String FIELD_START_TIME = "start_time";
    private static final String FIELD_END_TIME = "end_time";

    private static final String NOT_AVAILABLE = "n/a";

    private PassthroughBaselineComparator() {
        // utility class
    }

    /**
     * Whether a single metric stayed within the band, regressed, was not measured on either side, or —
     * for the measurement window alone — made the whole comparison invalid.
     */
    enum Verdict {
        PASS, REGRESSION, NOT_MEASURED, WINDOW_MISMATCH
    }

    /** One metric's candidate-vs-baseline comparison. */
    record MetricComparison(String label, String unit, Optional<Double> candidate,
    Optional<Double> baseline, Verdict verdict) {
    }

    /** The full comparison across the measurement window and the throughput and latency metrics. */
    record ComparisonResult(List<MetricComparison> metrics) {

        /**
         * A regression on any measured metric fails the run, and so does an incomparable measurement
         * window; a not-measured metric never does.
         *
         * @return whether the run must fail
         */
        boolean regressed() {
            return metrics.stream().anyMatch(metric -> metric.verdict() == Verdict.REGRESSION
                    || metric.verdict() == Verdict.WINDOW_MISMATCH);
        }

        /**
         * Whether the two arms' measured windows were too far apart for their rates to be compared.
         * <p>
         * Reported separately from {@link #regressed()} because it is a different failure: the run did
         * not regress, it produced a comparison that cannot be believed either way.
         *
         * @return whether the window precondition failed
         */
        boolean windowMismatched() {
            return metrics.stream().anyMatch(metric -> metric.verdict() == Verdict.WINDOW_MISMATCH);
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

        // Checked BEFORE the regression branch: when the windows are incomparable the throughput verdict
        // is meaningless, so reporting it as a regression would name the wrong defect.
        if (result.windowMismatched()) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.PASSTHROUGH_BASELINE_WINDOW_MISMATCH,
                    formatTolerance(resolveWindowTolerance()), windowMismatchDetail(result));
            throw new IllegalStateException(
                    "empty-passthrough_sni run and baseline were measured over incomparable windows");
        }
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
        // The window row leads the list because it is the comparison's precondition: when it fails, the
        // rows beneath it describe rates taken over different denominators and mean nothing.
        Optional<Double> candidateWindow = windowMillis(candidate);
        Optional<Double> baselineWindow = windowMillis(baseline);
        MetricComparison window = new MetricComparison("measurement window", "ms",
                candidateWindow, baselineWindow,
                windowVerdict(candidateWindow, baselineWindow, resolveWindowTolerance()));
        MetricComparison rps = new MetricComparison("throughput", "RPS",
                topLevelMetric(candidate, FIELD_REQUESTS_PER_SECOND),
                topLevelMetric(baseline, FIELD_REQUESTS_PER_SECOND),
                throughputVerdict(topLevelMetric(candidate, FIELD_REQUESTS_PER_SECOND),
                        topLevelMetric(baseline, FIELD_REQUESTS_PER_SECOND), tolerance));
        MetricComparison p50 = latencyComparison("latency p50", candidate, baseline, FIELD_P50, tolerance);
        MetricComparison p99 = latencyComparison("latency p99", candidate, baseline, FIELD_P99, tolerance);
        return new ComparisonResult(List.of(window, rps, p50, p99));
    }

    /**
     * Reads a summary's measured window in milliseconds from its {@code start_time} / {@code end_time}
     * ISO-8601 pair.
     * <p>
     * An absent, non-string or unparseable pair is treated as NOT MEASURED rather than as a zero-length
     * window: a {@code 0} here would read as a maximally mismatched window and would fail every run
     * whose summaries predate these fields.
     *
     * @param summary the parsed summary
     * @return the window length in milliseconds, or empty when the pair is absent or unparseable
     */
    static Optional<Double> windowMillis(JsonObject summary) {
        Optional<Instant> start = instantField(summary, FIELD_START_TIME);
        Optional<Instant> end = instantField(summary, FIELD_END_TIME);
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((double) (end.get().toEpochMilli() - start.get().toEpochMilli()));
    }

    /**
     * Parses one ISO-8601 instant field, treating an absent, non-string or malformed value as absent.
     *
     * @param summary the parsed summary
     * @param field   the field name
     * @return the parsed instant, or empty
     */
    private static Optional<Instant> instantField(JsonObject summary, String field) {
        if (!summary.has(field) || !summary.get(field).isJsonPrimitive()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(summary.get(field).getAsString()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The comparability verdict for the two arms' measured windows.
     * <p>
     * The band is applied to the BASELINE window, matching how {@link #throughputVerdict} and
     * {@link #latencyVerdict} anchor their bands, so the same override reads the same way across all
     * three. A mismatch is {@link Verdict#WINDOW_MISMATCH} and fails the run — it is never normalised
     * away and never downgraded to a warning (see the class javadoc's operator ruling).
     *
     * @param candidate       the candidate window in milliseconds, when measured
     * @param baseline        the baseline window in milliseconds, when measured
     * @param windowTolerance the fractional band the two windows must agree within
     * @return {@link Verdict#NOT_MEASURED} when either side is absent, else PASS / WINDOW_MISMATCH
     */
    static Verdict windowVerdict(Optional<Double> candidate, Optional<Double> baseline, double windowTolerance) {
        if (candidate.isEmpty() || baseline.isEmpty()) {
            return Verdict.NOT_MEASURED;
        }
        double allowedDrift = Math.abs(baseline.get()) * windowTolerance;
        return Math.abs(candidate.get() - baseline.get()) <= allowedDrift
                ? Verdict.PASS : Verdict.WINDOW_MISMATCH;
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
                .append("; window-comparability band: ").append(formatTolerance(resolveWindowTolerance()))
                .append(". The measurement-window row is a PRECONDITION, not a metric: a "
                        + "`WINDOW_MISMATCH` means the two arms were measured over different-length "
                        + "windows, so their rates are not comparable and the run fails rather than "
                        + "being rescaled. `n/a` means the run did not measure the metric.\n\n")
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
        return detailFor(result, Verdict.REGRESSION);
    }

    /**
     * Renders the mismatched window for the failure diagnostic, naming both windows so the operator
     * fixes the measurement rather than the threshold.
     */
    private static String windowMismatchDetail(ComparisonResult result) {
        return detailFor(result, Verdict.WINDOW_MISMATCH);
    }

    /** Renders every metric carrying the given verdict as a single diagnostic fragment. */
    private static String detailFor(ComparisonResult result, Verdict verdict) {
        return result.metrics().stream()
                .filter(metric -> metric.verdict() == verdict)
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
        return resolveFraction(TOLERANCE_PROPERTY, DEFAULT_TOLERANCE);
    }

    /**
     * Resolves the window-comparability band from the {@code passthrough.baseline.window.tolerance}
     * system property, falling back to {@link #DEFAULT_WINDOW_TOLERANCE}. An invalid override is fatal
     * under the same rule {@link #resolveTolerance()} applies: a negative value would invert the
     * precondition and a value above 1 would disable it.
     *
     * @return the fractional window band in {@code [0, 1]}
     */
    static double resolveWindowTolerance() {
        return resolveFraction(WINDOW_TOLERANCE_PROPERTY, DEFAULT_WINDOW_TOLERANCE);
    }

    /**
     * Resolves one fraction-valued system property, rejecting a set-but-invalid override rather than
     * silently defaulting it.
     *
     * @param property the system-property name
     * @param fallback the value used when the property is unset or blank
     * @return the resolved fraction in {@code [0, 1]}
     */
    private static double resolveFraction(String property, double fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        double value;
        try {
            value = Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    property + " must be a fraction in [0, 1], got \"" + raw + "\"", e);
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    property + " must be a fraction in [0, 1], got \"" + raw + "\"");
        }
        return value;
    }
}
