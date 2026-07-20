/**
 * @fileoverview Benchmark for the 50MB request-body upload route (k6 -> gateway -> nginx).
 *
 * This is the `upload-50MB` matrix aspect. At this body size the run is transfer-bound rather
 * than request-rate bound, so `throughput_mbps` is the headline figure and the latency
 * percentiles describe whole-body completion time. Both are reported: throughput alone would
 * hide a gateway that sustains aggregate bytes while stalling individual transfers.
 *
 * <strong>Reduced concurrency is deliberate.</strong> The VU default here is far below the
 * throughput aspects' 50. Two reasons, both load-bearing:
 *   * each VU holds its own 50MB body in its own JS runtime, so VU count multiplies generator
 *     memory directly -- at 50 VUs the load generator would need ~2.5GB for payloads alone and
 *     would be measuring its own memory pressure;
 *   * saturating the link with 50 concurrent 50MB transfers measures the network, not the
 *     gateway, and does so identically for both targets, compressing the very difference the
 *     comparison exists to surface.
 *
 * The body rides under the anchor's 64 MiB cap (67108864 bytes), so a 50MB (5e7) body is
 * MEASURED rather than rejected at the edge. That headroom is intentional: a rejection path is
 * much faster than a transfer path, so an under-sized cap would report flatteringly good numbers
 * instead of failing. The explicit HTTP-200 check below is what turns such a misconfiguration
 * into a build failure rather than an excellent-looking result.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'uploadLarge';
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/upload/large');

/** 50 MB (decimal), matching the aspect name and the `throughput_mbps` scale. */
const BODY_BYTES = 5e7;

/** Reduced VU default for the transfer-bound aspect -- see the concurrency note above. */
const LARGE_UPLOAD_VUS = 5;

// Built once in init context and replayed by every iteration; see the memory note above for why
// this allocation is the reason VU count stays low.
const BODY = 'x'.repeat(BODY_BYTES);

export const options = {
    vus: vus(LARGE_UPLOAD_VUS),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    insecureSkipTLSVerify: true,
    thresholds: {
        http_req_failed: [`rate<=${maxErrorRate()}`],
        checks: [`rate>=${1 - maxErrorRate()}`],
    },
};

export default function () {
    const response = http.post(TARGET_URL, BODY, {
        headers: { 'Content-Type': 'application/octet-stream' },
        tags: { benchmark: BENCHMARK_NAME },
    });
    check(response, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data, { throughput: true });
}
