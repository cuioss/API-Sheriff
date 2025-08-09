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
package de.cuioss.sheriff.api.benchmark;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import de.cuioss.sheriff.api.ApiSheriff;
import de.cuioss.sheriff.api.config.ApiGatewayConfig;

/**
 * JMH benchmark suite for API Sheriff library performance testing.
 * This class provides comprehensive benchmarks for various API Sheriff operations
 * including rate limiting, request validation, and configuration handling.
 * 
 * <h2>Benchmark Categories:</h2>
 * <ul>
 *   <li>Single-threaded rate limiting performance</li>
 *   <li>Multi-threaded concurrent request handling</li>
 *   <li>Configuration validation overhead</li>
 *   <li>Memory allocation patterns</li>
 * </ul>
 * 
 * <h2>Running Benchmarks:</h2>
 * <pre>
 * java -jar benchmark-library.jar
 * </pre>
 * 
 * <h2>Benchmark Parameters:</h2>
 * <ul>
 *   <li>{@code rateLimit} - Maximum requests per time window</li>
 *   <li>{@code timeWindow} - Time window duration in seconds</li>
 *   <li>{@code clientCount} - Number of concurrent clients to simulate</li>
 * </ul>
 *
 * @author API Sheriff Team
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xmx2G", "-server"})
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public class BenchmarkRunner {

    @Param({"100", "1000", "10000"})
    private int rateLimit;

    @Param({"1", "5", "10"})
    private int timeWindow;

    @Param({"1", "10", "100"})
    private int clientCount;

    private ApiSheriff apiSheriff;
    private String[] clientIds;
    private String testEndpoint;

    /**
     * Sets up the benchmark environment before each benchmark run.
     * Creates ApiSheriff instances with different configurations and
     * prepares test data for consistent benchmarking.
     */
    @Setup(Level.Trial)
    public void setup() {
        ApiGatewayConfig config = ApiGatewayConfig.builder()
            .rateLimit(rateLimit)
            .timeWindow(Duration.ofSeconds(timeWindow))
            .requestTimeout(Duration.ofSeconds(30))
            .corsEnabled(true)
            .build();

        apiSheriff = new ApiSheriff(config);
        testEndpoint = "/api/benchmark/test";

        // Pre-generate client IDs for consistent testing
        clientIds = new String[clientCount];
        for (int i = 0; i < clientCount; i++) {
            clientIds[i] = "client-" + i;
        }
    }

    /**
     * Cleans up resources after benchmark completion.
     * Resets API Sheriff state and releases any allocated resources.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        // Reset all client states for clean benchmark runs
        for (String clientId : clientIds) {
            apiSheriff.resetClientState(clientId);
        }
    }

    /**
     * Benchmarks single-threaded request processing performance.
     * Measures throughput of sequential request validation calls.
     *
     * @return the result of the request validation (to prevent dead code elimination)
     */
    @Benchmark
    public boolean benchmarkSingleThreadedRequests() {
        String clientId = clientIds[0];
        return apiSheriff.isRequestAllowed(clientId, testEndpoint);
    }

    /**
     * Benchmarks multi-threaded concurrent request processing.
     * Uses round-robin client selection to simulate real-world load patterns.
     *
     * @return the result of the request validation
     */
    @Benchmark
    @Threads(4)
    public boolean benchmarkConcurrentRequests() {
        // Use thread ID to select client in a round-robin fashion
        long threadId = Thread.currentThread().getId();
        int clientIndex = (int) (threadId % clientCount);
        String clientId = clientIds[clientIndex];
        
        return apiSheriff.isRequestAllowed(clientId, testEndpoint);
    }

    /**
     * Benchmarks configuration validation performance.
     * Measures overhead of configuration validation calls.
     *
     * @return true if validation passes (to prevent dead code elimination)
     */
    @Benchmark
    public boolean benchmarkConfigurationValidation() {
        return apiSheriff.validateConfiguration().isEmpty();
    }

    /**
     * Benchmarks client state reset operations.
     * Measures performance of administrative operations.
     */
    @Benchmark
    public void benchmarkClientStateReset() {
        String clientId = clientIds[0];
        apiSheriff.resetClientState(clientId);
    }

    /**
     * Benchmarks mixed workload with various operations.
     * Simulates realistic API gateway usage patterns combining
     * request validation, configuration checks, and state management.
     *
     * @return combined result of all operations
     */
    @Benchmark
    public boolean benchmarkMixedWorkload() {
        String clientId = clientIds[0];
        
        // 80% request validation, 15% config validation, 5% state reset
        int operation = (int) (Math.random() * 100);
        
        if (operation < 80) {
            return apiSheriff.isRequestAllowed(clientId, testEndpoint);
        } else if (operation < 95) {
            return apiSheriff.validateConfiguration().isEmpty();
        } else {
            apiSheriff.resetClientState(clientId);
            return true;
        }
    }

    /**
     * Benchmarks rate limiter behavior under heavy load.
     * Specifically tests performance when rate limits are frequently exceeded.
     *
     * @return the result of the request validation
     */
    @Benchmark
    public boolean benchmarkRateLimitExceeded() {
        String clientId = "heavy-client";
        
        // Make multiple requests to exceed rate limit
        for (int i = 0; i < rateLimit + 10; i++) {
            apiSheriff.isRequestAllowed(clientId, testEndpoint);
        }
        
        // This should be rejected due to rate limiting
        return apiSheriff.isRequestAllowed(clientId, testEndpoint);
    }

    /**
     * Main method to run benchmarks with custom configurations.
     * Allows running specific benchmarks or all benchmarks with custom settings.
     *
     * @param args command line arguments (optional benchmark filters)
     * @throws RunnerException if benchmark execution fails
     */
    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
            .include(BenchmarkRunner.class.getSimpleName())
            .forks(1)
            .threads(1)
            .warmupIterations(2)
            .measurementIterations(3)
            .jvmArgs("-Xmx1G", "-server")
            .build();

        new Runner(options).run();
    }
}