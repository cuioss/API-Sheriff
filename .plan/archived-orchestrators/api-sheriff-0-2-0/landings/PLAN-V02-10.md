# Landing Analysis: PLAN-V02-10 — Delete the Process-Global Truststore Override

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #382 (https://github.com/cuioss/API-Sheriff/pull/382)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-04 from the inbox
> message `plan-v02-10-per-client-tls-trust-018.md` (inbox-scan mode, `landing-check`
> `complete: true`), after verifying each material claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against merge commit `35f2bb37` on `origin/main` (25 files, +808/−217) and the PR state read
through the CI abstraction (`state: merged`, same commit). The spec carried one deliverable; the
plan's outline cut it into four, all reported done.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Delete the process-global truststore override at every site | shipped-modified | All ten integration `sheriff-config*/gateway.yaml` overlays, the two trust-properties files under `integration-tests/src/main/docker/certificates/`, `OneOffGatewayContainers.java` and the integration documentation are in the diff. A new guard, `ItProfileConfigBindingWiringTest`, fails the build if a `-Djavax.net.ssl.*` argument reaches a gateway through compose `entrypoint`/`command` or a one-off launch site. ADR-0042 and ADR-0045 were narrowed to the population actually checked. Per the PR body, every integration gateway now resolves JWKS trust through `jwks.tls_profile` and the BFF back channel through `egress_tls.oidc_tls_profile`, logs WARN `ApiSheriff-126` at boot and no longer logs `ApiSheriff-122`. |
| (unplanned) close the edge relay race | added-unplanned | The merge queue's re-test exposed a pre-existing race: `DispatchStage` attached the upstream pause too late, and a relay-start failure left the client unanswered. Fixed in `edge/DispatchStage.java` and `edge/GatewayEdgeRoute.java` with deterministic regression tests that reproduce the failure on unmodified `main` at `5ddf8081`. This is the only change under `api-sheriff/src/main/`, and it is the subject of the PR's `fix:` prefix. |

**Surface delta** (`inbox landing-check`, declared 3 vs realized 25): `expansion_detected`, 22
added paths, none declared and untouched.

**Collision with `PLAN-V02-13`, not predicted by the gate.** Both plans changed
`edge/DispatchStage.java`, `edge/GatewayEdgeRoute.java`, `DispatchStageTest`, `GatewayEdgeRouteTest`
and `doc/configuration.adoc`. The spec declared no `api-sheriff/src/main/` path because the
deliverable needed none; the overlap came from the unplanned race fix, found at the merge queue
after `PLAN-V02-13` had merged. A declaration could not have predicted it. The lesson is the
opposite one: the gate is only as good as the declarations, and an unplanned production fix in a
hygiene plan is exactly what it cannot see.

## Metrics and Anomalies

- Tokens: 8,927,445 total.
- Duration: 25h41m wall (92,468 s).
- Anomalies:
  - The merge queue's re-test failed once (`PipelineVerbIT.putIsForwarded`,
    `NoHttpResponseException` after 60 s). It was first classified as environmental, then as a
    pre-existing timing issue, and only a reproduction on the base tree established it as the relay
    race above (candidate lesson `-001`).
  - Two Maven builds ran in the same worktree at once and produced a false
    `ClassNotFoundException` failure (candidate lesson `-002`).
  - The benchmark comparison in the PR body is marked noisy: the two runs ran under very different
    machine load and the base run failed its own noise-band check, so no difference is attributed.
  - Seventeen candidate lessons, all drained (see Reconciliation).

## Routing and Merge Behavior

- Review: zero unresolved resolvable threads. CodeRabbit raised several findings on the JSSE guard
  across three rounds; all were fixed (`e5a4748f`, `74c2b275`). Sourcery was quota-refused
  throughout (optional bot).
- CI/merge: 33 PR checks, all green; merged through the merge queue as `35f2bb37`.
- Post-merge, checked by the orchestrator 2026-10-04:
  - PR-attached `Run Integration Benchmarks` (run 37189109618): success.
  - Main-branch push runs for `35f2bb37`: `Maven Build` (run 37189108811) success with every job
    green including `deploy-snapshot`; `Integration Tests`, `Demo Client E2E`, `Scorecard` success.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `382`; `landing` `landings/PLAN-V02-10.md`;
      `plan_marshall_plan_id` `plan-v02-10-per-client-tls-trust`
- [x] inbox `-018` archived (`reconciled`); seventeen candidate lessons dispositioned and archived:
      ten promoted (two of them aggregated clusters, `-011`+`-012` and `-014`…`-017`), three folded as
      recurrences into existing lessons (`-003` into `2026-10-03-06-005`; `-004` and `-005` into
      `2026-10-03-06-006`)
- [x] collision with `PLAN-V02-13` recorded in `epic.md` § Queue annotations
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- None staged. The relay-race fix is complete with its regression tests. Issue #201 (`MtlsHandshakeIT`
  fail-open under `-Pjfr`) stays with `PLAN-V02-11` as before.
