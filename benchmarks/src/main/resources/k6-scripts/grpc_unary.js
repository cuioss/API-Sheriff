/**
 * @fileoverview Benchmark for the gRPC unary echo relay (k6 -> gateway -> in-repo gRPC echo).
 *
 * This is the `grpc` matrix aspect. It measures the gateway's cost of relaying a unary gRPC call
 * over the forced-HTTP/2 upstream path: route selection on the bare service path, the opaque
 * length-prefixed request/response framing, and the trailer relay. Per Clarification 1 this k6
 * gRPC benchmark supersedes the plan doc's ghz proposal, keeping the whole matrix on one load
 * generator and one summary format.
 *
 * The gateway routes gRPC on the bare service path (operator decision 2026-07-21): the route
 * matches /de.cuioss.sheriff.api.integration.grpc.Echo and sets an identical upstream.path, so a
 * stock gRPC client dials the real /{package}.Echo/Unary method path and it reaches the upstream
 * unchanged. k6's gRPC client is exactly such a stock client -- it derives the method path from the
 * loaded proto, so no client-side path rewriting is needed.
 *
 * Like the WebSocket aspect, this one cannot ride the static nginx fairness backend: nginx-static
 * is not a gRPC server. It targets the in-repo Quarkus gRPC echo upstream (GRPC_ECHO). The APISIX
 * side mirrors this with a grpc-echo route; see the fairness caveat in apisix.yaml and README.adoc.
 */
import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { grpcAddress } from './lib/target.js';

const BENCHMARK_NAME = 'grpcUnary';
const ADDRESS = __ENV.TARGET_ADDRESS || grpcAddress();

// The fully-qualified gRPC method: proto package + service / method. k6 derives the :path from the
// loaded descriptor, so this is the real service path the bare-service-path route matches.
const SERVICE_METHOD = 'de.cuioss.sheriff.api.integration.grpc.Echo/Unary';
const PAYLOAD = 'sheriff-grpc';

// The proto is bundled into the k6 image alongside the scripts (Dockerfile.k6 copies the whole
// k6-scripts/ tree to /scripts), so it resolves under that import root inside the container.
const client = new grpc.Client();
client.load(['/scripts'], 'echo.proto');

// k6 records unary latency under grpc_req_duration, but emits no built-in call-rate or failure-rate
// metric, so this aspect sources its throughput and error fraction from these custom metrics (see
// lib/summary.js metric-override options).
const calls = new Counter('grpc_calls');
const grpcFailed = new Rate('grpc_req_failed');

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    insecureSkipTLSVerify: true,
    thresholds: {
        grpc_req_failed: [`rate<=${maxErrorRate()}`],
        checks: [`rate>=${1 - maxErrorRate()}`],
    },
};

export default function () {
    // Connect once per VU (on its first iteration) rather than per call: a per-call dial would fold
    // TLS + HTTP/2 connection setup into every measured request and swamp the relay cost the
    // benchmark targets. The connection is held open for the VU's lifetime.
    if (__ITER === 0) {
        client.connect(ADDRESS, { plaintext: false, timeout: '5s' });
    }

    const response = client.invoke(SERVICE_METHOD, { message: PAYLOAD });
    calls.add(1);
    const ok = response && response.status === grpc.StatusOK;
    grpcFailed.add(!ok);
    check(response, {
        'status is OK': (r) => r.status === grpc.StatusOK,
        'echoes the message': (r) => ok && r.message.message === PAYLOAD,
    });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data, {
        durationMetric: 'grpc_req_duration',
        requestsMetric: 'grpc_calls',
        failuresMetric: 'grpc_req_failed',
    });
}
