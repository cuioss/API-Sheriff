#!/bin/bash
set -e

# Script to build the native executable when it is MISSING or STALE.
# Targets the api-sheriff module (the deployable application)
#
# CALLER OBLIGATION — a native rebuild here obliges the caller to rebuild the Docker image.
# The image COPIES whatever runner is on disk at image-build time, so `compose up` alone silently
# reuses the stale image and a freshly built runner never reaches the container. Every caller must
# run `docker compose build api-sheriff` (or its profile equivalent) AFTER this script:
#   - demo-client/scripts/start-dev-environment.sh  -> `$COMPOSE_CMD build api-sheriff`
#   - integration-tests/pom.xml (-Pintegration-tests) -> exec:exec@docker-build-distroless
#   - integration-tests/pom.xml (-Pbenchmark, JFR)    -> exec:exec@docker-build-jfr
#   - benchmarks/pom.xml -> chains exec:exec@build-native-if-needed then @docker-build-distroless
# All four satisfy the obligation today; keep it that way when adding a caller.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(cd "${PROJECT_DIR}/.." && pwd)"
APP_TARGET_DIR="${ROOT_DIR}/api-sheriff/target"

# The build inputs whose mtime decides freshness: the application sources, the module POM and the
# reactor POM. A runner older than ANY of these was built from code that is no longer on disk.
BUILD_INPUTS=(
    "${ROOT_DIR}/api-sheriff/src"
    "${ROOT_DIR}/api-sheriff/pom.xml"
    "${ROOT_DIR}/pom.xml"
)

# Check if native executable exists in api-sheriff target
RUNNER_FILE=$(find "${APP_TARGET_DIR}" -name "*-runner" -type f 2>/dev/null | head -n 1)

# Decide ONCE why (or whether) a build is needed. An empty reason means "fresh, skip".
BUILD_REASON=""
if [[ -z "$RUNNER_FILE" ]]; then
    BUILD_REASON="no native executable found under ${APP_TARGET_DIR}"
else
    # Deterministic staleness check: is any build input newer (mtime) than the runner binary?
    # This is what makes the script rebuild after a source change instead of blindly reusing a
    # stale runner — the existence-only check it replaces made a fix look like it did not work.
    NEWER_INPUT=$(find "${BUILD_INPUTS[@]}" -newer "$RUNNER_FILE" -print 2>/dev/null | head -n 1 || true)
    if [[ -n "$NEWER_INPUT" ]]; then
        BUILD_REASON="build input is newer than the runner: ${NEWER_INPUT}"
    fi
fi

if [[ -n "$BUILD_REASON" ]]; then
    echo "🔨 Building the native executable — ${BUILD_REASON}"
    echo "This will take approximately 2 minutes..."

    # Build native executable with full lifecycle (ensures resources/augmentation)
    cd "${ROOT_DIR}"
    ./mvnw --no-transfer-progress clean package -Pnative -pl api-sheriff -DskipTests

    # Verify it was created
    RUNNER_FILE=$(find "${APP_TARGET_DIR}" -name "*-runner" -type f 2>/dev/null | head -n 1)
    if [[ -z "$RUNNER_FILE" ]]; then
        echo "❌ Failed to build native executable"
        exit 1
    fi
    echo "✅ Native executable built: $(basename "$RUNNER_FILE")"
    echo "⚠️  The Docker image must now be rebuilt — 'compose up' alone would reuse the stale image."
else
    echo "✅ Native executable is fresh: $(basename "$RUNNER_FILE") is newer than every build input"
    echo "   (api-sheriff/src, api-sheriff/pom.xml, pom.xml) — skipping the native build"
fi
