#!/bin/bash
# Bring the API Sheriff compose sample up and gate on it actually being ready to serve.
#
# Four steps, in this order and no others:
#
#   1. PREFLIGHT the gateway image, so a missing image fails in seconds with an actionable message
#      instead of as a compose pull error buried under three service startups.
#   2. DERIVE the readiness probe targets from the resolved Compose model, BEFORE anything starts —
#      a model this script cannot read is a failure worth having in two seconds rather than after
#      Keycloak has booted, and the derived rows are what the diagnostics in step 3 report against.
#   3. WAIT — gate layer 1: `up -d --wait`, which blocks on each service's health: the gateway's
#      baked HEALTHCHECK for api-sheriff, Keycloak's declared one, and plain "running" for the demo
#      upstream, which declares none. Unscoped is correct here — that is the right meaning for each
#      of the three services this sample runs.
#   4. ASSERT — gate layer 2: one single-shot /q/health/ready per derived target. The baked probe is
#      a bare TCP accept on the management port — it must be, because the management scheme is
#      deployment-bound (ADR-0025) — so it proves the interface is listening, not that the gateway
#      reported READY. This assertion closes that gap, and needs no retry budget precisely because
#      step 3 already blocked on health: a non-UP answer here is a real defect, and retrying would
#      only delay reporting it.
#
# The readiness contract itself is still derived, never restated (ADR-0031): WHICH services to probe
# comes from the de.cuioss.sheriff.management-scheme label, WHICH scheme from that label's value, and
# WHICH host port from the port published against the container-port selector 9000. Only that
# container-side selector is a literal, and deliberately so — it is a stable platform-level fact used
# to pick the management binding out of a service's port list, not a per-deployment value. Renumbering
# a published port, or adding a second gateway instance, needs no edit here.
#
# Invoked by the deployment module's opt-in `compose-sample` Maven profile at pre-integration-test,
# and directly by an operator following doc/user/compose-sample.adoc. Both paths are the same path.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_DIR="$(dirname "${SCRIPT_DIR}")"

cd "${SAMPLE_DIR}"

if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
else
    echo "❌ Docker Compose not available (neither 'docker compose' nor 'docker-compose')"
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker daemon not running — start Docker/Rancher Desktop first"
    exit 1
fi

# The TLS material is generated, never committed (docker/certificates/.gitignore). Both Keycloak and
# the gateway mount it read-only, so without it Keycloak crash-loops and the readiness gate below
# burns its whole budget against a stack that was never going to come up. Checking for it here turns
# that into one line naming the one-time command that fixes it.
if [[ ! -f "${SAMPLE_DIR}/docker/certificates/localhost.crt" \
   || ! -f "${SAMPLE_DIR}/docker/certificates/localhost.key" ]]; then
    echo "❌ The sample's TLS material is missing."
    echo "   docker/certificates/localhost.crt and localhost.key are generated, never committed."
    echo "   Generate them once, then re-run this script:"
    echo "     ./docker/certificates/generate-certificates.sh"
    exit 1
fi

# ---- 1. Image preflight ------------------------------------------------------------------------
# The effective gateway image is DERIVED from the resolved Compose model, never restated here. The
# default lives once in .env; an exported API_SHERIFF_IMAGE overrides it. Compose has already
# applied that precedence by the time it answers `config`, so reading the answer back is the only
# way this preflight and the stack that actually starts cannot disagree — the same
# derive-don't-restate rule step 2 below follows for the readiness probe (ADR-0031).
#
# This must sit AFTER the COMPOSE_CMD block above: it dereferences that array.
if ! IMAGE_REF="$("${COMPOSE_CMD[@]}" config --format json | python3 -c '
import json, sys

try:
    model = json.load(sys.stdin)
except ValueError as exc:
    sys.exit("could not parse the resolved Compose model as JSON (%s). This script needs a Compose "
             "version supporting `config --format json`." % exc)

image = ((model.get("services") or {}).get("api-sheriff") or {}).get("image")
if not image:
    # Empty means API_SHERIFF_IMAGE resolved to nothing -- .env is missing or was emptied. Failing
    # here is the point: falling through would preflight the empty string and then hand compose a
    # service with no image at all.
    sys.exit("the resolved Compose model carries no image for service api-sheriff. Check that "
             ".env sets API_SHERIFF_IMAGE, or export it explicitly.")

sys.stdout.write(image)
')"; then
    echo "❌ Could not derive the gateway image from docker-compose.yml (see above)"
    exit 1
fi

# Present locally is the common case for a local-build override; absent means exactly ONE pull
# attempt. A pull that fails is terminal — falling through to `compose up` would surface the same
# failure again, later, as a less legible error against a half-started stack.
echo "🔎 Checking for the gateway image ${IMAGE_REF}..."
if docker image inspect "${IMAGE_REF}" >/dev/null 2>&1; then
    echo "✅ Found ${IMAGE_REF} locally."
else
    echo "⏬ Not present locally — pulling ${IMAGE_REF} (one attempt)..."
    if ! docker pull "${IMAGE_REF}"; then
        echo ""
        echo "❌ The gateway image ${IMAGE_REF} is neither present locally nor pullable."
        echo ""
        echo "   Two routes, pick one:"
        echo ""
        echo "   (a) PUBLISHED IMAGE — the sample's default. Check the reference and your network"
        echo "       access to ghcr.io, then re-run. If that release does not exist yet, point"
        echo "       API_SHERIFF_IMAGE at one that does."
        echo ""
        echo "   (b) LOCAL BUILD — build the gateway yourself and point the sample at the result:"
        echo "         python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args \"clean install -Pnative -pl api-sheriff -am -DskipTests\""
        echo "         docker compose -f integration-tests/docker-compose.yml build api-sheriff"
        echo "         export API_SHERIFF_IMAGE=api-sheriff:distroless"
        echo "       Both commands run from the repository root."
        echo ""
        exit 1
    fi
fi

# ---- 2. Derive the readiness probe targets ------------------------------------------------------
# Runs BEFORE the bring-up: a model this script cannot read should fail in two seconds, and the rows
# it produces are what the diagnostics below report against when the wait itself fails.
SCHEME_LABEL="de.cuioss.sheriff.management-scheme"
MANAGEMENT_CONTAINER_PORT="9000"

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

# Print the container log and the full health payload. A readiness failure is almost always explained
# by one of these two, and an operator should not have to know which commands to run next. Both gate
# layers below call this, so the evidence is identical whichever one caught the problem.
capture_sample_diagnostics() {
    local service="$1"
    local mgmt_url="$2"
    local diag_opts=(-s --connect-timeout 2 --max-time 5)

    # -k ONLY on https, and only because the sample's certificate is self-signed. Without it curl
    # fails certificate validation and writes nothing — destroying the payload this exists to show.
    if [[ "$mgmt_url" == https://* ]]; then
        diag_opts+=(-k)
    fi

    echo "----- ${COMPOSE_CMD[*]} logs ${service} -----"
    "${COMPOSE_CMD[@]}" logs --no-color "${service}" </dev/null 2>&1 || true
    echo "----- ${mgmt_url}/q/health -----"
    curl "${diag_opts[@]}" "${mgmt_url}/q/health" 2>&1 || true
    echo ""
}

# ---- 3. Gate layer 1: wait on the declared health signals ---------------------------------------
# `--wait` blocks until every service is healthy, or running where it declares no healthcheck. For
# api-sheriff that is the HEALTHCHECK baked into Dockerfile.native, so the polling this script used to
# hand-roll is now the container runtime's job and the probe cadence has exactly one home.
#
# The `if ! ...; then` wrapper is required rather than stylistic: set -e is active, so a bare failing
# command would abort before the diagnostics could run.
echo "🐳 Starting the compose sample (api-sheriff, keycloak, demo-api)..."
if ! "${COMPOSE_CMD[@]}" up -d --wait --wait-timeout 120; then
    echo "❌ The compose sample did not report healthy within 120s"
    while read -r SERVICE SCHEME PORT; do
        [[ -z "$SERVICE" ]] && continue
        capture_sample_diagnostics "$SERVICE" "${SCHEME}://localhost:${PORT}"
    done <<< "$TARGETS"
    exit 1
fi

# ---- 4. Gate layer 2: assert readiness semantics ------------------------------------------------
while read -r SERVICE SCHEME PORT; do
    [[ -z "$SERVICE" ]] && continue
    MGMT_URL="${SCHEME}://localhost:${PORT}"

    # -f is load-bearing: /q/health/ready answers 503 when the gateway is not READY, and WITHOUT -f
    # curl exits 0 on that 503 — so this assertion would pass on a gateway that is merely listening.
    # -k is applied from the DERIVED scheme, so a plain-HTTP management interface is probed without
    # it and no branch on the service name is needed.
    READY_OPTS=(-sf --connect-timeout 2 --max-time 5)
    if [[ "$SCHEME" == "https" ]]; then
        READY_OPTS+=(-k)
    fi

    if ! curl "${READY_OPTS[@]}" "${MGMT_URL}/q/health/ready" > /dev/null 2>&1; then
        echo "❌ ${SERVICE} reported healthy but ${MGMT_URL}/q/health/ready is not UP"
        capture_sample_diagnostics "$SERVICE" "$MGMT_URL"
        exit 1
    fi
    echo "✅ ${SERVICE} is ready!"
done <<< "$TARGETS"

echo ""
echo "🎉 The API Sheriff compose sample is ready."
echo "   Try it:  curl -k https://localhost:8443/api"
