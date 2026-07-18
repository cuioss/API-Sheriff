#!/bin/bash
# Negative check: an invalid mounted configuration MUST make the api-sheriff
# container fail fast and exit non-zero, never serving on partial configuration.
#
# ConfigProducer validates the mounted gateway configuration at boot
# (StartupEvent). On any violation it logs structured ERROR records and throws, so
# Quarkus exits non-zero. This script exercises two independent invalid
# configurations and asserts a fail-fast non-zero exit for each:
#   1. a schema-invalid gateway.yaml (non-integer version + an unknown top-level key,
#      both rejected by the D2 schema);
#   2. an anchor-violation gateway.yaml (two anchors whose prefixes are not pairwise
#      disjoint — '/api' contains '/api/v1'), rejected by the ADR-0007 anchor rules.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

IMAGE="api-sheriff:distroless"
CONTAINER_NAME="api-sheriff-invalid-config-check"
BOOT_TIMEOUT_SECONDS=60
CONFIG_DIRS=()

cleanup() {
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    if [[ ${#CONFIG_DIRS[@]} -gt 0 ]]; then
        for dir in "${CONFIG_DIRS[@]}"; do
            [[ -n "${dir}" ]] && rm -rf "${dir}"
        done
    fi
}
trap cleanup EXIT

if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    echo "❌ Required image '${IMAGE}' not found."
    echo "   Build it first: ./mvnw verify -Pintegration-tests -pl integration-tests -am"
    exit 1
fi

# Boots the api-sheriff container against the mounted config dir and asserts it
# exits non-zero (fail-fast). $1 = config dir, $2 = human label, $3 = an extra
# grep marker expected in the fail-fast logs.
assert_fails_to_boot() {
    local config_dir="$1"
    local label="$2"
    local marker="$3"

    echo "🚦 Starting '${CONTAINER_NAME}' with ${label}..."
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    docker run -d --name "${CONTAINER_NAME}" \
        -e SHERIFF_CONFIG_DIR=/app/sheriff-config \
        -e QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt \
        -e QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key \
        -v "${PROJECT_DIR}/src/main/docker/certificates:/app/certificates:ro" \
        -v "${config_dir}:/app/sheriff-config:ro" \
        "${IMAGE}" >/dev/null

    echo "⏳ Waiting up to ${BOOT_TIMEOUT_SECONDS}s for the container to exit..."
    set +e
    local exit_code
    exit_code="$(timeout "${BOOT_TIMEOUT_SECONDS}" docker wait "${CONTAINER_NAME}")"
    local wait_status=$?
    set -e

    if [[ ${wait_status} -ne 0 ]]; then
        echo "❌ docker wait timed out — the container failed to exit and may be"
        echo "   serving despite the invalid configuration (${label})."
        docker logs "${CONTAINER_NAME}" 2>&1 | tail -50 || true
        exit 1
    fi

    local logs
    logs="$(docker logs "${CONTAINER_NAME}" 2>&1)"
    echo "📄 Container exited with code ${exit_code}. Fail-fast markers:"
    printf '%s\n' "${logs}" \
        | grep -E "Refusing to start|ApiSheriff-20[01]|configuration is invalid|${marker}" || true

    if ! grep -Fq -- "${marker}" <<<"${logs}"; then
        echo "❌ Expected validation marker '${marker}' was absent for ${label}."
        exit 1
    fi

    if [[ "${exit_code}" == "0" ]]; then
        echo "❌ Expected a non-zero exit for ${label}, but the container exited 0."
        exit 1
    fi

    echo "✅ ${label} correctly caused a fail-fast non-zero exit (${exit_code})."
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
}

# Case 1: a schema-invalid gateway.yaml — version must be an integer, and unknown
# top-level keys are not allowed.
SCHEMA_INVALID_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${SCHEMA_INVALID_DIR}")
cat > "${SCHEMA_INVALID_DIR}/gateway.yaml" <<'YAML'
version: "not-an-integer"
unknown_key: true
YAML
# mktemp -d creates a 700-permission directory the container's non-root user
# cannot read; without this the boot fails on "configuration file not found"
# instead of exercising the validation under test.
chmod 755 "${SCHEMA_INVALID_DIR}"
chmod 644 "${SCHEMA_INVALID_DIR}/gateway.yaml"
# Marker is the unknown property KEY (never a config value): the D5 binding-error
# redaction guarantees raw scalar values are not echoed into fail-fast logs, so
# asserting on the rejected value would contradict the redaction contract.
assert_fails_to_boot "${SCHEMA_INVALID_DIR}" "a schema-invalid configuration" "unknown_key"

# Case 2: an anchor-violation gateway.yaml — two anchors whose prefixes are not
# pairwise disjoint ('/api' contains '/api/v1'), rejected by the ADR-0007 anchor
# rules in the all-violations ConfigValidator pass.
ANCHOR_INVALID_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${ANCHOR_INVALID_DIR}")
cat > "${ANCHOR_INVALID_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "anchor-violation"
anchors:
  api:
    path_prefix: /api
    auth:
      require: none
  apiv1:
    path_prefix: /api/v1
    auth:
      require: none
YAML
chmod 755 "${ANCHOR_INVALID_DIR}"
chmod 644 "${ANCHOR_INVALID_DIR}/gateway.yaml"
assert_fails_to_boot "${ANCHOR_INVALID_DIR}" "an anchor-violation configuration" "pairwise disjoint"

echo "✅ All invalid configurations correctly caused fail-fast non-zero exits."
