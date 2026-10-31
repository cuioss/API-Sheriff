#!/bin/bash
# Start API Sheriff Integration Tests using Docker Compose

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$PROJECT_DIR")"
APP_TARGET_DIR="${ROOT_DIR}/api-sheriff/target"

# The retry budgets of the host-side waits that still poll, one attempt per second. Each budget is
# declared once, here, and handed to wait_for_url — never restated in a loop, a message or a comment.
# The gateway instances are not polled by this script at all: Compose waits on their baked HEALTHCHECK
# (see the two-layer gate below).
KEYCLOAK_WAIT_ATTEMPTS=120
BACKEND_WAIT_ATTEMPTS=30

# shellcheck source=lib-docker-compose.sh
source "${SCRIPT_DIR}/lib-docker-compose.sh"

echo "🚀 Starting API Sheriff Integration Tests with Docker Compose"
echo "Project directory: ${PROJECT_DIR}"
echo "Root directory: ${ROOT_DIR}"

# Resolve the compose command (docker compose plugin vs standalone docker-compose)
COMPOSE_BASE="$(resolve_compose_cmd || true)"
if [[ -z "$COMPOSE_BASE" ]]; then
    echo "❌ Docker Compose not available (neither 'docker compose' nor 'docker-compose')"
    exit 1
fi
if ! docker_daemon_up; then
    echo "❌ Docker daemon not running — start Docker/Rancher Desktop first"
    exit 1
fi

cd "${PROJECT_DIR}"

# Check build approach - Native executable + Docker copy vs Docker build
RUNNER_FILE=$(find "${APP_TARGET_DIR}" -name "*-runner" -type f 2>/dev/null | head -n 1)

# Which image this stack runs is DECLARED by the lane that starts it, not inferred from the
# daemon. Each Maven profile's start-integration-app execution sets SHERIFF_IMAGE_TYPE to the
# image it has just built and tagged — "distroless" for -Pintegration-tests, "jfr" for -Pjfr —
# and that declaration is read first here.
#
# It replaces a heuristic that sniffed `docker images` and preferred api-sheriff:jfr whenever
# one existed, falling back to api-sheriff:distroless otherwise. The preference is local state
# masquerading as intent: an api-sheriff:jfr image left on the daemon by an earlier -Pjfr run
# outlives that run, so a later -Pintegration-tests run composed the jfr overlay and tested a
# week-old binary while the distroless image it had just built sat unused. That produced a
# failing suite whose failure was about neither the code under test nor the image the lane
# built — the most expensive kind of false negative, because nothing in the output says the
# wrong image was selected.
#
# A declared type is REQUIRED to exist. Falling back to the other image is exactly the silent
# substitution this replaces, so a missing image fails loudly and names what it expected.
resolve_image_tag() {
    docker images --format "{{.Repository}}:{{.Tag}}" | grep "^api-sheriff:$1$" || true
}

case "${SHERIFF_IMAGE_TYPE:-}" in
    distroless | jfr)
        IMAGE_TYPE="$SHERIFF_IMAGE_TYPE"
        AVAILABLE_IMAGE="$(resolve_image_tag "$IMAGE_TYPE")"
        if [[ -z "$AVAILABLE_IMAGE" ]]; then
            echo "❌ SHERIFF_IMAGE_TYPE=${IMAGE_TYPE} but no api-sheriff:${IMAGE_TYPE} image exists"
            echo "The lane that set it builds that image before starting the stack, so this is a"
            echo "build-order or tagging failure. Refusing to substitute a different image —"
            echo "that substitution is how a stale image silently hijacks a run."
            echo "Available images:"
            docker images | grep api-sheriff || echo "  No api-sheriff images found"
            exit 1
        fi
        echo "🎯 Image type declared by the lane: SHERIFF_IMAGE_TYPE=${IMAGE_TYPE}"
        ;;
    "")
        # No lane declared one. This is the benchmarks module, a hand-run bring-up, or any other
        # caller that legitimately has no profile behind it, so the old sniff is kept for them —
        # as a fallback for the undeclared case only, never as an override of a declared one.
        echo "ℹ️  SHERIFF_IMAGE_TYPE unset — falling back to detecting an image on the daemon"
        JFR_IMAGE="$(resolve_image_tag jfr)"
        DISTROLESS_IMAGE="$(resolve_image_tag distroless)"
        if [[ -n "$JFR_IMAGE" ]]; then
            AVAILABLE_IMAGE="$JFR_IMAGE"
            IMAGE_TYPE="jfr"
        elif [[ -n "$DISTROLESS_IMAGE" ]]; then
            AVAILABLE_IMAGE="$DISTROLESS_IMAGE"
            IMAGE_TYPE="distroless"
        else
            AVAILABLE_IMAGE=""
            IMAGE_TYPE="none"
        fi
        ;;
    *)
        echo "❌ SHERIFF_IMAGE_TYPE=${SHERIFF_IMAGE_TYPE} is not a known image type"
        echo "Expected 'distroless' or 'jfr', or leave it unset to detect one."
        exit 1
        ;;
esac

# The overlay set follows from the selected type. COMPOSE_CMD is deliberately left unset for
# "none": there is no image to run, and the missing-image branch below exits before any compose
# command is built.
if [[ "$IMAGE_TYPE" == "jfr" ]]; then
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml -f docker-compose.jfr.yml"
elif [[ "$IMAGE_TYPE" == "distroless" ]]; then
    COMPOSE_CMD="$COMPOSE_BASE -f docker-compose.yml"
fi

# Benchmark mode: overlay the static nginx backend and repoint the gateway upstream
if [[ "${BENCHMARK_MODE:-false}" == "true" ]] && [[ -n "$COMPOSE_CMD" ]]; then
    COMPOSE_CMD="$COMPOSE_CMD -f docker-compose.benchmark.yml"
    echo "🏁 Benchmark mode: overlaying static nginx backend (docker-compose.benchmark.yml)"
fi

IMAGE_EXISTS=$([ ! -z "$AVAILABLE_IMAGE" ] && echo "true" || echo "false")

if [[ -n "$RUNNER_FILE" ]] && [[ "$IMAGE_EXISTS" == "true" ]]; then
    echo "📦 Using Maven-built native executable: $(basename "$RUNNER_FILE")"
    echo "🐳 Docker image: $AVAILABLE_IMAGE ($IMAGE_TYPE mode)"
    MODE="native (Maven-built + Docker copy) - $IMAGE_TYPE"
elif [[ "$IMAGE_EXISTS" == "true" ]]; then
    echo "📦 Using Docker-built native image: $AVAILABLE_IMAGE ($IMAGE_TYPE mode)"
    MODE="native (Docker-built) - $IMAGE_TYPE"
else
    echo "❌ Neither native executable nor Docker image found"
    echo "Expected: api-sheriff/target/*-runner file and api-sheriff image"
    echo "Available images:"
    docker images | grep api-sheriff || echo "  No api-sheriff images found"
    echo "Run: mvnw verify -Pintegration-tests -pl integration-tests -am"
    exit 1
fi


# Set LOG_TARGET_DIR to a dedicated log subdirectory for Quarkus file logging.
# The api-sheriff container runs as the distroless 'nonroot' user (uid 65532), but this
# host directory is created by the (differently-numbered) Maven user, so the bind-mounted
# /logs is not writable by the container and the file log sink fails with
# "FileNotFoundException: /logs/quarkus.log (Permission denied)". Grant world write on
# a dedicated 'quarkus-logs' subdirectory only — least privilege — so the container can write
# quarkus.log there without making the entire build target tree world-writable (ephemeral
# test output — the container keeps its no-new-privileges / cap_drop / read_only posture).
#
# Mode 1777, not 0777: the sticky bit is what keeps that world write from also being a
# world DELETE. Without it any local account on a shared CI runner or developer host can
# remove or replace quarkus.log — the gateway's own log for the run, and the first thing
# read to diagnose a failure — so that evidence is locally tamperable. The sticky bit
# restricts unlink and rename to the file's owner and the directory's owner, costing the
# container nothing: it still creates and rotates the files it owns, and 'mvn clean' runs
# as the build user that OWNS this directory.
LOG_TARGET_ROOT="${LOG_TARGET_DIR:-${PROJECT_DIR}/target}"
export LOG_TARGET_DIR="${LOG_TARGET_ROOT}/quarkus-logs"
mkdir -p "${LOG_TARGET_DIR}"
chmod 1777 "${LOG_TARGET_DIR}"
echo "📁 Quarkus logs will be written to: ${LOG_TARGET_DIR}/quarkus.log"

# The JFR overlay bind-mounts ./target/jfr-recordings at /tmp/jfr-output, and that host directory
# must exist and be container-writable before the first compose command resolves the overlay —
# see scripts/prepare-jfr-output-dir.sh for why, and for why both this script and the `jfr` Maven
# profile call it. Only the JFR overlay needs it, so it runs only when that overlay is composed.
if [[ "$IMAGE_TYPE" == "jfr" ]]; then
    ./scripts/prepare-jfr-output-dir.sh "${PROJECT_DIR}"
fi

# Discover every host-side probe target from the resolved Compose model, BEFORE anything is started.
#
# The service set, each service's published management port, and the scheme its management interface
# speaks are all DERIVED from that model — none of them is restated here. An earlier version
# hand-maintained a "service:port" list plus a separate block for the plain-HTTP instance, under a
# comment instructing the reader to keep the list in lockstep with docker-compose.yml. A hardcoded
# list that must mirror a set defined elsewhere is a defect unless it is derived from that source, so
# it is derived: adding, removing or renumbering an api-sheriff* service needs no edit here, and
# neither does moving Keycloak's published management port.
#
# The scheme comes from each service's de.cuioss.sheriff.management-scheme label rather than from its
# name, and that is what collapses the plain-management special case into a single readiness loop.
# That instance is probed over http:// with NO -k: if it ever needs -k, the plain-management opt-out
# has silently stopped working and THAT is the bug, not the probe.
#
# The management ROOT PATH comes from each service's de.cuioss.sheriff.management-root-path label for
# the same reason the scheme does: it is a build-time-fixed property of the image that a deployment
# can move, so a probe path spelled out here would be a second copy to keep in lockstep with the
# gateway's own configuration. Deriving it means moving the gateway's management context path needs
# no edit in this script. Both labels are REQUIRED, never defaulted — see the discovery block.
#
# The root-path label's value is carried out of the discovery program RAW. The trailing-slash trim
# that renders a root path of "/" as the empty string lives ONCE, in lib-docker-compose.sh's
# normalize_root_path, and every read loop below applies it to the column it just read — so the rule
# cannot drift between the Python discovery and the shell that consumes it.
#
# Keycloak carries the same labels for the same reason, so its wait derives its scheme and port here
# too rather than restating either. Its management ROOT PATH is the one thing not derived: Keycloak
# serves health at /health/ready under no prefix at all, so its root-path column is deliberately
# discarded below and its own literal is kept.
#
# The same model also yields the published host port of every other host-side target the script
# names: Keycloak's HTTPS port (the console URL printed at the end), go-httpbin's port (the upstream
# wait) and, when the benchmark overlay is composed, nginx-static's port (the static-backend wait).
# Those come out as separate "port <service> <container_port> <published_port>" rows, so the host port
# a service publishes is never restated here either. The HOST stays the literal localhost on every
# probe: the probes run on the Docker host, and the management interfaces serve a certificate bundle
# issued for localhost, so it is an invariant of where the probe runs rather than a fact to derive.
#
# The block runs BEFORE the first `compose up` on purpose: a model this script cannot read is a
# failure worth having in two seconds rather than after Keycloak has booted.
echo "⏳ Discovering probe targets from the Compose model..."
if ! DISCOVERED_TARGETS="$($COMPOSE_CMD config --format json | python3 -c '
import json
import sys

SCHEME_LABEL = "de.cuioss.sheriff.management-scheme"
ROOT_PATH_LABEL = "de.cuioss.sheriff.management-root-path"
MANAGEMENT_CONTAINER_PORT = "9000"
IDP_SERVICE = "keycloak"
GATEWAY_PREFIX = "api-sheriff"
# The non-management host ports the script names, as (service, container port, required). An
# optional service yields a row only when the resolved model contains it — nginx-static exists only
# under the benchmark overlay.
HOST_PORTS = (
    (IDP_SERVICE, "8443", True),
    ("go-httpbin", "8080", True),
    ("nginx-static", "8080", False),
)


def published_ports(spec, container_port):
    return [port.get("published") for port in (spec.get("ports") or [])
            if str(port.get("target")) == container_port and port.get("published")]


try:
    model = json.load(sys.stdin)
except ValueError as exc:
    sys.exit("could not parse the resolved Compose model as JSON (%s). This script needs a Compose "
             "version supporting `config --format json`." % exc)

all_services = model.get("services") or {}
selected = {name: spec for name, spec in all_services.items() if name.startswith(GATEWAY_PREFIX)}
if not selected:
    sys.exit("no api-sheriff* services found in the resolved Compose model — refusing to run with a "
             "readiness gate that would probe nothing")
if IDP_SERVICE not in all_services:
    sys.exit("no %s service found in the resolved Compose model — refusing to run with an IdP wait "
             "that would probe nothing" % IDP_SERVICE)
selected[IDP_SERVICE] = all_services[IDP_SERVICE]

rows = []
problems = []
for name in sorted(selected):
    spec = selected[name]
    labels = spec.get("labels") or {}
    scheme = labels.get(SCHEME_LABEL)
    root_path = labels.get(ROOT_PATH_LABEL)
    published = published_ports(spec, MANAGEMENT_CONTAINER_PORT)
    usable = True
    if scheme not in ("http", "https"):
        problems.append("%s: missing or invalid %s label (got %r)" % (name, SCHEME_LABEL, scheme))
        usable = False
    # An absolute root path is required rather than defaulted. Defaulting to "/q" here would let a
    # service that lost its label keep passing against the shipped default, which is exactly the
    # drift this label exists to make impossible.
    if not root_path or not root_path.startswith("/"):
        problems.append("%s: missing or non-absolute %s label (got %r)"
                        % (name, ROOT_PATH_LABEL, root_path))
        usable = False
    if len(published) != 1:
        problems.append("%s: expected exactly one host port published against container port %s, "
                        "found %r" % (name, MANAGEMENT_CONTAINER_PORT, published))
        usable = False
    if usable:
        # The root path is emitted RAW, and LAST. The column order is unchanged and stays
        # LOAD-BEARING: the shell `read` calls downstream name this field last, so appending a
        # fifth column after it would shift every later column and silently hand the wrong value
        # to GATEWAY_MGMT_ROOT.
        #
        # Raw, because the trailing-slash trim has exactly ONE home -- normalize_root_path() in
        # lib-docker-compose.sh -- which the shell applies to this field after reading each row. An
        # rstrip("/") here would be a second copy of that rule, free to drift from the shell one.
        # Raw also means this field is never empty (the check above rejects a root path that is not
        # absolute), so the trim can no longer collapse it to a whitespace-only column.
        rows.append("%s %s %s %s" % (name, scheme, published[0], root_path))

# The port rows lead with the literal "port", which no service name above can collide with: the
# management rows are keycloak or api-sheriff*, and the shell selects each kind by its own prefix.
for service, container_port, required in HOST_PORTS:
    if service not in all_services:
        if required:
            problems.append("%s: service not found in the resolved Compose model" % service)
        continue
    published = published_ports(all_services[service], container_port)
    if len(published) != 1:
        problems.append("%s: expected exactly one host port published against container port %s, "
                        "found %r" % (service, container_port, published))
        continue
    rows.append("port %s %s %s" % (service, container_port, published[0]))

if problems:
    sys.exit("probe-target discovery failed:\n  " + "\n  ".join(problems))

sys.stdout.write("\n".join(rows) + "\n")
')"; then
    echo "❌ Could not derive the probe targets from docker-compose.yml (see the error above)"
    exit 1
fi

# Split the derived rows by role, mirroring demo-client/scripts/start-dev-environment.sh's
# IDP_TARGET / GATEWAY_TARGETS split. The IdP row drives the Keycloak wait and the Keycloak banner
# entry; the api-sheriff* rows drive the gateway readiness loop and the Application URLs banner; the
# port rows are read one port at a time through derived_port below. Each kind is selected POSITIVELY
# by its own prefix — selecting the gateways as "everything that is not keycloak" would sweep the port
# rows into the readiness loop as well.
KEYCLOAK_TARGET="$(printf '%s\n' "$DISCOVERED_TARGETS" | grep "^keycloak ")"
READINESS_TARGETS="$(printf '%s\n' "$DISCOVERED_TARGETS" | grep "^api-sheriff")"

# Prints the host port SERVICE publishes against CONTAINER_PORT, read off the discovered port rows, or
# nothing when the model has no such row. An awk field match rather than grep: set -e is active, and a
# grep that matches nothing exits non-zero and would abort the script — the optional nginx-static row
# is legitimately absent outside benchmark mode.
derived_port() {
    printf '%s\n' "$DISCOVERED_TARGETS" \
        | awk -v service="$1" -v target="$2" '$1 == "port" && $2 == service && $3 == target { print $4 }'
}

KEYCLOAK_HTTPS_PORT="$(derived_port keycloak 8443)"
UPSTREAM_PORT="$(derived_port go-httpbin 8080)"

# Polls URL once per second with the curl options held in the array named OPTS_NAME, up to ATTEMPTS
# times, and returns once it answers. On the last failed attempt it names the URL, points at SERVICE's
# Compose log and exits the script — a failed wait is a failed bring-up. The options are passed by
# array NAME, through ${!name[@]} indirection rather than a nameref, because the shebang's /bin/bash
# is 3.2 on macOS hosts and namerefs need 4.3.
#
# This is the one polling loop in the script; every host-side wait goes through it.
wait_for_url() {
    local url="$1"
    local opts_ref="$2[@]"
    local opts=("${!opts_ref}")
    local attempts="$3"
    local label="$4"
    local service="$5"
    local attempt
    for ((attempt = 1; attempt <= attempts; attempt++)); do
        if curl "${opts[@]}" "${url}" > /dev/null 2>&1; then
            echo "✅ ${label} is ready!"
            return 0
        fi
        if [ "$attempt" -eq "$attempts" ]; then
            echo "❌ ${label} did not answer ${url} within ${attempts} attempts"
            echo "Check logs with: ${COMPOSE_BASE} logs ${service}"
            exit 1
        fi
        echo "⏳ Waiting for ${label}... (attempt ${attempt}/${attempts})"
        sleep 1
    done
}

# The trailing _ discards Keycloak's management-root-path column deliberately: Keycloak serves
# /health/ready directly on the management port under no root-path prefix, so the gateway root path
# its label carries for label-set uniformity must NOT be spliced in here.
read -r _ KC_MGMT_SCHEME KC_MGMT_PORT _ <<< "$KEYCLOAK_TARGET"
KEYCLOAK_HEALTH_URL="${KC_MGMT_SCHEME}://localhost:${KC_MGMT_PORT}/health/ready"

# -f matters here exactly as it does on the gateway probe below: /health/ready answers 503 while
# Keycloak is still starting, and without -f curl exits 0 on that 503 — so the wait would clear as
# soon as the port ACCEPTED rather than when Keycloak was actually ready, which is the very race the
# comment below says this gate exists to remove.
KEYCLOAK_PROBE_OPTS=(-sf --connect-timeout 2 --max-time 5)
if [[ "$KC_MGMT_SCHEME" == "https" ]]; then
    # -k is load-bearing on an HTTPS management interface: it serves a self-signed localhost bundle,
    # and without it curl fails certificate validation and this wait degrades into a silent
    # KEYCLOAK_WAIT_ATTEMPTS timeout against a perfectly healthy container.
    KEYCLOAK_PROBE_OPTS+=(-k)
fi

# Bring up Keycloak FIRST and wait until it is READY before starting the gateway. The api-sheriff
# native app starts loading the Keycloak issuers' JWKS at boot and reports readiness DOWN until every
# issuer's key set has loaded (ADR-0027). A gateway started before Keycloak answers
# retries the fetch on a bounded backoff (1 s doubling, capped at 30 s), so it recovers — but the
# layer-2 readiness assertion below is a SINGLE SHOT taken right after the gateways report healthy,
# and a key set still waiting out a backoff at that moment fails it. Gating the gateway start on a
# ready Keycloak makes each gateway's first fetch succeed within a moment of boot, long before the
# baked health check first reports healthy. (docker compose up -d keycloak starts Keycloak and its
# own dependencies only; the gateway and remaining infra are started afterwards.)
echo "🐳 Starting Keycloak first (the Quarkus $MODE gateway starts only after Keycloak is ready)..."
(cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d keycloak)

# Wait for Keycloak to be ready first
echo "⏳ Waiting for Keycloak to be ready (management ${KC_MGMT_SCHEME} on ${KC_MGMT_PORT})..."
wait_for_url "${KEYCLOAK_HEALTH_URL}" KEYCLOAK_PROBE_OPTS "${KEYCLOAK_WAIT_ATTEMPTS}" "Keycloak" keycloak

# Keycloak is ready — now bring up the gateway and the remaining containers.
echo "🐳 Starting the gateway ($MODE) and remaining containers..."
(cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d)

# The plain-HTTP backends: -f so an error status is not mistaken for readiness, and no -k.
BACKEND_PROBE_OPTS=(-sf --connect-timeout 2 --max-time 5)

# Wait for the go-httpbin upstream backend (proxy target) to be ready
echo "⏳ Waiting for go-httpbin upstream to be ready..."
wait_for_url "http://localhost:${UPSTREAM_PORT}/status/200" BACKEND_PROBE_OPTS "${BACKEND_WAIT_ATTEMPTS}" \
    "go-httpbin upstream" go-httpbin

# Wait for the static nginx backend (benchmark mode only)
if [[ "${BENCHMARK_MODE:-false}" == "true" ]]; then
    STATIC_BACKEND_PORT="$(derived_port nginx-static 8080)"
    if [[ -z "$STATIC_BACKEND_PORT" ]]; then
        echo "❌ Benchmark mode, but the resolved Compose model publishes no nginx-static port 8080"
        exit 1
    fi
    echo "⏳ Waiting for nginx-static backend to be ready..."
    wait_for_url "http://localhost:${STATIC_BACKEND_PORT}/" BACKEND_PROBE_OPTS "${BACKEND_WAIT_ATTEMPTS}" \
        "nginx-static backend" nginx-static
fi

# Capture everything a failed bring-up needs to be diagnosed from CI artifacts alone: the container
# log and the aggregate health payload, written under target/failsafe-reports/ so they are uploaded
# with the Failsafe reports. Both failure paths of the two-layer gate below call this, so the evidence
# is identical whichever layer caught the problem — a container that never reported healthy and a
# container that reported healthy but is not READY are diagnosed from the same two files.
#
# The health path is passed in as the service's derived management root path rather than spelled out,
# so this collector keeps working against a gateway whose management context path was moved.
capture_gateway_diagnostics() {
    local service="$1"
    local mgmt_url="$2"
    local mgmt_root="$3"
    local diag_dir="target/failsafe-reports"
    local diag_opts=(-s --connect-timeout 2 --max-time 5)

    # -k for the same reason the readiness assertion needs it: an https:// management interface serves
    # a self-signed localhost bundle, so without it curl fails certificate validation and writes an
    # empty health payload — destroying the very evidence this function exists to capture.
    if [[ "$mgmt_url" == https://* ]]; then
        diag_opts+=(-k)
    fi

    mkdir -p "$diag_dir"
    # Both captures are explicitly non-fatal. This function runs only on a path that has already
    # decided to fail, and its caller loops over every gateway instance before exiting — so a probe
    # that refuses the connection (the common case here, and exactly what we are diagnosing) must not
    # be able to cut the loop short and cost us the diagnostics for the remaining instances. Today
    # `pipefail` is not set, so each pipeline already reports tee's status rather than curl's and the
    # `|| true` is belt-and-braces; it is written anyway so that turning `pipefail` on later cannot
    # silently convert this collector into an early exit. The operator sample's
    # capture_sample_diagnostics guards the same two commands the same way.
    echo "----- $COMPOSE_BASE logs ${service} -----"
    $COMPOSE_BASE logs --no-color "${service}" </dev/null 2>&1 | tee "$diag_dir/${service}-app.log" || true
    echo "----- ${mgmt_url}${mgmt_root}/health -----"
    curl "${diag_opts[@]}" "${mgmt_url}${mgmt_root}/health" 2>&1 | tee "$diag_dir/${service}-health.json" || true
    echo ""
}

# Gate the suite on every gateway instance being READY — not merely started — in two layers.
#
# Layer 1 (waiting) is Compose's own wait on the image's baked-in HEALTHCHECK. api-sheriff:distroless
# is built from Dockerfile.native, which bakes an exec-form probe, so `up -d --wait` blocks until every
# named service reports healthy. The polling that used to live here as a hand-rolled retry loop is now
# the container runtime's job, and the probe cadence has exactly one home — that HEALTHCHECK line. It
# is deliberately not restated here, and there is no retry budget in this script to drift from it.
#
# The wait is scoped to the derived gateway service names, NOT the whole stack. `--wait` with no
# service arguments waits on every service in the resolved model, which would implicitly health-gate
# keycloak, go-httpbin, asset-origin and the benchmark overlay's nginx-static. None of those carries a
# baked probe and each already has its own wait above, so an unscoped `--wait` would gate on something
# other than "the gateways are healthy". The service names come from the same READINESS_TARGETS rows
# derived from the Compose model before the bring-up, so adding or renaming an api-sheriff* service
# needs no edit here.
#
# Layer 2 (semantics) is the assertion the baked probe deliberately cannot make. That probe is a bare
# TCP accept on the management port — it must be, because the management scheme is deployment-bound
# (ADR-0025) — so it proves the interface is listening, not that GatewayReadinessCheck reported UP:
# the configuration document bound and, where a token_validation block is configured, every
# configured issuer's JWKS key set loaded. A single-shot readiness probe per instance,
# addressed beneath that instance's derived management root path, closes exactly that gap. Every
# instance is asserted, not just the primary one: the suites drive the
# TLS ports directly — MtlsHandshakeIT, the Bff*Cookie*IT suites (BffCookieStatelessnessIT drives BOTH
# cookie instances in one test), WebSocketProxyIT's relay-exhaustion regression against the
# low-admission instance — so an unasserted instance is a race that surfaces as a connection refusal
# in the IT phase rather than as a start-up failure here.
#
# Single-shot with no retry budget is correct precisely BECAUSE layer 1 already waited: by the time
# this runs the instance has been reported healthy by the runtime, so a non-UP readiness answer is a
# real defect, and retrying would only delay reporting it. That holds only while Keycloak is ready
# before the gateways start (see the Keycloak gate above): readiness also waits for each issuer's
# key set, which the TCP-accept health check cannot see (the readiness-gate rules in
# doc/development/integration-test-topology.adoc record the residual). That is also why the scheme still comes
# from each service's management-scheme label rather than from its name — the plain-management
# instance is asserted over http:// with NO -k, and if it ever needs -k the plain-management opt-out
# has silently stopped working and THAT is the bug, not the probe.
GATEWAY_SERVICES="$(printf '%s\n' "$READINESS_TARGETS" | awk '{ print $1 }')"

echo "⏳ Waiting for the discovered gateway instances to report healthy..."
START_TIME=$(date +%s)

# Word-splitting GATEWAY_SERVICES into one argument per service is the point, hence the unquoted
# expansion. The `if ! ...; then` wrapper is required rather than stylistic: set -e is active, so a
# bare failing command would abort before the diagnostics below could run.
# shellcheck disable=SC2086
if ! (cd "${PROJECT_DIR}" && $COMPOSE_CMD up -d --wait --wait-timeout 180 $GATEWAY_SERVICES); then
    echo "❌ Not every gateway instance reported healthy within 180s"
    while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT GATEWAY_MGMT_ROOT; do
        [[ -z "$GATEWAY_SERVICE" ]] && continue
        GATEWAY_MGMT_ROOT="$(normalize_root_path "${GATEWAY_MGMT_ROOT}")"
        capture_gateway_diagnostics "${GATEWAY_SERVICE}" \
            "${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}" "${GATEWAY_MGMT_ROOT}"
    done <<< "$READINESS_TARGETS"
    exit 1
fi
echo "✅ Every gateway instance reported healthy — asserting readiness semantics..."

GATEWAY_COUNT=0
while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT GATEWAY_MGMT_ROOT; do
    [[ -z "$GATEWAY_SERVICE" ]] && continue
    GATEWAY_MGMT_ROOT="$(normalize_root_path "${GATEWAY_MGMT_ROOT}")"
    GATEWAY_COUNT=$((GATEWAY_COUNT + 1))
    GATEWAY_MGMT_URL="${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}"
    GATEWAY_READY_URL="${GATEWAY_MGMT_URL}${GATEWAY_MGMT_ROOT}/health/ready"

    GATEWAY_READY_OPTS=(-sf --connect-timeout 2 --max-time 5)
    if [[ "$GATEWAY_MGMT_SCHEME" == "https" ]]; then
        # -k is load-bearing on the HTTPS instances: their management interface serves a self-signed
        # localhost bundle, and without it curl fails certificate validation and this assertion fails
        # against a perfectly healthy container. -f is equally load-bearing: the readiness endpoint
        # answers 503 when the check is DOWN, and without -f curl exits 0 on that 503.
        GATEWAY_READY_OPTS+=(-k)
    fi

    if ! curl "${GATEWAY_READY_OPTS[@]}" "${GATEWAY_READY_URL}" > /dev/null 2>&1; then
        echo "❌ ${GATEWAY_SERVICE} reported healthy but ${GATEWAY_READY_URL} is not UP"
        capture_gateway_diagnostics "${GATEWAY_SERVICE}" "${GATEWAY_MGMT_URL}" "${GATEWAY_MGMT_ROOT}"
        exit 1
    fi
    echo "✅ ${GATEWAY_SERVICE} gateway instance is ready!"
done <<< "$READINESS_TARGETS"

TOTAL_TIME=$(($(date +%s) - START_TIME))
echo "✅ All ${GATEWAY_COUNT} gateway instances are ready!"
# The instances were all started by the same `compose up -d` above and come up concurrently, so this
# brackets both layers of the gate: the wall-clock time until the slowest of them reported healthy and
# then answered ready, not the sum of their startup times.
echo "📈 Actual startup time: ${TOTAL_TIME}s (container + application, ${GATEWAY_COUNT} instances in parallel)"

# Extract native startup time from logs
NATIVE_STARTUP=$($COMPOSE_BASE logs api-sheriff 2>/dev/null | grep "started in" | sed -n 's/.*started in \([0-9.]*\)s.*/\1/p' | tail -1)
if [ ! -z "$NATIVE_STARTUP" ]; then
    echo "⚡ Native app startup: ${NATIVE_STARTUP}s (application only)"
fi

# Show actual image size
IMAGE_SIZE=$(docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}" | grep "api-sheriff:" | grep -v integration | awk '{print $2}' | head -1)
if [ ! -z "$IMAGE_SIZE" ]; then
    echo "📦 Image size: ${IMAGE_SIZE} (native image)"
fi

echo ""
echo "🎉 API Sheriff Integration Benchmark Environment is running!"
echo ""
echo "📱 Application URLs:"
# Listed from the SAME discovered targets the readiness loop probed, so this stays correct when an
# instance is added, removed or renumbered — and so it cannot claim management is HTTPS-only, which
# the plain-management instance makes false.
while read -r GATEWAY_SERVICE GATEWAY_MGMT_SCHEME GATEWAY_MGMT_PORT GATEWAY_MGMT_ROOT; do
    [[ -z "$GATEWAY_SERVICE" ]] && continue
    GATEWAY_MGMT_ROOT="$(normalize_root_path "${GATEWAY_MGMT_ROOT}")"
    echo "  🔍 ${GATEWAY_SERVICE} management: ${GATEWAY_MGMT_SCHEME}://localhost:${GATEWAY_MGMT_PORT}${GATEWAY_MGMT_ROOT}/health (metrics at ${GATEWAY_MGMT_ROOT}/metrics)"
done <<< "$READINESS_TARGETS"
echo "  🔑 Keycloak:       https://localhost:${KEYCLOAK_HTTPS_PORT}/auth"
echo ""
echo "🧪 Quick test commands (an https:// management port serves a self-signed cert — -k is required there):"
# Printed from the SAME derived Keycloak row the wait above probed, so this cannot drift from what
# docker-compose.yml publishes.
echo "  curl ${KEYCLOAK_PROBE_OPTS[*]} ${KEYCLOAK_HEALTH_URL}"
echo ""
echo "🛑 To stop: ./scripts/stop-integration-container.sh"
echo "📋 To view logs: ${COMPOSE_BASE} logs -f"
