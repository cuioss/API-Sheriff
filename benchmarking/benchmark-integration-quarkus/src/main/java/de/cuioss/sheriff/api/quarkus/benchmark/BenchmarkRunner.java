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

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import de.cuioss.sheriff.api.ApiSheriff;

/**
 * JMH benchmark suite for API Sheriff Quarkus integration performance testing.
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

    private ApiSheriff apiSheriff;

    @Setup(Level.Trial)
    public void setup() {
        // Simple placeholder implementation for benchmarking
        apiSheriff = new ApiSheriff();
    }

    @Benchmark
    public String benchmarkGetStatus() {
        return apiSheriff.getStatus();
    }

    /**
     * Main method to run the benchmarks.
     *
     * @param args command line arguments
     * @throws Exception if benchmark execution fails
     */
    public static void main(String[] args) throws Exception {
        // Configure JMH options
        Options options = new OptionsBuilder()
                .include(BenchmarkRunner.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
                .result("target/benchmark-results/integration-benchmark-result.json")
                .build();

        // Run the benchmarks
        Collection<RunResult> results = new Runner(options).run();

        // Check if benchmarks ran successfully
        if (results.isEmpty()) {
            throw new IllegalStateException("No benchmark results were produced");
        }

        System.out.println("Benchmark completed successfully with " + results.size() + " results");
    }
}