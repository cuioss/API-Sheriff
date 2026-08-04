/**
 * @fileoverview Benchmark for the Quarkus management interface's AGGREGATE health surface over TLS.
 *
 * Drives `/q/health` on the management port, which runs every registered health check (including
 * the token-sheriff JWKS readiness check) and is therefore the heavier of the two retained health
 * benchmarks. Its sibling `health_live.js` drives `/q/health/live` on the same scheme and port;
 * the two are distinguished by work done, not by transport.
 *
 * This benchmark previously targeted the gateway's own `https://api-sheriff:8443/api/health`
 * data-plane endpoint and differenced against `proxied_static.js` to isolate proxy cost. That is
 * no longer true: `/api/health` (and `/api/info`) were DELETED by the pre-1.0 clean break in
 * PR #76, so the route no longer exists and every request was a deny-by-default 404. Do not
 * restore it — the health benchmarks measure the management interface now.
 *
 * The name is retained deliberately so the existing badge / history / trend series is not
 * orphaned, but the numbers carry a one-time target-change discontinuity and are not comparable
 * to pre-change `gatewayHealth` history.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, scenario, SUMMARY_TREND_STATS, vus } from './lib/summary.js';

const BENCHMARK_NAME = 'gatewayHealth';
// Hard-coded rather than routed through lib/target.js: the health benchmarks are deliberately
// excluded from the cross-gateway comparison lane (APISIX exposes no management interface).
const TARGET_URL = __ENV.TARGET_URL || 'https://api-sheriff:9000/q/health';

export const options = {
    scenarios: { default: scenario(vus(50), duration()) },
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
