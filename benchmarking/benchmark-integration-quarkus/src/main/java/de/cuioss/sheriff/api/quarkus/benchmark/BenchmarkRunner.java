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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

import de.cuioss.sheriff.api.ApiSheriff;
import de.cuioss.sheriff.api.config.ApiGatewayConfig;

/**
 * JMH benchmark suite for API Sheriff Quarkus integration performance testing.
 * This class provides benchmarks specifically for the Quarkus integration,
 * testing CDI injection performance, configuration loading, and integration overhead.
 * 
 * <p>This benchmark suite complements the library benchmarks by focusing on
 * integration-specific performance characteristics including:</p>
 * <ul>
 *   <li>CDI injection and proxy overhead</li>
 *   <li>Configuration property loading from application.properties</li>
 *   <li>Quarkus startup and runtime performance</li>
 *   <li>Native image compilation impact</li>
 * </ul>
 * 
 * <h2>Configuration:</h2>
 * <p>This benchmark uses the standard Quarkus configuration mechanism
 * and can be configured via application.properties or environment variables.</p>
 * 
 * <h2>Running in Quarkus:</h2>
 * <pre>
 * ./mvnw quarkus:dev
 * </pre>
 * 
 * <h2>Running as JMH Benchmark:</h2>
 * <pre>
 * java -jar benchmark-integration-quarkus-runner.jar
 * </pre>
 *
 * @author API Sheriff Team
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xmx2G", "-server"})
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@ApplicationScoped
public class BenchmarkRunner {

    /**
     * Injected API Sheriff instance configured via Quarkus CDI.
     * This tests the performance of CDI injection and proxy calls.
     */
    @Inject
    ApiSheriff apiSheriff;

    /**
     * Injected configuration instance to test configuration access performance.
     */
    @Inject
    ApiGatewayConfig config;

    @Param({"1", "10", "100"})
    private int concurrentClients;

    @Param({"/api/v1/users", "/api/v1/orders", "/api/v1/products"})
    private String endpoint;

    private String[] clientIds;
    private ApiSheriff directApiSheriff; // For comparison with direct instantiation

    /**
     * Sets up the benchmark environment.
     * Initializes test data and creates comparison instances.
     */
    @Setup(Level.Trial)
    public void setup() {
        // Initialize client IDs for testing
        clientIds = new String[concurrentClients];
        for (int i = 0; i < concurrentClients; i++) {
            clientIds[i] = "benchmark-client-" + i;
        }

        // Create a direct ApiSheriff instance for comparison
        ApiGatewayConfig directConfig = ApiGatewayConfig.builder()
            .rateLimit(1000)
            .timeWindow(Duration.ofMinutes(1))
            .requestTimeout(Duration.ofSeconds(30))
            .corsEnabled(false)
            .build();
        
        directApiSheriff = new ApiSheriff(directConfig);
    }

    /**
     * Cleans up resources after benchmarking.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        // Reset client states for clean benchmark runs
        if (apiSheriff != null) {
            for (String clientId : clientIds) {
                apiSheriff.resetClientState(clientId);
            }
        }
        
        if (directApiSheriff != null) {
            for (String clientId : clientIds) {
                directApiSheriff.resetClientState(clientId);
            }
        }
    }

    /**
     * Benchmarks CDI-injected API Sheriff performance.
     * This measures the overhead of CDI proxy calls and injection.
     *
     * @return the result of request validation
     */
    @Benchmark
    public boolean benchmarkInjectedApiSheriff() {
        String clientId = clientIds[0];
        return apiSheriff.isRequestAllowed(clientId, endpoint);
    }

    /**
     * Benchmarks direct API Sheriff instantiation for comparison.
     * This provides a baseline for measuring CDI overhead.
     *
     * @return the result of request validation
     */
    @Benchmark
    public boolean benchmarkDirectApiSheriff() {
        String clientId = clientIds[0];
        return directApiSheriff.isRequestAllowed(clientId, endpoint);
    }

    /**
     * Benchmarks concurrent access through CDI injection.
     * Tests thread safety and performance under concurrent load.
     *
     * @return the result of request validation
     */
    @Benchmark
    @Threads(4)
    public boolean benchmarkConcurrentInjectedAccess() {
        long threadId = Thread.currentThread().getId();
        int clientIndex = (int) (threadId % concurrentClients);
        String clientId = clientIds[clientIndex];
        
        return apiSheriff.isRequestAllowed(clientId, endpoint);
    }

    /**
     * Benchmarks configuration access performance.
     * Measures overhead of accessing injected configuration properties.
     *
     * @return the rate limit from configuration
     */
    @Benchmark
    public int benchmarkConfigurationAccess() {
        return config.getRateLimit();
    }

    /**
     * Benchmarks validation operations through CDI.
     * Tests configuration validation performance with injection overhead.
     *
     * @return true if configuration is valid
     */
    @Benchmark
    public boolean benchmarkConfigurationValidation() {
        return apiSheriff.validateConfiguration().isEmpty();
    }

    /**
     * Benchmarks mixed operations simulating real Quarkus application usage.
     * Combines request processing, configuration access, and administrative operations.
     *
     * @return combined result of operations
     */
    @Benchmark
    public boolean benchmarkQuarkusRealWorldUsage() {
        String clientId = clientIds[0];
        
        // Simulate typical usage pattern in Quarkus application
        int operation = (int) (Math.random() * 100);
        
        if (operation < 70) {
            // 70% request validation
            return apiSheriff.isRequestAllowed(clientId, endpoint);
        } else if (operation < 85) {
            // 15% configuration access
            return config.getRateLimit() > 0;
        } else if (operation < 95) {
            // 10% validation
            return apiSheriff.validateConfiguration().isEmpty();
        } else {
            // 5% administrative operations
            apiSheriff.resetClientState(clientId);
            return true;
        }
    }

    /**
     * Benchmarks rate limiting behavior with different endpoints.
     * Tests how endpoint variation affects performance in Quarkus context.
     *
     * @return the result of request validation
     */
    @Benchmark
    public boolean benchmarkMultiEndpointRateLimiting() {
        String clientId = "multi-endpoint-client";
        
        // Rotate through different endpoints to test caching behavior
        String[] endpoints = {"/api/v1/users", "/api/v1/orders", "/api/v1/products"};
        String selectedEndpoint = endpoints[(int) (Math.random() * endpoints.length)];
        
        return apiSheriff.isRequestAllowed(clientId, selectedEndpoint);
    }

    /**
     * Benchmarks performance under rate limit pressure.
     * Tests behavior when rate limits are frequently exceeded in Quarkus context.
     *
     * @return the result of request validation (mostly false due to rate limiting)
     */
    @Benchmark
    public boolean benchmarkRateLimitPressure() {
        String clientId = "pressure-test-client";
        
        // Generate burst of requests to test rate limiting performance
        for (int i = 0; i < 50; i++) {
            apiSheriff.isRequestAllowed(clientId, endpoint);
        }
        
        // This request should likely be rate limited
        return apiSheriff.isRequestAllowed(clientId, endpoint);
    }
}