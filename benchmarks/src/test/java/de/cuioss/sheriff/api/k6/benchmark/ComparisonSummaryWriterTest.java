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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ComparisonSummaryWriter}.
 * <p>
 * Value-asserting against known-numbers two-target fixtures, matching the discipline of
 * {@link K6BenchmarkConverterTest}: the concrete rendered figures and the per-aspect unit
 * selection are pinned, so a regression that swapped MBps for RPS on the transfer-bound aspect --
 * or that silently rendered an unmeasured metric as 0 -- fails here rather than passing a
 * presence-only check.
 */
class ComparisonSummaryWriterTest {

    private static final String SHERIFF = "api-sheriff";
    private static final String APISIX = "apisix";

    @TempDir
    Path tempDir;

    /** A request-rate aspect: compared on RPS, with both percentiles measured. */
    private static JsonObject unauthSummary(double rps, double p50, double p99) {
        return parse("""
                {
                  "benchmark_name": "proxiedStatic",
                  "gateway_target": "api-sheriff",
                  "requests_per_second": %s,
                  "error_rate": 0.0,
                  "latency_ms": { "avg": 4.0, "p50": %s, "p99": %s }
                }
                """.formatted(rps, p50, p99));
    }

    /** The transfer-bound aspect: carries throughput_mbps alongside the latency percentiles. */
    private static JsonObject uploadLargeSummary(double mbps, double rps, double p50, double p99) {
        return parse("""
                {
                  "benchmark_name": "uploadLarge",
                  "gateway_target": "api-sheriff",
                  "requests_per_second": %s,
                  "throughput_mbps": %s,
                  "error_rate": 0.0,
                  "latency_ms": { "avg": 900.0, "p50": %s, "p99": %s }
                }
                """.formatted(rps, mbps, p50, p99));
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void shouldCompareRequestRateAspectOnRps() {
        // Arrange
        Map<String, JsonObject> sheriff = Map.of("proxiedStatic", unauthSummary(8511.49, 1.69, 29.99));
        Map<String, JsonObject> apisix = Map.of("proxiedStatic", unauthSummary(6120.25, 2.44, 41.10));

        // Act
        String rendered = ComparisonSummaryWriter.render(SHERIFF, sheriff, APISIX, apisix);

        // Assert
        assertTrue(rendered.contains("| proxiedStatic | RPS | 8511.49 | 6120.25 | 1.69 / 29.99 | 2.44 / 41.10 |"),
                "request-rate aspect must be compared on RPS with both P50/P99 pairs:\n" + rendered);
    }

    @Test
    void shouldCompareUploadLargeAspectOnMbpsNotRps() {
        // Arrange -- requests_per_second is deliberately present and distinct from throughput_mbps,
        // so a regression selecting the wrong field renders a recognisably wrong number.
        Map<String, JsonObject> sheriff = Map.of("uploadLarge", uploadLargeSummary(412.75, 8.25, 605.10, 980.40));
        Map<String, JsonObject> apisix = Map.of("uploadLarge", uploadLargeSummary(298.10, 5.96, 838.72, 1420.55));

        // Act
        String rendered = ComparisonSummaryWriter.render(SHERIFF, sheriff, APISIX, apisix);

        // Assert
        assertTrue(rendered.contains("| uploadLarge | MBps | 412.75 | 298.10 | 605.10 / 980.40 | 838.72 / 1420.55 |"),
                "transfer-bound aspect must be compared on MBps:\n" + rendered);
        assertFalse(rendered.contains("| uploadLarge | RPS |"),
                "uploadLarge must not be reported on RPS");
        assertFalse(rendered.contains("8.25"), "the RPS figure must not leak into the MBps column");
    }

    @Test
    void shouldRenderNotAvailableForAnAspectOnlyOneTargetProduced() {
        // Arrange -- a half-failed comparison run: APISIX produced no graphql summary.
        Map<String, JsonObject> sheriff = Map.of("graphql", unauthSummary(3200.00, 12.50, 88.00));

        // Act
        String rendered = ComparisonSummaryWriter.render(SHERIFF, sheriff, APISIX, Map.of());

        // Assert -- the row survives so the gap is visible, and the missing side reads n/a
        // rather than 0, which would look like a measured collapse.
        assertTrue(rendered.contains("| graphql | RPS | 3200.00 | n/a | 12.50 / 88.00 | n/a / n/a |"),
                "an aspect only one target produced must still render, with n/a for the other:\n" + rendered);
        assertFalse(rendered.contains("| 0.00 |"), "an absent measurement must never render as 0");
    }

    @Test
    void shouldRenderNotAvailableWhenThroughputWasNotMeasured() {
        // Arrange -- uploadLarge without throughput_mbps (an older summary shape).
        Map<String, JsonObject> sheriff = Map.of("uploadLarge", unauthSummary(9.10, 601.00, 970.00));

        // Act
        String rendered = ComparisonSummaryWriter.render(SHERIFF, sheriff, APISIX, Map.of());

        // Assert
        assertTrue(rendered.contains("| uploadLarge | MBps | n/a | n/a |"),
                "a missing throughput metric must render n/a, never the RPS fallback:\n" + rendered);
    }

    @Test
    void shouldReadEveryTargetSummaryFromDisk() throws Exception {
        // Arrange
        Path targetDir = tempDir.resolve(SHERIFF);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("proxiedStatic-summary.json"),
                unauthSummary(8511.49, 1.69, 29.99).toString(), StandardCharsets.UTF_8);
        Files.writeString(targetDir.resolve("uploadLarge-summary.json"),
                uploadLargeSummary(412.75, 8.25, 605.10, 980.40).toString(), StandardCharsets.UTF_8);

        // Act
        Map<String, JsonObject> summaries = ComparisonSummaryWriter.readTargetSummaries(tempDir, SHERIFF);

        // Assert -- keyed by benchmark_name, not by file name
        assertEquals(2, summaries.size());
        assertTrue(summaries.containsKey("proxiedStatic"));
        assertTrue(summaries.containsKey("uploadLarge"));
    }

    @Test
    void shouldReturnNoSummariesWhenTargetDirectoryIsAbsent() throws Exception {
        // Arrange -- nothing on disk for this target

        // Act
        Map<String, JsonObject> summaries = ComparisonSummaryWriter.readTargetSummaries(tempDir, APISIX);

        // Assert -- absent target degrades to an empty column rather than aborting the render
        assertTrue(summaries.isEmpty());
    }

    @Test
    void shouldWriteComparisonArtifactBesideTheInputs() throws Exception {
        // Arrange
        for (String target : new String[]{SHERIFF, APISIX}) {
            Path targetDir = tempDir.resolve(target);
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve("proxiedStatic-summary.json"),
                    unauthSummary(8511.49, 1.69, 29.99).toString(), StandardCharsets.UTF_8);
        }

        // Act
        ComparisonSummaryWriter.main(new String[]{tempDir.toString(), SHERIFF, APISIX});

        // Assert -- written under the results root, never into a gh-pages tree
        Path artifact = tempDir.resolve(ComparisonSummaryWriter.OUTPUT_FILE_NAME);
        assertTrue(Files.exists(artifact), "comparison summary must be written beside the inputs");
        String rendered = Files.readString(artifact, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("proxiedStatic"), "artifact must carry the compared aspect");
        assertFalse(Files.exists(tempDir.resolve("gh-pages-ready")),
                "the comparison artifact must not create a gh-pages deployment tree");
    }
}
