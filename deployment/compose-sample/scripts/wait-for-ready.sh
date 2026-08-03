#!/bin/bash
# Block until the API Sheriff compose sample is READY to serve, or fail loudly with the diagnostics
# needed to find out why it is not.
#
# The whole probe target is DERIVED from the resolved Compose model — never restated here (ADR-0031):
#
#   * WHICH services to probe   the services carrying the de.cuioss.sheriff.management-scheme label
#   * WHICH scheme to speak     that label's value, http or https
#   * WHICH host port to hit    the port published against the fixed container-port selector 9000
#
# Only the container-side management port is a literal, and deliberately so: it is a stable
# platform-level fact used as the SELECTOR that picks the management binding out of a service's port
# list, not a per-deployment value. Renumbering the published port in docker-compose.yml, or adding a
# second gateway instance, therefore needs no edit to this script.
#
# It probes READINESS, not liveness. /q/health/live answers as soon as the process is up, which is
# strictly earlier than the point at which the gateway can actually serve a request — gating on it
# would clear this wait before the stack is usable, which is the exact race this script exists to
# remove.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_DIR="$(dirname "${SCRIPT_DIR}")"

# The retry budget, as ONE constant read by every site that needs it — the loop bound, the
# last-attempt comparison and the progress message. Three literals would drift apart; one cannot.
#
# 60 attempts at 1s. The measured worst case for this stack is the native gateway reaching readiness
# a few seconds after Keycloak reports its management port bound; 60s is roughly a 5x headroom over
# that on a machine under load. Re-measure before shrinking it — a budget that is too small turns a
# slow machine into a spurious failure, which is worse than waiting.
READY_ATTEMPTS=60

SCHEME_LABEL="de.cuioss.sheriff.management-scheme"
MANAGEMENT_CONTAINER_PORT="9000"

cd "${SAMPLE_DIR}"

if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
else
    echo "❌ Docker Compose not available (neither 'docker compose' nor 'docker-compose')"
    exit 1
fi

echo "⏳ Deriving the readiness probe targets from the Compose model..."
if ! TARGETS="$("${COMPOSE_CMD[@]}" config --format json | python3 -c '
import json
import sys

SCHEME_LABEL = sys.argv[1]
MANAGEMENT_CONTAINER_PORT = sys.argv[2]

try:
    model = json.load(sys.stdin)
except ValueError as exc:
    sys.exit("could not parse the resolved Compose model as JSON (%s). This script needs a Compose "
             "version supporting `config --format json`." % exc)

services = model.get("services") or {}

rows = []
problems = []
for name, spec in sorted(services.items()):
    labels = spec.get("labels") or {}
    scheme = labels.get(SCHEME_LABEL)
    if scheme is None:
        # Not a probe target. A service without the label is not part of the readiness contract --
        # that is how the IdP and the demo upstream stay out of this loop without being named here.
        continue
    if scheme not in ("http", "https"):
        problems.append("%s: invalid %s label (got %r, expected http or https)"
                        % (name, SCHEME_LABEL, scheme))
        continue
    published = [port.get("published") for port in (spec.get("ports") or [])
                 if str(port.get("target")) == MANAGEMENT_CONTAINER_PORT and port.get("published")]
    if len(published) != 1:
        problems.append("%s: expected exactly one host port published against the management "
                        "container port %s, found %r" % (name, MANAGEMENT_CONTAINER_PORT, published))
        continue
    rows.append("%s %s %s" % (name, scheme, published[0]))

if problems:
    sys.exit("readiness target discovery failed:\n  " + "\n  ".join(problems))

if not rows:
    # An empty target set must be a HARD failure. Passing here would report the stack ready without
    # having probed anything at all -- a vacuous green, and the most dangerous outcome available.
    sys.exit("readiness target discovery found no service carrying the %s label. At least one is "
             "required, or this gate proves nothing." % SCHEME_LABEL)

sys.stdout.write("\n".join(rows) + "\n")
' "${SCHEME_LABEL}" "${MANAGEMENT_CONTAINER_PORT}")"; then
    echo "❌ Could not derive the readiness targets from docker-compose.yml (see above)"
    exit 1
fi

while read -r SERVICE SCHEME PORT; do
    [[ -z "$SERVICE" ]] && continue

    # -f is load-bearing: the readiness endpoint answers 503 while the gateway is still starting, and
    # WITHOUT -f curl exits 0 on that 503 -- so the wait would clear the moment the port ACCEPTED a
    # connection rather than when the gateway was ready, reintroducing the very race this gate removes.
    PROBE_OPTS=(-sf --connect-timeout 2 --max-time 5)
    DIAG_OPTS=(-s --connect-timeout 2 --max-time 5)
    if [[ "$SCHEME" == "https" ]]; then
        # -k ONLY on https, and only because the sample's certificate is self-signed. Without it curl
        # fails certificate validation and this wait degrades into a silent full-budget timeout
        # against a perfectly healthy container. It is applied from the DERIVED scheme, so a service
        # whose management interface is plain HTTP is probed without it and no branch on the service
        # name is needed.
        PROBE_OPTS+=(-k)
        DIAG_OPTS+=(-k)
    fi
    MGMT_URL="${SCHEME}://localhost:${PORT}"

    echo "⏳ Waiting for ${SERVICE} to be ready (management ${SCHEME} on ${PORT})..."
    for ((i = 1; i <= READY_ATTEMPTS; i++)); do
        if curl "${PROBE_OPTS[@]}" "${MGMT_URL}/q/health/ready" > /dev/null 2>&1; then
            echo "✅ ${SERVICE} is ready!"
            break
        fi
        if [ "$i" -eq "$READY_ATTEMPTS" ]; then
            echo "❌ ${SERVICE} did not become ready within ${READY_ATTEMPTS} attempts"
            # Print the container log and the full health payload. A readiness failure is almost
            # always explained by one of these two, and a developer should not have to know which
            # commands to run next.
            echo "----- ${COMPOSE_CMD[*]} logs ${SERVICE} -----"
            "${COMPOSE_CMD[@]}" logs --no-color "${SERVICE}" </dev/null 2>&1 || true
            echo "----- ${MGMT_URL}/q/health -----"
            curl "${DIAG_OPTS[@]}" "${MGMT_URL}/q/health" 2>&1 || true
            echo ""
            exit 1
        fi
        echo "⏳ Waiting for ${SERVICE}... (attempt $i/${READY_ATTEMPTS})"
        sleep 1
    done
done <<< "$TARGETS"

echo ""
echo "🎉 The API Sheriff compose sample is ready."
echo "   Try it:  curl -k https://localhost:8443/api"
