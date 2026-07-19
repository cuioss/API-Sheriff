/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.k6.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import de.cuioss.benchmarking.common.config.BenchmarkType;
import de.cuioss.benchmarking.common.constants.BenchmarkConstants;
import de.cuioss.benchmarking.common.converter.BenchmarkConverter;
import de.cuioss.benchmarking.common.model.BenchmarkData;
import de.cuioss.benchmarking.common.report.MetricConversionUtil;
import de.cuioss.benchmarking.common.report.MetricsComputer;
import de.cuioss.tools.logging.CuiLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Converts k6 {@code handleSummary()} output to the central {@link BenchmarkData} model.
 * <p>
 * This is the k6 counterpart of the upstream {@code WrkBenchmarkConverter}: only the raw
 * input format differs, so the downstream report pipeline (badges, 10-run history, trends,
 * GitHub Pages) consumes an identically-shaped {@link BenchmarkData} and needs no change.
 *
 * <h2>Expected summary schema</h2>
 * Each k6 script writes one JSON document per run through the shared
 * {@code k6-scripts/lib/summary.js} helper:
 * <pre>{@code
 * {
 *   "benchmark_name": "healthLiveCheck",
 *   "gateway_target": "api-sheriff",
 *   "start_time": "2026-07-19T10:00:00Z",
 *   "end_time":   "2026-07-19T10:01:00Z",
 *   "requests_per_second": 8511.49,
 *   "error_rate": 0.0,
 *   "latency_ms": {
 *     "avg": 5.87, "stdev": 7.42,
 *     "p50": 1.69, "p75": 3.18, "p90": 14.60, "p99": 29.99
 *   }
 * }
 * }</pre>
 * <p>
 * Latency values are already in milliseconds — k6 reports {@code http_req_duration} in ms —
 * so the converter performs no unit conversion and cannot introduce a conversion regression.
 * Percentiles are keyed exactly as the report pipeline expects ({@code "50.0"}, {@code "75.0"},
 * {@code "90.0"}, {@code "99.0"}); only measured percentiles are emitted and missing ones are
 * never estimated or fabricated.
 *
 * @since 1.0
 */
public class K6BenchmarkConverter implements BenchmarkConverter {

    private static final CuiLogger LOGGER = new CuiLogger(K6BenchmarkConverter.class);

    /**
     * Extension of the summary documents read from the k6 results directory.
     * <p>
     * That directory holds nothing but k6 {@code handleSummary()} exports, so every {@code .json}
     * member is a summary — the same "all files of the toolchain's output extension" contract the
     * wrk converter applied to {@code .txt}. Scripts name their export
     * {@code <benchmark>-summary.json} by convention; the parse keys off the extension so a
     * fixture or a differently-named export is never silently skipped.
     */
    public static final String K6_SUMMARY_FILE_EXTENSION = ".json";

    /** Key for the k6-reported average latency (ms) in {@link BenchmarkData.Benchmark#getAdditionalData()}. */
    static final String LATENCY_AVG_MS = "latencyAvgMs";

    /** Key for the gateway a run was taken against, in {@link BenchmarkData.Benchmark#getAdditionalData()}. */
    static final String GATEWAY_TARGET = "gatewayTarget";

    /** Key for the k6-reported request failure ratio in {@link BenchmarkData.Benchmark#getAdditionalData()}. */
    static final String ERROR_RATE = "errorRate";

    private static final String FIELD_BENCHMARK_NAME = "benchmark_name";
    private static final String FIELD_GATEWAY_TARGET = "gateway_target";
    private static final String FIELD_REQUESTS_PER_SECOND = "requests_per_second";
    private static final String FIELD_ERROR_RATE = "error_rate";
    private static final String FIELD_LATENCY_MS = "latency_ms";

    private static final String SCORE_UNIT = "ops/s";

    /** Percentile field name in the summary mapped to the report-pipeline percentile key. */
    private static final Map<String, String> PERCENTILE_KEYS = Map.of(
            "p50", "50.0",
            "p75", "75.0",
            "p90", "90.0",
            "p99", "99.0");

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern(BenchmarkConstants.Report.DateFormats.DISPLAY_TIMESTAMP_PATTERN)
                    .withZone(ZoneOffset.UTC);

    @Override
    public BenchmarkData convert(Path sourcePath) throws IOException {
        List<BenchmarkData.Benchmark> benchmarks = Files.isDirectory(sourcePath)
                ? parseDirectory(sourcePath)
                : singletonOrEmpty(parseSummaryFile(sourcePath));

        return BenchmarkData.builder()
                .metadata(createMetadata())
                .overview(createOverview(benchmarks))
                .benchmarks(benchmarks)
                .build();
    }

    private static List<BenchmarkData.Benchmark> singletonOrEmpty(BenchmarkData.Benchmark benchmark) {
        return benchmark != null ? List.of(benchmark) : List.of();
    }

    private List<BenchmarkData.Benchmark> parseDirectory(Path dir) throws IOException {
        List<Path> summaryFiles;
        try (Stream<Path> files = Files.list(dir)) {
            summaryFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(K6_SUMMARY_FILE_EXTENSION))
                    .sorted()
                    .toList();
        }

        List<BenchmarkData.Benchmark> benchmarks = new ArrayList<>();
        for (Path summaryFile : summaryFiles) {
            BenchmarkData.Benchmark benchmark = parseSummaryFile(summaryFile);
            if (benchmark != null) {
                benchmarks.add(benchmark);
            }
        }
        return benchmarks;
    }

    /**
     * Parses one k6 summary document into a benchmark.
     *
     * @param file the k6 summary file
     * @return the parsed benchmark, or {@code null} when the document is unparseable or omits a
     *         mandatory field — the caller skips such files rather than emitting a partial benchmark
     * @throws IOException if reading the file fails
     */
    private BenchmarkData.Benchmark parseSummaryFile(Path file) throws IOException {
        JsonObject summary;
        try {
            summary = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            LOGGER.error(e, K6BenchmarkLogMessages.ERROR.FAILED_PARSE_SUMMARY, file, e.getMessage());
            return null;
        }

        String name = requiredString(summary, FIELD_BENCHMARK_NAME, file);
        if (name == null) {
            return null;
        }
        if (!summary.has(FIELD_REQUESTS_PER_SECOND)) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.INCOMPLETE_SUMMARY, file, FIELD_REQUESTS_PER_SECOND);
            return null;
        }

        double requestsPerSec = summary.get(FIELD_REQUESTS_PER_SECOND).getAsDouble();
        JsonObject latency = summary.has(FIELD_LATENCY_MS)
                ? summary.getAsJsonObject(FIELD_LATENCY_MS)
                : new JsonObject();

        double latencyAvg = optionalDouble(latency, "avg");
        double latencyStdev = optionalDouble(latency, "stdev");
        String gatewayTarget = summary.has(FIELD_GATEWAY_TARGET)
                ? summary.get(FIELD_GATEWAY_TARGET).getAsString()
                : "unknown";

        LOGGER.info(K6BenchmarkLogMessages.INFO.SUMMARY_PARSED, name, gatewayTarget);

        Map<String, Object> additionalData = new LinkedHashMap<>();
        additionalData.put(LATENCY_AVG_MS, latencyAvg);
        additionalData.put(GATEWAY_TARGET, gatewayTarget);
        additionalData.put(ERROR_RATE, optionalDouble(summary, FIELD_ERROR_RATE));

        return BenchmarkData.Benchmark.builder()
                .name(name)
                // The gateway target is carried in fullName because it is the only serialized
                // field that can hold it: the report pipeline's convertBenchmarks() whitelists
                // the fields it emits and drops additionalData entirely, so a target recorded
                // only there would never reach a report. name stays the bare benchmark name so
                // the gh-pages history/trend keys remain stable across the toolchain swap.
                .fullName("k6." + gatewayTarget + "." + name)
                .mode("thrpt")
                .rawScore(requestsPerSec)
                .score(MetricConversionUtil.formatThroughput(requestsPerSec))
                .scoreUnit(SCORE_UNIT)
                .throughput(MetricConversionUtil.formatThroughput(requestsPerSec))
                .latency(MetricConversionUtil.formatLatency(latencyAvg))
                .error(latencyStdev)
                .variabilityCoefficient(latencyAvg > 0 ? (latencyStdev / latencyAvg * 100) : 0)
                .confidenceLow(Math.max(0, latencyAvg - latencyStdev))
                .confidenceHigh(latencyAvg + latencyStdev)
                .percentiles(extractPercentiles(latency))
                .additionalData(additionalData)
                .build();
    }

    /**
     * Extracts the measured latency percentiles, keyed as the report pipeline expects.
     * Percentiles absent from the summary are omitted, never estimated.
     *
     * @param latency the {@code latency_ms} object of a k6 summary
     * @return the measured percentiles in ascending order
     */
    private Map<String, Double> extractPercentiles(JsonObject latency) {
        Map<String, Double> percentiles = new LinkedHashMap<>();
        for (String field : List.of("p50", "p75", "p90", "p99")) {
            if (latency.has(field)) {
                percentiles.put(PERCENTILE_KEYS.get(field), latency.get(field).getAsDouble());
            }
        }
        return percentiles;
    }

    private String requiredString(JsonObject summary, String field, Path file) {
        if (!summary.has(field)) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.INCOMPLETE_SUMMARY, file, field);
            return null;
        }
        return summary.get(field).getAsString();
    }

    private double optionalDouble(JsonObject object, String field) {
        return object.has(field) ? object.get(field).getAsDouble() : 0;
    }

    /**
     * Builds the report metadata. The upstream {@code ReportMetadataFactory} is package-private
     * to the converter package, so the same shape is assembled here from the shared constants.
     *
     * @return the report metadata
     */
    private BenchmarkData.Metadata createMetadata() {
        Instant now = Instant.now();
        return BenchmarkData.Metadata.builder()
                .timestamp(now.toString())
                .displayTimestamp(DISPLAY_FORMAT.format(now))
                .benchmarkType(BenchmarkType.INTEGRATION.getDisplayName())
                .reportVersion(BenchmarkConstants.Report.Versions.REPORT_VERSION)
                .build();
    }

    private BenchmarkData.Overview createOverview(List<BenchmarkData.Benchmark> benchmarks) {
        if (benchmarks.isEmpty()) {
            return BenchmarkData.Overview.builder()
                    .throughput("N/A")
                    .latency("N/A")
                    .throughputOpsPerSec(0.0)
                    .latencyMs(0.0)
                    .performanceScore(0)
                    .performanceGrade("F")
                    .performanceGradeClass("grade-f")
                    .build();
        }

        BenchmarkData.Benchmark primary = benchmarks.getFirst();
        double throughput = primary.getRawScore();
        double latencyMs = resolveLatencyMs(primary);
        // Delegate to MetricsComputer - the single home for score/grade computation
        int score = MetricsComputer.computeIntegrationScore(throughput, latencyMs);
        String grade = MetricsComputer.gradeIntegration(score);

        return BenchmarkData.Overview.builder()
                .throughput(primary.getThroughput())
                .latency(primary.getLatency())
                .throughputOpsPerSec(throughput)
                .latencyMs(latencyMs)
                .throughputBenchmarkName(primary.getName())
                .latencyBenchmarkName(primary.getName())
                .performanceScore(score)
                .performanceGrade(grade)
                .performanceGradeClass("grade-" + grade.toLowerCase())
                .build();
    }

    /**
     * Resolves the overview latency (ms) from measured data only: the measured P50 if present,
     * otherwise the k6-reported average latency.
     *
     * @param benchmark the primary benchmark
     * @return the measured latency in milliseconds, or 0 if neither value was reported
     */
    private double resolveLatencyMs(BenchmarkData.Benchmark benchmark) {
        if (benchmark.getPercentiles() != null && benchmark.getPercentiles().containsKey("50.0")) {
            return benchmark.getPercentiles().get("50.0");
        }
        if (benchmark.getAdditionalData() != null
                && benchmark.getAdditionalData().get(LATENCY_AVG_MS) instanceof Double avg) {
            return avg;
        }
        return 0;
    }
}
