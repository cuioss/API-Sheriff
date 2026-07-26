/**
 * @fileoverview Benchmark for the Quarkus management LIVENESS endpoint over TLS.
 *
 * Drives `/q/health/live` on the management port — the liveness probe only, not the aggregate
 * surface its sibling `gateway_health.js` drives on the same scheme and port. The two retained
 * health benchmarks are distinguished by work done, not by transport.
 *
 * This is NO LONGER plain HTTP. Quarkus' management interface has exactly one port, so activating
 * TLS converted port 9000 itself to HTTPS and no plain-HTTP surface remains anywhere in the stack.
 * It therefore no longer measures "the plain-HTTP floor with no TLS or routing". It remains the
 * LIGHTEST path in the stack — no gateway routing, no request pipeline, no upstream — so it is
 * still the harness-health indicator: a run whose numbers collapse here indicates a host or
 * container problem rather than a gateway regression. Its numbers now include TLS handshake and
 * record-layer cost.
 *
 * The name is retained deliberately so the existing badge / history / trend series is not
 * orphaned, but the numbers carry a one-time target-change discontinuity and are not comparable
 * to pre-change `healthLiveCheck` history.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';

const BENCHMARK_NAME = 'healthLiveCheck';
const TARGET_URL = __ENV.TARGET_URL || 'https://api-sheriff:9000/q/health/live';

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    // The benchmark stack terminates TLS with the self-signed localhost bundle mounted into
    // every service, so certificate verification is skipped for the load generator only.
    // Load-bearing since the management port became HTTPS: without it every request fails
    // certificate validation and the benchmark reports a total failure.
    insecureSkipTLSVerify: true,
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
