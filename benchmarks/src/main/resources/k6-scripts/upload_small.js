/**
 * @fileoverview Benchmark for the 1MB request-body upload route (k6 -> gateway -> nginx).
 *
 * This is the `upload-1MB` matrix aspect. It measures the gateway's streaming request-body path
 * at a body size that is large enough to leave the header-processing regime but small enough to
 * still run at full benchmark concurrency, so it isolates per-byte admission cost from the
 * per-request cost the unauth aspect already measures.
 *
 * Both `throughput_mbps` and the latency percentiles are reported: at this size the run is still
 * request-rate bound, so latency remains the comparable figure, while throughput makes the
 * transfer volume explicit alongside the 50MB aspect.
 *
 * The backend is the same fast static nginx every aspect targets -- it accepts and discards the
 * body without parsing it, so the number is gateway body-handling cost, not upstream work.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'uploadSmall';
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/upload/small');

/** 1 MB (decimal), matching the aspect name and the `throughput_mbps` scale. */
const BODY_BYTES = 1e6;

// Built once in init context and replayed by every iteration. A body generated per iteration
// would put the generator's own allocation cost inside the measured window and report it as
// gateway latency.
const BODY = 'x'.repeat(BODY_BYTES);

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
    const response = http.post(TARGET_URL, BODY, {
        headers: { 'Content-Type': 'application/octet-stream' },
        tags: { benchmark: BENCHMARK_NAME },
    });
    check(response, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data, { throughput: true });
}
