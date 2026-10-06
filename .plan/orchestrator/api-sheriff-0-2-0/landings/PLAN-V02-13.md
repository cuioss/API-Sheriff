# Landing Analysis: PLAN-V02-13 — The Terminal-Rejection Contract

epic: api-sheriff-0-2-0
workstream: WS-03
pr: #383 (https://github.com/cuioss/API-Sheriff/pull/383)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-04 from the inbox
> message `plan-v02-13-terminal-rejection-contract-001.md` (inbox-scan mode, `landing-check`
> `complete: true`), after verifying each material claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against squash commit `5ddf8081` on `origin/main` (39 files, +3,089/−269) and the PR state
read through the CI abstraction (`state: merged`, same commit). The plan reports 7 of 7 deliverables.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — re-categorise the three misfiled members | shipped-as-specified | PR title `feat(events)!: recategorise routing rejections…`; ADR-0059 *Routing rejections report under their own ROUTING problem category*; `EventCategory.java` and `EventType.java` in the diff; issue #188 closed. |
| D2 — HTML eligibility of the two re-categorised members | shipped-as-specified | `ErrorPageClassifier` in the diff; per the #189 comment, the 405 and the reserved-passthrough-host 404 are now HTML-eligible, the 405 with the fixed title `Method Not Allowed`. |
| D3 — rendering constraints pinned by tests | shipped-as-specified | `GatewayEdgeErrorPageTest`, `ErrorPageClassifierTest`, `ErrorContractIT` in the diff. |
| D4 — `/auth/userinfo` left alone | shipped-as-specified | The surface delta lists `/auth/userinfo` and `SessionAuthenticationStage.java` as declared but untouched — the expected outcome for a no-edit boundary. |
| D5 — tests, documentation, ADR | shipped-as-specified | ADR-0059 (status `Proposed`); `doc/LogMessages.adoc`, `doc/configuration.adoc`, `doc/user/portal.adoc`, `doc/user/protocol-routes.adoc`. |
| D6 — request framing `gw-02` | shipped-modified | `FramingGate.java`, `FramingGateTest`, `GatewayEdgeFramingCorpusTest` in the diff. The threat model now records `gw-02` as **PARTIAL**: the HTTP/2 clauses beyond the stream-scoped gate rejection are not pinned. |
| D7 — WebSocket Origin allowlist at boot for session routes | shipped-as-specified | `ConfigValidator` in the diff; the threat model's `gw-09` row now reads *allowlist mandatory at boot on bearer and session routes; session cookie `SameSite=Lax`*; ADR-0015 updated. |

**Surface delta** (`inbox landing-check`, declared 11 vs realized 39): `expansion_detected`, 31
added paths. Most are the tests and documentation one would expect, but the expansion reached into
`edge/DispatchStage.java`, `routing/RouteRuntime.java`, `config/model/Require.java`,
`config/model/WebSocketConfig.java`, `quarkus/SheriffMetrics.java` and `ApiSheriffLogMessages.java`,
none of them declared.

**Collision with the concurrently running `PLAN-V02-10`, not predicted by the gate.** Both plans
changed `edge/DispatchStage.java`, `edge/GatewayEdgeRoute.java`, `DispatchStageTest`,
`GatewayEdgeRouteTest` and `doc/configuration.adoc`. The gate passed them as disjoint because
neither spec declared `DispatchStage.java` and `PLAN-V02-10` declared no `api-sheriff/src/main/`
path at all. This plan merged first; `PLAN-V02-10` integrated over it. See
`landings/PLAN-V02-10.md`.

## Metrics and Anomalies

- Tokens: 7,103,106 total.
- Duration: 15h55m wall (57,324 s).
- Anomalies: the finalize loop-back count went past the configured maximum — round 6 was authorized
  by the operator for a CodeRabbit fix, and one further CodeRabbit fix (an atomic abort claim in
  `DispatchStage`) was applied under the same authorization. `adr-propose` and `lessons-capture`
  were skipped; the plan filed no candidate lessons.
- A pre-existing flaky test surfaced in CI: `BffRuntimeProducerTest.shouldRefuseAnUnboundRefreshInClientSecretMode`
  failed once with a `ConcurrentModificationException` from `assertNoRecordCarriesTheSecret`. It
  passed on re-run. A live plan outside this epic, `fix-four-audit-findings-and-bff-flaky-test`, is
  open under `.plan/local/plans/` and appears to own it.

## Routing and Merge Behavior

- Review: zero unresolved resolvable threads.
- CI/merge: 34 PR checks, all green; merged through the merge queue as `5ddf8081`.
- Post-merge, checked by the orchestrator 2026-10-04:
  - PR-attached `Run Integration Benchmarks` (run 37148465291): success.
  - Main-branch push runs for `5ddf8081`: `Maven Build` (run 37148463886) success with every job
    green including `deploy-snapshot`; `Integration Tests`, `Demo Client E2E`, `Scorecard` success.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `383`; `landing` `landings/PLAN-V02-13.md`;
      `plan_marshall_plan_id` `plan-v02-13-terminal-rejection-contract`
- [x] inbox message archived (`reconciled`)
- [x] `PLAN-V02-06` and `PLAN-V02-07` specs corrected: the taxonomy dependency is satisfied
- [x] the `gw-02` HTTP/2 residue folded into `PLAN-V02-06` D8, which already owns HTTP/2 framing
      bounds; declared surface unchanged
- [x] Watches added: issue #189 remainder, ADR-0059 acceptance
- [x] collision with `PLAN-V02-10` recorded in `epic.md` § Queue annotations
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- **Issue #189 stays open** for a demo-client panel (`demo-client/src/main/resources/spa/index.html`,
  `app.js`) that fires each variant and reports status, `Content-Type` and redirect, plus three
  documents: `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc`, and the
  PROHIBITED ASSERTION scope wording in `demo-client/doc/playwright-suite.adoc`. The issue comment
  also lists `doc/plan/04-request-pipeline.adoc`, which no longer exists on `main` — that item is
  already discharged. Recorded as a Watch; no plan staged.
- **ADR-0059 is `Proposed`** and needs an acceptance decision. Recorded as a Watch.
- **`gw-02` PARTIAL** — folded into `PLAN-V02-06` D8.
