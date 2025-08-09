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
package de.cuioss.sheriff.api.quarkus.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Shared utility class for building JMH benchmark options.
 * Consolidates common option parsing logic for benchmark runners.
 *
 * @author API Sheriff Team
 */
public final class BenchmarkOptionsHelper {

    private BenchmarkOptionsHelper() {
        // Utility class
    }

    /**
     * Gets the result format from system property or defaults to JSON.
     *
     * @return the result format type
     */
    public static ResultFormatType getResultFormat() {
        String format = System.getProperty("jmh.result.format", "JSON");
        try {
            return ResultFormatType.valueOf(format.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResultFormatType.JSON;
        }
    }

    /**
     * Gets the result file path from system property or returns the default.
     * Ensures parent directory exists.
     *
     * @param defaultFileName the default file name to use if property not set
     * @return the result file path
     */
    public static String getResultFile(String defaultFileName) {
        String filePrefix = System.getProperty("jmh.result.filePrefix");
        if (filePrefix != null && !filePrefix.isEmpty()) {
            String resultFile = filePrefix + ".json";
            // Ensure parent directory exists
            File file = new File(resultFile);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            return resultFile;
        }
        return defaultFileName;
    }

    /**
     * Gets the measurement time from system property or uses the provided default.
     *
     * @param defaultTime the default time value (e.g., "2s", "5s")
     * @return the parsed time value
     */
    public static TimeValue getMeasurementTime(String defaultTime) {
        String time = System.getProperty("jmh.time", defaultTime);
        return parseTimeValue(time);
    }

    /**
     * Gets the warmup time from system property or uses the provided default.
     *
     * @param defaultTime the default time value (e.g., "2s", "3s")
     * @return the parsed time value
     */
    public static TimeValue getWarmupTime(String defaultTime) {
        String time = System.getProperty("jmh.warmupTime", defaultTime);
        return parseTimeValue(time);
    }

    /**
     * Gets the thread count from system property or uses the provided default.
     * Supports "MAX" to use all available processors.
     *
     * @param defaultCount the default thread count
     * @return the thread count
     */
    public static int getThreadCount(int defaultCount) {
        String threads = System.getProperty("jmh.threads");
        if (threads != null) {
            if ("MAX".equalsIgnoreCase(threads)) {
                return Runtime.getRuntime().availableProcessors();
            }
            try {
                return Integer.parseInt(threads);
            } catch (NumberFormatException e) {
                // Fall through to default
            }
        }
        return defaultCount;
    }

    /**
     * Gets the number of forks from system property or uses the provided default.
     *
     * @param defaultForks the default number of forks
     * @return the number of forks
     */
    public static int getForks(int defaultForks) {
        return Integer.parseInt(System.getProperty("jmh.forks", String.valueOf(defaultForks)));
    }

    /**
     * Gets the warmup iterations from system property or uses the provided default.
     *
     * @param defaultIterations the default number of warmup iterations
     * @return the number of warmup iterations
     */
    public static int getWarmupIterations(int defaultIterations) {
        return Integer.parseInt(System.getProperty("jmh.warmupIterations", String.valueOf(defaultIterations)));
    }

    /**
     * Gets the measurement iterations from system property or uses the provided default.
     *
     * @param defaultIterations the default number of measurement iterations
     * @return the number of measurement iterations
     */
    public static int getMeasurementIterations(int defaultIterations) {
        return Integer.parseInt(System.getProperty("jmh.measurementIterations", String.valueOf(defaultIterations)));
    }

    /**
     * Parse a time value string (e.g., "2s", "100ms", "1m").
     *
     * @param timeStr the time string to parse
     * @return the parsed TimeValue
     */
    private static TimeValue parseTimeValue(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return TimeValue.seconds(1);
        }

        timeStr = timeStr.trim().toLowerCase();

        // Extract numeric part and unit
        StringBuilder numPart = new StringBuilder();
        StringBuilder unitPart = new StringBuilder();
        boolean foundUnit = false;

        for (char c : timeStr.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                if (!foundUnit) {
                    numPart.append(c);
                }
            } else {
                foundUnit = true;
                unitPart.append(c);
            }
        }

        try {
            long value = Long.parseLong(numPart.toString());
            String unit = unitPart.toString();

            switch (unit) {
                case "ms":
                case "milliseconds":
                case "millis":
                    return TimeValue.milliseconds(value);
                case "s":
                case "sec":
                case "seconds":
                    return TimeValue.seconds(value);
                case "m":
                case "min":
                case "minutes":
                    return TimeValue.minutes(value);
                default:
                    // Default to seconds if no unit or unknown unit
                    return TimeValue.seconds(value);
            }
        } catch (NumberFormatException e) {
            // Default fallback
            return TimeValue.seconds(1);
        }
    }
}