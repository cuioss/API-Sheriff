/*
 * Copyright © 2025 CUI-OpenSource-Software (info@cuioss.de)
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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests WRK output parsing for the {@code /q/health/live} and {@code /api/health}
 * gateway endpoints. Uses synthetic WRK output to validate the parsing pipeline
 * without requiring running containers.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class GatewayEndpointBenchmarkTest {

    @TempDir
    Path tempDir;

    private WrkResultPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new WrkResultPostProcessor();
    }

    @Test
    void parseHealthLiveBenchmark() throws Exception {
        // Arrange — synthetic WRK output for /q/health/live endpoint
        String wrkOutput = """
                === BENCHMARK METADATA ===
                benchmark_name: healthLiveCheck
                start_time: 1700000000
                start_time_iso: 2023-11-14T22:13:20Z
                === WRK OUTPUT ===

                Running 30s test @ https://api-sheriff:8443/q/health/live
                  4 threads and 50 connections
                  Thread Stats   Avg      Stdev     Max   +/- Stdev
                    Latency   650.00us  300.00us   8.00ms   92.00%
                    Req/Sec     7.50k   800.00     12.00k    70.00%
                  Latency Distribution
                     50%  600.00us
                     75%    0.85ms
                     90%    1.10ms
                     99%    2.50ms
                  450000 requests in 30.00s, 120.00MB read
                Requests/sec:  15000.00
                Transfer/sec:      4.00MB

                === BENCHMARK COMPLETE ===
                end_time: 1700000030
                end_time_iso: 2023-11-14T22:13:50Z
                duration_seconds: 30
                """;

        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Files.writeString(wrkDir.resolve("wrk-health-live-results.txt"), wrkOutput);

        // Act
        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        // Assert — verify JSON structure and percentile ordering
        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertTrue(Files.exists(jsonFile), "Benchmark data JSON should be created");

        JsonObject json = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
        JsonObject benchmark = json.getAsJsonArray("benchmarks").get(0).getAsJsonObject();

        assertEquals("healthLiveCheck", benchmark.get("name").getAsString());
        assertTrue(benchmark.has("score"), "Benchmark should have score");
        assertTrue(benchmark.has("scoreUnit"), "Benchmark should have scoreUnit");

        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertTrue(percentiles.get("50.0").getAsDouble() <= percentiles.get("75.0").getAsDouble(),
                "P50 should be <= P75");
        assertTrue(percentiles.get("75.0").getAsDouble() <= percentiles.get("90.0").getAsDouble(),
                "P75 should be <= P90");
        assertTrue(percentiles.get("90.0").getAsDouble() <= percentiles.get("99.0").getAsDouble(),
                "P90 should be <= P99");
    }

    @Test
    void parseApiHealthBenchmark() throws Exception {
        // Arrange — synthetic WRK output for /api/health endpoint
        String wrkOutput = """
                === BENCHMARK METADATA ===
                benchmark_name: gatewayHealth
                start_time: 1700000000
                start_time_iso: 2023-11-14T22:13:20Z
                === WRK OUTPUT ===

                Running 30s test @ https://api-sheriff:8443/api/health
                  4 threads and 50 connections
                  Thread Stats   Avg      Stdev     Max   +/- Stdev
                    Latency   700.00us  350.00us   9.00ms   91.00%
                    Req/Sec     7.00k   750.00     11.00k    72.00%
                  Latency Distribution
                     50%  650.00us
                     75%    0.90ms
                     90%    1.20ms
                     99%    2.80ms
                  420000 requests in 30.00s, 110.00MB read
                Requests/sec:  14000.00
                Transfer/sec:      3.67MB

                === BENCHMARK COMPLETE ===
                end_time: 1700000030
                end_time_iso: 2023-11-14T22:13:50Z
                duration_seconds: 30
                """;

        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Files.writeString(wrkDir.resolve("wrk-api-health-results.txt"), wrkOutput);

        // Act
        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        // Assert — verify parsing succeeds with different latency values
        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertTrue(Files.exists(jsonFile), "Benchmark data JSON should be created");

        JsonObject json = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
        JsonObject benchmark = json.getAsJsonArray("benchmarks").get(0).getAsJsonObject();

        assertEquals("gatewayHealth", benchmark.get("name").getAsString());
        assertFalse(benchmark.get("score").getAsString().isEmpty(), "Score should not be empty");

        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        double p50 = percentiles.get("50.0").getAsDouble();
        double p99 = percentiles.get("99.0").getAsDouble();
        assertTrue(p50 > 0, "P50 should be positive");
        assertTrue(p50 <= p99, "P50 should be <= P99");
    }
}
