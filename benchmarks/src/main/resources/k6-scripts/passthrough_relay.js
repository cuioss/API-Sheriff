/**
 * @fileoverview Benchmark for the accept-time passthrough L4 relay and its empty-mode baseline.
 *
 * Two modes, selected by the `PASSTHROUGH_SNI` env var:
 *
 *   mapped  -> drive the *passthrough* path (k6 -> gateway public TLS port -> opaque L4 TCP relay
 *              -> TLS-enabled backend). The ClientHello SNI names a `tls.passthrough_sni` host, so
 *              the gateway relays the intact, still-encrypted byte stream at L4 without terminating;
 *              the backend completes the handshake and presents its *own* certificate. Measures
 *              relay throughput/latency through the active passthrough path. Emits `passthroughRelay`.
 *
 *   empty   -> drive the *same proxied static route* the PLAN-04 `unauth` baseline uses, but with
 *              `passthrough_sni` empty — D1's zero-overhead default, where the front listener is
 *              never created and the terminated Quarkus HTTPS listener owns the public port directly.
 *              This is the no-regression comparison side: `PassthroughBaselineComparator` reads its
 *              summary against the stored PLAN-04 `proxiedStatic` baseline. Emits `passthroughRelayEmpty`.
 *
 * One script body drives both modes so the two runs share identical VU/duration/threshold plumbing
 * and only the SNI/route differs. Native k6 thresholds (`http_req_failed`, `checks`) gate the run,
 * exactly as the other aspects: a run that starts rejecting every request exits non-zero rather than
 * benchmarking as an improvement. An unknown `PASSTHROUGH_SNI` value is fatal rather than defaulted,
 * mirroring `lib/target.js` — a mislabelled run is worse than a failed one.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

/** The passthrough mode this run measures, defaulting to the active relay path. */
const MODE = (__ENV.PASSTHROUGH_SNI || 'mapped').toLowerCase();

/**
 * The mapped SNI host the passthrough run's ClientHello carries. It must both resolve to the gateway
 * container and be listed in `tls.passthrough_sni`; the benchmark compose overlay (D4) provides that
 * mapping and the TLS-enabled backend behind it. Overridable so a run can target a different edge.
 */
const PASSTHROUGH_TARGET_URL = __ENV.PASSTHROUGH_TARGET_URL || 'https://passthrough.api-sheriff:8443/get';

/**
 * Resolves the (benchmarkName, url) pair for the selected mode. An unknown mode is fatal at module
 * load, before any VU starts, so a typo can never silently fall through to one of the two paths and
 * mislabel the summary.
 */
function resolveMode() {
    switch (MODE) {
        case 'mapped':
            return { benchmarkName: 'passthroughRelay', url: __ENV.TARGET_URL || PASSTHROUGH_TARGET_URL };
        case 'empty':
            return { benchmarkName: 'passthroughRelayEmpty', url: __ENV.TARGET_URL || targetUrl('/proxy/static') };
        default:
            throw new Error(`PASSTHROUGH_SNI must be one of mapped, empty, got "${__ENV.PASSTHROUGH_SNI}"`);
    }
}

const { benchmarkName: BENCHMARK_NAME, url: TARGET_URL } = resolveMode();

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
