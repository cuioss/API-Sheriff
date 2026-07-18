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
package de.cuioss.sheriff.api.wrk.benchmark;

import de.cuioss.benchmarking.common.config.BenchmarkType;
import de.cuioss.benchmarking.common.converter.WrkBenchmarkConverter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static de.cuioss.benchmarking.common.util.BenchmarkingLogMessages.ERROR;
import static de.cuioss.benchmarking.common.util.BenchmarkingLogMessages.INFO;

/**
 * Post-processor for WRK benchmark results.
 * <p>
 * This class converts WRK output files to the central BenchmarkData format
 * and uses the unified report generation infrastructure from cui-benchmarking-common.
 */
public class WrkResultPostProcessor {

    private static final CuiLogger LOGGER = new CuiLogger(WrkResultPostProcessor.class);

    // File naming constants
    public static final String WRK_OUTPUT_FILE_SUFFIX = "-results.txt";

    private final WrkBenchmarkConverter converter = new WrkBenchmarkConverter();
    private final ReportGenerator reportGenerator = new ReportGenerator();
    private final GitHubPagesGenerator gitHubPagesGenerator = new GitHubPagesGenerator();
    private final PrometheusMetricsManager prometheusMetricsManager = new PrometheusMetricsManager();

    // Map to store benchmark metadata (name -> timestamps)
    private final Map<String, BenchmarkMetadata> benchmarkMetadataMap = new HashMap<>();

    /**
     * Holds metadata for a benchmark execution.
     */
    private record BenchmarkMetadata(String name, Instant startTime, Instant endTime) {
    }

    /**
     * Main entry point for processing WRK benchmark results.
     * Usage:
     *   - args[0]=inputDir, args[1]=outputDir (optional)
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            LOGGER.error(ERROR.WRK_USAGE_ERROR);
            System.exit(1);
        }

        try {
            Path inputDir = Path.of(args[0]);
            WrkResultPostProcessor processor = new WrkResultPostProcessor();

            // Normal processing mode
            Path outputDir = args.length > 1 ?
                    Path.of(args[1]) :
                    inputDir.getParent().resolve("benchmark-results");

            processor.process(inputDir, outputDir);

            LOGGER.info(INFO.RESULTS_AVAILABLE, outputDir);

        } catch (IOException e) {
            LOGGER.error(e, ERROR.WRK_PROCESSOR_FAILED);
            System.exit(1);
        }
    }

    /**
     * Processes WRK output files and generates reports.
     *
     * @param inputDir Directory containing WRK output files
     * @param outputDir Directory to write reports to
     * @throws IOException if processing fails
     */
    public void process(Path inputDir, Path outputDir) throws IOException {
        LOGGER.info(INFO.WRK_PROCESSING_START);

        // Parse all WRK result files to extract metadata
        parseBenchmarkMetadata(inputDir);

        // Validate input directory
        if (!Files.exists(inputDir)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
        }

        if (!Files.isDirectory(inputDir)) {
            throw new IllegalArgumentException("Input path is not a directory: " + inputDir);
        }

        // Check for WRK output files in wrk subdirectory
        Path wrkDir = inputDir.resolve("wrk");
        boolean hasWrkFiles = false;
        if (Files.exists(wrkDir)) {
            try (Stream<Path> files = Files.list(wrkDir)) {
                // Same suffix contract as parseBenchmarkMetadata: canonical WRK output
                // files are *-results.txt, so both the presence check here and the
                // metadata parse key off WRK_OUTPUT_FILE_SUFFIX (no bare *.txt drift).
                hasWrkFiles = files.anyMatch(p -> p.getFileName().toString().endsWith(WRK_OUTPUT_FILE_SUFFIX));
            }
        }

        if (!hasWrkFiles) {
            LOGGER.error(ERROR.NO_WRK_FILES, wrkDir);
        }

        // Convert WRK output to BenchmarkData from wrk subdirectory
        BenchmarkData benchmarkData;
        if (!Files.exists(wrkDir)) {
            LOGGER.error(ERROR.WRK_DIR_NOT_EXIST, wrkDir);
            benchmarkData = BenchmarkData.builder()
                    .metadata(BenchmarkData.Metadata.builder()
                            .reportVersion("2.0")
                            .timestamp(Instant.now().toString())
                            .displayTimestamp(Instant.now().toString())
                            .benchmarkType("Integration Performance")
                            .build())
                    .overview(BenchmarkData.Overview.builder()
                            .throughput("0 ops/s")
                            .latency("0ms")
                            .throughputBenchmarkName("N/A")
                            .latencyBenchmarkName("N/A")
                            .performanceScore(0)
                            .performanceGrade("F")
                            .performanceGradeClass("grade-f")
                            .build())
                    .benchmarks(List.of())
                    .build();
        } else {
            benchmarkData = converter.convert(wrkDir);
        }

        if (benchmarkData.getBenchmarks() == null || benchmarkData.getBenchmarks().isEmpty()) {
            LOGGER.error(ERROR.NO_BENCHMARK_DATA);
        }

        // Generate reports using new OutputDirectoryStructure (no duplication)
        Files.createDirectories(outputDir);

        // Create OutputDirectoryStructure for organized file generation
        OutputDirectoryStructure structure = new OutputDirectoryStructure(outputDir);
        structure.ensureDirectories();

        // Generate HTML reports directly to gh-pages-ready directory
        String deploymentPath = structure.getDeploymentDir().toString();
        reportGenerator.generateIndexPage(benchmarkData, BenchmarkType.INTEGRATION, deploymentPath);
        reportGenerator.generateTrendsPage(deploymentPath);
        reportGenerator.copySupportFiles(deploymentPath);

        // Collect real-time Prometheus metrics for the benchmark execution
        collectPrometheusMetrics(benchmarkData, structure);

        // Generate GitHub Pages deployment-specific assets (404.html, robots.txt, sitemap.xml)
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
     * Parse benchmark metadata from WRK result files.
     *
     * @param inputDir The directory containing benchmark results
     * @throws IOException if reading files fails
     */
    private void parseBenchmarkMetadata(Path inputDir) throws IOException {
        Path wrkDir = inputDir.resolve("wrk");
        if (!Files.exists(wrkDir)) {
            return;
        }

        try (Stream<Path> files = Files.list(wrkDir)) {
            List<Path> wrkFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(WRK_OUTPUT_FILE_SUFFIX))
                    .toList();

            if (wrkFiles.isEmpty()) {
                String message = "CRITICAL: No WRK result files (*%s) found in %s. Ensure benchmarks were run successfully."
                        .formatted(WRK_OUTPUT_FILE_SUFFIX, inputDir);
                LOGGER.error(ERROR.NO_WRK_FILES, inputDir);
                throw new IllegalStateException(message);
            }

            for (Path wrkFile : wrkFiles) {
                parseSingleBenchmarkMetadata(wrkFile);
            }

            if (benchmarkMetadataMap.isEmpty()) {
                String message = "CRITICAL: No valid benchmark metadata found in any result files";
                LOGGER.error(ERROR.NO_BENCHMARK_DATA);
                throw new IllegalStateException(message);
            }
        }
    }

    /**
     * Parse metadata from a single WRK result file.
     *
     * @param resultFile Path to the WRK result file
     */
    private void parseSingleBenchmarkMetadata(Path resultFile) {
        try {
            List<String> lines = Files.readAllLines(resultFile);
            String benchmarkName = null;
            Instant startTime = null;
            Instant endTime = null;
            boolean inMetadata = false;

            for (String line : lines) {
                if ("=== BENCHMARK METADATA ===".equals(line)) {
                    inMetadata = true;
                } else if ("=== WRK OUTPUT ===".equals(line)) {
                    inMetadata = false;
                } else if (inMetadata || line.startsWith("end_time:")) {
                    if (line.startsWith("benchmark_name: ")) {
                        benchmarkName = line.substring(16).trim();
                    } else if (line.startsWith("start_time: ")) {
                        long epochSeconds = Long.parseLong(line.substring(12).trim());
                        startTime = Instant.ofEpochSecond(epochSeconds);
                    } else if (line.startsWith("end_time: ")) {
                        long epochSeconds = Long.parseLong(line.substring(10).trim());
                        endTime = Instant.ofEpochSecond(epochSeconds);
                    }
                }
            }

            if (benchmarkName == null || startTime == null || endTime == null) {
                LOGGER.error(ERROR.INCOMPLETE_METADATA, resultFile, benchmarkName, startTime, endTime);
                return;
            }

            BenchmarkMetadata metadata = new BenchmarkMetadata(benchmarkName, startTime, endTime);
            benchmarkMetadataMap.put(benchmarkName, metadata);

        } catch (IOException | NumberFormatException e) {
            LOGGER.error(ERROR.FAILED_PARSE_METADATA, resultFile, e.getMessage());
        }
    }

    /**
     * Find metadata for a benchmark by its exact name.
     * <p>
     * Metadata is keyed by the {@code benchmark_name} value read from the
     * {@code === BENCHMARK METADATA ===} block of each {@value #WRK_OUTPUT_FILE_SUFFIX}
     * result file, and the {@link BenchmarkData.Benchmark} name produced by the
     * converter is that same {@code benchmark_name}. Matching is therefore an exact
     * name lookup: the earlier bidirectional {@code contains()} fallback (plus the
     * "single entry wins" heuristic) was nondeterministic whenever two benchmark
     * names overlapped as substrings, and has been removed. A name with no exact
     * metadata entry is a real mismatch — the caller logs it and skips that
     * benchmark rather than silently binding to an unrelated run.
     *
     * @param benchmarkName The benchmark name from {@link BenchmarkData.Benchmark}
     * @return the metadata for the exactly-named benchmark, or an empty {@link Optional} if none exists
     */
    private Optional<BenchmarkMetadata> findMetadataForBenchmark(String benchmarkName) {
        return Optional.ofNullable(benchmarkMetadataMap.get(benchmarkName));
    }
}
