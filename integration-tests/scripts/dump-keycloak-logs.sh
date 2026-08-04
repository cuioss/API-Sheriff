#!/bin/bash

# Keycloak Container Log Dumping Script
# Usage: ./dump-keycloak-logs.sh <target-directory>
# Example: ./dump-keycloak-logs.sh target
#
# Note: Quarkus logs are written by default to target/quarkus-logs/quarkus.log via file logging
#       (this script only dumps the Keycloak container logs to the <target-directory> argument)

set -euo pipefail

# Configuration
KEYCLOAK_CONTAINER_NAME="integration-tests-keycloak-1"
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
# The list MUST name every gateway instance docker-compose.yml starts, not just the primary and the
# mTLS peer: the Bff*Cookie*IT suites drive the two dedicated cookie-mode instances and
# WebSocketProxyIT's relay-exhaustion regression drives the low-admission-budget instance, and a
# CI-only failure on those instances previously produced NO uploaded log at all, forcing a local
# repro to see the gateway's own rejection reason. An admission refusal in particular is a bare 503
# on the wire whose reason exists only in the gateway's own log. Keep this list in lockstep with the
# api-sheriff* services in integration-tests/docker-compose.yml — all SEVEN of them are named below.
#
# The plain-mgmt instance was missing from this list until PLAN-46 despite having shipped earlier;
# its boot-time downgrade WARN (ApiSheriff-115) is exactly the kind of evidence that exists only in
# the container's own output, so its absence here was a real diagnosability hole rather than a
# harmless omission. The passthrough-empty instance is the seventh, added with the benchmark's
# empty-mode arm.
FAILSAFE_DIR="${TARGET_ABS_PATH}/failsafe-reports"
mkdir -p "$FAILSAFE_DIR" || true
for app in integration-tests-api-sheriff-1 \
           integration-tests-api-sheriff-mtls-1 \
           integration-tests-api-sheriff-cookie-1 \
           integration-tests-api-sheriff-cookie-2-1 \
           integration-tests-api-sheriff-ws-admission-1 \
           integration-tests-api-sheriff-plain-mgmt-1 \
           integration-tests-api-sheriff-passthrough-empty-1; do
    if docker ps -a --format "{{.Names}}" | grep -q "^${app}$"; then
        echo "📥 Dumping app logs: ${app} -> ${FAILSAFE_DIR}/${app}.log"
        docker logs "$app" > "${FAILSAFE_DIR}/${app}.log" 2>&1 || true
    fi
done

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
