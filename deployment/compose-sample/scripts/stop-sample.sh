#!/bin/bash
# Tear the API Sheriff compose sample down completely.
#
# `down -v --remove-orphans` — and unlike demo-client/scripts/stop-dev-environment.sh, that full
# teardown is the RIGHT call here. That script must select services by name because it borrows
# integration-tests/docker-compose.yml, a stack somebody else may also be running. This sample owns
# its own compose file and therefore its own Compose project: everything `down` reaches is something
# this sample started, so there is no collateral damage to avoid.
#
#   -v                removes the keycloak-data volume, so the next bring-up re-imports the realm
#                     from scratch. A sample should start from a known state every time; persisting
#                     it across runs is what makes "it worked yesterday" hard to reproduce.
#   --remove-orphans  clears containers left behind by an earlier revision of the compose file.
#
# Runs at Maven's pre-clean and post-integration-test phases as best-effort cleanup, so a host with
# no Docker at all exits cleanly rather than failing the build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_DIR="$(dirname "${SCRIPT_DIR}")"

if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
else
    echo "ℹ️  Docker Compose not available — nothing to stop, skipping cleanup."
    exit 0
fi

if ! docker info >/dev/null 2>&1; then
    echo "ℹ️  Docker daemon not running — nothing to stop, skipping cleanup."
    exit 0
fi

cd "${SAMPLE_DIR}"

# Nothing running is the normal case (an aborted run may have left nothing behind), not a failure.
echo "🛑 Stopping the API Sheriff compose sample..."
"${COMPOSE_CMD[@]}" down -v --remove-orphans

echo "✅ Compose sample stopped; its containers, network and volume are removed."
