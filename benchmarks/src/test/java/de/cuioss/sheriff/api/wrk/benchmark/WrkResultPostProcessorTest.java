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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WrkResultPostProcessor}.
 * <p>
 * Fixtures are loaded from the test classpath (not CWD-relative paths, which
 * only resolve when the JVM working directory happens to be the module root),
 * and the value-asserting tests pin the concrete metric numbers (latency
 * percentiles and throughput) parsed from the known synthetic fixtures so a
 * unit-conversion regression in the converter is caught rather than silently
 * passing an ordering-only check.
 */
class WrkResultPostProcessorTest {

    private static final String HEALTH_FIXTURE = "wrk-health-results.txt";
    private static final String API_HEALTH_FIXTURE = "wrk-api-health-results.txt";

    @TempDir
    Path tempDir;

    private WrkResultPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new WrkResultPostProcessor();
    }

    /**
     * Copies a fixture from the test classpath into the temp {@code wrk} directory
     * the processor reads. Loading via the classpath makes the test independent of
     * the JVM working directory.
     */
    private void copyFixtureToWrkDir(String fixtureName) throws IOException {
        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        try (InputStream in = WrkResultPostProcessorTest.class.getClassLoader()
                     .getResourceAsStream(fixtureName)) {
            assertNotNull(in, "Fixture must be on the test classpath: " + fixtureName);
            Files.copy(in, wrkDir.resolve(fixtureName));
        }
    }

    private JsonObject processAndReadJson() throws IOException {
        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);
        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertTrue(Files.exists(jsonFile), "benchmark-data.json should be created");
        return JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
    }

    private static JsonObject benchmarkNamed(JsonObject json, String name) {
        var benchmarks = json.getAsJsonArray("benchmarks");
        for (int i = 0; i < benchmarks.size(); i++) {
            JsonObject benchmark = benchmarks.get(i).getAsJsonObject();
            if (name.equals(benchmark.get("name").getAsString())) {
                return benchmark;
            }
        }
        return null;
    }

    @Test
    void healthLivePercentilesAndThroughputMatchFixtureValues() throws Exception {
        // Arrange
        copyFixtureToWrkDir(HEALTH_FIXTURE);

        // Act
        JsonObject json = processAndReadJson();

        // Assert — concrete values parsed from wrk-health-results.txt
        JsonObject benchmark = benchmarkNamed(json, "healthLiveCheck");
        assertNotNull(benchmark, "healthLiveCheck benchmark must be present (exact name match)");
        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertAll("healthLiveCheck metrics",
                () -> assertEquals(1.69, percentiles.get("50.0").getAsDouble(), 0.001, "P50 latency (ms)"),
                () -> assertEquals(3.18, percentiles.get("75.0").getAsDouble(), 0.001, "P75 latency (ms)"),
                () -> assertEquals(14.60, percentiles.get("90.0").getAsDouble(), 0.001, "P90 latency (ms)"),
                () -> assertEquals(29.99, percentiles.get("99.0").getAsDouble(), 0.001, "P99 latency (ms)"),
                () -> assertEquals("8.5K ops/s", benchmark.get("score").getAsString(),
                        "throughput score (formatted from Requests/sec: 8511.49)"));
    }

    @Test
    void gatewayHealthPercentilesAndThroughputMatchFixtureValues() throws Exception {
        // Arrange
        copyFixtureToWrkDir(API_HEALTH_FIXTURE);

        // Act
        JsonObject json = processAndReadJson();

        // Assert — concrete values parsed from wrk-api-health-results.txt
        JsonObject benchmark = benchmarkNamed(json, "gatewayHealth");
        assertNotNull(benchmark, "gatewayHealth benchmark must be present (exact name match)");
        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertAll("gatewayHealth metrics",
                () -> assertEquals(1.73, percentiles.get("50.0").getAsDouble(), 0.001, "P50 latency (ms)"),
                () -> assertEquals(2.97, percentiles.get("75.0").getAsDouble(), 0.001, "P75 latency (ms)"),
                () -> assertEquals(14.05, percentiles.get("90.0").getAsDouble(), 0.001, "P90 latency (ms)"),
                () -> assertEquals(28.27, percentiles.get("99.0").getAsDouble(), 0.001, "P99 latency (ms)"),
                () -> assertEquals("8.6K ops/s", benchmark.get("score").getAsString(),
                        "throughput score (formatted from Requests/sec: 8592.89)"));
    }

    @Test
    void exactNameLookupResolvesEachBenchmarkIndependently() throws Exception {
        // Arrange — both fixtures present so the exact-name contract must
        // disambiguate them (the removed bidirectional-contains() fallback was
        // nondeterministic on overlapping names).
        copyFixtureToWrkDir(HEALTH_FIXTURE);
        copyFixtureToWrkDir(API_HEALTH_FIXTURE);

        // Act
        JsonObject json = processAndReadJson();

        // Assert
        assertNotNull(benchmarkNamed(json, "healthLiveCheck"), "healthLiveCheck resolved by exact name");
        assertNotNull(benchmarkNamed(json, "gatewayHealth"), "gatewayHealth resolved by exact name");
        assertEquals(2, json.getAsJsonArray("benchmarks").size(), "both benchmarks present, none dropped");
    }

    @Test
    void comprehensiveStructureGeneration() throws Exception {
        // Arrange
        copyFixtureToWrkDir(HEALTH_FIXTURE);
        copyFixtureToWrkDir(API_HEALTH_FIXTURE);

        // Act
        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        // Assert — full GitHub-Pages report structure
        verifyComprehensiveStructure(outputDir);
    }

    @Test
    void missingFileHandling() {
        // Arrange — empty wrk directory, no fixtures
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(tempDir.resolve("wrk"));
        assertThrows(IllegalStateException.class, () ->
            processor.process(tempDir, outputDir));

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertFalse(Files.exists(jsonFile), "JSON should not be created with missing inputs");
    }

    @Test
    void parseRealWrkFormatVariations() throws Exception {
        // Arrange — inline synthetic output, exercises a differently-named benchmark
        String wrkOutput = """
                === BENCHMARK METADATA ===
                benchmark_name: format-test
                start_time: 1700000000
                start_time_iso: 2023-11-14T22:13:20Z
                === WRK OUTPUT ===

                Running 10s test @ https://localhost:10443/test
                  4 threads and 20 connections
                  Thread Stats   Avg      Stdev     Max   +/- Stdev
                    Latency   849.00us  500.00us  10.00ms   90.00%
                    Req/Sec     5.00k   1.00k    10.00k    75.00%
                  Latency Distribution
                     50%  805.00us
                     75%    1.08ms
                     90%    1.54ms
                     99%    4.01ms
                  100000 requests in 10.00s, 50.00MB read
                Requests/sec:  10000.00
                Transfer/sec:      5.00MB

                === BENCHMARK COMPLETE ===
                end_time: 1700000010
                end_time_iso: 2023-11-14T22:13:30Z
                duration_seconds: 10
                """;

        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Files.writeString(wrkDir.resolve("wrk-format-test-results.txt"), wrkOutput);

        // Act
        JsonObject json = processAndReadJson();

        // Assert
        JsonObject benchmark = benchmarkNamed(json, "format-test");
        assertNotNull(benchmark, "format-test benchmark must be present");
        assertTrue(benchmark.has("mode"));
        assertTrue(benchmark.has("score"));
        assertTrue(benchmark.has("scoreUnit"));
        assertTrue(benchmark.getAsJsonObject("percentiles").get("50.0").getAsDouble() > 0,
                "P50 should be positive");
    }

    /**
     * Verify that WRK generates the same comprehensive structure as JMH benchmarks.
     */
    private void verifyComprehensiveStructure(Path outputDir) throws IOException {
        Path ghPagesDir = outputDir.resolve("gh-pages-ready");
        assertTrue(Files.exists(ghPagesDir), "GitHub Pages directory should be created");

        assertTrue(Files.exists(ghPagesDir.resolve("index.html")), "index.html should be created");
        assertTrue(Files.exists(ghPagesDir.resolve("trends.html")), "trends.html should be created");
        assertTrue(Files.exists(ghPagesDir.resolve("report-styles.css")), "CSS file should exist");
        assertTrue(Files.exists(ghPagesDir.resolve("data-loader.js")), "JavaScript file should exist");

        Path badgesDir = ghPagesDir.resolve("badges");
        assertTrue(Files.exists(badgesDir), "Badges directory should be created");

        Path dataFile = ghPagesDir.resolve("data/benchmark-data.json");
        assertTrue(Files.exists(dataFile), "benchmark-data.json should be created");

        JsonObject json = JsonParser.parseString(Files.readString(dataFile)).getAsJsonObject();

        assertTrue(json.has("metadata"), "Should have metadata section");
        JsonObject metadata = json.getAsJsonObject("metadata");
        assertTrue(metadata.has("timestamp"), "Should have timestamp");
        assertTrue(metadata.has("displayTimestamp"), "Should have displayTimestamp");
        assertEquals("Integration Performance", metadata.get("benchmarkType").getAsString());
        assertEquals("2.0", metadata.get("reportVersion").getAsString());

        assertTrue(json.has("overview"), "Should have overview section");
        JsonObject overview = json.getAsJsonObject("overview");
        assertTrue(overview.has("performanceScore"), "Should have performance score");
        assertTrue(overview.has("performanceGrade"), "Should have performance grade");

        assertTrue(json.has("benchmarks"), "Should have benchmarks array");
        assertTrue(json.getAsJsonArray("benchmarks").size() > 0, "Should have at least one benchmark");

        assertTrue(json.has("chartData"), "Should have chart data section");
        assertTrue(json.has("trends"), "Should have trends section");

        JsonObject benchmark = json.getAsJsonArray("benchmarks").get(0).getAsJsonObject();
        assertTrue(benchmark.has("name"), "Benchmark should have name");
        assertTrue(benchmark.has("mode"), "Benchmark should have mode");
        assertTrue(benchmark.has("score"), "Benchmark should have score");
        assertTrue(benchmark.has("scoreUnit"), "Benchmark should have scoreUnit");
        assertTrue(benchmark.has("percentiles"), "Benchmark should have percentiles");
    }
}
