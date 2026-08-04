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
 *   empty   -> drive the *same proxied static route* against a *different gateway instance*: the
 *              dedicated `api-sheriff-passthrough-empty` service, whose overlaid `gateway.yaml`
 *              declares no `tls.passthrough_sni` at all. `passthrough_sni` is a property of the
 *              whole gateway process — declaring it non-empty is what starts the accept-time SNI
 *              front listener — so emptiness cannot be selected per request on the primary
 *              instance, which declares two entries for its whole lifetime. On this instance the
 *              front listener is never created and Quarkus terminates TLS on the public port
 *              directly. This is the no-regression comparison side: `PassthroughBaselineComparator`
 *              reads its summary against the primary instance's `proxiedStatic` baseline over the
 *              same route and the same nginx-static upstream. Emits `passthroughRelayEmpty`.
 *
 * One script body drives both modes so the two runs share identical VU/duration/threshold plumbing
 * and only the target edge and route differ. Native k6 thresholds (`http_req_failed`, `checks`) gate the run,
 * exactly as the other aspects: a run that starts rejecting every request exits non-zero rather than
 * benchmarking as an improvement. An unknown `PASSTHROUGH_SNI` value is fatal rather than defaulted,
 * mirroring `lib/target.js` — a mislabelled run is worse than a failed one.
 */
import http from 'k6/http';
import { check } from 'k6';
import { buildSummary, duration, maxErrorRate, scenario, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { passthroughEmptyUrl } from './lib/target.js';

/** The passthrough mode this run measures, defaulting to the active relay path. */
const MODE = (__ENV.PASSTHROUGH_SNI || 'mapped').toLowerCase();

/**
 * The mapped SNI host the passthrough run's ClientHello carries. Three separate pieces must line up,
 * and all three live in the BASE integration-test configuration -- not in the benchmark overlay:
 *
 *   * `integration-tests/docker-compose.yml` puts `passthrough.test.example` in the `api-sheriff`
 *     service's network `aliases`, which is what makes the name resolve to the gateway container;
 *   * `integration-tests/src/main/docker/sheriff-config/gateway.yaml` lists it under
 *     `tls.passthrough_sni`, mapping it to the `PASSTHROUGH_BACKEND` topology alias;
 *   * the `passthrough-backend` service in that same base compose file is the TLS-enabled backend
 *     the relay dials, and its certificate carries the name as a SAN.
 *
 * Overridable so a run can target a different edge.
 */
const PASSTHROUGH_TARGET_URL = __ENV.PASSTHROUGH_TARGET_URL || 'https://passthrough.test.example:8443/get';

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
            return { benchmarkName: 'passthroughRelayEmpty', url: __ENV.TARGET_URL || passthroughEmptyUrl('/proxy/static') };
        default:
            throw new Error(`PASSTHROUGH_SNI must be one of mapped, empty, got "${__ENV.PASSTHROUGH_SNI}"`);
    }
}

const { benchmarkName: BENCHMARK_NAME, url: TARGET_URL } = resolveMode();

export const options = {
    scenarios: { default: scenario(vus(50), duration()) },
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
