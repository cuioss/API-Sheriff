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
 * The body-transfer aspects (`uploadSmall` / `uploadLarge`) additionally carry a
 * `throughput_mbps` field; every other field is common to all scripts.
 *
 * The HTTP aspects source their headline figures from k6's built-in `http_req_duration` /
 * `http_reqs` / `http_req_failed` metrics. The non-HTTP protocol aspects added for plan-05
 * (`websocketEcho`, `grpcUnary`) do not populate those metrics — k6 records gRPC calls under
 * `grpc_req_duration` and WebSocket traffic under `ws_*`, with no built-in request/failure rate.
 * {@link buildSummary} therefore accepts `durationMetric` / `requestsMetric` / `failuresMetric`
 * option overrides so those aspects can name the trend/counter/rate metrics that actually
 * characterise them (a custom trend for the round-trip/call latency, a counter for the throughput
 * rate, a custom rate for the failure fraction). The overrides default to the `http_*` names, so
 * every existing HTTP script is unchanged.
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

import { gatewayTarget } from './target.js';

/**
 * Bytes per megabyte for the `throughput_mbps` derivation. Decimal MB (10^6), matching how the
 * upload aspects name their payloads (1MB / 50MB) so the reported rate and the stated body size
 * are on the same scale.
 */
const BYTES_PER_MB = 1e6;

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
 * "Measured duration" means the WHOLE run, load phase plus graceful-stop tail -- and
 * `requests_per_second` is the request counter's rate over that same denominator. A single VU
 * that stalls at cutoff therefore stretches the denominator without adding requests and deflates
 * the reported rate, which is why the tail is bounded at the scenario level; see
 * {@link DEFAULT_GRACEFUL_STOP}.
 *
 * A body-transfer aspect additionally opts into `throughput_mbps` via `options.throughput`. It is
 * derived from k6's own `data_sent` rate rather than from the body size times the request rate,
 * so a partially-transferred or rejected body cannot be counted as fully delivered bytes. The
 * field is omitted entirely for the latency/RPS aspects rather than emitted as 0, so a reader
 * never mistakes "not a transfer benchmark" for "transferred nothing".
 *
 * @param {string} benchmarkName the stable benchmark name -- also the gh-pages history/trend key
 * @param {object} data the k6 end-of-test summary object
 * @param {{throughput?: boolean, durationMetric?: string, requestsMetric?: string,
 *          failuresMetric?: string}} [options={}] opt-ins for aspect-specific summary fields and
 *          per-aspect overrides of which k6 metric backs the latency / request-rate / failure-rate
 *          figures (defaulting to the built-in `http_*` metrics)
 * @returns {object} a k6 `handleSummary()` return mapping output paths to their content
 */
export function buildSummary(benchmarkName, data, options = {}) {
    const endMs = Date.now();
    const state = data.state || {};
    const durationMs = typeof state.testRunDurationMs === 'number' ? state.testRunDurationMs : 0;

    const metrics = data.metrics || {};
    const duration = (metrics[options.durationMetric || 'http_req_duration'] || {}).values || {};
    const requests = (metrics[options.requestsMetric || 'http_reqs'] || {}).values || {};
    const failures = (metrics[options.failuresMetric || 'http_req_failed'] || {}).values || {};

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

    if (options.throughput) {
        const sent = (metrics.data_sent || {}).values || {};
        summary.throughput_mbps = round(sent.rate / BYTES_PER_MB);
    }

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

/**
 * The graceful-stop window every aspect scenario bounds its tail with.
 *
 * The value is neither `0` nor k6's own default, and both exclusions are load-bearing:
 *
 *   * **Not `0`.** A zero-length graceful stop interrupts every in-flight iteration at cutoff.
 *     Interrupted iterations register as failed requests, so at a high enough concurrency they
 *     can breach the `0.01` {@link maxErrorRate} ceiling (`BENCHMARK_MAX_ERROR_RATE`) and fail a
 *     run that measured perfectly well. The tail must exist; it must merely be bounded.
 *   * **Not k6's 30s default.** `requests_per_second` is a counter rate over
 *     `state.testRunDurationMs`, which spans the graceful-stop tail (see the module header). A
 *     single stalled VU keeps the run alive to the hard cap and adds the whole tail to the
 *     denominator while adding no requests, so on a 60s run the 30s default inflates the measured
 *     window by up to 50% and deflates the reported rate by the same factor -- large enough to
 *     read as a ~35% throughput regression that never happened.
 *
 * At `5s` the worst case a stalled VU can produce is `5/60` = 8.3% of window inflation, which
 * stays inside the comparator's 10% window band and well inside its 15% throughput band, so a
 * stall can no longer manufacture a regression verdict.
 *
 * @type {string}
 */
export const DEFAULT_GRACEFUL_STOP = '5s';

/**
 * Builds the explicit `constant-vus` scenario every aspect script declares.
 *
 * The scenario form is chosen over k6's `vus` / `duration` shorthand precisely because the
 * shorthand offers no way to bound the graceful stop: it silently applies the 30s default, and
 * that default is what lets one stalled VU inflate the measured window (see
 * {@link DEFAULT_GRACEFUL_STOP}). Declaring the scenario explicitly makes the tail a stated,
 * bounded part of every aspect's contract instead of an inherited default.
 *
 * The tail is {@link DEFAULT_GRACEFUL_STOP} for every aspect, deliberately with no per-aspect
 * override: one lane-wide value is what makes the two compared arms' measured windows commensurable
 * in the first place. An aspect that ever needs a longer tail changes it here, for all of them.
 *
 * @param {number} vuCount the constant VU count to hold for the run
 * @param {string} runDuration the k6 duration string the VUs are held for
 * @returns {{executor: string, vus: number, duration: string, gracefulStop: string}} the scenario
 */
export function scenario(vuCount, runDuration) {
    return {
        executor: 'constant-vus',
        vus: vuCount,
        duration: runDuration,
        gracefulStop: DEFAULT_GRACEFUL_STOP,
    };
}
