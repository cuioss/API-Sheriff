/**
 * @fileoverview Benchmark for the bearer-validated proxied route (k6 -> gateway -> upstream).
 *
 * Measures the added cost of offline bearer-token validation on the hot path: every request
 * carries a valid access token the gateway validates offline before forwarding upstream. The
 * token is minted once in {@link setup} from the Keycloak `benchmark` realm and replayed for
 * the whole run, so the run measures the gateway's per-request validation + proxy overhead
 * rather than Keycloak's token-issuance cost.
 *
 * This is the `bearer` matrix aspect, and it is new CI coverage: the wrk-era
 * `bearer_proxied_benchmark.sh` was registered in no Maven execution, so bearer did not run in
 * CI before this migration.
 *
 * Anti-silent-wrong-result gate: `checks` carries an explicit HTTP-200 assertion, so a run
 * whose tokens are all rejected (401) scores a checks rate of 0 and fails the build instead of
 * reporting the fast rejection path as an excellent result.
 */
import http from 'k6/http';
import { check, fail } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'bearerProxied';
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/secure/get');

// Keycloak is reached by service name on the shared api-sheriff network. The benchmark realm
// import pins frontendUrl to https://keycloak:8443, so a token minted here carries exactly the
// `iss` claim the gateway's benchmark-realm issuer validates against.
const KEYCLOAK_TOKEN_URL = __ENV.KEYCLOAK_TOKEN_URL
    || 'https://keycloak:8443/realms/benchmark/protocol/openid-connect/token';
// THROWAWAY BENCHMARK-REALM CREDENTIALS, LITERAL BY NECESSITY — read this before "fixing" it.
//
// These four literals are not injected secrets with a convenience default: under the compose
// harness they are the OPERATIVE values. integration-tests/docker-compose.yml passes only
// BENCHMARK_VUS, BENCHMARK_DURATION, BENCHMARK_MAX_ERROR_RATE, GATEWAY_TARGET and PASSTHROUGH_SNI
// through to the k6 service, so KEYCLOAK_* is never set there and every `||` branch below is taken.
//
// They are literal because they must MATCH the realm import that provisions them —
// integration-tests/src/main/docker/keycloak/benchmark-realm.json declares the `benchmark-client`
// secret and the `benchmark-user` password verbatim. That file is the co-authoritative copy, so
// dropping the fallbacks here and failing fast would not remove the literal from the repository;
// it would only break the benchmark under the shipped harness while the same strings stayed in the
// realm JSON. Changing one without the other yields an all-401 run, which the `checks` threshold
// above turns into a hard build failure rather than a silently fast result.
//
// The mirror is CHECKED, not merely documented: integration-tests carries a surefire guard,
// BenchmarkRealmCredentialConsistencyTest, that reads both this file and the realm JSON and fails
// the build when the two sides disagree — so changing one without the other no longer surfaces as
// an all-401 benchmark run.
//
// Containment, which is what makes this acceptable rather than a finding: the realm exists only
// inside an ephemeral Keycloak container on a local bridge network, it is created for the load test
// and destroyed with the stack, and neither literal reaches any api-sheriff production source or
// the packaged artifact (ADR-0032). Nothing here grants access to anything outside that stack.
const KEYCLOAK_CLIENT_ID = __ENV.KEYCLOAK_CLIENT_ID || 'benchmark-client';
const KEYCLOAK_CLIENT_SECRET = __ENV.KEYCLOAK_CLIENT_SECRET || 'benchmark-secret';
const KEYCLOAK_USERNAME = __ENV.KEYCLOAK_USERNAME || 'benchmark-user';
const KEYCLOAK_PASSWORD = __ENV.KEYCLOAK_PASSWORD || 'benchmark-password';

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

/**
 * Mints one access token from the benchmark realm before the measured window opens.
 *
 * A missing or unparseable token is fatal here rather than deferred to the run: without it
 * every request would be rejected 401, and a 401-only run measures the rejection path, not the
 * validation path this benchmark exists to measure.
 *
 * @returns {{token: string}} the bearer token replayed by every VU iteration
 */
export function setup() {
    const response = http.post(
        KEYCLOAK_TOKEN_URL,
        {
            grant_type: 'password',
            client_id: KEYCLOAK_CLIENT_ID,
            client_secret: KEYCLOAK_CLIENT_SECRET,
            username: KEYCLOAK_USERNAME,
            password: KEYCLOAK_PASSWORD,
        },
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
    );

    if (response.status !== 200) {
        fail(`could not obtain an access token from ${KEYCLOAK_TOKEN_URL}: HTTP ${response.status}`);
    }

    const token = (response.json() || {}).access_token;
    if (!token) {
        fail(`token response from ${KEYCLOAK_TOKEN_URL} carried no access_token`);
    }

    return { token: token };
}

export default function (data) {
    const response = http.get(TARGET_URL, {
        headers: {
            Authorization: `Bearer ${data.token}`,
            Accept: 'application/json',
        },
        tags: { benchmark: BENCHMARK_NAME },
    });
    check(response, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data);
}
