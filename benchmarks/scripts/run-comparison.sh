#!/usr/bin/env bash
#
# On-demand comparative benchmark entry point (API Sheriff vs APISIX).
#
# This is the ONLY documented way to run the comparison lane. It is deliberately registered in no
# Maven profile and referenced by no workflow under .github/workflows/ -- the comparison is an
# operator-invoked investigation, not CI. Wiring it into CI would multiply the benchmark wall time
# by the number of targets and would publish comparison numbers into the baseline trend series.
#
# Results are written to a directory segregated from BOTH the CI baseline results and the gh-pages
# tree, so a comparison run can never overwrite a baseline artifact or leak into the published
# history/trend series keyed by benchmark name.
#
# Usage:
#   run-comparison.sh [--target NAME] [--aspects a,b,c] [--output DIR] [--duration D] [--vus N]
#
#   --target    gateway to measure: api-sheriff (default) | apisix | both
#   --aspects   comma-separated subset of the matrix (default: all six)
#   --output    results root (default: target/comparison-results)
#   --duration  k6 run duration per aspect (default: 60s)
#   --vus       VU override for the throughput aspects; upload-50MB keeps its reduced
#               concurrency regardless (see UPLOAD_LARGE_VUS below)
#   --no-dashboard
#               skip the supplementary HTML report and run through the stock baseline k6
#               image instead of the dashboard-enabled one
#
# The xk6-dashboard HTML report is PURELY ADDITIVE. Every aspect emits its handleSummary() JSON in
# the generic cuioss benchmark format regardless of this flag -- that JSON is what
# K6BenchmarkConverter and ComparisonSummaryWriter consume, and the HTML is a human-facing extra
# beside it. Disabling the dashboard therefore changes what a person can browse, never what the
# comparison artifact is computed from.
#
# Prerequisite: the three-file comparison stack must be up --
#   docker compose -f docker-compose.yml \
#                  -f docker-compose.benchmark.yml \
#                  -f docker-compose.apisix.yml \
#                  --profile comparison up -d --build
#
set -euo pipefail

# --- the matrix -------------------------------------------------------------
# Aspect name -> k6 script. ONLY these six are comparable across gateways. The retained
# healthLiveCheck and gatewayHealth benchmarks are deliberately absent: they measure API Sheriff's
# own management port and /api/health surface, which APISIX does not expose. Driving them under
# GATEWAY_TARGET=apisix would still label their summaries `gateway_target: apisix` while actually
# measuring API Sheriff -- mislabelled data that reads as correct. Keeping the matrix closed here
# is what makes that unreachable.
declare -A ASPECT_SCRIPTS=(
    [unauth]=proxied_static.js
    [bearer]=bearer_proxied.js
    [http2]=http2.js
    [graphql]=graphql.js
    [upload-1MB]=upload_small.js
    [upload-50MB]=upload_large.js
)
ALL_ASPECTS="unauth,bearer,http2,graphql,upload-1MB,upload-50MB"

# Reduced concurrency for the transfer-bound aspect; see upload_large.js for the rationale (per-VU
# 50MB payload memory, and link saturation masking the gateway difference).
UPLOAD_LARGE_VUS=5

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHMARKS_DIR="$(dirname "${SCRIPT_DIR}")"
COMPOSE_DIR="${BENCHMARKS_DIR}/../integration-tests"

TARGET="api-sheriff"
ASPECTS="${ALL_ASPECTS}"
OUTPUT_DIR="${BENCHMARKS_DIR}/target/comparison-results"
DURATION="60s"
VUS="50"
DASHBOARD="true"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)   TARGET="$2";     shift 2 ;;
        --aspects)  ASPECTS="$2";    shift 2 ;;
        --output)   OUTPUT_DIR="$2"; shift 2 ;;
        --duration) DURATION="$2";   shift 2 ;;
        --vus)      VUS="$2";        shift 2 ;;
        --no-dashboard) DASHBOARD="false"; shift ;;
        -h|--help)  sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# The dashboard-enabled image carries xk6-dashboard linked into the k6 binary; the baseline image
# does not, so the dashboard output flag is only ever passed to the former.
if [[ "${DASHBOARD}" == "true" ]]; then
    K6_SERVICE="k6-dashboard"
else
    K6_SERVICE="k6"
fi

case "${TARGET}" in
    api-sheriff|apisix) TARGETS=("${TARGET}") ;;
    both)               TARGETS=("api-sheriff" "apisix") ;;
    *) echo "--target must be api-sheriff, apisix or both; got '${TARGET}'" >&2; exit 2 ;;
esac

# Validate the aspect subset up front: an unknown aspect must fail before any run starts, not
# after a 60s benchmark has already been taken against a partial matrix.
IFS=',' read -r -a SELECTED <<< "${ASPECTS}"
for aspect in "${SELECTED[@]}"; do
    if [[ -z "${ASPECT_SCRIPTS[${aspect}]:-}" ]]; then
        echo "unknown aspect '${aspect}'; supported: ${ALL_ASPECTS}" >&2
        exit 2
    fi
done

mkdir -p "${OUTPUT_DIR}"
echo "Comparison run -> ${OUTPUT_DIR}"
echo "  targets: ${TARGETS[*]}"
echo "  aspects: ${ASPECTS}"

for target in "${TARGETS[@]}"; do
    target_dir="${OUTPUT_DIR}/${target}"
    mkdir -p "${target_dir}"

    for aspect in "${SELECTED[@]}"; do
        script="${ASPECT_SCRIPTS[${aspect}]}"
        aspect_vus="${VUS}"
        if [[ "${aspect}" == "upload-50MB" ]]; then
            aspect_vus="${UPLOAD_LARGE_VUS}"
        fi

        # The dashboard report is written per run AND per aspect, into the same segregated
        # results directory as the summary JSON. `report=` makes xk6-dashboard emit a
        # self-contained single-file HTML document with every asset inlined -- no external CDN
        # and no served endpoint -- so the artifact stays readable offline and after the stack
        # is torn down.
        k6_args=(run)
        if [[ "${DASHBOARD}" == "true" ]]; then
            k6_args+=(--out "web-dashboard=report=/results/${aspect}-dashboard.html")
        fi
        k6_args+=("/scripts/${script}")

        echo "==> ${target} / ${aspect} (${script}, ${aspect_vus} VUs, ${DURATION}, dashboard=${DASHBOARD})"
        docker compose \
            -f "${COMPOSE_DIR}/docker-compose.yml" \
            -f "${COMPOSE_DIR}/docker-compose.benchmark.yml" \
            -f "${COMPOSE_DIR}/docker-compose.apisix.yml" \
            --profile comparison \
            run --rm \
            -e "GATEWAY_TARGET=${target}" \
            -e "BENCHMARK_VUS=${aspect_vus}" \
            -e "BENCHMARK_DURATION=${DURATION}" \
            -v "${target_dir}:/results" \
            "${K6_SERVICE}" "${k6_args[@]}"
    done
done

echo
echo "Per-aspect summaries written under ${OUTPUT_DIR}/<target>/."
if [[ "${DASHBOARD}" == "true" ]]; then
    echo "Self-contained HTML reports: ${OUTPUT_DIR}/<target>/<aspect>-dashboard.html"
fi
echo "Render the side-by-side table with ComparisonSummaryWriter:"
echo "  ./mvnw -pl benchmarks exec:java \\"
echo "    -Dexec.mainClass=de.cuioss.sheriff.api.k6.benchmark.ComparisonSummaryWriter \\"
echo "    -Dexec.args=\"${OUTPUT_DIR} api-sheriff apisix\""
