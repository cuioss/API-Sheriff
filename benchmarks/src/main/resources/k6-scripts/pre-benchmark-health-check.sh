#!/bin/bash
# Pre-flight check: verify every BENCHMARK TARGET is actually served before the lane starts.
#
# This gate exists so a broken target fails here, naming the target, rather than 60 seconds later
# as an opaque k6 threshold breach. It covers both halves of the matrix:
#   - the management interface (HTTPS), which the gatewayHealth and healthLiveCheck benchmarks drive;
#   - the gateway DATA PLANE, which every other benchmark drives. Only the proxiedStatic route is
#     probed: it is the matrix baseline, so its failure invalidates every other data-plane number.
# Prometheus and Keycloak are supporting infrastructure, not benchmark targets, but a benchmark run
# without them yields artifacts with missing metrics or failing auth, so they are gated too.
set -e

INTEGRATION_SERVICE_URL="${INTEGRATION_SERVICE_URL:?INTEGRATION_SERVICE_URL must be set}"
MANAGEMENT_URL="${MANAGEMENT_URL:?MANAGEMENT_URL must be set}"
PROMETHEUS_URL="${PROMETHEUS_URL:?PROMETHEUS_URL must be set}"
KEYCLOAK_URL="${KEYCLOAK_URL:?KEYCLOAK_URL must be set}"

MAX_RETRIES=30
RETRY_INTERVAL=2

check_service() {
    local name="$1"
    local url="$2"
    local retries=0

    echo "Checking ${name} at ${url}..."
    while [ $retries -lt $MAX_RETRIES ]; do
        if curl -k -s -o /dev/null -w "%{http_code}" "$url" | grep -q "200"; then
            echo "${name} is ready."
            return 0
        fi
        retries=$((retries + 1))
        echo "Waiting for ${name}... (attempt ${retries}/${MAX_RETRIES})"
        sleep $RETRY_INTERVAL
    done

    echo "ERROR: benchmark target '${name}' at ${url} did not serve 200 within $((MAX_RETRIES * RETRY_INTERVAL))s"
    echo "ERROR: aborting the benchmark lane — a benchmark against an unserved target measures nothing."
    return 1
}

echo "=== Pre-Benchmark Target Check ==="

# Management floor first: establish that the instance is alive before asking it to route.
check_service "Quarkus management (health/live)" "${MANAGEMENT_URL}/q/health/live"
check_service "Prometheus" "${PROMETHEUS_URL}/-/ready"
check_service "Keycloak" "${KEYCLOAK_URL}/health/ready"
# Data-plane target. Until this probe existed INTEGRATION_SERVICE_URL was required but never used,
# so a deleted or misrouted data-plane route sailed through pre-flight and surfaced only as a k6
# threshold breach. /proxy/static is the matrix baseline driven by proxied_static.js.
check_service "Gateway data plane (proxiedStatic target)" "${INTEGRATION_SERVICE_URL}/proxy/static"

echo "=== All benchmark targets are served. Proceeding with benchmarks. ==="
