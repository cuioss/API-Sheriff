#!/bin/bash
# Pre-flight check: verify Quarkus, Prometheus, and Keycloak are reachable before benchmarks start
set -e

INTEGRATION_SERVICE_URL="${INTEGRATION_SERVICE_URL:?INTEGRATION_SERVICE_URL must be set}"
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

    echo "ERROR: ${name} at ${url} did not become ready within $((MAX_RETRIES * RETRY_INTERVAL))s"
    return 1
}

echo "=== Pre-Benchmark Health Check ==="

check_service "Quarkus (health/live)" "${INTEGRATION_SERVICE_URL}/q/health/live"
check_service "Prometheus" "${PROMETHEUS_URL}/-/ready"
check_service "Keycloak" "${KEYCLOAK_URL}/health/ready"

echo "=== All services are ready. Proceeding with benchmarks. ==="
