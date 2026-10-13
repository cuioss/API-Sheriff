# Landing Analysis: PLAN-V02-21 — Act on Review Findings That Were Never Read Before Merge

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #439 (https://github.com/cuioss/API-Sheriff/pull/439)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-13 from the inbox
> message `plan-v02-21-unread-review-findings-004.md` (inbox-scan mode), after verifying each material
> claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `48e8d5a6` (#439, squash, 4 commits, 10 files).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — cookie-mode seal at INFO only at login | shipped-as-specified | `CookieSessionBinding` makes `bind` the only call site of `COOKIE_SESSION_SEALED`; re-seals through `persist` / `persistReissuingCookie` log at DEBUG. `CookieSessionBindingTest` (+62 lines), `doc/LogMessages.adoc` updated. |
| D2 — one polling helper in `start-integration-container.sh` | shipped-as-specified | `wait_for_url()` (line 334); `KEYCLOAK_WAIT_ATTEMPTS=120`, `BACKEND_WAIT_ATTEMPTS=30`; no literal `1443`, `18080` or `18081` left in the script. Port rows are emitted as `port <service> …` and readiness targets are selected positively (`^api-sheriff`), fixing an exclusion-filter leak the outline Q-Gate found. |
| D3 — three compose fixes | shipped-as-specified | `x-gateway-hardening` and `x-gateway-backend-deps` anchors; all 12 gateway services merge the backend deps (`service_healthy` on `passthrough-backend` and `toxiproxy`), because every gateway mounts the shared `sheriff-config` endpoints; the start-time `apk add` is gone (new fixture image `integration-tests/src/main/docker/passthrough-backend/Dockerfile`). |
| D4 — `ImageMetadataIT` inspects the lane's image | **declined by the operator at refine** | The Failsafe excludes in `integration-tests/pom.xml` already keep each lane off the other image type. No file changed for it. |
| D5 — ADR-0031 says what the scripts do | shipped-as-specified | ADR-0031 amended in place (+39 lines); `doc/development/integration-test-topology.adoc` aligned. |

**Realized footprint against the declared surface.** All 10 files lie inside the declared surface. Not
touched although declared: `ImageMetadataIT` and `integration-tests/pom.xml` (D4 declined),
`demo-client/scripts/start-dev-environment.sh` (see residue).

## Metrics and Anomalies

- Tokens: 3,698,752. Duration: 4h26m wall (15,990 s).
- Sonar: 0 new-code issues. Self-review: 0 blocking findings.
- Anomalies:
  - The outline leaf left 3-outline before the orchestrator-level Q-Gate ran; the plan's
    orchestrator moved it back by hand. Same root as lesson `2026-10-04-09-003` (fourth occurrence).
  - The pre-push gate recorded module tests as DEGRADED (no bundle derivation in this Maven repo).
  - Local `verify -Pintegration-tests` failed twice on `Http2EarlyResponseResetIT` (20 s batch
    timeout) while CI was green on the same head — a local flake of the test #431 added.

## Routing and Merge Behavior

- Merged through the merge queue; the merge-group `Maven Build` and `Integration Tests` runs succeeded.
- Post-merge on `48e8d5a6`, checked 2026-10-13: `Maven Build`, `Config Validation`, `Demo Client E2E`
  and Scorecard push runs **success**; the push `Integration Tests` run and the PR-attached
  Performance Benchmark run 38409700336 were **still in progress** when this was written.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `439`; `landing` `landings/PLAN-V02-21.md`
- [x] inbox `-001`…`-004` archived: `-002` and `-003` promoted (`2026-10-13-14-001`, `-002`); `-001`
      folded into `2026-10-04-09-003` as its fourth occurrence; `-004` reconciled here
- [x] Watch added for the two documentation leftovers below
- [x] `PLAN-V02-23` now waits on `PLAN-V02-12` alone

## Follow-Ups

- **ADR-0031 still says "seven gateway instances"** (line 115); the stack runs twelve. Out of the plan's
  scope; recorded as a Watch.
- **`demo-client/scripts/start-dev-environment.sh` keeps a literal `120`** for its Keycloak wait
  (lines 244–255), contrary to the amended ADR-0031's one-named-constant rule. Recorded as a Watch;
  `PLAN-V02-22` (demo client) is the natural home.
- Post-merge `Integration Tests` and benchmark results on `48e8d5a6` — confirm at the next analyze.
