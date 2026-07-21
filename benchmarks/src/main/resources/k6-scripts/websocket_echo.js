/**
 * @fileoverview Benchmark for the WebSocket echo relay (k6 -> gateway -> go-httpbin echo).
 *
 * This is the `ws` matrix aspect. It measures the gateway's cost of admitting a WebSocket upgrade
 * and relaying frames opaquely in both directions: the handshake (including the fail-closed Origin
 * gate on the `/ws/echo` route), the upgrade to a relayed socket, and the per-frame round-trip
 * through the opaque relay. Each VU iteration opens one socket, performs a fixed number of echo
 * round-trips measuring per-message round-trip time, then closes it.
 *
 * Unlike the HTTP aspects, this one cannot ride the static nginx fairness backend: nginx-static is
 * not a WebSocket echo server. It targets the same go-httpbin `/websocket/echo` upstream the
 * WebSocket integration tests use (via API Sheriff's WS_UPSTREAM topology alias). The APISIX side
 * of the comparison mirrors this with a go-httpbin proxy-ws route; see the fairness caveat in
 * apisix.yaml and README.adoc -- the ws and grpc aspects are the two aspects that deliberately do
 * NOT share the single static backend, because no static backend speaks their protocol.
 *
 * The `/ws/echo` route enforces a fail-closed Origin allowlist (GW-09 / CSWSH), so the handshake
 * MUST carry an allow-listed `Origin`; an absent or foreign Origin would be rejected 403 before the
 * upstream is dialed and the run would measure a rejection instead of the relay.
 */
import { WebSocket } from 'k6/websockets';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { wsUrl } from './lib/target.js';

const BENCHMARK_NAME = 'websocketEcho';
const TARGET_URL = __ENV.TARGET_URL || wsUrl('/ws/echo');

// The /ws/echo route's fail-closed Origin allowlist admits exactly this origin (see
// endpoints/websocket.yaml); overridable for a differently-configured edge.
const ORIGIN = __ENV.WS_ORIGIN || 'https://sheriff.test';

// Round-trips per socket, held constant across both gateways so the reconnection-to-relay ratio is
// identical on each side and the measured throughput stays comparable.
const MESSAGES_PER_SESSION = 20;
const PAYLOAD = 'sheriff-ws-echo';

// The round-trip latency trend and the throughput counter this aspect is characterised on -- k6's
// built-in metrics describe HTTP requests, not relayed WebSocket frames, so the summary sources
// these custom metrics (see lib/summary.js metric-override options). `ws_errors` is the failure
// fraction the summary reports as error_rate.
const rtt = new Trend('ws_rtt', true);
const roundtrips = new Counter('ws_roundtrips');
const wsErrors = new Rate('ws_errors');

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    insecureSkipTLSVerify: true,
    thresholds: {
        ws_errors: [`rate<=${maxErrorRate()}`],
        checks: [`rate>=${1 - maxErrorRate()}`],
    },
};

export default function () {
    const socket = new WebSocket(TARGET_URL, null, {
        headers: { Origin: ORIGIN },
        tags: { benchmark: BENCHMARK_NAME },
    });

    let sent = 0;
    let sentAt = 0;

    const sendOne = () => {
        sentAt = Date.now();
        socket.send(PAYLOAD);
        sent += 1;
    };

    socket.onopen = () => sendOne();

    socket.onmessage = (message) => {
        rtt.add(Date.now() - sentAt);
        roundtrips.add(1);
        const echoed = check(message, { 'frame echoes the payload': (m) => m.data === PAYLOAD });
        wsErrors.add(!echoed);
        if (sent >= MESSAGES_PER_SESSION) {
            socket.close();
            return;
        }
        sendOne();
    };

    socket.onerror = () => wsErrors.add(true);
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data, {
        durationMetric: 'ws_rtt',
        requestsMetric: 'ws_roundtrips',
        failuresMetric: 'ws_errors',
    });
}
