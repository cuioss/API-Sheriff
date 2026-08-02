#!/bin/bash
# Tear down the trimmed demo stack — and ONLY it.
#
# The teardown mirrors start-dev-environment.sh's explicit service selection exactly: it stops and
# removes keycloak, api-sheriff and api-sheriff-cookie by name. It deliberately does NOT run
# `compose down`, which would tear down every service in integration-tests/docker-compose.yml —
# including containers a developer started by other means (a full
# integration-tests/scripts/start-integration-container.sh stack running alongside, say). Removing
# something this script did not start is not cleanup, it is collateral damage.
#
# For the same reason there is no `--remove-orphans` here: an "orphan" from this selection's point
# of view is simply somebody else's container.
#
# This runs at Maven's pre-clean and post-integration-test phases as best-effort cleanup, so a host
# with no Docker at all exits cleanly rather than failing the build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(cd "${MODULE_DIR}/.." && pwd)"
IT_DIR="${ROOT_DIR}/integration-tests"

# The SAME three services start-dev-environment.sh selects.
DEMO_SERVICES=(keycloak api-sheriff api-sheriff-cookie)

# shellcheck source=../../integration-tests/scripts/lib-docker-compose.sh
source "${IT_DIR}/scripts/lib-docker-compose.sh"

echo "🛑 Stopping the trimmed API Sheriff demo stack (${DEMO_SERVICES[*]})"

COMPOSE_BASE="$(resolve_compose_cmd || true)"
if [[ -z "$COMPOSE_BASE" ]]; then
    echo "ℹ️  Docker Compose not available — nothing to stop, skipping cleanup."
    exit 0
fi
if ! docker_daemon_up; then
    echo "ℹ️  Docker daemon not running — nothing to stop, skipping cleanup."
    exit 0
fi

cd "${IT_DIR}"
COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml"

# `rm -s -f` stops the named containers and removes them without prompting. Nothing running is the
# normal case (an aborted run may have left nothing behind), not a failure.
echo "📦 Stopping and removing the demo containers..."
$COMPOSE_CMD rm --stop --force "${DEMO_SERVICES[@]}"

# Report what — if anything — the selection left behind. Any container still up here belongs to
# somebody else's bring-up and is deliberately untouched.
REMAINING="$($COMPOSE_CMD ps --services --filter status=running || true)"
if [[ -z "$REMAINING" ]]; then
    echo "✅ Demo stack stopped — no compose container is running."
else
    echo "✅ Demo stack stopped. These containers were started by other means and are left alone:"
    echo "$REMAINING" | sed 's/^/  • /'
fi
