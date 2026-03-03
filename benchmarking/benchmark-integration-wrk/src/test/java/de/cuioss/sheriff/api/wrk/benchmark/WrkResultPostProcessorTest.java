/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static de.cuioss.sheriff.api.wrk.benchmark.WrkResultPostProcessor.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WrkResultPostProcessor}.
 * Tests that the processor correctly parses WRK output format,
 * not specific values that change with each benchmark run.
 */
class WrkResultPostProcessorTest {

    @TempDir
    Path tempDir;

    private WrkResultPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new WrkResultPostProcessor();
    }

    @Test
    void comprehensiveStructureGeneration() throws Exception {
        // Copy real benchmark outputs to temp directory wrk subdirectory
        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Path healthSource = Path.of("src/test/resources/wrk-health-results.txt");
        Path jwtSource = Path.of("src/test/resources/wrk-jwt-results.txt");
        Files.copy(healthSource, wrkDir.resolve(WRK_HEALTH_OUTPUT_FILE));
        Files.copy(jwtSource, wrkDir.resolve(WRK_JWT_OUTPUT_FILE));

        // Process results
        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        // Verify comprehensive structure matching JMH benchmarks
        verifyComprehensiveStructure();
    }

    @Test
    void parseWrkHealthOutput() throws Exception {
        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Path sourceFile = Path.of("src/test/resources/wrk-health-results.txt");
        Path targetFile = wrkDir.resolve(WRK_HEALTH_OUTPUT_FILE);
        Files.copy(sourceFile, targetFile);

        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertTrue(Files.exists(jsonFile), "Benchmark data file should be created");

        String jsonContent = Files.readString(jsonFile);
        JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();

        assertTrue(json.has("metadata"));
        JsonObject metadata = json.getAsJsonObject("metadata");
        assertTrue(metadata.has("timestamp"));
        assertTrue(metadata.has("displayTimestamp"));
        assertEquals("Integration Performance", metadata.get("benchmarkType").getAsString());
        assertEquals("2.0", metadata.get("reportVersion").getAsString());

        assertTrue(json.has("benchmarks"));
        JsonObject healthBenchmark = json.getAsJsonArray("benchmarks").get(0).getAsJsonObject();
        assertEquals(BENCHMARK_NAME_HEALTH, healthBenchmark.get("name").getAsString());
        assertTrue(healthBenchmark.has("mode"));
        assertTrue(healthBenchmark.has("score"));
        assertTrue(healthBenchmark.has("scoreUnit"));

        JsonObject percentiles = healthBenchmark.getAsJsonObject("percentiles");
        assertTrue(percentiles.has("50.0"));
        assertTrue(percentiles.has("75.0"));
        assertTrue(percentiles.has("90.0"));
        assertTrue(percentiles.has("99.0"));

        double p50 = percentiles.get("50.0").getAsDouble();
        double p75 = percentiles.get("75.0").getAsDouble();
        double p90 = percentiles.get("90.0").getAsDouble();
        double p99 = percentiles.get("99.0").getAsDouble();

        assertTrue(p50 <= p75, "P50 should be <= P75");
        assertTrue(p75 <= p90, "P75 should be <= P90");
        assertTrue(p90 <= p99, "P90 should be <= P99");
    }

    @Test
    void parseWrkJwtOutput() throws Exception {
        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Path sourceFile = Path.of("src/test/resources/wrk-jwt-results.txt");
        Path targetFile = wrkDir.resolve(WRK_JWT_OUTPUT_FILE);
        Files.copy(sourceFile, targetFile);

        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        String jsonContent = Files.readString(jsonFile);
        JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();

        JsonObject jwtBenchmark = null;
        var benchmarks = json.getAsJsonArray("benchmarks");
        for (int i = 0; i < benchmarks.size(); i++) {
            var bench = benchmarks.get(i).getAsJsonObject();
            String benchName = bench.get("name").getAsString();
            if (BENCHMARK_NAME_JWT.equals(benchName) || "jwt-validation".equals(benchName)) {
                jwtBenchmark = bench;
                break;
            }
        }

        assertNotNull(jwtBenchmark, "JWT Validation benchmark should be present");
        assertTrue(jwtBenchmark.has("mode"));
        assertTrue(jwtBenchmark.has("score"));
        assertTrue(jwtBenchmark.has("scoreUnit"));

        JsonObject percentiles = jwtBenchmark.getAsJsonObject("percentiles");
        assertTrue(percentiles.has("50.0"));
        assertTrue(percentiles.has("75.0"));
        assertTrue(percentiles.has("90.0"));
        assertTrue(percentiles.has("99.0"));

        double p50 = percentiles.get("50.0").getAsDouble();
        double p75 = percentiles.get("75.0").getAsDouble();
        double p90 = percentiles.get("90.0").getAsDouble();
        double p99 = percentiles.get("99.0").getAsDouble();

        assertTrue(p50 <= p75, "P50 should be <= P75");
        assertTrue(p75 <= p90, "P75 should be <= P90");
        assertTrue(p90 <= p99, "P90 should be <= P99");
    }

    @Test
    void missingFileHandling() throws Exception {
        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Path outputDir = tempDir.resolve("output");
        assertThrows(IllegalStateException.class, () -> processor.process(tempDir, outputDir));

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertFalse(Files.exists(jsonFile), "JSON should not be created with missing inputs");
    }

    @Test
    void parseRealWrkFormatVariations() throws Exception {
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
        Path testFile = wrkDir.resolve(WRK_HEALTH_OUTPUT_FILE);
        Files.writeString(testFile, wrkOutput);

        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        JsonObject json = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
        JsonObject benchmark = json.getAsJsonArray("benchmarks").get(0).getAsJsonObject();

        assertTrue(benchmark.has("name"));
        assertTrue(benchmark.has("mode"));
        assertTrue(benchmark.has("score"));
        assertTrue(benchmark.has("scoreUnit"));

        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        double p50 = percentiles.get("50.0").getAsDouble();
        assertTrue(p50 > 0, "P50 should be positive");
    }

    /**
     * Verify that WRK generates the same comprehensive structure as JMH benchmarks
     */
    private void verifyComprehensiveStructure() throws IOException {
        Path outputDir = tempDir.resolve("output");
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

        String jsonContent = Files.readString(dataFile);
        JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();

        assertTrue(json.has("metadata"), "Should have metadata section");
        JsonObject metadata = json.getAsJsonObject("metadata");
        assertTrue(metadata.has("timestamp"), "Should have timestamp");
        assertTrue(metadata.has("displayTimestamp"), "Should have displayTimestamp");
        assertTrue(metadata.has("benchmarkType"), "Should have benchmarkType");
        assertTrue(metadata.has("reportVersion"), "Should have reportVersion");

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
