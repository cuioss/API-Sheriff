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
# remove or replace quarkus.log — the gateway's own log for the run, and the first thing
# read to diagnose a failure — so that evidence is locally tamperable. The sticky bit
# restricts unlink and rename to the file's owner and the directory's owner, costing the
# container nothing: it still creates and rotates the files it owns, and 'mvn clean' runs
# as the build user that OWNS this directory.
LOG_TARGET_ROOT="${LOG_TARGET_DIR:-${PROJECT_DIR}/target}"
export LOG_TARGET_DIR="${LOG_TARGET_ROOT}/quarkus-logs"
mkdir -p "${LOG_TARGET_DIR}"
chmod 1777 "${LOG_TARGET_DIR}"
echo "📁 Quarkus logs will be written to: ${LOG_TARGET_DIR}/quarkus.log"

# The JFR overlay bind-mounts ./target/jfr-recordings at /tmp/jfr-output, and that host directory
# must exist and be container-writable before the first compose command resolves the overlay —
# see scripts/prepare-jfr-output-dir.sh for why, and for why both this script and the `jfr` Maven
# profile call it. Only the JFR overlay needs it, so it runs only when that overlay is composed.
if [[ "$IMAGE_TYPE" == "jfr" ]]; then
    ./scripts/prepare-jfr-output-dir.sh "${PROJECT_DIR}"
fi

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

# Capture everything a failed bring-up needs to be diagnosed from CI artifacts alone: the container
# log and the /q/health payload, written under target/failsafe-reports/ so they are uploaded with the
# Failsafe reports. Both failure paths of the two-layer gate below call this, so the evidence is
# identical whichever layer caught the problem — a container that never reported healthy and a
# container that reported healthy but is not READY are diagnosed from the same two files.
capture_gateway_diagnostics() {
    local service="$1"
    local mgmt_url="$2"
    local diag_dir="target/failsafe-reports"
    local diag_opts=(-s --connect-timeout 2 --max-time 5)

    # -k for the same reason the readiness assertion needs it: an https:// management interface serves
    # a self-signed localhost bundle, so without it curl fails certificate validation and writes an
    # empty health payload — destroying the very evidence this function exists to capture.
    if [[ "$mgmt_url" == https://* ]]; then
        diag_opts+=(-k)
    fi

    mkdir -p "$diag_dir"
    # Both captures are explicitly non-fatal. This function runs only on a path that has already
    # decided to fail, and its caller loops over every gateway instance before exiting — so a probe
    # that refuses the connection (the common case here, and exactly what we are diagnosing) must not
    # be able to cut the loop short and cost us the diagnostics for the remaining instances. Today
    # `pipefail` is not set, so each pipeline already reports tee's status rather than curl's and the
    # `|| true` is belt-and-braces; it is written anyway so that turning `pipefail` on later cannot
    # silently convert this collector into an early exit. The operator sample's
    # capture_sample_diagnostics guards the same two commands the same way.
    echo "----- $COMPOSE_BASE logs ${service} -----"
    $COMPOSE_BASE logs --no-color "${service}" </dev/null 2>&1 | tee "$diag_dir/${service}-app.log" || true
    echo "----- ${mgmt_url}/q/health -----"
    curl "${diag_opts[@]}" "${mgmt_url}/q/health" 2>&1 | tee "$diag_dir/${service}-health.json" || true
    echo ""
}

# Gate the suite on every gateway instance being READY — not merely started — in two layers.
#
# Layer 1 (waiting) is Compose's own wait on the image's baked-in HEALTHCHECK. api-sheriff:distroless
# is built from Dockerfile.native, which bakes an exec-form probe, so `up -d --wait` blocks until every
# named service reports healthy. The polling that used to live here as a hand-rolled retry loop is now
# the container runtime's job, and the probe cadence has exactly one home — that HEALTHCHECK line. It
# is deliberately not restated here, and there is no retry budget in this script to drift from it.
#
# The wait is scoped to the derived gateway service names, NOT the whole stack. `--wait` with no
# service arguments waits on every service in the resolved model, which would implicitly health-gate
# keycloak, go-httpbin, asset-origin and the benchmark overlay's nginx-static. None of those carries a
# baked probe and each already has its own wait above, so an unscoped `--wait` would gate on something
# other than "the gateways are healthy". The service names come from the same READINESS_TARGETS rows
# derived from the Compose model before the bring-up, so adding or renaming an api-sheriff* service
# needs no edit here.
#
# Layer 2 (semantics) is the assertion the baked probe deliberately cannot make. That probe is a bare
# TCP accept on the management port — it must be, because the management scheme is deployment-bound
# (ADR-0025) — so it proves the interface is listening, not that GatewayReadinessCheck reported UP:
# the configuration document bound and, where a token_validation block is configured, the
# @GatewayValidator-qualified TokenValidator resolved. A single-shot /q/health/ready per instance
# closes exactly that gap. Every instance is asserted, not just the primary one: the suites drive the
# TLS ports directly — MtlsHandshakeIT, the Bff*Cookie*IT suites (BffCookieStatelessnessIT drives BOTH
# cookie instances in one test), WebSocketProxyIT's relay-exhaustion regression against the
# low-admission instance — so an unasserted instance is a race that surfaces as a connection refusal
# in the IT phase rather than as a start-up failure here.
#
# Single-shot with no retry budget is correct precisely BECAUSE layer 1 already waited: by the time
# this runs the instance has been reported healthy by the runtime, so a non-UP readiness answer is a
# real defect, and retrying would only delay reporting it. That is also why the scheme still comes
# from each service's management-scheme label rather than from its name — the plain-management
# instance is asserted over http:// with NO -k, and if it ever needs -k the plain-management opt-out
# has silently stopped working and THAT is the bug, not the probe.
GATEWAY_SERVICES="$(printf '%s\n' "$READINESS_TARGETS" | awk '{ print $1 }')"

echo "⏳ Waiting for the discovered gateway instances to report healthy..."
START_TIME=$(date +%s)

# Word-splitting GATEWAY_SERVICES into one argument per service is the point, hence the unquoted
# expansion. The `if ! ...; then` wrapper is required rather than stylistic: set -e is active, so a
# bare failing command would abort before the diagnostics below could run.
# shellcheck disable=SC2086
if ! (cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d --wait --wait-timeout 180 $GATEWAY_SERVICES); then
    echo "❌ Not every gateway instance reported healthy within 180s"
    while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT; do
        [[ -z "$GATEWAY_SERVICE" ]] && continue
        capture_gateway_diagnostics "${GATEWAY_SERVICE}" \
            "${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}"
    done <<< "$READINESS_TARGETS"
    exit 1
fi
echo "✅ Every gateway instance reported healthy — asserting readiness semantics..."

GATEWAY_COUNT=0
while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT; do
    [[ -z "$GATEWAY_SERVICE" ]] && continue
    GATEWAY_COUNT=$((GATEWAY_COUNT + 1))
    GATEWAY_MGMT_URL="${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}"

    GATEWAY_READY_OPTS=(-sf --connect-timeout 2 --max-time 5)
    if [[ "$GATEWAY_MGMT_SCHEME" == "https" ]]; then
        # -k is load-bearing on the HTTPS instances: their management interface serves a self-signed
        # localhost bundle, and without it curl fails certificate validation and this assertion fails
        # against a perfectly healthy container. -f is equally load-bearing: /q/health/ready answers
        # 503 when the check is DOWN, and without -f curl exits 0 on that 503.
        GATEWAY_READY_OPTS+=(-k)
    fi

    if ! curl "${GATEWAY_READY_OPTS[@]}" "${GATEWAY_MGMT_URL}/q/health/ready" > /dev/null 2>&1; then
        echo "❌ ${GATEWAY_SERVICE} reported healthy but ${GATEWAY_MGMT_URL}/q/health/ready is not UP"
        capture_gateway_diagnostics "${GATEWAY_SERVICE}" "${GATEWAY_MGMT_URL}"
        exit 1
    fi
    echo "✅ ${GATEWAY_SERVICE} gateway instance is ready!"
done <<< "$READINESS_TARGETS"

TOTAL_TIME=$(($(date +%s) - START_TIME))
echo "✅ All ${GATEWAY_COUNT} gateway instances are ready!"
# The instances were all started by the same `compose up -d` above and come up concurrently, so this
# brackets both layers of the gate: the wall-clock time until the slowest of them reported healthy and
# then answered ready, not the sum of their startup times.
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
