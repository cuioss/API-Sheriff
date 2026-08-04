#!/bin/bash

# Keycloak Container Log Dumping Script
# Usage: ./dump-keycloak-logs.sh <target-directory>
# Example: ./dump-keycloak-logs.sh target
#
# Note: Quarkus logs are written by default to target/quarkus-logs/quarkus.log via file logging
#       (this script only dumps the Keycloak container logs to the <target-directory> argument)

set -euo pipefail

# Configuration
#
# Compose names every container "<project>-<service>-<index>", and the project name is
# COMPOSE_PROJECT_NAME when set, otherwise the basename of the Compose project directory — the same
# resolution start-integration-container.sh relies on when it brings the stack up from PROJECT_DIR.
# Deriving the prefix here rather than hardcoding "integration-tests-" is what keeps this script
# working when a run sets COMPOSE_PROJECT_NAME; the hardcoded form silently found no container and
# dumped nothing at all.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_PROJECT="${COMPOSE_PROJECT_NAME:-$(basename "$PROJECT_DIR")}"

KEYCLOAK_CONTAINER_NAME="${COMPOSE_PROJECT}-keycloak-1"
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
KEYCLOAK_LOG_FILENAME="keycloak-logs-${TIMESTAMP}.txt"

# Parameter validation
if [ $# -ne 1 ]; then
    echo "❌ Error: Target directory parameter required"
    echo "Usage: $0 <target-directory>"
    echo "Example: $0 target"
    exit 1
fi

TARGET_DIR="$1"

# Create target directory if it doesn't exist
if [ ! -d "$TARGET_DIR" ]; then
    echo "📁 Creating target directory: $TARGET_DIR"
    mkdir -p "$TARGET_DIR"
fi

# Resolve absolute path for clarity
TARGET_ABS_PATH=$(cd "$TARGET_DIR" && pwd)
KEYCLOAK_LOG_FILE_PATH="${TARGET_ABS_PATH}/${KEYCLOAK_LOG_FILENAME}"

echo "🚀 Dumping Keycloak container logs..."
echo "📦 Keycloak container: $KEYCLOAK_CONTAINER_NAME"
echo "📝 Output file: $KEYCLOAK_LOG_FILE_PATH"

# Best-effort dump of the api-sheriff app containers into failsafe-reports (uploaded as a CI
# artifact) so a TEST failure — not just a startup failure — is diagnosable from the app's stdout.
# Never fail the build on a dump problem.
#
# EVERY gateway instance must be dumped, not just the primary and the mTLS peer: the Bff*Cookie*IT
# suites drive the two dedicated cookie-mode instances, WebSocketProxyIT's relay-exhaustion
# regression drives the low-admission-budget instance, and a CI-only failure on those instances
# previously produced NO uploaded log at all, forcing a local repro to see the gateway's own
# rejection reason. An admission refusal in particular is a bare 503 on the wire whose reason exists
# only in the gateway's own log.
#
# The set is DERIVED from what is actually running rather than restated here. It used to be a
# hardcoded list of seven container names under a comment instructing the reader to keep it in
# lockstep with the api-sheriff* services in docker-compose.yml — and it had already drifted: the
# plain-mgmt instance was missing from it until PLAN-46 despite having shipped earlier, so its
# boot-time downgrade WARN (ApiSheriff-115) was silently undumped. A hardcoded list that must mirror
# a set defined elsewhere is a defect unless it is derived from that source, exactly as
# start-integration-container.sh derives its readiness targets from the resolved Compose model, so
# adding, removing or renaming an api-sheriff* service now needs no edit here.
#
# The match is anchored to THIS project's own container prefix, so a foreign stack's api-sheriff
# containers (a compose-sample or demo-client environment running on the same host) can never be
# swept in, and no non-gateway service of this project can either — no other service name in
# docker-compose.yml begins with "api-sheriff".
FAILSAFE_DIR="${TARGET_ABS_PATH}/failsafe-reports"
mkdir -p "$FAILSAFE_DIR" || true
APP_CONTAINERS=$(docker ps -a --format "{{.Names}}" \
    | grep -E "^${COMPOSE_PROJECT}-api-sheriff(-[a-z0-9-]+)?-[0-9]+$" || true)
if [ -z "$APP_CONTAINERS" ]; then
    echo "⚠️  No ${COMPOSE_PROJECT}-api-sheriff* containers found — no app logs to dump"
fi
while read -r app; do
    [ -z "$app" ] && continue
    echo "📥 Dumping app logs: ${app} -> ${FAILSAFE_DIR}/${app}.log"
    docker logs "$app" > "${FAILSAFE_DIR}/${app}.log" 2>&1 || true
done <<< "$APP_CONTAINERS"

# Check if container exists and is running
if ! docker ps --format "{{.Names}}" | grep -q "^${KEYCLOAK_CONTAINER_NAME}$"; then
    if docker ps -a --format "{{.Names}}" | grep -q "^${KEYCLOAK_CONTAINER_NAME}$"; then
        echo "⚠️  Warning: Container $KEYCLOAK_CONTAINER_NAME exists but is not running"
        echo "📋 Attempting to dump logs from stopped container..."
    else
        echo "❌ Error: Container $KEYCLOAK_CONTAINER_NAME not found"
        echo "🔍 Available containers:"
        docker ps -a --format "table {{.Names}}\t{{.Status}}"
        exit 1
    fi
else
    echo "✅ Container is running"
fi

# Dump logs
echo "📥 Dumping Keycloak logs..."
if docker logs "$KEYCLOAK_CONTAINER_NAME" > "$KEYCLOAK_LOG_FILE_PATH" 2>&1; then
    LOG_SIZE=$(wc -l < "$KEYCLOAK_LOG_FILE_PATH")
    FILE_SIZE=$(du -h "$KEYCLOAK_LOG_FILE_PATH" | cut -f1)
    echo "✅ Successfully dumped $LOG_SIZE lines ($FILE_SIZE)"
    echo "📍 Full path: $KEYCLOAK_LOG_FILE_PATH"
    echo "🎉 Keycloak logs successfully dumped!"
    exit 0
else
    echo "❌ Failed to dump logs from container: $KEYCLOAK_CONTAINER_NAME"
    exit 1
fi
