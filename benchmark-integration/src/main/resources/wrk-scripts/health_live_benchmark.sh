#!/bin/bash
# Benchmark runner for /q/health/live endpoint
set -e

WRK_THREADS="${WRK_THREADS:-5}"
WRK_CONNECTIONS="${WRK_CONNECTIONS:-50}"
WRK_DURATION="${WRK_DURATION:-60s}"
WRK_TIMEOUT="${WRK_TIMEOUT:-2s}"
COMPOSE_DIR="${COMPOSE_DIR:?COMPOSE_DIR must be set}"

BENCHMARK_NAME="healthLiveCheck"
TARGET_URL="https://api-sheriff:8443/q/health/live"

echo "=== BENCHMARK METADATA ==="
echo "benchmark_name: ${BENCHMARK_NAME}"
echo "target_url: ${TARGET_URL}"
echo "threads: ${WRK_THREADS}"
echo "connections: ${WRK_CONNECTIONS}"
echo "duration: ${WRK_DURATION}"
echo "start_time: $(date +%s)"
echo "=== WRK OUTPUT ==="

cd "${COMPOSE_DIR}"
docker compose run --rm wrk \
    -t"${WRK_THREADS}" \
    -c"${WRK_CONNECTIONS}" \
    -d"${WRK_DURATION}" \
    --timeout "${WRK_TIMEOUT}" \
    --latency \
    -s /scripts/health_live_check.lua \
    "${TARGET_URL}"

echo "end_time: $(date +%s)"
echo "=== BENCHMARK COMPLETE ==="
