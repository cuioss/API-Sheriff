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
# The api-sheriff container runs as the distroless 'nonroot' user (uid 65532), but this
# host directory is created by the (differently-numbered) Maven user, so the bind-mounted
# /logs is not writable by the container and the file log sink fails with
# "FileNotFoundException: /logs/quarkus.log (Permission denied)". Grant world write on
# a dedicated 'quarkus-logs' subdirectory only — least privilege — so the container can write
# quarkus.log there without making the entire build target tree world-writable (ephemeral
# test output — the container keeps its no-new-privileges / cap_drop / read_only posture).
#
# Mode 1777, not 0777: the sticky bit is what keeps that world write from also being a
# world DELETE. Without it any local account on a shared CI runner or developer host can
# remove or replace quarkus.log — the file this script uploads as a failure-diagnosis
# artifact — so the evidence read after a failed run is locally tamperable. The sticky bit
# restricts unlink and rename to the file's owner and the directory's owner, costing the
# container nothing: it still creates and rotates the files it owns, and 'mvn clean' runs
# as the build user that OWNS this directory.
LOG_TARGET_ROOT="${LOG_TARGET_DIR:-${PROJECT_DIR}/target}"
export LOG_TARGET_DIR="${LOG_TARGET_ROOT}/quarkus-logs"
mkdir -p "${LOG_TARGET_DIR}"
chmod 1777 "${LOG_TARGET_DIR}"
echo "📁 Quarkus logs will be written to: ${LOG_TARGET_DIR}/quarkus.log"

# Discover every host-side probe target from the resolved Compose model, BEFORE anything is started.
#
# The service set, each service's published management port, and the scheme its management interface
# speaks are all DERIVED from that model — none of them is restated here. An earlier version
# hand-maintained a "service:port" list plus a separate block for the plain-HTTP instance, under a
# comment instructing the reader to keep the list in lockstep with docker-compose.yml. A hardcoded
# list that must mirror a set defined elsewhere is a defect unless it is derived from that source, so
# it is derived: adding, removing or renumbering an api-sheriff* service needs no edit here, and
# neither does moving Keycloak's published management port.
#
# The scheme comes from each service's de.cuioss.sheriff.management-scheme label rather than from its
# name, and that is what collapses the plain-management special case into a single readiness loop.
# That instance is probed over http:// with NO -k: if it ever needs -k, the plain-management opt-out
# has silently stopped working and THAT is the bug, not the probe.
#
# Keycloak carries the same label for the same reason, so its wait derives its whole probe URL here
# too rather than restating a scheme and a port the model already owns.
#
# The block runs BEFORE the first `compose up` on purpose: a model this script cannot read is a
# failure worth having in two seconds rather than after Keycloak has booted.
echo "⏳ Discovering probe targets from the Compose model..."
if ! DISCOVERED_TARGETS="$($COMPOSE_CMD config --format json | python3 -c '
import json
import sys

SCHEME_LABEL = "de.cuioss.sheriff.management-scheme"
MANAGEMENT_CONTAINER_PORT = "9000"
IDP_SERVICE = "keycloak"
GATEWAY_PREFIX = "api-sheriff"

try:
    model = json.load(sys.stdin)
except ValueError as exc:
    sys.exit("could not parse the resolved Compose model as JSON (%s). This script needs a Compose "
             "version supporting `config --format json`." % exc)

all_services = model.get("services") or {}
selected = {name: spec for name, spec in all_services.items() if name.startswith(GATEWAY_PREFIX)}
if not selected:
    sys.exit("no api-sheriff* services found in the resolved Compose model — refusing to run with a "
             "readiness gate that would probe nothing")
if IDP_SERVICE not in all_services:
    sys.exit("no %s service found in the resolved Compose model — refusing to run with an IdP wait "
             "that would probe nothing" % IDP_SERVICE)
selected[IDP_SERVICE] = all_services[IDP_SERVICE]

rows = []
problems = []
for name in sorted(selected):
    spec = selected[name]
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
    sys.exit("probe-target discovery failed:\n  " + "\n  ".join(problems))

sys.stdout.write("\n".join(rows) + "\n")
')"; then
    echo "❌ Could not derive the probe targets from docker-compose.yml (see the error above)"
    exit 1
fi

# Split the derived rows by role, mirroring demo-client/scripts/start-dev-environment.sh's
# IDP_TARGET / GATEWAY_TARGETS split. The IdP row drives the Keycloak wait and the Keycloak banner
# entry; the api-sheriff* rows drive the gateway readiness loop and the Application URLs banner.
KEYCLOAK_TARGET="$(printf '%s\n' "$DISCOVERED_TARGETS" | grep "^keycloak ")"
READINESS_TARGETS="$(printf '%s\n' "$DISCOVERED_TARGETS" | grep -v "^keycloak ")"
read -r _ KC_MGMT_SCHEME KC_MGMT_PORT <<< "$KEYCLOAK_TARGET"
KEYCLOAK_HEALTH_URL="${KC_MGMT_SCHEME}://localhost:${KC_MGMT_PORT}/health/ready"

# -f matters here exactly as it does on the gateway probe below: /health/ready answers 503 while
# Keycloak is still starting, and without -f curl exits 0 on that 503 — so the wait would clear as
# soon as the port ACCEPTED rather than when Keycloak was actually ready, which is the very race the
# comment below says this gate exists to remove.
KEYCLOAK_PROBE_OPTS=(-sf --connect-timeout 2 --max-time 5)
if [[ "$KC_MGMT_SCHEME" == "https" ]]; then
    # -k is load-bearing on an HTTPS management interface: it serves a self-signed localhost bundle,
    # and without it curl fails certificate validation and this wait degrades into a silent
    # 120-attempt timeout against a perfectly healthy container.
    KEYCLOAK_PROBE_OPTS+=(-k)
fi

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
echo "⏳ Waiting for Keycloak to be ready (management ${KC_MGMT_SCHEME} on ${KC_MGMT_PORT})..."
for i in {1..120}; do
    if curl "${KEYCLOAK_PROBE_OPTS[@]}" "${KEYCLOAK_HEALTH_URL}" > /dev/null 2>&1; then
        echo "✅ Keycloak is ready!"
        break
    fi
    if [ "$i" -eq 120 ]; then
        echo "❌ Keycloak did not answer ${KEYCLOAK_HEALTH_URL} within 120 attempts"
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

# Wait for every gateway instance to become READY — not merely live.
#
# The probe is /q/health/ready, which on this gateway means GatewayReadinessCheck reported UP:
# the configuration document is bound and, when a token_validation block is configured, the
# @GatewayValidator-qualified TokenValidator resolved. /q/health/live answers as soon as the
# process is up, which is strictly earlier than the point at which the suite can drive it — an
# instance that is live but not ready serves the first IT request against an unbound validator.
#
# Every instance must be waited on, not just the primary one: the suites drive the TLS ports
# directly — MtlsHandshakeIT, the Bff*Cookie*IT suites (BffCookieStatelessnessIT drives BOTH cookie
# instances in one test), WebSocketProxyIT's relay-exhaustion regression against the low-admission
# instance — so an unwaited instance is a race that surfaces as a connection refusal in the IT phase
# rather than as a start-up failure here.
#
# READINESS_TARGETS is the api-sheriff*-only subset of the rows discovered before the bring-up.
#
# The retry budget is ONE number, declared once and read by all three of the loop bound, the
# last-attempt comparison and the progress echo. It used to be three literal 30s that could drift
# apart; a re-size now touches a single line.
#
# The value is measured, not chosen by feel. A sub-second prober run against all six instances under
# CPU contention put the live-to-ready delta at 0.00s on every one of them — which is not luck but
# what GatewayReadinessCheck means: its `jwks` datum is a BOOT-TIME constructibility fact (ADR-0027),
# forced into existence by TokenValidatorProducer.onStartup and cached thereafter, so readiness flips
# at the same moment liveness does and this gate costs no additional wait over the liveness probe it
# replaced.
#
# The per-instance figures and the headroom argument have a single home —
# doc/development/integration-test-topology.adoc, "Where the retry budget came from". Read the
# numbers there rather than restating them here, where they would drift.
#
# 30 attempts is retained on that evidence, and is consumed only on failure, so the headroom is free.
# Do not shrink it toward the observed times: CI runners are slower than the machine measured there.
GATEWAY_READY_ATTEMPTS=30

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
        # into a silent full-budget timeout against a perfectly healthy container.
        GATEWAY_PROBE_OPTS+=(-k)
        GATEWAY_DIAG_OPTS+=(-k)
    fi
    GATEWAY_MGMT_URL="${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}"

    echo "⏳ Waiting for ${GATEWAY_SERVICE} (management ${GATEWAY_MGMT_SCHEME} on ${GATEWAY_MGMT_PORT})..."
    for ((i = 1; i <= GATEWAY_READY_ATTEMPTS; i++)); do
        if curl "${GATEWAY_PROBE_OPTS[@]}" "${GATEWAY_MGMT_URL}/q/health/ready" > /dev/null 2>&1; then
            echo "✅ ${GATEWAY_SERVICE} gateway instance is ready!"
            break
        fi
        if [ "$i" -eq "$GATEWAY_READY_ATTEMPTS" ]; then
            echo "❌ ${GATEWAY_SERVICE} gateway instance failed to start within ${GATEWAY_READY_ATTEMPTS} attempts"
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
        echo "⏳ Waiting for ${GATEWAY_SERVICE}... (attempt $i/${GATEWAY_READY_ATTEMPTS})"
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
# Printed from the SAME derived Keycloak row the wait above probed, so this cannot drift from what
# docker-compose.yml publishes.
echo "  curl ${KEYCLOAK_PROBE_OPTS[*]} ${KEYCLOAK_HEALTH_URL}"
echo ""
echo "🛑 To stop: ./scripts/stop-integration-container.sh"
echo "📋 To view logs: ${COMPOSE_BASE} logs -f"
