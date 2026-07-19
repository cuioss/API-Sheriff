/**
 * @fileoverview Shared k6 `handleSummary()` helper for the API Sheriff benchmark lane.
 *
 * Every benchmark script funnels its run through {@link buildSummary}, so all scripts emit
 * one identically-shaped JSON document that `K6BenchmarkConverter` consumes:
 *
 * ```json
 * {
 *   "benchmark_name": "healthLiveCheck",
 *   "gateway_target": "api-sheriff",
 *   "start_time": "2026-07-19T10:00:00.000Z",
 *   "end_time":   "2026-07-19T10:01:00.000Z",
 *   "requests_per_second": 8511.49,
 *   "error_rate": 0.0,
 *   "latency_ms": { "avg": 5.87, "p50": 1.69, "p75": 3.18, "p90": 14.6, "p99": 29.99 }
 * }
 * ```
 *
 * Latency values are passed through in milliseconds -- k6 reports `http_req_duration` in ms
 * and the converter performs no unit conversion -- so no rounding-or-scaling step can
 * introduce a conversion regression between the engine and the report.
 *
 * `latency_ms.stdev` is deliberately absent. k6 exposes only `avg`/`min`/`med`/`max` and the
 * configured percentiles for a trend metric; it does not report a standard deviation, and the
 * individual samples are not reachable from `handleSummary()`. Emitting a derived-looking
 * number here would be a fabricated statistic, so the field is omitted and the converter's
 * `optionalDouble` treats it as absent. Consequence, accepted for the toolchain swap: the
 * report's `error` / variability-coefficient / confidence-band columns are zero-width for k6
 * runs, where the wrk pipeline populated them from wrk's own stdev.
 */

/** Summary field name to the k6 `http_req_duration` percentile key backing it. */
const PERCENTILES = [
    ['p50', 'p(50)'],
    ['p75', 'p(75)'],
    ['p90', 'p(90)'],
    ['p99', 'p(99)'],
];

/**
 * Trend statistics every benchmark script must request so the percentiles the report pipeline
 * expects are actually measured. A percentile missing from this list is absent from the
 * summary and is never estimated downstream.
 *
 * @type {string[]}
 */
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(50)', 'p(75)', 'p(90)', 'p(99)'];

/**
 * Resolves the gateway a run is taken against. Defaults to API Sheriff, so the CI baseline
 * lane needs no environment plumbing; the comparison lane overrides it per target.
 *
 * @returns {string} the gateway target label recorded in the summary
 */
export function gatewayTarget() {
    return __ENV.GATEWAY_TARGET || 'api-sheriff';
}

/**
 * Rounds to a fixed precision, mapping a missing or non-finite metric to 0.
 *
 * @param {number|undefined} value the raw metric value
 * @param {number} [digits=2] decimal places to keep
 * @returns {number} the rounded value, or 0 when the metric was not measured
 */
function round(value, digits = 2) {
    if (typeof value !== 'number' || !isFinite(value)) {
        return 0;
    }
    const factor = Math.pow(10, digits);
    return Math.round(value * factor) / factor;
}

/**
 * Builds the cuioss-format summary document for a completed run and routes it to the results
 * volume under the benchmark's own name.
 *
 * The execution window is reconstructed from k6's own run duration rather than from a wall
 * clock captured in `setup()`: `handleSummary()` receives no setup data, and
 * `data.state.testRunDurationMs` is the engine's authoritative measured duration.
 *
 * @param {string} benchmarkName the stable benchmark name -- also the gh-pages history/trend key
 * @param {object} data the k6 end-of-test summary object
 * @returns {object} a k6 `handleSummary()` return mapping output paths to their content
 */
export function buildSummary(benchmarkName, data) {
    const endMs = Date.now();
    const state = data.state || {};
    const durationMs = typeof state.testRunDurationMs === 'number' ? state.testRunDurationMs : 0;

    const metrics = data.metrics || {};
    const duration = (metrics.http_req_duration || {}).values || {};
    const requests = (metrics.http_reqs || {}).values || {};
    const failures = (metrics.http_req_failed || {}).values || {};

    const latency = { avg: round(duration.avg) };
    for (const [field, k6Key] of PERCENTILES) {
        if (typeof duration[k6Key] === 'number') {
            latency[field] = round(duration[k6Key]);
        }
    }

    const summary = {
        benchmark_name: benchmarkName,
        gateway_target: gatewayTarget(),
        start_time: new Date(endMs - durationMs).toISOString(),
        end_time: new Date(endMs).toISOString(),
        requests_per_second: round(requests.rate),
        error_rate: round(failures.rate, 6),
        latency_ms: latency,
    };

    const document = JSON.stringify(summary, null, 2);
    const result = {};
    result[`/results/${benchmarkName}-summary.json`] = document;
    result.stdout = `\n=== ${benchmarkName} (${summary.gateway_target}) ===\n${document}\n`;
    return result;
}

/**
 * Resolves the failure-rate ceiling every benchmark gates on, as a fraction in [0, 1].
 *
 * A set-but-invalid override is fatal rather than ignored: a negative value would invert the
 * gate, a value above 1 would disable it, and a malformed value would silently mask the
 * intended threshold. The 0.01 default therefore applies only when the variable is unset --
 * carrying over the validation the retired `WRK_MAX_ERROR_RATE` lua gate performed.
 *
 * @returns {number} the failure-rate ceiling
 * @throws {Error} when the override is set but is not a finite fraction in [0, 1]
 */
export function maxErrorRate() {
    const raw = __ENV.BENCHMARK_MAX_ERROR_RATE;
    if (raw === undefined || raw === '') {
        return 0.01;
    }
    const value = Number(raw);
    if (!isFinite(value) || value < 0 || value > 1) {
        throw new Error(
            `BENCHMARK_MAX_ERROR_RATE must be a finite number in [0, 1], got "${raw}"`);
    }
    return value;
}

/**
 * Resolves a positive-integer VU count from the environment.
 *
 * @param {number} fallback the VU count to use when unset
 * @returns {number} the resolved VU count
 */
export function vus(fallback) {
    const raw = parseInt(__ENV.BENCHMARK_VUS, 10);
    return Number.isNaN(raw) || raw <= 0 ? fallback : raw;
}

/**
 * Resolves the run duration from the environment.
 *
 * @param {string} [fallback='60s'] the duration to use when unset
 * @returns {string} the resolved k6 duration string
 */
export function duration(fallback = '60s') {
    return __ENV.BENCHMARK_DURATION || fallback;
}
