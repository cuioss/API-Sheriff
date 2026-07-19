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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the bearer-validated proxied-route benchmark's two contract halves without running
 * containers: that its WRK output <em>runs and reports</em> through the parsing pipeline, and that
 * the shipped Lua check enforces the failure-rate gate (error counters fail the run above threshold).
 * <p>
 * The live run is the {@code -Pbenchmark} profile's job (native container + Keycloak + wrk); these
 * unit tests assert the reporting pipeline against synthetic WRK output and the structural presence
 * of the bearer wiring and the error gate in the shipped script resources.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BearerProxiedRouteBenchmarkTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the bearer-validated benchmark output runs and reports through the parser")
    void bearerBenchmarkRunsAndReports() throws Exception {
        // Arrange — synthetic WRK output for the bearerProxied benchmark
        String wrkOutput = """
                === BENCHMARK METADATA ===
                benchmark_name: bearerProxied
                start_time: 1700000000
                start_time_iso: 2023-11-14T22:13:20Z
                === WRK OUTPUT ===

                Running 30s test @ https://api-sheriff:8443/secure/get
                  5 threads and 50 connections
                  Thread Stats   Avg      Stdev     Max   +/- Stdev
                    Latency   900.00us  400.00us  11.00ms   90.00%
                    Req/Sec     5.00k   600.00      9.00k    73.00%
                  Latency Distribution
                     50%  850.00us
                     75%    1.10ms
                     90%    1.60ms
                     99%    3.20ms
                  300000 requests in 30.00s, 90.00MB read
                Requests/sec:  10000.00
                Transfer/sec:      3.00MB
                --- Lua Script Summary ---
                Successful requests (200): 300000
                Non-200 responses: 0
                Failure rate: 0.0000 (non-2xx + socket errors; fail threshold 0.0100)

                === BENCHMARK COMPLETE ===
                end_time: 1700000030
                end_time_iso: 2023-11-14T22:13:50Z
                duration_seconds: 30
                """;

        Path wrkDir = tempDir.resolve("wrk");
        Files.createDirectories(wrkDir);
        Files.writeString(wrkDir.resolve("wrk-bearer-proxied-results.txt"), wrkOutput);

        // Act
        Path outputDir = tempDir.resolve("output");
        new WrkResultPostProcessor().process(tempDir, outputDir);

        // Assert — the benchmark reported with an ordered percentile distribution
        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertTrue(Files.exists(jsonFile), "Benchmark data JSON should be created");

        JsonObject json = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
        JsonObject benchmark = json.getAsJsonArray("benchmarks").get(0).getAsJsonObject();

        assertEquals("bearerProxied", benchmark.get("name").getAsString());
        assertTrue(benchmark.has("score"), "Benchmark should have a score");
        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertTrue(percentiles.get("50.0").getAsDouble() <= percentiles.get("99.0").getAsDouble(),
                "P50 should be <= P99");
    }

    @Test
    @DisplayName("the shipped Lua check enforces the failure-rate gate above threshold")
    void errorCountersFailTheRunAboveThreshold() throws Exception {
        String lua = readResource("/wrk-scripts/bearer_proxied_check.lua");

        // The gate folds non-2xx AND socket errors, then exits non-zero above the threshold — so
        // a rejected-token or unreachable-upstream run fails rather than scoring a false PASS.
        assertTrue(lua.contains("WRK_MAX_ERROR_RATE"), "gate must honour the WRK_MAX_ERROR_RATE override");
        assertTrue(lua.contains("summary.errors.connect"), "gate must fold socket-level errors");
        assertTrue(lua.contains("error_rate > MAX_ERROR_RATE"), "gate must compare against the threshold");
        assertTrue(lua.contains("os.exit(1)"), "gate must fail the run non-zero above the threshold");
    }

    @Test
    @DisplayName("the benchmark runner acquires a bearer token and drives the bearer route")
    void benchmarkRunnerDrivesBearerRoute() throws Exception {
        String script = readResource("/wrk-scripts/bearer_proxied_benchmark.sh");

        assertTrue(script.contains("grant_type=password"), "runner must mint an access token");
        assertTrue(script.contains("BENCHMARK_BEARER_TOKEN"), "runner must pass the token to wrk");
        assertTrue(script.contains("bearer_proxied_check.lua"), "runner must drive the bearer check script");
        assertTrue(script.contains("/secure/"), "runner must target the bearer-protected route");
    }

    private String readResource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "classpath resource must be present: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
