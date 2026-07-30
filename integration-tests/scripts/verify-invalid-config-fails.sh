#!/bin/bash
# Negative check: an invalid mounted configuration MUST make the api-sheriff
# container fail fast and exit non-zero, never serving on partial configuration.
#
# ConfigProducer validates the mounted gateway configuration at boot
# (StartupEvent). On any violation it logs structured ERROR records and throws, so
# Quarkus exits non-zero. This script exercises six independent invalid
# configurations and asserts a fail-fast non-zero exit for each:
#   1. a schema-invalid gateway.yaml (non-integer version + an unknown top-level key,
#      both rejected by the D2 schema);
#   2. an anchor-violation gateway.yaml (two anchors whose prefixes are not pairwise
#      disjoint — '/api' contains '/api/v1'), rejected by the ADR-0007 anchor rules;
#   3. a fail-closed WebSocket violation (a bearer websocket route with no
#      allowed_origins allowlist), rejected by the ADR-0015 rule;
#   4. a security_defaults profile outside the mode set (the dropped 'default' preset),
#      rejected by the D2 schema's profile enum — the VALUE RANGE gate;
#   5. profile 'none' on an effectively-authenticated route, refused by the fail-closed
#      ADR-0023 ConfigValidator rule on the effective-access-level dimension;
#   6. profile 'none' on a type: bff route, refused by the same rule on the anchor-type
#      dimension.
#
# Cases 4-6 split the two ADR-0023 gates deliberately: the schema owns the profile
# value range (case 4) and ConfigValidator owns the posture refusal (cases 5-6), so a
# regression in either gate fails a distinct case rather than being masked by the other.

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
    type: proxy
    access: public
  apiv1:
    path_prefix: /api/v1
    type: proxy
    access: public
YAML
chmod 755 "${ANCHOR_INVALID_DIR}"
chmod 644 "${ANCHOR_INVALID_DIR}/gateway.yaml"
assert_fails_to_boot "${ANCHOR_INVALID_DIR}" "an anchor-violation configuration" "pairwise disjoint"

# Case 3: a fail-closed WebSocket violation (ADR-0015) — a bearer 'protocol: websocket'
# route that declares no (empty/absent) allowed_origins allowlist. The running WebSocket
# integration stack cannot host this route (it aborts boot fail-fast), so the fail-closed
# contract WebSocketProxyIT documents is proven here end-to-end: the bound config trips the
# ConfigValidator WS allowlist rule and the container exits non-zero. A complete, otherwise
# valid config is assembled (gateway.yaml + topology.properties + endpoints/websocket.yaml) so
# the ONLY violation is the missing allowlist on the bearer WS route.
WS_FAILCLOSED_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${WS_FAILCLOSED_DIR}")
mkdir -p "${WS_FAILCLOSED_DIR}/endpoints"
cat > "${WS_FAILCLOSED_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "ws-fail-closed"
anchors:
  ws:
    path_prefix: /ws
    type: proxy
    access: public
YAML
cat > "${WS_FAILCLOSED_DIR}/topology.properties" <<'PROPS'
WS_UPSTREAM=http://go-httpbin:8080/websocket/echo
PROPS
cat > "${WS_FAILCLOSED_DIR}/endpoints/websocket.yaml" <<'YAML'
endpoint:
  id: websocket
  base_url: WS_UPSTREAM
  anchor: ws
  routes:
    - id: ws-bearer-open
      protocol: websocket
      auth:
        require: bearer
      match:
        path_prefix: /ws/bearer
YAML
chmod 755 "${WS_FAILCLOSED_DIR}" "${WS_FAILCLOSED_DIR}/endpoints"
chmod 644 "${WS_FAILCLOSED_DIR}/gateway.yaml" "${WS_FAILCLOSED_DIR}/topology.properties" \
    "${WS_FAILCLOSED_DIR}/endpoints/websocket.yaml"
# Marker: the tail of the ConfigValidator fail-closed message. The route id is a config KEY
# (safe to assert on), never a redacted scalar value.
assert_fails_to_boot "${WS_FAILCLOSED_DIR}" "a fail-closed WebSocket configuration" "fail-closed"

# Case 4: a security_defaults profile outside the mode set (ADR-0023). The value range is owned by
# the D2 JSON Schema (three symmetric enum sites), NOT by ConfigValidator — the dropped 'default'
# preset is the canonical out-of-range value, so this case proves the schema still guards the range
# after the mode set was narrowed to strict/lenient/none.
PROFILE_RANGE_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${PROFILE_RANGE_DIR}")
cat > "${PROFILE_RANGE_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "profile-out-of-range"
security_defaults:
  profile: default
YAML
chmod 755 "${PROFILE_RANGE_DIR}"
chmod 644 "${PROFILE_RANGE_DIR}/gateway.yaml"
# Marker is the config KEY, never the rejected scalar: the D5 binding-error redaction contract
# guarantees raw values are not echoed, so asserting on 'default' would contradict it.
assert_fails_to_boot "${PROFILE_RANGE_DIR}" "an out-of-range security_defaults profile" "profile"

# Case 5: profile 'none' on an effectively-authenticated route (ADR-0023). The anchor's bearer floor
# makes every route under it effectively authenticated, so the fail-closed ConfigValidator rule
# refuses the mode at boot rather than serving a route whose url-parameter validation is off in
# front of a token-bearing surface. A complete, otherwise valid config is assembled so this refusal
# is the ONLY violation.
NONE_AUTHENTICATED_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${NONE_AUTHENTICATED_DIR}")
mkdir -p "${NONE_AUTHENTICATED_DIR}/endpoints"
cat > "${NONE_AUTHENTICATED_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "none-on-authenticated"
anchors:
  secure:
    path_prefix: /secure
    type: proxy
    access: authenticated
    auth:
      require: bearer
token_validation:
  issuers:
    - name: it-static
      issuer: https://api-sheriff.test/it
      jwks:
        source: file
        file: /app/certificates/test-jwks.json
YAML
cat > "${NONE_AUTHENTICATED_DIR}/topology.properties" <<'PROPS'
SECURE_UPSTREAM=http://go-httpbin:8080/anything
PROPS
cat > "${NONE_AUTHENTICATED_DIR}/endpoints/secure.yaml" <<'YAML'
endpoint:
  id: secure
  base_url: SECURE_UPSTREAM
  anchor: secure
  routes:
    - id: secure-none-mode
      match:
        path_prefix: /secure/none
      security_filter:
        profile: none
YAML
chmod 755 "${NONE_AUTHENTICATED_DIR}" "${NONE_AUTHENTICATED_DIR}/endpoints"
chmod 644 "${NONE_AUTHENTICATED_DIR}/gateway.yaml" "${NONE_AUTHENTICATED_DIR}/topology.properties" \
    "${NONE_AUTHENTICATED_DIR}/endpoints/secure.yaml"
# Marker: the fixed refusing-dimension fragment of the ConfigValidator message. Route ids and
# anchor names are config KEYS (safe to assert on); no rejected scalar VALUE is asserted.
assert_fails_to_boot "${NONE_AUTHENTICATED_DIR}" "profile 'none' on an authenticated route" \
    "effective access level is 'authenticated'"

# Case 6: profile 'none' on a type: bff route (ADR-0023). The second refusing dimension: a BFF
# surface mediates a browser session, so the mode is refused on the anchor TYPE independently of
# the access level. ADR-0013 requires a bff anchor to be access: authenticated, so this fixture
# necessarily trips both dimensions — the marker below pins the anchor-type one specifically.
NONE_BFF_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${NONE_BFF_DIR}")
mkdir -p "${NONE_BFF_DIR}/endpoints"
cat > "${NONE_BFF_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "none-on-bff"
anchors:
  shell:
    path_prefix: /shell
    type: bff
    access: authenticated
    auth:
      require: bearer
token_validation:
  issuers:
    - name: it-static
      issuer: https://api-sheriff.test/it
      jwks:
        source: file
        file: /app/certificates/test-jwks.json
YAML
cat > "${NONE_BFF_DIR}/topology.properties" <<'PROPS'
SHELL_UPSTREAM=http://go-httpbin:8080/anything
PROPS
cat > "${NONE_BFF_DIR}/endpoints/shell.yaml" <<'YAML'
endpoint:
  id: shell
  base_url: SHELL_UPSTREAM
  anchor: shell
  routes:
    - id: shell-none-mode
      match:
        path_prefix: /shell/view
      security_filter:
        profile: none
YAML
chmod 755 "${NONE_BFF_DIR}" "${NONE_BFF_DIR}/endpoints"
chmod 644 "${NONE_BFF_DIR}/gateway.yaml" "${NONE_BFF_DIR}/topology.properties" \
    "${NONE_BFF_DIR}/endpoints/shell.yaml"
assert_fails_to_boot "${NONE_BFF_DIR}" "profile 'none' on a type: bff route" "is type 'bff'"

echo "✅ All invalid configurations correctly caused fail-fast non-zero exits."
