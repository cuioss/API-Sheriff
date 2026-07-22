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
/**
 * k6 benchmark post-processing for API Sheriff.
 * <p>
 * k6 is the project's sole benchmark framework (plan 04b). This package adapts the JSON each
 * k6 script writes from its {@code handleSummary()} export onto the central
 * {@code de.cuioss.benchmarking.common} report model, so the established badges / 10-run
 * history / trends / GitHub Pages pipeline consumes k6 runs unchanged.
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.k6.benchmark.K6BenchmarkConverter} — implements the
 *       upstream {@code BenchmarkConverter} seam, mapping a k6 summary onto {@code BenchmarkData}
 *       (throughput score, latency percentiles, gateway target).</li>
 *   <li>{@link de.cuioss.sheriff.gateway.k6.benchmark.K6ResultPostProcessor} — the {@code main}
 *       entry point invoked from the {@code -Pbenchmark} Maven profile; drives report and
 *       GitHub Pages generation plus Prometheus metric collection.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.k6.benchmark.K6BenchmarkLogMessages} — the structured
 *       {@code K6Benchmark}-prefixed log records for the k6-specific messages.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * These classes are single-threaded build-time utilities driven from a Maven execution; they
 * are not intended for concurrent use.
 *
 * @since 1.0
 */
package de.cuioss.sheriff.gateway.k6.benchmark;
