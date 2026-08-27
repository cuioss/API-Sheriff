#!/bin/bash
# Negative check: an invalid mounted configuration MUST make the api-sheriff
# container fail fast and exit non-zero, never serving on partial configuration.
#
# ConfigProducer validates the mounted gateway configuration at boot
# (StartupEvent). On any violation it logs structured ERROR records and throws, so
# Quarkus exits non-zero. This script exercises seven independent invalid
# configurations and asserts a fail-fast non-zero exit for each:
#   1. a schema-invalid gateway.yaml (non-integer version + an unknown top-level key,
#      both rejected by the D2 schema);
#   2. an anchor-violation gateway.yaml (two anchors whose prefixes are not pairwise
#      disjoint — '/api' contains '/api/v1'), rejected by the ADR-0007 anchor rules;
#   3. a fail-closed WebSocket violation (a bearer websocket route with no
#      allowed_origins allowlist), rejected by the ADR-0015 rule;
#   4. a security_defaults profile outside the mode set (the dropped 'default' preset),
#      rejected by the D2 schema's profile enum — the VALUE RANGE gate;
#   5. profile 'minimal' on an effectively-authenticated route, refused by the fail-closed
#      ADR-0024 ConfigValidator rule on the effective-access-level dimension;
#   6. profile 'minimal' on a type: bff route, refused by the same rule on the anchor-type
#      dimension;
#   7. an unresolvable JWKS source (token_validation issuer whose 'source: file' names a
#      path that does not exist), which aborts boot from the ADR-0027 eager-assembly seam.
#
# Cases 4-6 split the two ADR-0024 gates deliberately: the schema owns the profile
# value range (case 4) and ConfigValidator owns the posture refusal (cases 5-6), so a
# regression in either gate fails a distinct case rather than being masked by the other.
#
# Case 7 is the only case that also carries a NEGATIVE leg (it publishes the management
# port and proves nothing ever answered on it). Cases 1-6 are refused by ConfigProducer
# before any bean that could open a port is created, whereas case 7 is refused later, from
# a StartupEvent observer — so it is the case where "exited non-zero" alone would not rule
# out a port having been served first. See assert_fails_to_boot's $4 parameter.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

IMAGE="api-sheriff:distroless"
CONTAINER_NAME="api-sheriff-invalid-config-check"
BOOT_TIMEOUT_SECONDS=60
CONFIG_DIRS=()

# The container-side management port, matching MANAGEMENT_CONTAINER_PORT in
# start-integration-container.sh's probe-target discovery.
MANAGEMENT_CONTAINER_PORT=9000
# Host port for case 7's negative leg. Deliberately outside the 19000-19005 block
# docker-compose.yml publishes for the six gateway instances, so this script can run
# against a live integration stack without colliding with it. A collision surfaces as
# docker's own "port is already allocated" failure under `set -e`, which is loud enough.
MGMT_PROBE_PORT=19009

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
# grep marker expected in the fail-fast logs. $4 = an OPTIONAL host port to publish
# against the container's management port; supplying it adds the negative leg — the
# wait becomes an observing one and the case additionally proves that no management
# interface ever answered (see the assertions at the end of this function).
assert_fails_to_boot() {
    local config_dir="$1"
    local label="$2"
    local marker="$3"
    local mgmt_probe_port="${4:-}"

    echo "🚦 Starting '${CONTAINER_NAME}' with ${label}..."
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    # Assembled as an array so the optional port mapping can be appended without a second
    # docker run invocation. The array is never empty, so expanding it does not trip the
    # `set -u` empty-array behaviour of the bash 3.2 that ships on macOS.
    local run_args=(
        -d --name "${CONTAINER_NAME}"
        -e SHERIFF_CONFIG_DIR=/app/sheriff-config
        -e QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt
        -e QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key
        -v "${PROJECT_DIR}/src/main/docker/certificates:/app/certificates:ro"
        -v "${config_dir}:/app/sheriff-config:ro"
    )
    if [[ -n "${mgmt_probe_port}" ]]; then
        # Bound to the LOOPBACK interface, not to every host interface. This leg exists precisely
        # for the case where the container DOES serve an unauthenticated management interface it
        # should never have opened — publishing that on 0.0.0.0 would make the regression this
        # assertion is hunting reachable from off-host for the whole boot window, on whatever CI or
        # developer machine happens to run the suite. The probe below targets localhost, so the
        # narrower bind costs the assertion nothing.
        run_args+=(-p "127.0.0.1:${mgmt_probe_port}:${MANAGEMENT_CONTAINER_PORT}")
    fi
    run_args+=("${IMAGE}")
    docker run "${run_args[@]}" >/dev/null

    local exit_code=""
    local wait_status=0
    local served_by=""

    if [[ -z "${mgmt_probe_port}" ]]; then
        echo "⏳ Waiting up to ${BOOT_TIMEOUT_SECONDS}s for the container to exit..."
        # Deadline poll rather than `timeout ${BOOT_TIMEOUT_SECONDS} docker wait`. `timeout` is GNU
        # coreutils and is NOT present on macOS, where the absent binary made this leg fail with
        # `timeout: command not found` and then report "the container failed to exit and may be
        # serving despite the invalid configuration" — the exact opposite of what had happened, on a
        # run whose container had in fact refused to start and logged ApiSheriff-201 correctly. A
        # negative control that inverts its verdict on the platform it runs on is worse than no
        # control, because the false red is indistinguishable from the true one it exists to raise.
        # The probing branch below already resolves its deadline this way, so both branches now share
        # one shape and neither depends on a binary outside POSIX.
        local deadline=$(($(date +%s) + BOOT_TIMEOUT_SECONDS))
        local running
        while true; do
            running="$(docker inspect -f '{{.State.Running}}' "${CONTAINER_NAME}" 2>/dev/null || echo unknown)"
            if [[ "${running}" == "false" ]]; then
                exit_code="$(docker inspect -f '{{.State.ExitCode}}' "${CONTAINER_NAME}")"
                break
            fi
            if (($(date +%s) >= deadline)); then
                wait_status=1
                break
            fi
            sleep 1
        done
    else
        echo "⏳ Waiting up to ${BOOT_TIMEOUT_SECONDS}s for the container to exit, probing"
        echo "   published management port ${mgmt_probe_port} for the whole window..."
        local deadline=$(($(date +%s) + BOOT_TIMEOUT_SECONDS))
        local scheme code probe_opts running
        while true; do
            for scheme in http https; do
                probe_opts=(-s -o /dev/null -w '%{http_code}' --connect-timeout 1 --max-time 2)
                # -k on the https probe only: a TLS management interface presents the same
                # self-signed localhost bundle the compose instances serve, and certificate
                # validation failing there would mask a port that DID answer. Both schemes
                # are probed because which one the management interface speaks is a
                # configuration outcome, and this leg must not depend on predicting it.
                if [[ "${scheme}" == "https" ]]; then
                    probe_opts+=(-k)
                fi
                # Any HTTP status at all — including 503 — means the interface ANSWERED,
                # which is the partial-config serving this leg exists to catch. '000' is
                # curl's no-response code and is what a refused or reset connection yields.
                code="$(curl "${probe_opts[@]}" \
                    "${scheme}://localhost:${mgmt_probe_port}/q/health/ready" || true)"
                if [[ -n "${code}" && "${code}" != "000" ]]; then
                    served_by="${scheme} (HTTP ${code})"
                fi
            done

            running="$(docker inspect -f '{{.State.Running}}' "${CONTAINER_NAME}" 2>/dev/null || echo unknown)"
            if [[ "${running}" == "false" ]]; then
                exit_code="$(docker inspect -f '{{.State.ExitCode}}' "${CONTAINER_NAME}")"
                break
            fi
            if (($(date +%s) >= deadline)); then
                wait_status=1
                break
            fi
            sleep 1
        done
    fi

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

    if [[ -n "${mgmt_probe_port}" ]]; then
        # Negative leg. The assertions above are the POSITIVE leg and prove only that the
        # container ended up exiting non-zero with the expected marker. A regression that
        # opened the ports first and failed afterwards would satisfy both and let the case
        # pass green while a partial-config management port had in fact been served. Two
        # co-assertions close that from both sides:
        #   (a) the log line below is emitted the instant the management listener binds, so
        #       its absence is a race-free proof that no listener was ever opened;
        #   (b) the live probe above sampled the published port across the whole boot window,
        #       so it is the direct "nothing answered on the wire" observation.
        # (a) is the load-bearing one — (b) samples, and a serving window shorter than its
        # cadence could slip past it, which is exactly why it is not asserted alone.
        if grep -Fq -- "Management interface listening on" <<<"${logs}"; then
            echo "❌ A management listener was announced for ${label} — the container served"
            echo "   on partially-applied configuration before exiting."
            exit 1
        fi
        if [[ -n "${served_by}" ]]; then
            echo "❌ Management port ${mgmt_probe_port} answered over ${served_by} for ${label} —"
            echo "   a readiness endpoint served a partial-config response."
            exit 1
        fi
        echo "✅ No management listener was announced and port ${mgmt_probe_port} never answered."
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

# Case 4: a security_defaults profile outside the mode set (ADR-0024). The value range is owned by
# the D2 JSON Schema (three symmetric enum sites), NOT by ConfigValidator — the dropped 'default'
# preset is the canonical out-of-range value, so this case proves the schema still guards the range
# after the mode set was narrowed to strict/lenient/minimal.
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
# Marker is the JSON-pointer instance location the schema violation reports —
# 'gateway.yaml [/security_defaults/profile]' in the aggregated ConfigLoadException message. It is
# a config KEY PATH, never the rejected scalar: the D5 binding-error redaction contract guarantees
# raw values are not echoed, so asserting on 'default' would contradict it. The pointer is also
# preferred over any slice of the enum-rejection sentence itself, which the schema validator
# renders in the container's locale and would make a text marker locale-fragile. The bare word
# 'profile' was too weak — it can appear in unrelated startup output, so the case could pass green
# even if the D2 enum gate regressed entirely.
assert_fails_to_boot "${PROFILE_RANGE_DIR}" "an out-of-range security_defaults profile" \
    "/security_defaults/profile"

# Case 5: profile 'minimal' on an effectively-authenticated route (ADR-0024). The anchor's bearer
# floor makes every route under it effectively authenticated, so the fail-closed ConfigValidator rule
# refuses the mode at boot rather than serving a route whose url-parameter validation is off in
# front of a token-bearing surface. A complete, otherwise valid config is assembled so this refusal
# is the ONLY violation.
MINIMAL_AUTHENTICATED_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${MINIMAL_AUTHENTICATED_DIR}")
mkdir -p "${MINIMAL_AUTHENTICATED_DIR}/endpoints"
cat > "${MINIMAL_AUTHENTICATED_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "minimal-on-authenticated"
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
cat > "${MINIMAL_AUTHENTICATED_DIR}/topology.properties" <<'PROPS'
SECURE_UPSTREAM=http://go-httpbin:8080/anything
PROPS
cat > "${MINIMAL_AUTHENTICATED_DIR}/endpoints/secure.yaml" <<'YAML'
endpoint:
  id: secure
  base_url: SECURE_UPSTREAM
  anchor: secure
  routes:
    - id: secure-minimal-mode
      match:
        path_prefix: /secure/minimal
      security_filter:
        profile: minimal
YAML
chmod 755 "${MINIMAL_AUTHENTICATED_DIR}" "${MINIMAL_AUTHENTICATED_DIR}/endpoints"
chmod 644 "${MINIMAL_AUTHENTICATED_DIR}/gateway.yaml" "${MINIMAL_AUTHENTICATED_DIR}/topology.properties" \
    "${MINIMAL_AUTHENTICATED_DIR}/endpoints/secure.yaml"
# Marker: the fixed refusing-dimension fragment of the ConfigValidator message. Route ids and
# anchor names are config KEYS (safe to assert on); no rejected scalar VALUE is asserted.
assert_fails_to_boot "${MINIMAL_AUTHENTICATED_DIR}" "profile 'minimal' on an authenticated route" \
    "effective access level is 'authenticated'"

# Case 6: profile 'minimal' on a type: bff route (ADR-0024). The second refusing dimension: a BFF
# surface mediates a browser session, so the mode is refused on the anchor TYPE independently of
# the access level. ADR-0013 requires a bff anchor to be access: authenticated, so this fixture
# necessarily trips both dimensions — the marker below pins the anchor-type one specifically.
MINIMAL_BFF_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${MINIMAL_BFF_DIR}")
mkdir -p "${MINIMAL_BFF_DIR}/endpoints"
cat > "${MINIMAL_BFF_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "minimal-on-bff"
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
cat > "${MINIMAL_BFF_DIR}/topology.properties" <<'PROPS'
SHELL_UPSTREAM=http://go-httpbin:8080/anything
PROPS
cat > "${MINIMAL_BFF_DIR}/endpoints/shell.yaml" <<'YAML'
endpoint:
  id: shell
  base_url: SHELL_UPSTREAM
  anchor: shell
  routes:
    - id: shell-minimal-mode
      match:
        path_prefix: /shell/view
      security_filter:
        profile: minimal
YAML
chmod 755 "${MINIMAL_BFF_DIR}" "${MINIMAL_BFF_DIR}/endpoints"
chmod 644 "${MINIMAL_BFF_DIR}/gateway.yaml" "${MINIMAL_BFF_DIR}/topology.properties" \
    "${MINIMAL_BFF_DIR}/endpoints/shell.yaml"
assert_fails_to_boot "${MINIMAL_BFF_DIR}" "profile 'minimal' on a type: bff route" "is type 'bff'"

# Case 7: an unresolvable JWKS source — a token_validation issuer whose 'source: file' names a
# path that is not present in the mounted config. This is the boot-time constructibility contract
# ADR-0027 describes and TokenValidatorProducer.onStartup forces into existence: the producer
# invokes a method on the @ApplicationScoped validator proxy at StartupEvent, which runs the full
# assembly path and throws here, so a gateway that cannot build a validator refuses to run at all
# rather than deferring the failure to the first bearer request.
#
# This case, uniquely, passes MGMT_PROBE_PORT to get the negative leg. The reason is the seam it
# exercises: cases 1-6 are refused by ConfigProducer while the route table is being produced —
# before any bean that could open a port exists — whereas this one is refused from a StartupEvent
# OBSERVER, which is late enough that "did a port open first?" is a real question rather than a
# structurally impossible one. Asserting only the non-zero exit would leave that question
# unanswered, and a future change that moved JWKS assembly behind the listener bind would keep the
# case green while shipping exactly the partial-config serving it exists to forbid.
#
# The config is otherwise complete and valid — anchor, endpoint, topology — so the unresolvable
# JWKS is the ONLY violation and the case cannot pass on an unrelated refusal.
JWKS_UNRESOLVABLE_DIR="$(mktemp -d)"
CONFIG_DIRS+=("${JWKS_UNRESOLVABLE_DIR}")
mkdir -p "${JWKS_UNRESOLVABLE_DIR}/endpoints"
cat > "${JWKS_UNRESOLVABLE_DIR}/gateway.yaml" <<'YAML'
version: 1
metadata:
  config_version: "jwks-unresolvable"
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
        file: /app/sheriff-config/absent-jwks.json
YAML
cat > "${JWKS_UNRESOLVABLE_DIR}/topology.properties" <<'PROPS'
SECURE_UPSTREAM=http://go-httpbin:8080/anything
PROPS
cat > "${JWKS_UNRESOLVABLE_DIR}/endpoints/secure.yaml" <<'YAML'
endpoint:
  id: secure
  base_url: SECURE_UPSTREAM
  anchor: secure
  routes:
    - id: secure-echo
      match:
        path_prefix: /secure/echo
YAML
chmod 755 "${JWKS_UNRESOLVABLE_DIR}" "${JWKS_UNRESOLVABLE_DIR}/endpoints"
chmod 644 "${JWKS_UNRESOLVABLE_DIR}/gateway.yaml" "${JWKS_UNRESOLVABLE_DIR}/topology.properties" \
    "${JWKS_UNRESOLVABLE_DIR}/endpoints/secure.yaml"
# Marker is the fixed sentence token-sheriff's JWKSKeyLoader raises, WITHOUT the path it appends.
# Keeping the configured path out of the marker holds this case to the same discipline as the
# others (assert on the fixed diagnostic, never on the rejected scalar) and additionally keeps the
# assertion independent of the path this fixture happens to choose.
assert_fails_to_boot "${JWKS_UNRESOLVABLE_DIR}" "an unresolvable JWKS source" \
    "Cannot read JWKS file" "${MGMT_PROBE_PORT}"

echo "✅ All invalid configurations correctly caused fail-fast non-zero exits."
