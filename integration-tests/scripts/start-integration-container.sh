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
    if curl -k -s --connect-timeout 2 --max-time 5 https://localhost:1090/health/ready > /dev/null 2>&1; then
        echo "✅ Keycloak is ready!"
        break
    fi
    if [ "$i" -eq 120 ]; then
        echo "❌ Keycloak failed to become ready within 120 attempts"
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
    if curl -sf --connect-timeout 2 --max-time 5 http://localhost:18080/status/200 > /dev/null 2>&1; then
        echo "✅ go-httpbin upstream is ready!"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "❌ go-httpbin upstream failed to start within 30 attempts"
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
        if curl -sf --connect-timeout 2 --max-time 5 http://localhost:18081/ > /dev/null 2>&1; then
            echo "✅ nginx-static backend is ready!"
            break
        fi
        if [ "$i" -eq 30 ]; then
            echo "❌ nginx-static backend failed to start within 30 attempts"
            echo "Check logs with: ${COMPOSE_BASE} logs nginx-static"
            exit 1
        fi
        echo "⏳ Waiting for nginx-static... (attempt $i/30)"
        sleep 1
    done
fi

# Wait for every gateway instance to become ready.
#
# The instance set, each instance's published management port, and the scheme its management
# interface speaks are all DERIVED from the resolved Compose model — none of them is restated here.
# An earlier version hand-maintained a "service:port" list plus a separate block for the plain-HTTP
# instance, under a comment instructing the reader to keep the list in lockstep with
# docker-compose.yml. A hardcoded list that must mirror a set defined elsewhere is a defect unless it
# is derived from that source, so it is derived: adding, removing or renumbering an api-sheriff*
# service needs no edit in this file.
#
# The scheme comes from each service's de.cuioss.sheriff.management-scheme label rather than from its
# name, and that is what collapses the plain-management special case into this one loop. That
# instance is probed over http:// with NO -k: if it ever needs -k, the plain-management opt-out has
# silently stopped working and THAT is the bug, not the probe.
#
# Every instance must be waited on, not just the primary one: the suites drive the TLS ports
# directly — MtlsHandshakeIT, the Bff*Cookie*IT suites (BffCookieStatelessnessIT drives BOTH cookie
# instances in one test), WebSocketProxyIT's relay-exhaustion regression against the low-admission
# instance — so an unwaited instance is a race that surfaces as a connection refusal in the IT phase
# rather than as a start-up failure here.
echo "⏳ Discovering gateway readiness targets from the Compose model..."
if ! READINESS_TARGETS="$($COMPOSE_CMD config --format json | python3 -c '
import json
import sys

SCHEME_LABEL = "de.cuioss.sheriff.management-scheme"
MANAGEMENT_CONTAINER_PORT = "9000"

try:
    model = json.load(sys.stdin)
except ValueError as exc:
    sys.exit("could not parse the resolved Compose model as JSON (%s). This script needs a Compose "
             "version supporting `config --format json`." % exc)

services = {name: spec for name, spec in (model.get("services") or {}).items()
            if name.startswith("api-sheriff")}
if not services:
    sys.exit("no api-sheriff* services found in the resolved Compose model — refusing to run with a "
             "readiness gate that would probe nothing")

rows = []
problems = []
for name in sorted(services):
    spec = services[name]
    scheme = (spec.get("labels") or {}).get(SCHEME_LABEL)
    published = [port.get("published") for port in (spec.get("ports") or [])
                 if str(port.get("target")) == MANAGEMENT_CONTAINER_PORT and port.get("published")]
    usable = True
    if scheme not in ("http", "https"):
        problems.append("%s: missing or invalid %s label (got %r)" % (name, SCHEME_LABEL, scheme))
        usable = False
    if len(published) != 1:
        problems.append("%s: expected exactly one host port published against container port %s, "
                        "found %r" % (name, MANAGEMENT_CONTAINER_PORT, published))
        usable = False
    if usable:
        rows.append("%s %s %s" % (name, scheme, published[0]))

if problems:
    sys.exit("gateway readiness discovery failed:\n  " + "\n  ".join(problems))

sys.stdout.write("\n".join(rows) + "\n")
')"; then
    echo "❌ Could not derive the gateway readiness targets from docker-compose.yml (see the error above)"
    exit 1
fi

echo "⏳ Waiting for the discovered gateway instances to be ready..."
START_TIME=$(date +%s)
GATEWAY_COUNT=0
while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT; do
    [[ -z "$GATEWAY_SERVICE" ]] && continue
    GATEWAY_COUNT=$((GATEWAY_COUNT + 1))

    GATEWAY_PROBE_OPTS=(-sf --connect-timeout 2 --max-time 5)
    GATEWAY_DIAG_OPTS=(-s --connect-timeout 2 --max-time 5)
    if [[ "$GATEWAY_MGMT_SCHEME" == "https" ]]; then
        # -k is load-bearing on the HTTPS instances: their management interface serves a self-signed
        # localhost bundle, and without it curl fails certificate validation and this wait degrades
        # into a silent 30-attempt timeout against a perfectly healthy container.
        GATEWAY_PROBE_OPTS+=(-k)
        GATEWAY_DIAG_OPTS+=(-k)
    fi
    GATEWAY_MGMT_URL="${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}"

    echo "⏳ Waiting for ${GATEWAY_SERVICE} (management ${GATEWAY_MGMT_SCHEME} on ${GATEWAY_MGMT_PORT})..."
    for i in {1..30}; do
        if curl "${GATEWAY_PROBE_OPTS[@]}" "${GATEWAY_MGMT_URL}/q/health/live" > /dev/null 2>&1; then
            echo "✅ ${GATEWAY_SERVICE} gateway instance is ready!"
            break
        fi
        if [ "$i" -eq 30 ]; then
            echo "❌ ${GATEWAY_SERVICE} gateway instance failed to start within 30 attempts"
            # Capture the container log + health payload so a startup failure is diagnosable from CI
            # artifacts (uploaded via the failsafe-reports folder).
            DIAG_DIR="target/failsafe-reports"
            mkdir -p "$DIAG_DIR"
            echo "----- $COMPOSE_BASE logs ${GATEWAY_SERVICE} -----"
            $COMPOSE_BASE logs --no-color "${GATEWAY_SERVICE}" </dev/null 2>&1 | tee "$DIAG_DIR/${GATEWAY_SERVICE}-app.log"
            echo "----- ${GATEWAY_MGMT_URL}/q/health -----"
            curl "${GATEWAY_DIAG_OPTS[@]}" "${GATEWAY_MGMT_URL}/q/health" 2>&1 | tee "$DIAG_DIR/${GATEWAY_SERVICE}-health.json"
            echo ""
            exit 1
        fi
        echo "⏳ Waiting for ${GATEWAY_SERVICE}... (attempt $i/30)"
        sleep 1
    done
done <<< "$READINESS_TARGETS"

TOTAL_TIME=$(($(date +%s) - START_TIME))
echo "✅ All ${GATEWAY_COUNT} gateway instances are ready!"
# The instances were all started by the same `compose up -d` above and come up concurrently, so this
# is the wall-clock time until the slowest of them answered, not the sum of their startup times.
echo "📈 Actual startup time: ${TOTAL_TIME}s (container + application, ${GATEWAY_COUNT} instances in parallel)"

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
# Listed from the SAME discovered targets the readiness loop probed, so this stays correct when an
# instance is added, removed or renumbered — and so it cannot claim management is HTTPS-only, which
# the plain-management instance makes false.
while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT; do
    [[ -z "$GATEWAY_SERVICE" ]] && continue
    echo "  🔍 ${GATEWAY_SERVICE} management: ${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}/q/health (metrics at /q/metrics)"
done <<< "$READINESS_TARGETS"
echo "  🔑 Keycloak:       https://localhost:1443/auth"
echo ""
echo "🧪 Quick test commands (an https:// management port serves a self-signed cert — -k is required there):"
echo "  curl -k https://localhost:1090/health/ready"
echo ""
echo "🛑 To stop: ./scripts/stop-integration-container.sh"
echo "📋 To view logs: ${COMPOSE_BASE} logs -f"
