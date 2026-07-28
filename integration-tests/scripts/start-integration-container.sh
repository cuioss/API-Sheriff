#!/bin/bash
# Start API Sheriff Integration Tests using Docker Compose

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$PROJECT_DIR")"
APP_TARGET_DIR="${ROOT_DIR}/api-sheriff/target"

# shellcheck source=lib-docker-compose.sh
source "${SCRIPT_DIR}/lib-docker-compose.sh"

echo "🚀 Starting API Sheriff Integration Tests with Docker Compose"
echo "Project directory: ${PROJECT_DIR}"
echo "Root directory: ${ROOT_DIR}"

# Resolve the compose command (docker compose plugin vs standalone docker-compose)
COMPOSE_BASE="$(resolve_compose_cmd || true)"
if [[ -z "$COMPOSE_BASE" ]]; then
    echo "❌ Docker Compose not available (neither 'docker compose' nor 'docker-compose')"
    exit 1
fi
if ! docker_daemon_up; then
    echo "❌ Docker daemon not running — start Docker/Rancher Desktop first"
    exit 1
fi

cd "${PROJECT_DIR}"

# Check build approach - Native executable + Docker copy vs Docker build
RUNNER_FILE=$(find "${APP_TARGET_DIR}" -name "*-runner" -type f 2>/dev/null | head -n 1)
# Detect image type - prefer JFR if available, fallback to distroless
JFR_IMAGE=$(docker images --format "{{.Repository}}:{{.Tag}}" | grep "^api-sheriff:jfr$" || true)
DISTROLESS_IMAGE=$(docker images --format "{{.Repository}}:{{.Tag}}" | grep "^api-sheriff:distroless$" || true)

if [[ -n "$JFR_IMAGE" ]]; then
    AVAILABLE_IMAGE="$JFR_IMAGE"
    IMAGE_TYPE="jfr"
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml -f docker-compose.jfr.yml"
elif [[ -n "$DISTROLESS_IMAGE" ]]; then
    AVAILABLE_IMAGE="$DISTROLESS_IMAGE"
    IMAGE_TYPE="distroless"
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml"
else
    AVAILABLE_IMAGE=""
    IMAGE_TYPE="none"
fi

# Benchmark mode: overlay the static nginx backend and repoint the gateway upstream
if [[ "${BENCHMARK_MODE:-false}" == "true" ]] && [[ -n "$COMPOSE_CMD" ]]; then
    COMPOSE_CMD="$COMPOSE_CMD -f docker-compose.benchmark.yml"
    echo "🏁 Benchmark mode: overlaying static nginx backend (docker-compose.benchmark.yml)"
fi

IMAGE_EXISTS=$([ ! -z "$AVAILABLE_IMAGE" ] && echo "true" || echo "false")

if [[ -n "$RUNNER_FILE" ]] && [[ "$IMAGE_EXISTS" == "true" ]]; then
    echo "📦 Using Maven-built native executable: $(basename "$RUNNER_FILE")"
    echo "🐳 Docker image: $AVAILABLE_IMAGE ($IMAGE_TYPE mode)"
    MODE="native (Maven-built + Docker copy) - $IMAGE_TYPE"
elif [[ "$IMAGE_EXISTS" == "true" ]]; then
    echo "📦 Using Docker-built native image: $AVAILABLE_IMAGE ($IMAGE_TYPE mode)"
    MODE="native (Docker-built) - $IMAGE_TYPE"
else
    echo "❌ Neither native executable nor Docker image found"
    echo "Expected: api-sheriff/target/*-runner file and api-sheriff image"
    echo "Available images:"
    docker images | grep api-sheriff || echo "  No api-sheriff images found"
    echo "Run: mvnw verify -Pintegration-tests -pl integration-tests -am"
    exit 1
fi


# Set LOG_TARGET_DIR to a dedicated log subdirectory for Quarkus file logging.
# The api-sheriff native/distroless container runs as uid 1001, but this host
# directory is created by the (differently-numbered) Maven user, so the bind-mounted
# /logs is not writable by the container and the file log sink fails with
# "FileNotFoundException: /logs/quarkus.log (Permission denied)". Grant world write on
# a dedicated 'quarkus-logs' subdirectory only — least privilege — so uid 1001 can write
# quarkus.log there without making the entire build target tree world-writable (ephemeral
# test output — the container keeps its no-new-privileges / cap_drop / read_only posture).
LOG_TARGET_ROOT="${LOG_TARGET_DIR:-${PROJECT_DIR}/target}"
export LOG_TARGET_DIR="${LOG_TARGET_ROOT}/quarkus-logs"
mkdir -p "${LOG_TARGET_DIR}"
chmod 0777 "${LOG_TARGET_DIR}"
echo "📁 Quarkus logs will be written to: ${LOG_TARGET_DIR}/quarkus.log"

# Bring up Keycloak FIRST and wait until it is READY before starting the gateway. The api-sheriff
# native app eagerly loads the Keycloak issuers' JWKS at boot; if it starts before Keycloak can
# answer, that initial load fails (ConnectException) and — with a long background-refresh interval —
# the issuer stays unhealthy for the whole test run, so every mediated login's token validation
# fails with "No healthy issuer configuration found". Under CI's shared-CPU contention Keycloak is
# slower to answer than the gateway's brief initial-retry window, which made this flake. Gating the
# gateway start on a ready Keycloak removes the race. (docker compose up -d keycloak starts Keycloak
# and its own dependencies only; the gateway and remaining infra are started afterwards.)
echo "🐳 Starting Keycloak first (the Quarkus $MODE gateway starts only after Keycloak is ready)..."
(cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d keycloak)

# Wait for Keycloak to be ready first
echo "⏳ Waiting for Keycloak to be ready..."
for i in {1..120}; do
    if curl -k -s https://localhost:1090/health/ready > /dev/null 2>&1; then
        echo "✅ Keycloak is ready!"
        break
    fi
    if [ $i -eq 120 ]; then
        echo "❌ Keycloak failed to become ready within 120 seconds"
        echo "Check logs with: ${COMPOSE_BASE} logs keycloak"
        exit 1
    fi
    echo "⏳ Waiting for Keycloak... (attempt $i/120)"
    sleep 1
done

# Keycloak is ready — now bring up the gateway and the remaining containers.
echo "🐳 Starting the gateway ($MODE) and remaining containers..."
(cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d)

# Wait for the go-httpbin upstream backend (proxy target) to be ready
echo "⏳ Waiting for go-httpbin upstream to be ready..."
for i in {1..30}; do
    if curl -sf http://localhost:18080/status/200 > /dev/null 2>&1; then
        echo "✅ go-httpbin upstream is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ go-httpbin upstream failed to start within 30 seconds"
        echo "Check logs with: ${COMPOSE_BASE} logs go-httpbin"
        exit 1
    fi
    echo "⏳ Waiting for go-httpbin... (attempt $i/30)"
    sleep 1
done

# Wait for the static nginx backend (benchmark mode only)
if [[ "${BENCHMARK_MODE:-false}" == "true" ]]; then
    echo "⏳ Waiting for nginx-static backend to be ready..."
    for i in {1..30}; do
        if curl -sf http://localhost:18081/ > /dev/null 2>&1; then
            echo "✅ nginx-static backend is ready!"
            break
        fi
        if [ $i -eq 30 ]; then
            echo "❌ nginx-static backend failed to start within 30 seconds"
            echo "Check logs with: ${COMPOSE_BASE} logs nginx-static"
            exit 1
        fi
        echo "⏳ Waiting for nginx-static... (attempt $i/30)"
        sleep 1
    done
fi

# Wait for Quarkus service to be ready and measure startup time
echo "⏳ Waiting for Quarkus service to be ready..."
START_TIME=$(date +%s)
for i in {1..30}; do
    # -k is load-bearing: the management interface is HTTPS-only (single port, self-signed
    # localhost bundle). Without it curl fails certificate validation and this wait degrades into
    # a silent 30-attempt timeout against a perfectly healthy container.
    if curl -skf https://localhost:19000/q/health/live > /dev/null 2>&1; then
        END_TIME=$(date +%s)
        TOTAL_TIME=$((END_TIME - START_TIME))
        echo "✅ Quarkus service is ready!"
        echo "📈 Actual startup time: ${TOTAL_TIME}s (container + application)"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Quarkus service failed to start within 30 seconds"
        # Capture the app container log + health payloads so a startup failure is
        # diagnosable from CI artifacts (uploaded via the failsafe-reports folder).
        DIAG_DIR="target/failsafe-reports"
        mkdir -p "$DIAG_DIR"
        echo "----- $COMPOSE_BASE logs api-sheriff -----"
        $COMPOSE_BASE logs --no-color api-sheriff 2>&1 | tee "$DIAG_DIR/api-sheriff-app.log"
        echo "----- /q/health -----"
        curl -sk https://localhost:19000/q/health 2>&1 | tee "$DIAG_DIR/api-sheriff-health.json"
        echo ""
        exit 1
    fi
    echo "⏳ Waiting for Quarkus... (attempt $i/30)"
    sleep 1
done

# Wait for the dedicated mTLS gateway instance (api-sheriff-mtls) to be ready. It reuses the same
# native image and reaches readiness offline (static-file JWKS), published on management port 19001.
# MtlsHandshakeIT connects to its TLS port 10444, so it must be up before the IT phase.
echo "⏳ Waiting for the mTLS gateway instance to be ready..."
for i in {1..30}; do
    if curl -skf https://localhost:19001/q/health/live > /dev/null 2>&1; then
        echo "✅ mTLS gateway instance is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ mTLS gateway instance failed to start within 30 seconds"
        DIAG_DIR="target/failsafe-reports"
        mkdir -p "$DIAG_DIR"
        echo "----- $COMPOSE_BASE logs api-sheriff-mtls -----"
        $COMPOSE_BASE logs --no-color api-sheriff-mtls 2>&1 | tee "$DIAG_DIR/api-sheriff-mtls-app.log"
        curl -sk https://localhost:19001/q/health 2>&1 | tee "$DIAG_DIR/api-sheriff-mtls-health.json"
        echo ""
        exit 1
    fi
    echo "⏳ Waiting for mTLS gateway... (attempt $i/30)"
    sleep 1
done

# Wait for the two dedicated cookie-mode gateway instances (api-sheriff-cookie on 10445 /
# management 19002, and its key-sharing peer api-sheriff-cookie-2 on 10446 / management 19003).
# Both reuse the same native image and reach readiness offline (static-file JWKS). The Bff*Cookie*IT
# suites drive their TLS ports directly — and BffCookieStatelessnessIT drives BOTH in one test — so
# an unwaited instance is a race that surfaces as a connection refusal in the IT phase, not as a
# start-up failure here. Mirrors the mTLS block above exactly.
for cookie_instance in "api-sheriff-cookie:19002" "api-sheriff-cookie-2:19003"; do
    COOKIE_SERVICE="${cookie_instance%%:*}"
    COOKIE_MGMT_PORT="${cookie_instance##*:}"
    echo "⏳ Waiting for the ${COOKIE_SERVICE} gateway instance to be ready..."
    for i in {1..30}; do
        if curl -skf "https://localhost:${COOKIE_MGMT_PORT}/q/health/live" > /dev/null 2>&1; then
            echo "✅ ${COOKIE_SERVICE} gateway instance is ready!"
            break
        fi
        if [ $i -eq 30 ]; then
            echo "❌ ${COOKIE_SERVICE} gateway instance failed to start within 30 seconds"
            DIAG_DIR="target/failsafe-reports"
            mkdir -p "$DIAG_DIR"
            echo "----- $COMPOSE_BASE logs ${COOKIE_SERVICE} -----"
            $COMPOSE_BASE logs --no-color "${COOKIE_SERVICE}" 2>&1 | tee "$DIAG_DIR/${COOKIE_SERVICE}-app.log"
            curl -sk "https://localhost:${COOKIE_MGMT_PORT}/q/health" 2>&1 | tee "$DIAG_DIR/${COOKIE_SERVICE}-health.json"
            echo ""
            exit 1
        fi
        echo "⏳ Waiting for ${COOKIE_SERVICE} gateway... (attempt $i/30)"
        sleep 1
    done
done

# Extract native startup time from logs
NATIVE_STARTUP=$($COMPOSE_BASE logs api-sheriff 2>/dev/null | grep "started in" | sed -n 's/.*started in \([0-9.]*\)s.*/\1/p' | tail -1)
if [ ! -z "$NATIVE_STARTUP" ]; then
    echo "⚡ Native app startup: ${NATIVE_STARTUP}s (application only)"
fi

# Show actual image size
IMAGE_SIZE=$(docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}" | grep "api-sheriff:" | grep -v integration | awk '{print $2}' | head -1)
if [ ! -z "$IMAGE_SIZE" ]; then
    echo "📦 Image size: ${IMAGE_SIZE} (native image)"
fi

echo ""
echo "🎉 API Sheriff Integration Benchmark Environment is running!"
echo ""
echo "📱 Application URLs:"
echo "  🔍 Health Check:   https://localhost:19000/q/health"
echo "  📊 Metrics:        https://localhost:19000/q/metrics"
echo "  🔑 Keycloak:       https://localhost:1443/auth"
echo ""
echo "🧪 Quick test commands (management is HTTPS-only with a self-signed cert — -k is required):"
echo "  curl -skf https://localhost:19000/q/health/live"
echo "  curl -k https://localhost:1090/health/ready"
echo ""
echo "🛑 To stop: ./scripts/stop-integration-container.sh"
echo "📋 To view logs: ${COMPOSE_BASE} logs -f"
