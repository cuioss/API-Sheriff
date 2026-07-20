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

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;

/**
 * Structured log messages for the k6 benchmark post-processing harness.
 * <p>
 * All messages follow the format {@code K6Benchmark-[identifier]: [message]}.
 * The upstream {@code de.cuioss.benchmarking.common.util.BenchmarkingLogMessages}
 * catalogue is reused for every toolchain-neutral message; this class supplies only
 * the k6-specific records, whose upstream counterparts are wrk-branded and would
 * therefore emit a factually wrong message for a k6 run.
 * <p>
 * Identifier ranges follow the project logging standard:
 * <ul>
 *   <li>001-099: INFO messages</li>
 *   <li>100-199: WARN messages</li>
 *   <li>200-299: ERROR messages</li>
 * </ul>
 *
 * @since 1.0
 */
public final class K6BenchmarkLogMessages {

    private static final String PREFIX = "K6Benchmark";

    private K6BenchmarkLogMessages() {
        // utility class
    }

    /**
     * INFO level messages (1-99).
     */
    public static final class INFO {

        private INFO() {
            // utility class
        }

        /** Logged once when k6 summary post-processing begins. */
        public static final LogRecord PROCESSING_START = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(1)
                .template("Start processing k6 summary results from %s")
                .build();

        /** Logged once per parsed k6 summary file. */
        public static final LogRecord SUMMARY_PARSED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(2)
                .template("Parsed k6 summary for benchmark '%s' (target '%s')")
                .build();

        /** Logged once when the comparative side-by-side summary starts rendering. */
        public static final LogRecord COMPARISON_START = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(3)
                .template("Rendering comparison summary from %s for targets '%s' and '%s'")
                .build();

        /** Logged once when the comparative side-by-side summary has been written. */
        public static final LogRecord COMPARISON_WRITTEN = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(4)
                .template("Wrote comparison summary covering %s aspect(s) to %s")
                .build();
    }

    /**
     * ERROR level messages (200-299).
     */
    public static final class ERROR {

        private ERROR() {
            // utility class
        }

        /** Logged when the processor is invoked without the mandatory input directory. */
        public static final LogRecord USAGE_ERROR = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(200)
                .template("Usage: K6ResultPostProcessor <input-dir> [output-dir]")
                .build();

        /** Logged when the k6 result processor aborts on an I/O failure. */
        public static final LogRecord PROCESSOR_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(201)
                .template("Failed to execute k6 result processor")
                .build();

        /** Logged when the expected k6 summary directory is absent. */
        public static final LogRecord SUMMARY_DIR_NOT_EXIST = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(202)
                .template("k6 summary directory does not exist: %s")
                .build();

        /** Logged when the k6 summary directory contains no summary files. */
        public static final LogRecord NO_SUMMARY_FILES = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(203)
                .template("No k6 summary files found in: %s")
                .build();

        /** Logged when a single k6 summary file cannot be parsed. */
        public static final LogRecord FAILED_PARSE_SUMMARY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(204)
                .template("Failed to parse k6 summary file %s: %s")
                .build();

        /** Logged when a k6 summary omits a mandatory field. */
        public static final LogRecord INCOMPLETE_SUMMARY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(205)
                .template("Incomplete k6 summary in file %s (missing field '%s')")
                .build();

        /** Logged when the comparison writer is invoked with the wrong argument count. */
        public static final LogRecord COMPARISON_USAGE_ERROR = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(206)
                .template("Usage: ComparisonSummaryWriter <results-root> <target-a> <target-b>")
                .build();

        /** Logged when a target's per-run results directory is absent under the results root. */
        public static final LogRecord COMPARISON_TARGET_DIR_MISSING = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(207)
                .template("No results directory for target '%s' under %s")
                .build();
    }
}
