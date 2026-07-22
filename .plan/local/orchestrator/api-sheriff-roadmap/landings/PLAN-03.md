# Landing Analysis: PLAN-03 — Request Pipeline (roadmap 04)

epic: api-sheriff-roadmap
workstream: WS-02
pr: cuioss/API-Sheriff#76 (squash-merged as 9c47959 via merge queue)

> Landing record for one shipped plan. Written by the `analyze` verb after verifying claims
> against ground truth — a pasted claim is a lead, never a fact.

## Deliverable Fidelity vs Spec

Spec staged 13 work-breakdown items; the lifecycle grouped them into 12 deliverables — all
shipped (33 tasks incl. 12 fix/review/sonar tasks, ~34 commits, one squash merge).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Deps + ArchUnit arch-gate | shipped-as-specified | pre-approved deps flow (no ask stop) |
| D4 events/error edge/logging | shipped-modified (improved) | security audit found dormant D4 logging and wired it |
| RouteRuntime assembly + dedup | shipped-as-specified | `routing` package on main |
| Stages 0-3 + GW-01/GW-02 | shipped-modified (improved) | bot review added an RFC 7230 multiple-Content-Length smuggling guard |
| Stage 4 bearer validation | shipped-as-specified | `auth` package; 401/403 ITs |
| Stage 5 forward policy | shipped-as-specified | `forward` package, TcpPeerGate spoof tests |
| Stages 6-7 streamed dispatch | shipped-modified (improved) | simplify gate caught unwired StreamAwareRetryGate; end-to-end Content-Length hop-by-hop bug fixed |
| Vert.x edge + placeholder removal | shipped-as-specified | GatewayResource.java gone (verified); `edge` package present |
| Meters + readiness | shipped-modified (improved) | unwired sheriff_* meters caught and fixed by the plan's own gates |
| IT suite | shipped-as-specified | 23 ITs green on CI |
| Bearer WRK benchmark | shipped-as-specified | bearer_proxied_check.lua + .sh on main (joins the PLAN-09 k6 migration set) |
| Docs | shipped-as-specified | LogMessages.adoc, doc-first edge defaults |

## Metrics and Anomalies

- Tokens: 6,837,731 (largest plan so far); execute 4.30M / finalize 1.54M.
- Duration: 9h14m worked / 18h25m wall.
- Anomalies: two cold-resolution CI classpath gaps masked locally by the warm build daemon
  (captured as lesson); 8 Sonar reliability bugs fixed in-flight; Sonar gate OK at 87.2%
  new-code coverage.
- Build server: 62 daemon jobs served — worktree-based plan builds routed through marshalld
  as intended. The 2026-07-18 registration fix is fully validated.

## Routing and Merge Behavior

- Review: 17 bot comments — 12 fixed, 1 false positive, 4 noted with rationale; all resolved.
- CI/merge: fully green (builds, ITs, SonarCloud); squash-merged via merge queue (single
  parent verified). adr-propose skipped by operator lane opt-out.
- No surface collisions.

## Reconciliation Actions

- [x] status.json `plans[]` entry updated (PLAN-03 → shipped, pr=#76, landing=landings/PLAN-03.md)
- [x] epic.md queue reconciled from status.json
- [x] Watch retired: build-server worktree routing (verified — 62 daemon jobs)
- [x] Open Defect added: readiness-message disclosure on the management port (advisory)
- [x] Watch added: orphaned JAX-RS GatewayExceptionMapper (cleanup candidate for the next api-sheriff-touching plan)
- [x] resume_anchor updated
- [x] START-HERE block regenerated

## Follow-Ups

- Readiness-message disclosure (management port) → Open Defect; natural owners: PLAN-05
  (tls-edge) or the PLAN-08 security audit.
- Orphaned GatewayExceptionMapper → watch; fold into PLAN-04 (protocol processors) as
  incidental cleanup.
- CodeRabbit test-assertion nitpick → noted in the archived plan; not ledger-tracked.
- Operator-side: `wsl --update` + Rancher Desktop restart recommended by the plan so Docker
  integration survives the next reboot (operator action, not ledger-tracked).
- Five lessons (2026-07-19-17-001..005) in the corpus; the warm-daemon-masking and
  unit-green≠integrated lessons are directly relevant to PLAN-09's harness work.
