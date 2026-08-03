#!/bin/bash
# Bring the API Sheriff compose sample up and hand off to the readiness gate.
#
# Three steps, in this order and no others:
#
#   1. PREFLIGHT the gateway image, so a missing image fails in seconds with an actionable message
#      instead of as a compose pull error buried under three service startups.
#   2. BRING THE STACK UP.
#   3. DELEGATE to wait-for-ready.sh, which owns the readiness contract (ADR-0031). This script
#      derives no probe URL, names no port and hard-codes no scheme — restating any of them here
#      would be a second copy to keep in lockstep with docker-compose.yml.
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
# derive-don't-restate rule wait-for-ready.sh follows for the readiness probe (ADR-0031).
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

# ---- 2. Bring the stack up ---------------------------------------------------------------------
echo "🐳 Starting the compose sample (api-sheriff, keycloak, demo-api)..."
"${COMPOSE_CMD[@]}" up -d

# ---- 3. Hand off to the readiness gate ---------------------------------------------------------
# exec, so the gate's exit code IS this script's exit code and the Maven execution above it fails
# when the stack does not become ready.
exec "${SCRIPT_DIR}/wait-for-ready.sh"
