/**
 * @fileoverview Benchmark for the GraphQL-shaped POST route (k6 -> gateway -> nginx).
 *
 * This is the `graphql` matrix aspect. It measures the gateway's cost of admitting a
 * JSON-bodied POST on a method-restricted anchor: method matching, body admission under the
 * anchor's cap, and the path rewrite to the upstream. The backend is the same fast static nginx
 * every other aspect targets, so what is measured is GATEWAY PROCESSING rather than real GraphQL
 * resolution -- nginx answers 200 on any path and never parses the query.
 *
 * That is deliberate and is the benchmark's stated intent: pointing this aspect at a
 * JSON-serializing backend would fold the backend's parse cost into the gateway's number, and
 * would do so asymmetrically across the two gateways. See the fairness section of
 * doc/plan/04b-comparative-benchmark.adoc and the fairness invariant in apisix.yaml.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'graphql';
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/graphql');

// A representative introspection-shaped query. Held constant across both gateways and across
// runs: varying the body between targets would vary the bytes admitted and make the two sides
// incomparable.
const QUERY = JSON.stringify({
    query: '{ user(id: "1") { id name email roles { id name } } }',
});

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
    const response = http.post(TARGET_URL, QUERY, {
        headers: { 'Content-Type': 'application/json' },
        tags: { benchmark: BENCHMARK_NAME },
    });
    check(response, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data);
}
