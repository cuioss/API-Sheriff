#!/bin/bash
# Negative check: an invalid mounted configuration MUST make the api-sheriff
# container fail fast and exit non-zero, never serving on partial configuration.
#
# ConfigProducer validates the mounted gateway configuration at boot
# (StartupEvent). On any violation it logs structured ERROR records and throws, so
# Quarkus exits non-zero. This script mounts a deliberately invalid gateway.yaml
# (non-integer version + an unknown top-level key, both rejected by the D2 schema)
# and asserts the container exits with a non-zero code.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

IMAGE="api-sheriff:distroless"
CONTAINER_NAME="api-sheriff-invalid-config-check"
BOOT_TIMEOUT_SECONDS=60
INVALID_CONFIG_DIR=""

cleanup() {
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    [[ -n "${INVALID_CONFIG_DIR}" ]] && rm -rf "${INVALID_CONFIG_DIR}"
}
trap cleanup EXIT

if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    echo "❌ Required image '${IMAGE}' not found."
    echo "   Build it first: ./mvnw verify -Pintegration-tests -pl integration-tests -am"
    exit 1
fi

# Throwaway config dir whose gateway.yaml is invalid on two counts the schema
# rejects: version must be an integer, and unknown top-level keys are not allowed.
INVALID_CONFIG_DIR="$(mktemp -d)"
cat > "${INVALID_CONFIG_DIR}/gateway.yaml" <<'YAML'
version: "not-an-integer"
unknown_key: true
YAML

echo "🚦 Starting '${CONTAINER_NAME}' with an invalid mounted configuration..."
docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${CONTAINER_NAME}" \
    -e SHERIFF_CONFIG_DIR=/app/sheriff-config \
    -e QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt \
    -e QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key \
    -v "${PROJECT_DIR}/src/main/docker/certificates:/app/certificates:ro" \
    -v "${INVALID_CONFIG_DIR}:/app/sheriff-config:ro" \
    "${IMAGE}" >/dev/null

echo "⏳ Waiting up to ${BOOT_TIMEOUT_SECONDS}s for the container to exit..."
set +e
EXIT_CODE="$(timeout "${BOOT_TIMEOUT_SECONDS}" docker wait "${CONTAINER_NAME}")"
WAIT_STATUS=$?
set -e

if [[ ${WAIT_STATUS} -ne 0 ]]; then
    echo "❌ docker wait timed out — the container failed to exit and may be"
    echo "   serving despite the invalid configuration."
    docker logs "${CONTAINER_NAME}" 2>&1 | tail -50 || true
    exit 1
fi

echo "📄 Container exited with code ${EXIT_CODE}. Fail-fast markers (if any):"
docker logs "${CONTAINER_NAME}" 2>&1 | grep -E "Refusing to start|ApiSheriff-20[01]|configuration is invalid" || true

if [[ "${EXIT_CODE}" == "0" ]]; then
    echo "❌ Expected a non-zero exit for invalid configuration, but the container exited 0."
    exit 1
fi

echo "✅ Invalid configuration correctly caused a fail-fast non-zero exit (${EXIT_CODE})."
