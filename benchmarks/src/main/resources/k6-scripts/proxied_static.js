/**
 * @fileoverview Benchmark for the proxied static backend (k6 -> gateway -> nginx).
 *
 * Measures proxy overhead: the gateway forwards /proxy/* to the fast static nginx backend, so
 * the backend never saturates before the gateway does. This is the `unauth` matrix aspect --
 * plain proxy overhead with no authentication -- and its bearer counterpart isolates the added
 * cost of offline token validation.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'proxiedStatic';
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/proxy/static');

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    insecureSkipTLSVerify: true,
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
