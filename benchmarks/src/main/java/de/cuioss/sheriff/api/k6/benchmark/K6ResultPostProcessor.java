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
import de.cuioss.benchmarking.common.metrics.PrometheusMetricsManager;
import de.cuioss.benchmarking.common.model.BenchmarkData;
import de.cuioss.benchmarking.common.output.OutputDirectoryStructure;
import de.cuioss.benchmarking.common.report.GitHubPagesGenerator;
import de.cuioss.benchmarking.common.report.ReportGenerator;
import de.cuioss.tools.logging.CuiLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static de.cuioss.benchmarking.common.util.BenchmarkingLogMessages.ERROR;
import static de.cuioss.benchmarking.common.util.BenchmarkingLogMessages.INFO;

/**
 * Post-processor for k6 benchmark results.
 * <p>
 * Converts the k6 {@code handleSummary()} exports to the central {@link BenchmarkData} format
 * via {@link K6BenchmarkConverter} and drives the unified report-generation infrastructure from
 * {@code benchmarking-common}. This is the {@code main}-entry replacement for the wrk-era
 * processor and preserves its downstream sequence exactly, so the badges / 10-run history /
 * trends / GitHub Pages flow is unaffected by the toolchain swap.
 * <p>
 * Summaries are read from the {@code k6} subdirectory of the results directory, mirroring the
 * {@code wrk} subdirectory contract the previous processor used. Per-benchmark start and end
 * instants come from the summary JSON itself rather than from a text metadata block.
 *
 * @since 1.0
 */
public class K6ResultPostProcessor {

    private static final CuiLogger LOGGER = new CuiLogger(K6ResultPostProcessor.class);

    /** Name of the results subdirectory holding the k6 summary documents. */
    public static final String K6_RESULTS_SUBDIRECTORY = "k6";

    private final K6BenchmarkConverter converter = new K6BenchmarkConverter();
    private final ReportGenerator reportGenerator = new ReportGenerator();
    private final GitHubPagesGenerator gitHubPagesGenerator = new GitHubPagesGenerator();
    private final PrometheusMetricsManager prometheusMetricsManager = new PrometheusMetricsManager();

    /** Benchmark name to its execution window, keyed by the exact {@code benchmark_name}. */
    private final Map<String, BenchmarkMetadata> benchmarkMetadataMap = new HashMap<>();

    /**
     * Holds the execution window of a benchmark run.
     */
    private record BenchmarkMetadata(String name, Instant startTime, Instant endTime) {
    }

    /**
     * Main entry point for processing k6 benchmark results.
     * Usage:
     *   - args[0]=inputDir, args[1]=outputDir (optional)
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.USAGE_ERROR);
            System.exit(1);
        }

        try {
            Path inputDir = Path.of(args[0]);
            K6ResultPostProcessor processor = new K6ResultPostProcessor();

            Path outputDir = args.length > 1 ?
                    Path.of(args[1]) :
                    inputDir.getParent().resolve("benchmark-results");

            processor.process(inputDir, outputDir);

            LOGGER.info(INFO.RESULTS_AVAILABLE, outputDir);

        } catch (IOException e) {
            LOGGER.error(e, K6BenchmarkLogMessages.ERROR.PROCESSOR_FAILED);
            System.exit(1);
        }
    }

    /**
     * Processes k6 summary files and generates reports.
     *
     * @param inputDir Directory containing the {@code k6} summary subdirectory
     * @param outputDir Directory to write reports to
     * @throws IOException if processing fails
     */
    public void process(Path inputDir, Path outputDir) throws IOException {
        LOGGER.info(K6BenchmarkLogMessages.INFO.PROCESSING_START, inputDir);

        if (!Files.exists(inputDir)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
        }
        if (!Files.isDirectory(inputDir)) {
            throw new IllegalArgumentException("Input path is not a directory: " + inputDir);
        }

        Path k6Dir = inputDir.resolve(K6_RESULTS_SUBDIRECTORY);

        // Parse the execution windows first — this fails loud when no usable summary exists,
        // so an empty or missing k6 directory never silently produces an empty report.
        parseBenchmarkMetadata(k6Dir);

        BenchmarkData benchmarkData = converter.convert(k6Dir);

        if (benchmarkData.getBenchmarks() == null || benchmarkData.getBenchmarks().isEmpty()) {
            LOGGER.error(ERROR.NO_BENCHMARK_DATA);
        }

        Files.createDirectories(outputDir);

        OutputDirectoryStructure structure = new OutputDirectoryStructure(outputDir);
        structure.ensureDirectories();

        String deploymentPath = structure.getDeploymentDir().toString();
        reportGenerator.generateIndexPage(benchmarkData, BenchmarkType.INTEGRATION, deploymentPath);
        reportGenerator.generateTrendsPage(deploymentPath);
        reportGenerator.copySupportFiles(deploymentPath);

        collectPrometheusMetrics(benchmarkData, structure);

        gitHubPagesGenerator.generateDeploymentAssets(structure);
    }

    /**
     * Collect real-time metrics from Prometheus for the benchmark execution.
     *
     * @param benchmarkData The benchmark data containing benchmark names
     * @param structure The output directory structure
     */
    private void collectPrometheusMetrics(BenchmarkData benchmarkData, OutputDirectoryStructure structure) {
        if (benchmarkMetadataMap.isEmpty()) {
            LOGGER.error(ERROR.NO_PROMETHEUS_METADATA);
            return;
        }

        if (benchmarkData.getBenchmarks() != null) {
            for (BenchmarkData.Benchmark benchmark : benchmarkData.getBenchmarks()) {
                Optional<BenchmarkMetadata> metadata = findMetadataForBenchmark(benchmark.getName());

                if (metadata.isEmpty()) {
                    LOGGER.error(ERROR.NO_METADATA_FOR_BENCHMARK, benchmark.getName());
                    continue;
                }

                BenchmarkMetadata resolved = metadata.get();
                // collectMetricsForWrkBenchmark is the only Prometheus collection entry point the
                // upstream benchmarking-common artifact exposes. Its wrk-branded name is an upstream
                // API fact this plan deliberately does not touch; the rename belongs to the
                // K6BenchmarkConverter upstreaming follow-up (plan 04b, design decision D5).
                prometheusMetricsManager.collectMetricsForWrkBenchmark(
                        resolved.name,
                        resolved.startTime,
                        resolved.endTime,
                        structure.getBenchmarkResultsDir().toString()
                );
            }
        }

        copyPrometheusMetricsToDeployment(structure);
    }

    /**
     * Copies Prometheus metrics from the raw prometheus directory to the deployment data directory.
     *
     * @param structure The output directory structure
     */
    private void copyPrometheusMetricsToDeployment(OutputDirectoryStructure structure) {
        try {
            Path prometheusRawDir = structure.getPrometheusRawDir();
            Path deploymentDataDir = structure.getDataDir();

            if (!Files.exists(prometheusRawDir)) {
                return;
            }

            try (Stream<Path> files = Files.list(prometheusRawDir)) {
                files.filter(file -> file.getFileName().toString().endsWith(".json"))
                        .forEach(sourceFile -> {
                            try {
                                Path targetFile = deploymentDataDir.resolve(sourceFile.getFileName());
                                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                LOGGER.error(e, ERROR.FAILED_COPY_PROMETHEUS, sourceFile);
                            }
                        });
            }
        } catch (IOException e) {
            LOGGER.error(e, ERROR.FAILED_COPY_PROMETHEUS_DIR);
        }
    }

    /**
     * Parse the per-benchmark execution windows from the k6 summary files.
     *
     * @param k6Dir The {@code k6} subdirectory holding the summary documents
     * @throws IOException if reading files fails
     */
    private void parseBenchmarkMetadata(Path k6Dir) throws IOException {
        if (!Files.exists(k6Dir)) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.SUMMARY_DIR_NOT_EXIST, k6Dir);
            throw new IllegalStateException(
                    "CRITICAL: k6 summary directory does not exist: " + k6Dir
                            + ". Ensure benchmarks were run successfully.");
        }

        try (Stream<Path> files = Files.list(k6Dir)) {
            List<Path> summaryFiles = files
                    .filter(p -> p.getFileName().toString()
                            .endsWith(K6BenchmarkConverter.K6_SUMMARY_FILE_EXTENSION))
                    .toList();

            if (summaryFiles.isEmpty()) {
                LOGGER.error(K6BenchmarkLogMessages.ERROR.NO_SUMMARY_FILES, k6Dir);
                throw new IllegalStateException(
                        "CRITICAL: No k6 summary files (*%s) found in %s. Ensure benchmarks were run successfully."
                                .formatted(K6BenchmarkConverter.K6_SUMMARY_FILE_EXTENSION, k6Dir));
            }

            for (Path summaryFile : summaryFiles) {
                parseSingleBenchmarkMetadata(summaryFile);
            }
        }

        if (benchmarkMetadataMap.isEmpty()) {
            LOGGER.error(ERROR.NO_BENCHMARK_DATA);
            throw new IllegalStateException("CRITICAL: No valid benchmark metadata found in any summary files");
        }
    }

    /**
     * Parse the execution window from a single k6 summary file.
     *
     * @param summaryFile Path to the k6 summary file
     */
    private void parseSingleBenchmarkMetadata(Path summaryFile) {
        try {
            JsonObject summary = JsonParser.parseString(Files.readString(summaryFile)).getAsJsonObject();

            String benchmarkName = stringOrNull(summary, "benchmark_name");
            Instant startTime = instantOrNull(summary, "start_time");
            Instant endTime = instantOrNull(summary, "end_time");

            if (benchmarkName == null || startTime == null || endTime == null) {
                LOGGER.error(ERROR.INCOMPLETE_METADATA, summaryFile, benchmarkName, startTime, endTime);
                return;
            }

            benchmarkMetadataMap.put(benchmarkName, new BenchmarkMetadata(benchmarkName, startTime, endTime));

        } catch (IOException | JsonSyntaxException | IllegalStateException | DateTimeParseException e) {
            LOGGER.error(e, K6BenchmarkLogMessages.ERROR.FAILED_PARSE_SUMMARY, summaryFile, e.getMessage());
        }
    }

    private static String stringOrNull(JsonObject summary, String field) {
        return summary.has(field) ? summary.get(field).getAsString() : null;
    }

    private static Instant instantOrNull(JsonObject summary, String field) {
        return summary.has(field) ? Instant.parse(summary.get(field).getAsString()) : null;
    }

    /**
     * Find metadata for a benchmark by its exact name.
     * <p>
     * Metadata is keyed by the {@code benchmark_name} value read from each k6 summary document,
     * and the {@link BenchmarkData.Benchmark} name the converter produces is that same value.
     * Matching is therefore an exact name lookup: the earlier bidirectional {@code contains()}
     * fallback (plus the "single entry wins" heuristic) was nondeterministic whenever two
     * benchmark names overlapped as substrings, and is deliberately not reintroduced here. A name
     * with no exact metadata entry is a real mismatch — the caller logs it and skips that
     * benchmark rather than silently binding to an unrelated run.
     *
     * @param benchmarkName The benchmark name from {@link BenchmarkData.Benchmark}
     * @return the metadata for the exactly-named benchmark, or an empty {@link Optional} if none exists
     */
    private Optional<BenchmarkMetadata> findMetadataForBenchmark(String benchmarkName) {
        return Optional.ofNullable(benchmarkMetadataMap.get(benchmarkName));
    }
}
