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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import de.cuioss.sheriff.api.ApiSheriff;

/**
 * JMH benchmark suite for API Sheriff library performance testing.
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
        apiSheriff = new ApiSheriff();
    }

    @Benchmark
    public String benchmarkGetStatus() {
        return apiSheriff.getStatus();
    }

    public static void main(String[] args) throws RunnerException {
        // Configure JMH options using helper for system property configuration
        Options opt = new OptionsBuilder()
                .include(System.getProperty("jmh.include", BenchmarkRunner.class.getSimpleName()))
                .forks(BenchmarkOptionsHelper.getForks(1))
                .warmupIterations(BenchmarkOptionsHelper.getWarmupIterations(5))
                .measurementIterations(BenchmarkOptionsHelper.getMeasurementIterations(5))
                .measurementTime(BenchmarkOptionsHelper.getMeasurementTime("2s"))
                .warmupTime(BenchmarkOptionsHelper.getWarmupTime("2s"))
                .threads(BenchmarkOptionsHelper.getThreadCount(8))
                .resultFormat(BenchmarkOptionsHelper.getResultFormat())
                .result(BenchmarkOptionsHelper.getResultFile(getBenchmarkResultsDir() + "/micro-benchmark-result.json"))
                .build();

        new Runner(opt).run();
    }

    /**
     * Gets the benchmark results directory from system property or defaults to target/benchmark-results.
     *
     * @return the benchmark results directory path
     */
    private static String getBenchmarkResultsDir() {
        return System.getProperty("benchmark.results.dir", "target/benchmark-results");
    }
}