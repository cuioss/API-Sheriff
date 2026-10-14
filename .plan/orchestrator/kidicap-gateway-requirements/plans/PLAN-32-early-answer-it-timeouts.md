# PLAN-32: `Http2EarlyResponseResetIT` Times Out Since #448 — Reproduce, Fix, Prove

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-32-early-answer-it-timeouts.md` and is queued as one row file,
> `queue/PLAN-32.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
> Staged 2026-10-14 as **top priority** (operator) after PLAN-30's PR #448 merged (`b7b98699`) with the
> integration-test failure recorded in this epic's Open Defects still unfixed.

## Objective

`Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway` fails intermittently since
PLAN-30's change. On #448's head `bc002caf` it timed out in attempts 1 and 2 of Integration Tests run
38538582322 and passed on attempt 3; the merge-group run (38562814643) and the `main` push run after the
merge (38565584568) passed. That is 2 failures in 5 runs on the same code. On `main` before #448 the test
passed in every push and merge-group run since #431 (about 20). Every open PR now inherits the flake.

The failure: a batch of 10 HTTP/2 uploads on one connection does not settle within the test's 20 s
batch timeout (`TimeoutException` at `Http2EarlyResponseResetIT.java:144`); 289 of 290 ITs pass.

This plan finds the cause, fixes it in the gateway (not in the test), and proves the fix. PLAN-30's goal
— early upstream answers must not exhaust the gateway's local ports — must survive.

## Source

- This epic's Open Defect "PLAN-30's PR #448 fails `Http2EarlyResponseResetIT` in CI" (2026-10-14).
- Run 38538582322 attempts 1 and 2 (job logs: the `TimeoutException` at line 144, attempt 1 at
  09:05Z, attempt 2 at 09:31Z).

## Deliverables

1. **Reproduce before changing anything.** Run the IT repeatedly (at least 10 times) against `main`
   (`b7b98699` or later) and against `583a5fab` (the parent, before #448), locally and/or through CI
   re-runs, and record the failure rate of each. If it cannot be reproduced on `main` at all, STOP and
   file a finding to the epic inbox with the runs: the cause is then not established, and lesson
   `2026-10-13-14-001` (do not record a fix as working until a run shows it) applies.
2. **Find the cause.** The prime suspect is `b93aa3a6`'s `setSoLinger(0)` on the shared upstream
   `HttpClient` in `GatewayEdgeRoute`: it makes **every** upstream connection close abortive (an RST),
   not only the gateway-initiated close after an early answer that PLAN-30 targeted. Check also
   `fe151938`/`DispatchStage.onUpstreamRequestFailure` (the upstream request's exception handler) and the
   circuit-breaker name change (scheme and h2 now in the guard name). Confirm or refute each against the
   reproduction, not by reading.
3. **Fix it narrowly.** If the linger setting is the cause, scope the abortive close to the connection the
   gateway itself closes after an early answer (or find another way to keep PLAN-30's port-exhaustion
   fix), so ordinary upstream closes are graceful again. The IT stays as it is: do not raise its batch
   timeout or batch size to make it pass. If the test itself is wrong, say why with evidence and STOP for
   the operator before changing it.
4. **Prove it.** The IT passes in at least 10 consecutive runs on the branch, and PLAN-30's own
   reproduction (its port-exhaustion test and the upload benchmark) still passes. Amend ADR-0066 (or
   PLAN-30's amendment of it) if the mechanism changes.

Split guard: 4 deliverables.

## Claim Labels

- OBSERVED: #448 merged as `b7b98699` with head `bc002caf`; no commit after the recorded failure — `ci pr view 448`, 2026-10-14
- OBSERVED: run 38538582322 attempts 1 and 2 failed in `earlyAnsweredUploadsStayAnsweredWithoutGoAway` with `TimeoutException` at line 144; attempt 3, merge-group run 38562814643 and push run 38565584568 passed — job logs, 2026-10-14
- OBSERVED: `b93aa3a6` adds `.setSoLinger(0)` to the upstream `HttpClientOptions` in `GatewayEdgeRoute` — `git diff 583a5fab b7b98699`
- HYPOTHESIS: the linger setting causes the timeouts — confirm/refute by deliverable 1 and 2 (verify-at-outline)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — `GatewayEdgeEarlyResponseResetTest`, `GatewayEdgeRouteTest`, `DispatchStageTest`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/Http2EarlyResponseResetIT.java` — read-only unless deliverable 3's STOP clause is cleared by the operator
- HYPOTHESIS: `doc/adr/0066-…adoc`, `doc/architecture.adoc` — if the mechanism changes (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-30 (#448, `b7b98699`), merged.
- **Top priority (operator, 2026-10-14).** Runs now, concurrently with `api-sheriff-0-2-0`'s
  `PLAN-V02-23` (which also edits `GatewayEdgeRoute` and `ResponseStage`) and `PLAN-V02-12`'s second PR;
  whichever merges second rebases. This plan is small and should merge first.
- Before PLAN-29 (also edits `GatewayEdgeRoute` and `DispatchStage`).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-32-early-answer-it-timeouts.md" plan_id=plan-32-early-answer-it-timeouts
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
