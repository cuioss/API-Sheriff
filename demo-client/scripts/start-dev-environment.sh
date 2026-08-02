#!/bin/bash
# Bring up the TRIMMED three-container stack the demo suite needs: keycloak, api-sheriff and
# api-sheriff-cookie — and nothing else.
#
# This script starts a SUBSET of integration-tests/docker-compose.yml. It declares no stack of its
# own and changes nothing in that file: a second compose stack would be a second thing to keep in
# sync with the gateway configuration, and the two would drift.
#
# ---------------------------------------------------------------------------------------------
# STANDING PROHIBITION — a Compose profile cannot express the trimmed bring-up
#
#   A Compose profile cannot express a trimmed bring-up, because a service with no `profiles:` key
#   always starts and profiles therefore only ever ADD to the default set.
#
# Subtracting the other nine services would require tagging THEM with a profile, which would have
# two consequences in integration-tests:
#
#   * they would drop out of the unqualified `compose up -d` that
#     integration-tests/scripts/start-integration-container.sh relies on (that script's line
#     `(cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d)`); and
#   * they would disappear from `compose config --format json`, which that same script's
#     gateway-readiness discovery enumerates — and which hard-exits when the resulting set is empty.
#
# The demo therefore uses EXPLICIT SERVICE SELECTION. It delivers the identical three-container
# footprint with ZERO structural change to docker-compose.yml, which is strictly more minimal and
# carries no risk to the landed integration-test suite. Consequently this module adds no `profiles:`
# key to docker-compose.yml and touches neither of the two lines above.
#
# DO NOT restore the profile approach.
#
# The one refinement over a bare service list: `--no-deps` on the gateway bring-up. Both gateway
# services declare `depends_on: [keycloak, go-httpbin, asset-origin]`, so an unqualified
# `up -d keycloak api-sheriff api-sheriff-cookie` would also start go-httpbin and asset-origin —
# five containers, not three. The demo drives only the gateway's reserved /auth/* paths and the
# /assets/demo/* directory asset source, neither of which touches those two upstreams, and Keycloak
# (the one dependency that IS load-bearing, for JWKS) is started and waited on explicitly below
# before the gateways come up. `--no-deps` is what makes the three-container footprint real; it is
# still explicit service selection and it still changes nothing in docker-compose.yml.
# ---------------------------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(cd "${MODULE_DIR}/.." && pwd)"
IT_DIR="${ROOT_DIR}/integration-tests"
IT_SCRIPT_DIR="${IT_DIR}/scripts"

# The exact three services the demo needs. This list is the deliverable's whole mechanism.
DEMO_IDP_SERVICE="keycloak"
DEMO_GATEWAY_SERVICES=(api-sheriff api-sheriff-cookie)

# Reuse — never reimplement — the integration-tests helpers.
# shellcheck source=../../integration-tests/scripts/lib-docker-compose.sh
source "${IT_SCRIPT_DIR}/lib-docker-compose.sh"

echo "🚀 Starting the trimmed API Sheriff demo stack (${DEMO_IDP_SERVICE} + ${DEMO_GATEWAY_SERVICES[*]})"

COMPOSE_BASE="$(resolve_compose_cmd || true)"
if [[ -z "$COMPOSE_BASE" ]]; then
    echo "❌ Docker Compose not available (neither 'docker compose' nor 'docker-compose')"
    exit 1
fi
if ! docker_daemon_up; then
    echo "❌ Docker daemon not running — start Docker/Rancher Desktop first"
    exit 1
fi

# Build the native executable only if it is missing. Reused wholesale from integration-tests; the
# demo has no native-build logic of its own.
"${IT_SCRIPT_DIR}/build-native-if-needed.sh"

cd "${IT_DIR}"
COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml"

# Rebuild the image from the (possibly just-rebuilt) native executable. This is LOAD-BEARING:
# `compose up` alone silently reuses a stale image, so a native fix appears not to take effect and
# the suite fails against code that is no longer on disk. Same shape as integration-tests/pom.xml's
# docker-build-distroless execution. api-sheriff-cookie runs the SAME api-sheriff:distroless image,
# so building the one service covers both gateways.
echo "🐳 Rebuilding the api-sheriff image from the native executable..."
export DOCKER_BUILDKIT=1
$COMPOSE_CMD build api-sheriff

# Quarkus file logging writes to the bind-mounted /logs. The container runs as uid 1001 while this
# host directory is created by the (differently-numbered) build user, so without a world-writable
# dedicated subdirectory the file sink fails with "FileNotFoundException: /logs/quarkus.log
# (Permission denied)". Grant world write on that subdirectory ONLY — least privilege, ephemeral
# test output — exactly as integration-tests/scripts/start-integration-container.sh does. The
# container keeps its no-new-privileges / cap_drop / read_only posture.
LOG_TARGET_ROOT="${LOG_TARGET_DIR:-${IT_DIR}/target}"
export LOG_TARGET_DIR="${LOG_TARGET_ROOT}/quarkus-logs"
mkdir -p "${LOG_TARGET_DIR}"
chmod 0777 "${LOG_TARGET_DIR}"
echo "📁 Quarkus logs will be written to: ${LOG_TARGET_DIR}/quarkus.log"

# Keycloak FIRST, and READY, before either gateway starts. The native app eagerly loads the realm's
# JWKS at boot; if it starts before Keycloak can answer, that load fails and — with a long
# background-refresh interval — the issuer stays unhealthy for the whole run, so every login's token
# validation fails with "No healthy issuer configuration found". Gating the gateway start on a ready
# Keycloak removes the race.
echo "🐳 Starting ${DEMO_IDP_SERVICE} first (the gateways start only after it is ready)..."
$COMPOSE_CMD up -d "${DEMO_IDP_SERVICE}"

echo "⏳ Waiting for ${DEMO_IDP_SERVICE} to be ready..."
for i in {1..120}; do
    if curl -k -s --connect-timeout 2 --max-time 5 https://localhost:1090/health/ready > /dev/null 2>&1; then
        echo "✅ ${DEMO_IDP_SERVICE} is ready!"
        break
    fi
    if [ "$i" -eq 120 ]; then
        echo "❌ ${DEMO_IDP_SERVICE} failed to become ready within 120 attempts"
        echo "Check logs with: ${COMPOSE_CMD} logs ${DEMO_IDP_SERVICE}"
        exit 1
    fi
    echo "⏳ Waiting for ${DEMO_IDP_SERVICE}... (attempt $i/120)"
    sleep 1
done

# The trimmed bring-up itself — explicit service selection, --no-deps (see the standing prohibition
# in the header for both halves of that decision).
echo "🐳 Starting ONLY ${DEMO_GATEWAY_SERVICES[*]} (no other stack service is touched)..."
$COMPOSE_CMD up -d --no-deps "${DEMO_GATEWAY_SERVICES[@]}"

# Each gateway's management scheme and published port are DERIVED from the resolved Compose model,
# never restated here. docker-compose.yml owns both — the `de.cuioss.sheriff.management-scheme`
# label and the host port published against container port 9000 — and a list in this file that had
# to mirror them would be a defect the moment either changed. This is the same derivation
# integration-tests/scripts/start-integration-container.sh performs, narrowed to the demo's two
# named services instead of the api-sheriff* prefix.
echo "⏳ Discovering the demo gateways' readiness targets from the Compose model..."
if ! READINESS_TARGETS="$($COMPOSE_CMD config --format json | python3 -c '
import json
import sys

SCHEME_LABEL = "de.cuioss.sheriff.management-scheme"
MANAGEMENT_CONTAINER_PORT = "9000"

wanted = sys.argv[1:]

try:
    model = json.load(sys.stdin)
except ValueError as exc:
    sys.exit("could not parse the resolved Compose model as JSON (%s). This script needs a Compose "
             "version supporting `config --format json`." % exc)

services = model.get("services") or {}

rows = []
problems = []
for name in wanted:
    spec = services.get(name)
    if spec is None:
        problems.append("%s: not present in the resolved Compose model" % name)
        continue
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
    sys.exit("demo gateway readiness discovery failed:\n  " + "\n  ".join(problems))

sys.stdout.write("\n".join(rows) + "\n")
' "${DEMO_GATEWAY_SERVICES[@]}")"; then
    echo "❌ Could not derive the demo gateways' readiness targets from docker-compose.yml (see above)"
    exit 1
fi

echo "⏳ Waiting for the demo gateway instances to be ready..."
while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT; do
    [[ -z "$GATEWAY_SERVICE" ]] && continue

    GATEWAY_PROBE_OPTS=(-sf --connect-timeout 2 --max-time 5)
    GATEWAY_DIAG_OPTS=(-s --connect-timeout 2 --max-time 5)
    if [[ "$GATEWAY_MGMT_SCHEME" == "https" ]]; then
        # -k is load-bearing on an HTTPS management interface: it serves a self-signed localhost
        # bundle, and without it curl fails certificate validation and this wait degrades into a
        # silent 30-attempt timeout against a perfectly healthy container.
        GATEWAY_PROBE_OPTS+=(-k)
        GATEWAY_DIAG_OPTS+=(-k)
    fi
    GATEWAY_MGMT_URL="${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}"

    echo "⏳ Waiting for ${GATEWAY_SERVICE} (management ${GATEWAY_MGMT_SCHEME} on ${GATEWAY_MGMT_PORT})..."
    for i in {1..30}; do
        if curl "${GATEWAY_PROBE_OPTS[@]}" "${GATEWAY_MGMT_URL}/q/health/live" > /dev/null 2>&1; then
            echo "✅ ${GATEWAY_SERVICE} is ready!"
            break
        fi
        if [ "$i" -eq 30 ]; then
            echo "❌ ${GATEWAY_SERVICE} failed to start within 30 attempts"
            # Capture the container log + health payload so a startup failure is diagnosable from
            # the CI artifacts rather than only from a lost console.
            DIAG_DIR="${MODULE_DIR}/target/test-results"
            mkdir -p "$DIAG_DIR"
            echo "----- $COMPOSE_CMD logs ${GATEWAY_SERVICE} -----"
            $COMPOSE_CMD logs --no-color "${GATEWAY_SERVICE}" </dev/null 2>&1 | tee "$DIAG_DIR/${GATEWAY_SERVICE}-app.log"
            echo "----- ${GATEWAY_MGMT_URL}/q/health -----"
            curl "${GATEWAY_DIAG_OPTS[@]}" "${GATEWAY_MGMT_URL}/q/health" 2>&1 | tee "$DIAG_DIR/${GATEWAY_SERVICE}-health.json"
            echo ""
            exit 1
        fi
        echo "⏳ Waiting for ${GATEWAY_SERVICE}... (attempt $i/30)"
        sleep 1
    done
done <<< "$READINESS_TARGETS"

echo ""
echo "🎉 The trimmed demo stack is running — three containers:"
$COMPOSE_CMD ps --format "table {{.Service}}\t{{.State}}" "${DEMO_IDP_SERVICE}" "${DEMO_GATEWAY_SERVICES[@]}"
echo ""
echo "📱 Demo entry points (the SPA is served BY the gateway, so it is same-origin with /auth/*):"
echo "  🖥️  server-session gateway: https://localhost:10443/assets/demo/index.html"
echo "  🍪 cookie-session gateway: https://localhost:10445/assets/demo/index.html"
echo "  🔑 Keycloak:               https://localhost:1443/auth"
echo ""
echo "🧪 Run the suite:  cd demo-client && npx playwright test"
echo "🛑 To stop:        demo-client/scripts/stop-dev-environment.sh"
