#!/bin/bash
# Benchmark runner for the bearer-validated proxied route (wrk -> gateway -> upstream).
# Measures the added cost of offline bearer-token validation on the hot path: every
# request carries a valid access token the gateway validates offline before forwarding
# to the upstream. A token is minted once from the benchmark Keycloak realm and reused
# for the whole run (wrk replays it on every request), so the run measures the gateway's
# per-request validation + proxy overhead, not Keycloak's token-issuance cost.
#
# The companion bearer_proxied_check.lua gates the run on the failure rate: a run whose
# tokens are rejected (401) or whose upstream is unreachable fails non-zero (set -e here),
# so a broken bearer path never benchmarks as a PASS.
set -e

WRK_THREADS="${WRK_THREADS:-5}"
WRK_CONNECTIONS="${WRK_CONNECTIONS:-50}"
WRK_DURATION="${WRK_DURATION:-60s}"
WRK_TIMEOUT="${WRK_TIMEOUT:-2s}"
COMPOSE_DIR="${COMPOSE_DIR:?COMPOSE_DIR must be set}"

BENCHMARK_NAME="bearerProxied"
TARGET_URL="${TARGET_URL:-https://api-sheriff:8443/secure/get}"

# Keycloak token acquisition (benchmark realm). Reached over the host-published HTTPS
# port; overridable per environment. Credentials mirror benchmark-realm.json.
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-https://localhost:1443/realms/benchmark/protocol/openid-connect/token}"
KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-benchmark-client}"
KEYCLOAK_CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-benchmark-secret}"
KEYCLOAK_USERNAME="${KEYCLOAK_USERNAME:-benchmark-user}"
KEYCLOAK_PASSWORD="${KEYCLOAK_PASSWORD:-benchmark-password}"

echo "=== BENCHMARK METADATA ==="
echo "benchmark_name: ${BENCHMARK_NAME}"
echo "target_url: ${TARGET_URL}"
echo "threads: ${WRK_THREADS}"
echo "connections: ${WRK_CONNECTIONS}"
echo "duration: ${WRK_DURATION}"
echo "start_time: $(date +%s)"

echo "=== ACQUIRING BEARER TOKEN ==="
TOKEN_RESPONSE="$(curl -sk -X POST "${KEYCLOAK_TOKEN_URL}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=${KEYCLOAK_CLIENT_ID}" \
    -d "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
    -d "username=${KEYCLOAK_USERNAME}" \
    -d "password=${KEYCLOAK_PASSWORD}")"

BEARER_TOKEN="$(printf '%s' "${TOKEN_RESPONSE}" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
if [ -z "${BEARER_TOKEN}" ]; then
    echo "FATAL: could not obtain an access token from ${KEYCLOAK_TOKEN_URL}" >&2
    echo "response: ${TOKEN_RESPONSE}" >&2
    exit 1
fi
echo "token acquired (length ${#BEARER_TOKEN})"

echo "=== WRK OUTPUT ==="
cd "${COMPOSE_DIR}"
docker compose run --rm \
    -e "BENCHMARK_BEARER_TOKEN=${BEARER_TOKEN}" \
    -e "WRK_MAX_ERROR_RATE=${WRK_MAX_ERROR_RATE:-0.01}" \
    wrk \
    -t"${WRK_THREADS}" \
    -c"${WRK_CONNECTIONS}" \
    -d"${WRK_DURATION}" \
    --timeout "${WRK_TIMEOUT}" \
    --latency \
    -s /scripts/bearer_proxied_check.lua \
    "${TARGET_URL}"

echo "end_time: $(date +%s)"
echo "=== BENCHMARK COMPLETE ==="
