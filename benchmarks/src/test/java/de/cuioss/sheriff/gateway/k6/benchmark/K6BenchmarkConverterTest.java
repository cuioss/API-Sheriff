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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link K6BenchmarkConverter} and {@link K6ResultPostProcessor}.
 * <p>
 * These carry over the value-asserting discipline of the outgoing wrk test suite: the concrete
 * P50/P75/P90/P99 numbers and the formatted throughput score are pinned against known-numbers
 * fixtures, so a unit-conversion regression in the converter fails here rather than passing an
 * ordering-only check. The fixtures are loaded from the test classpath (not CWD-relative paths,
 * which only resolve when the JVM working directory happens to be the module root).
 */
class K6BenchmarkConverterTest {

    private static final String HEALTH_FIXTURE = "k6-summary-health-live.json";
    private static final String GATEWAY_HEALTH_FIXTURE = "k6-summary-gateway-health.json";

    @TempDir
    Path tempDir;

    private K6ResultPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new K6ResultPostProcessor();
    }

    /**
     * Copies a fixture from the test classpath into the temp {@code k6} directory the processor
     * reads. Loading via the classpath makes the test independent of the JVM working directory.
     */
    private void copyFixtureToK6Dir(String fixtureName) throws IOException {
        Path k6Dir = tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY);
        Files.createDirectories(k6Dir);
        try (InputStream in = K6BenchmarkConverterTest.class.getClassLoader()
                     .getResourceAsStream(fixtureName)) {
            assertNotNull(in, "Fixture must be on the test classpath: " + fixtureName);
            Files.copy(in, k6Dir.resolve(fixtureName));
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
        copyFixtureToK6Dir(HEALTH_FIXTURE);

        // Act
        JsonObject json = processAndReadJson();

        // Assert — concrete values parsed from k6-summary-health-live.json
        JsonObject benchmark = benchmarkNamed(json, "healthLiveCheck");
        assertNotNull(benchmark, "healthLiveCheck benchmark must be present (exact name match)");
        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertAll("healthLiveCheck metrics",
                () -> assertEquals(1.69, percentiles.get("50.0").getAsDouble(), 0.001, "P50 latency (ms)"),
                () -> assertEquals(3.18, percentiles.get("75.0").getAsDouble(), 0.001, "P75 latency (ms)"),
                () -> assertEquals(14.60, percentiles.get("90.0").getAsDouble(), 0.001, "P90 latency (ms)"),
                () -> assertEquals(29.99, percentiles.get("99.0").getAsDouble(), 0.001, "P99 latency (ms)"),
                () -> assertEquals("8.5K ops/s", benchmark.get("score").getAsString(),
                        "throughput score (formatted from requests_per_second: 8511.49)"),
                () -> assertEquals("ops/s", benchmark.get("scoreUnit").getAsString(), "score unit"));
    }

    @Test
    void gatewayHealthPercentilesAndThroughputMatchFixtureValues() throws Exception {
        // Arrange
        copyFixtureToK6Dir(GATEWAY_HEALTH_FIXTURE);

        // Act
        JsonObject json = processAndReadJson();

        // Assert — concrete values parsed from k6-summary-gateway-health.json
        JsonObject benchmark = benchmarkNamed(json, "gatewayHealth");
        assertNotNull(benchmark, "gatewayHealth benchmark must be present (exact name match)");
        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertAll("gatewayHealth metrics",
                () -> assertEquals(1.73, percentiles.get("50.0").getAsDouble(), 0.001, "P50 latency (ms)"),
                () -> assertEquals(2.97, percentiles.get("75.0").getAsDouble(), 0.001, "P75 latency (ms)"),
                () -> assertEquals(14.05, percentiles.get("90.0").getAsDouble(), 0.001, "P90 latency (ms)"),
                () -> assertEquals(28.27, percentiles.get("99.0").getAsDouble(), 0.001, "P99 latency (ms)"),
                () -> assertEquals("8.6K ops/s", benchmark.get("score").getAsString(),
                        "throughput score (formatted from requests_per_second: 8592.89)"),
                () -> assertEquals("ops/s", benchmark.get("scoreUnit").getAsString(), "score unit"));
    }

    @Test
    void gatewayTargetIsRecordedInBenchmarkMetadata() throws Exception {
        // Arrange
        copyFixtureToK6Dir(HEALTH_FIXTURE);

        // Act
        JsonObject json = processAndReadJson();

        // Assert — the gateway a run was taken against must survive into the emitted report, so a
        // downstream consumer can attribute a result to a specific gateway. It rides fullName
        // because the report pipeline whitelists the fields it emits and drops additionalData;
        // name stays bare so the gh-pages history/trend keys are unaffected.
        JsonObject benchmark = benchmarkNamed(json, "healthLiveCheck");
        assertNotNull(benchmark, "healthLiveCheck benchmark must be present");
        assertAll("gateway attribution",
                () -> assertEquals("k6.api-sheriff.healthLiveCheck", benchmark.get("fullName").getAsString(),
                        "gateway_target is attributable from the serialized fullName"),
                () -> assertEquals("healthLiveCheck", benchmark.get("name").getAsString(),
                        "name stays the bare benchmark name so history keys remain stable"));
    }

    @Test
    void exactNameLookupResolvesEachBenchmarkIndependently() throws Exception {
        // Arrange — both fixtures present so the exact-name contract must disambiguate them
        // (the removed bidirectional-contains() fallback was nondeterministic on overlapping names).
        copyFixtureToK6Dir(HEALTH_FIXTURE);
        copyFixtureToK6Dir(GATEWAY_HEALTH_FIXTURE);

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
        copyFixtureToK6Dir(HEALTH_FIXTURE);
        copyFixtureToK6Dir(GATEWAY_HEALTH_FIXTURE);

        // Act
        Path outputDir = tempDir.resolve("output");
        processor.process(tempDir, outputDir);

        // Assert — full GitHub-Pages report structure
        verifyComprehensiveStructure(outputDir);
    }

    @Test
    void missingFileHandling() throws Exception {
        // Arrange — empty k6 directory, no fixtures
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY));

        // Act + Assert — fail loud rather than publishing an empty report
        assertThrows(IllegalStateException.class, () ->
                processor.process(tempDir, outputDir));

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertFalse(Files.exists(jsonFile), "JSON should not be created with missing inputs");
    }

    @Test
    void missingK6DirectoryHandling() throws Exception {
        // Arrange — the k6 subdirectory does not exist at all
        Path outputDir = tempDir.resolve("output");

        // Act + Assert
        assertThrows(IllegalStateException.class, () ->
                processor.process(tempDir, outputDir));

        Path jsonFile = outputDir.resolve("gh-pages-ready/data/benchmark-data.json");
        assertFalse(Files.exists(jsonFile), "JSON should not be created without a k6 directory");
    }

    @Test
    void summaryMissingMandatoryFieldYieldsNoBenchmark() throws Exception {
        // Arrange — a summary without requests_per_second yields no benchmark rather than a
        // partial one, so an unusable run can never be reported as a measurement.
        Path k6Dir = tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY);
        Files.createDirectories(k6Dir);
        Files.writeString(k6Dir.resolve("incomplete-summary.json"), """
                {
                  "benchmark_name": "incomplete",
                  "start_time": "2026-07-19T10:00:00Z",
                  "end_time": "2026-07-19T10:01:00Z",
                  "latency_ms": {"p50": 1.0}
                }
                """);

        // Act
        JsonObject json = processAndReadJson();

        // Assert
        assertEquals(0, json.getAsJsonArray("benchmarks").size(),
                "a summary missing requests_per_second contributes no benchmark");
    }

    @Test
    void nonPrimitiveGatewayTargetFallsBackToUnknown() throws Exception {
        // Arrange — gateway_target present but a JSON array (non-primitive). getAsString() would
        // throw UnsupportedOperationException and crash the converter; the guard must fall back.
        Path k6Dir = tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY);
        Files.createDirectories(k6Dir);
        Files.writeString(k6Dir.resolve("weird-target-summary.json"), """
                {
                  "benchmark_name": "weirdTarget",
                  "gateway_target": ["api-sheriff"],
                  "start_time": "2026-07-19T10:00:00Z",
                  "end_time": "2026-07-19T10:01:00Z",
                  "requests_per_second": 1000.0,
                  "latency_ms": {"p50": 1.0}
                }
                """);

        // Act
        JsonObject json = assertDoesNotThrow(this::processAndReadJson,
                "a non-primitive gateway_target must not crash the converter");

        // Assert — the benchmark is produced with the unknown-target fallback
        JsonObject benchmark = benchmarkNamed(json, "weirdTarget");
        assertNotNull(benchmark, "the benchmark must still be produced");
        assertEquals("k6.unknown.weirdTarget", benchmark.get("fullName").getAsString(),
                "a non-primitive gateway_target must fall back to 'unknown'");
    }

    @Test
    void nonPrimitiveBenchmarkNameYieldsNoBenchmark() throws Exception {
        // Arrange — a valid summary plus one whose benchmark_name is a JSON object. requiredString()
        // must treat a non-primitive name as missing (skip), not crash on getAsString().
        Path k6Dir = tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY);
        Files.createDirectories(k6Dir);
        Files.writeString(k6Dir.resolve("good-summary.json"), """
                {
                  "benchmark_name": "goodRun",
                  "gateway_target": "api-sheriff",
                  "start_time": "2026-07-19T10:00:00Z",
                  "end_time": "2026-07-19T10:01:00Z",
                  "requests_per_second": 1000.0,
                  "latency_ms": {"p50": 1.0}
                }
                """);
        Files.writeString(k6Dir.resolve("bad-name-summary.json"), """
                {
                  "benchmark_name": {"unexpected": "object"},
                  "gateway_target": "api-sheriff",
                  "start_time": "2026-07-19T10:00:00Z",
                  "end_time": "2026-07-19T10:01:00Z",
                  "requests_per_second": 1000.0,
                  "latency_ms": {"p50": 1.0}
                }
                """);

        // Act
        JsonObject json = assertDoesNotThrow(this::processAndReadJson,
                "a non-primitive benchmark_name must not crash the converter");

        // Assert — only the valid benchmark is emitted; the object-named summary is skipped
        assertNotNull(benchmarkNamed(json, "goodRun"), "the valid benchmark must be present");
        assertEquals(1, json.getAsJsonArray("benchmarks").size(),
                "a summary with a non-primitive benchmark_name contributes no benchmark");
    }

    @Test
    void nonPrimitiveMetadataFieldIsSkippedWithoutCrashing() throws Exception {
        // Arrange — a valid summary plus one whose start_time is a JSON object (non-primitive).
        // Pre-guard, instantOrNull's getAsString() threw UnsupportedOperationException — uncaught
        // by the metadata parser's catch — and aborted the whole run.
        Path k6Dir = tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY);
        Files.createDirectories(k6Dir);
        Files.writeString(k6Dir.resolve("good-summary.json"), """
                {
                  "benchmark_name": "goodRun",
                  "gateway_target": "api-sheriff",
                  "start_time": "2026-07-19T10:00:00Z",
                  "end_time": "2026-07-19T10:01:00Z",
                  "requests_per_second": 1000.0,
                  "latency_ms": {"p50": 1.0}
                }
                """);
        Files.writeString(k6Dir.resolve("bad-time-summary.json"), """
                {
                  "benchmark_name": "badTime",
                  "gateway_target": "api-sheriff",
                  "start_time": {"nested": "not-a-string"},
                  "end_time": "2026-07-19T10:01:00Z",
                  "requests_per_second": 1000.0,
                  "latency_ms": {"p50": 1.0}
                }
                """);

        // Act — the non-primitive start_time must be treated as absent, not crash the run
        JsonObject json = assertDoesNotThrow(this::processAndReadJson,
                "a non-primitive metadata field must not abort processing");

        // Assert — the valid run is still reported
        assertNotNull(benchmarkNamed(json, "goodRun"), "the valid benchmark must be present");
    }

    @Test
    void onlyMeasuredPercentilesAreEmitted() throws Exception {
        // Arrange — a summary reporting only P50/P99; the absent percentiles must be omitted,
        // never estimated or fabricated.
        Path k6Dir = tempDir.resolve(K6ResultPostProcessor.K6_RESULTS_SUBDIRECTORY);
        Files.createDirectories(k6Dir);
        Files.writeString(k6Dir.resolve("sparse-summary.json"), """
                {
                  "benchmark_name": "sparseAspect",
                  "gateway_target": "apisix",
                  "start_time": "2026-07-19T10:00:00Z",
                  "end_time": "2026-07-19T10:01:00Z",
                  "requests_per_second": 1500.0,
                  "latency_ms": {"avg": 2.0, "p50": 1.5, "p99": 9.5}
                }
                """);

        // Act
        JsonObject json = processAndReadJson();

        // Assert
        JsonObject benchmark = benchmarkNamed(json, "sparseAspect");
        assertNotNull(benchmark, "sparseAspect benchmark must be present");
        JsonObject percentiles = benchmark.getAsJsonObject("percentiles");
        assertAll("only measured percentiles",
                () -> assertEquals(1.5, percentiles.get("50.0").getAsDouble(), 0.001, "measured P50"),
                () -> assertEquals(9.5, percentiles.get("99.0").getAsDouble(), 0.001, "measured P99"),
                () -> assertFalse(percentiles.has("75.0"), "unmeasured P75 must be omitted"),
                () -> assertFalse(percentiles.has("90.0"), "unmeasured P90 must be omitted"),
                // The general-purpose MetricConversionUtil.formatThroughput renders two fraction
                // digits below 2K ("1.50K") where the wrk-specific formatter rendered one ("1.5K").
                // The k6 lane deliberately uses the general formatter rather than the wrk-branded
                // one; the one-time gh-pages baseline restart means no historical series depends
                // on the old rendering.
                () -> assertEquals("1.50K ops/s", benchmark.get("score").getAsString(),
                        "throughput score (formatted from requests_per_second: 1500.0)"));
    }

    /**
     * Verify that k6 generates the same comprehensive structure the wrk toolchain produced.
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
