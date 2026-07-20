/**
 * @fileoverview Benchmark for the ALPN-negotiated HTTP/2 edge path (k6 -> gateway -> nginx).
 *
 * This is the `http2` matrix aspect. It drives the same proxied static route the `unauth` aspect
 * drives, over an HTTP/2 connection instead of HTTP/1.1, so the pair isolates the protocol's
 * effect on gateway overhead: same route, same upstream, same body, different wire protocol.
 *
 * <strong>Anti-silent-wrong-result gate.</strong> k6 negotiates h2 via ALPN and transparently
 * falls back to HTTP/1.1 when the edge does not advertise it. A fallback would produce a
 * perfectly healthy-looking run whose numbers are HTTP/1.1 numbers filed under the h2 aspect --
 * the worst failure mode available here, because nothing about the result would look wrong. The
 * explicit protocol check below therefore asserts the negotiated protocol per response, so a
 * gateway whose ALPN list loses `h2` fails the run instead of quietly re-measuring HTTP/1.1.
 *
 * API Sheriff advertises `alpn: ["h2", "http/1.1"]` in gateway.yaml; APISIX enables HTTP/2 on
 * its TLS listener. Both therefore negotiate h2 for this aspect.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'http2';
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/proxy/static');

/** The protocol k6 reports for a successfully ALPN-negotiated HTTP/2 connection. */
const EXPECTED_PROTO = 'HTTP/2.0';

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
    check(response, {
        'status is 200': (r) => r.status === 200,
        'negotiated HTTP/2': (r) => r.proto === EXPECTED_PROTO,
    });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data);
}
