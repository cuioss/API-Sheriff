/**
 * @fileoverview Benchmark for the gateway's own /api/health endpoint over TLS.
 *
 * Exercises the TLS-terminating inbound path and the gateway's request pipeline without
 * involving an upstream, so the difference against `proxied_static.js` isolates proxy cost.
 * Retained as a non-matrix benchmark so its existing badge / history / trend series is not
 * orphaned by the k6 swap.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';

const BENCHMARK_NAME = 'gatewayHealth';
const TARGET_URL = __ENV.TARGET_URL || 'https://api-sheriff:8443/api/health';

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    // The benchmark stack terminates TLS with the self-signed localhost bundle mounted into
    // every service, so certificate verification is skipped for the load generator only.
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
