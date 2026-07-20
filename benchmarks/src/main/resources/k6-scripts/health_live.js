/**
 * @fileoverview Benchmark for the Quarkus management liveness endpoint.
 *
 * Measures the management interface itself (plain HTTP on port 9000), not the gateway data
 * plane, so it acts as the harness's own floor: a run whose numbers collapse here indicates a
 * host or container problem rather than a gateway regression. Retained as a non-matrix
 * benchmark so its existing badge / history / trend series is not orphaned by the k6 swap.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';

const BENCHMARK_NAME = 'healthLiveCheck';
const TARGET_URL = __ENV.TARGET_URL || 'http://api-sheriff:9000/q/health/live';

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    // Native k6 thresholds replace the retired WRK_MAX_ERROR_RATE lua gate: a breach exits
    // non-zero, so a gateway that starts rejecting every request can never benchmark as an
    // improvement. The checks threshold is the status-code assertion the lua gate performed.
    thresholds: {
        http_req_failed: [`rate<=${maxErrorRate()}`],
        checks: [`rate>=${1 - maxErrorRate()}`],
    },
};

export default function () {
    const response = http.get(TARGET_URL, { tags: { benchmark: BENCHMARK_NAME } });
    check(response, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data);
}
