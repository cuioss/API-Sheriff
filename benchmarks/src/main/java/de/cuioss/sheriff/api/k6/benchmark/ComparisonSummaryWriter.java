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
import de.cuioss.tools.logging.CuiLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Renders the per-aspect side-by-side summary for an on-demand comparative benchmark run.
 * <p>
 * Reads the k6 summaries two targets produced under a shared results root and emits one
 * comparison table:
 *
 * <pre>{@code
 * <results-root>/
 *   api-sheriff/uploadLarge-summary.json
 *   apisix/uploadLarge-summary.json
 *   comparison-summary.md      <-- written here
 * }</pre>
 * <p>
 * <strong>Deliberately segregated from the published tree.</strong> The artifact is written
 * beside the comparison inputs, never into the gh-pages deployment tree and never into the CI
 * baseline results directory. Comparison numbers are taken on demand against a second gateway and
 * are not part of the baseline trend series keyed by benchmark name; publishing them would corrupt
 * that history with runs whose conditions differ from the CI lane's.
 * <p>
 * <strong>Metric selection.</strong> Each aspect is reported on the figure that actually
 * characterises it: the transfer-bound {@code uploadLarge} aspect is compared on throughput
 * (MBps), every other aspect on throughput in requests/second. P50 and P99 are always reported per
 * gateway. A metric a run did not measure is rendered as {@code n/a} rather than {@code 0} -- a
 * zero would read as a measured collapse rather than an absent measurement.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ComparisonSummaryWriter {

    private static final CuiLogger LOGGER = new CuiLogger(ComparisonSummaryWriter.class);

    /** Name of the rendered artifact, written directly under the results root. */
    static final String OUTPUT_FILE_NAME = "comparison-summary.md";

    /** Suffix every k6 script appends to its per-run summary export. */
    private static final String SUMMARY_SUFFIX = "-summary.json";

    /** The one aspect compared on transferred bytes rather than on request rate. */
    private static final String THROUGHPUT_ASPECT = "uploadLarge";

    private static final String FIELD_BENCHMARK_NAME = "benchmark_name";
    private static final String FIELD_REQUESTS_PER_SECOND = "requests_per_second";
    private static final String FIELD_THROUGHPUT_MBPS = "throughput_mbps";
    private static final String FIELD_LATENCY_MS = "latency_ms";
    private static final String FIELD_P50 = "p50";
    private static final String FIELD_P99 = "p99";

    private static final String NOT_AVAILABLE = "n/a";

    private ComparisonSummaryWriter() {
        // utility class
    }

    /**
     * Renders the comparison summary for two targets under one results root.
     *
     * @param args {@code <results-root> <target-a> <target-b>}
     * @throws IOException when the results root cannot be read or the artifact cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.COMPARISON_USAGE_ERROR);
            throw new IllegalArgumentException(
                    "expected <results-root> <target-a> <target-b>, got " + args.length + " argument(s)");
        }
        Path resultsRoot = Path.of(args[0]);
        String targetA = args[1];
        String targetB = args[2];

        LOGGER.info(K6BenchmarkLogMessages.INFO.COMPARISON_START, resultsRoot, targetA, targetB);

        Map<String, JsonObject> summariesA = readTargetSummaries(resultsRoot, targetA);
        Map<String, JsonObject> summariesB = readTargetSummaries(resultsRoot, targetB);

        String rendered = render(targetA, summariesA, targetB, summariesB);
        Path output = resultsRoot.resolve(OUTPUT_FILE_NAME);
        Files.writeString(output, rendered);

        Set<String> coveredAspects = new TreeSet<>(summariesA.keySet());
        coveredAspects.addAll(summariesB.keySet());
        LOGGER.info(K6BenchmarkLogMessages.INFO.COMPARISON_WRITTEN, coveredAspects.size(), output);
    }

    /**
     * Reads every k6 summary a target produced, keyed by benchmark name.
     *
     * @param resultsRoot the comparison results root
     * @param target      the gateway target label, also the sub-directory name
     * @return the target's summaries keyed by benchmark name, empty when the directory is absent
     * @throws IOException when the target directory cannot be listed
     */
    static Map<String, JsonObject> readTargetSummaries(Path resultsRoot, String target) throws IOException {
        Path targetDir = resultsRoot.resolve(target);
        if (!Files.isDirectory(targetDir)) {
            LOGGER.error(K6BenchmarkLogMessages.ERROR.COMPARISON_TARGET_DIR_MISSING, target, resultsRoot);
            return Map.of();
        }
        Map<String, JsonObject> summaries = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(targetDir)) {
            List<Path> summaryFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(SUMMARY_SUFFIX))
                    .sorted()
                    .toList();
            for (Path file : summaryFiles) {
                JsonObject summary = JsonParser.parseString(Files.readString(file))
                        .getAsJsonObject();
                summaries.put(benchmarkName(summary, file), summary);
            }
        }
        return summaries;
    }

    /**
     * Resolves a summary's benchmark name, falling back to the file name when the field is absent.
     *
     * @param summary the parsed summary document
     * @param file    the file it was read from
     * @return the benchmark name keying this aspect
     */
    private static String benchmarkName(JsonObject summary, Path file) {
        if (summary.has(FIELD_BENCHMARK_NAME)) {
            return summary.get(FIELD_BENCHMARK_NAME).getAsString();
        }
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - SUMMARY_SUFFIX.length());
    }

    /**
     * Renders the side-by-side markdown table across the union of both targets' aspects.
     *
     * @param targetA    the first gateway target label
     * @param summariesA the first target's summaries keyed by benchmark name
     * @param targetB    the second gateway target label
     * @param summariesB the second target's summaries keyed by benchmark name
     * @return the rendered markdown document
     */
    static String render(String targetA, Map<String, JsonObject> summariesA,
            String targetB, Map<String, JsonObject> summariesB) {
        // Union, sorted: an aspect only one side produced still gets a row, with the missing side
        // rendered n/a. Dropping such a row would hide a half-failed comparison run.
        List<String> aspects = new ArrayList<>(new TreeSet<>(summariesA.keySet()));
        summariesB.keySet().stream().filter(name -> !summariesA.containsKey(name)).sorted().forEach(aspects::add);

        StringBuilder out = new StringBuilder(512);
        out.append("# Comparative benchmark summary\n\n")
                .append("`").append(targetA).append("` vs `").append(targetB).append("`. ")
                .append("Throughput is requests/second, except `").append(THROUGHPUT_ASPECT)
                .append("` which is transfer-bound and reported in MBps. Latency percentiles are ")
                .append("milliseconds. `n/a` means the run did not measure the metric.\n\n")
                .append("| Aspect | Metric | ").append(targetA).append(" | ").append(targetB)
                .append(" | ").append(targetA).append(" P50/P99 | ").append(targetB).append(" P50/P99 |\n")
                .append("|---|---|---|---|---|---|\n");

        for (String aspect : aspects) {
            Optional<JsonObject> a = Optional.ofNullable(summariesA.get(aspect));
            Optional<JsonObject> b = Optional.ofNullable(summariesB.get(aspect));
            boolean throughputAspect = THROUGHPUT_ASPECT.equals(aspect);
            out.append("| ").append(aspect)
                    .append(" | ").append(throughputAspect ? "MBps" : "RPS")
                    .append(" | ").append(headline(a, throughputAspect))
                    .append(" | ").append(headline(b, throughputAspect))
                    .append(" | ").append(percentiles(a))
                    .append(" | ").append(percentiles(b))
                    .append(" |\n");
        }
        return out.toString();
    }

    /**
     * Renders an aspect's headline figure for one target.
     *
     * @param summary          the target's summary for this aspect, when it produced one
     * @param throughputAspect whether the aspect is compared on MBps rather than RPS
     * @return the rendered value, or {@code n/a} when not measured
     */
    private static String headline(Optional<JsonObject> summary, boolean throughputAspect) {
        String field = throughputAspect ? FIELD_THROUGHPUT_MBPS : FIELD_REQUESTS_PER_SECOND;
        return summary
                .filter(json -> json.has(field))
                .map(json -> String.format(Locale.ROOT, "%.2f", json.get(field).getAsDouble()))
                .orElse(NOT_AVAILABLE);
    }

    /**
     * Renders an aspect's {@code P50/P99} latency pair for one target.
     *
     * @param summary the target's summary for this aspect, when it produced one
     * @return the rendered {@code P50/P99} pair, with each half {@code n/a} when not measured
     */
    private static String percentiles(Optional<JsonObject> summary) {
        Optional<JsonObject> latency = summary
                .filter(json -> json.has(FIELD_LATENCY_MS))
                .map(json -> json.getAsJsonObject(FIELD_LATENCY_MS));
        return percentile(latency, FIELD_P50) + " / " + percentile(latency, FIELD_P99);
    }

    /**
     * Renders one latency percentile.
     *
     * @param latency the summary's {@code latency_ms} object, when present
     * @param field   the percentile field name
     * @return the rendered percentile, or {@code n/a} when not measured
     */
    private static String percentile(Optional<JsonObject> latency, String field) {
        return latency
                .filter(json -> json.has(field))
                .map(json -> String.format(Locale.ROOT, "%.2f", json.get(field).getAsDouble()))
                .orElse(NOT_AVAILABLE);
    }
}
