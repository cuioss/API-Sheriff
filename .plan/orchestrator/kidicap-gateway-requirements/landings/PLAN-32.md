# Landing Analysis: PLAN-32 — The Http2EarlyResponseResetIT Timeouts Since #448

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #466 (merged as 73233f1edb0fb63b4cafe6672984cd34b3ac0606)

> Landing record for one shipped plan. Lives at `landings/PLAN-32.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the inbox landing message `plan-32-early-answer-it-timeouts-029.md`.**
`inbox landing-check` reports `complete: true` with no missing keys. Corroborated 2026-10-15 at `73721077`:
`ci pr view 466` reports `state: merged` with that merge commit, which is on `origin/main` two commits
below its tip (#469 and #470, both ledger lands). `ci checks status --pr-number 466` reports
`overall_status: success`. All nine rows `pr comments --unresolved-only` returns carry an empty
`thread_id`, so no resolvable review thread is open. Issue #455 is `CLOSED`. The merged diff touches
17 files (+2055/−78).

## Deliverable Fidelity vs Spec

The spec staged 4 deliverables. The plan ran 6: the spec's four were split into D1–D5, and the operator
added D6 during the plan. Functional review: 4 met, 2 met with residuals, 0 not met.

| Deliverable | Verdict | Evidence |
|-------------|---------|----------|
| Spec 1 — reproduce in CI first (plan D1) | shipped | Reproduced in CI. The prime suspect `SO_LINGER 0` was **refuted**: arm M 5/120 vs no-linger arm R 3/120. Run counts are the sender's statement. The IT is unchanged on `main`; it is not in the merged diff |
| Spec 2 — find the cause (plan D2) | shipped, cause differs from the spec's suspect | Diag branch `diag/plan-32-main` @a660e3eb, run 38603393080: arm J 59/100, arm N 5/100, one signature. Cause: three bounds acting together. The upstream HTTP/1.1 pool holds 5 connections (Vert.x default). The inbound HTTP/2 connection window is 65,535 bytes, the same as one stream window. The upstream request head is written lazily with the first body chunk. Together these can deadlock a batch of concurrent uploads on one connection |
| Spec 3 — fix narrowly (plan D3) | shipped, different mechanism | The spec's fix (narrow the linger) became moot when the linger was refuted. Instead, `EdgeHardeningOptions` (+110) sets the inbound HTTP/2 connection window to max concurrent streams × initial stream window (clamped), and caps max concurrent streams at 100. `SO_LINGER 0` from #448 **stays** on every close of the default upstream client. The IT's batch timeout and size are untouched, as the spec required |
| Spec 4 — prove it (plan D4) | shipped | Sender: both diag arms 20/20 green at the plan head, then 10/10 per arm after the stream cap was added (spec: ≥10 consecutive). PR CI green. `GatewayEdgeEarlyResponseResetTest` (+153) reproduces the deadlock in the JVM and fails without the fix |
| Docs (plan D5) | shipped, with residuals | ADR-0060 amended, not ADR-0066 (the mechanism moved from the close lifecycle to body framing and flow control). Also `doc/architecture.adoc`, `doc/user/endpoint-routes.adoc`, `doc/LogMessages.adoc`. Two functional-review findings (1fbd73, 0ddd51): both docs stated a window derived from 100 streams that the code did not configure. Fixed by loop-back TASK-9, which made the cap explicit. Lesson `2026-10-15-12-001` |
| Operator-added — request-body idle bound (plan D6) | shipped | `DispatchStage` (+357) and `GatewayEdgeRoute` (+99): when no request-body byte arrives for 10 s during dispatch, the upstream request is reset, its pooled connection is released at once, and the client gets `408 Request Timeout` instead of the guard's 30 s `504`, which counted against the upstream circuit breaker. New `GatewayEdgeRequestBodyIdleTimeoutTest` (+435), a new log record (`ApiSheriffLogMessages`, `LogMessages.adoc`), and a new `EventType` (+`ErrorPageClassifier`). gRPC routes are excluded (`GrpcDispatchStage`). **A client-visible behaviour change**: a stalled upload now gets a fixed 408 after 10 s. The 10 s is not configurable |

## Metrics and Anomalies

- **10,290,935 tokens and 22 h 22 m wall** (80,493 s), the longest and most expensive plan in this epic,
  about 1.6× PLAN-30. The cost came from the refuted first hypothesis (two re-outlines), the 100/120-run
  diagnostic CI rounds, a finalize loop-back (TASK-9, stream cap) and a ci-verify loop-back (TASK-011,
  a timing race in the refactored test that only showed on a 2-core runner; lesson `2026-10-15-12-002`).
- Every finalize step up to `branch-cleanup` is recorded `done`. `emit-landing` and `archive-plan` read
  `pending`, which is expected because the landing is written before those two complete.
  `code-review` closed by **operator override** at dbf40cda: the final commit only applied two
  one-line Sonar dispositions on a head where review had closed clean. CI, the bots and Sonar re-ran on
  the pushed head. Sonar: 0 new-code issues (confirmed).
- `harness=unknown`: record-metrics found no harness stamp.
- Lessons: 28 candidates. 4 became 3 project lessons (`2026-10-15-12-001`, `-002`, `-003`). The other 24
  are plan-marshall process and tooling candidates, grouped into 13 messages and routed to plan-marshall's
  `lessons-routing` epic inbox. One of them (producer gap) is a recurrence of `lessons-routing-019`.
- **Surface: declared 5 entries (one a directory), realized 17 files.** The spec under-declared because
  the cause it named was refuted. Undeclared: `EdgeHardeningOptions`, `EdgeHardeningTest`,
  `GrpcDispatchStage`, `ApiSheriffLogMessages`, `EventType`, `EventTypeTest`, `ErrorPageClassifier`,
  `ErrorPageClassifierTest`, `GatewayEdgeRequestBodyIdleTimeoutTest` (inside the declared test directory),
  `doc/LogMessages.adoc`, `doc/user/endpoint-routes.adoc`, ADR-0060. Untouched: ADR-0066 (HYPOTHESIS),
  `GatewayEdgeRouteTest`, and the IT, which was read-only by design. Most of the expansion is D6, which the
  operator added after staging.

## Routing and Merge Behavior

- Review: automatic-review recorded 0 comments (2 empty bot reviews, 1 refused). No findings carried
  forward.
- CI/merge: through the merge queue (`step.branch-cleanup.merge_mechanism=merge_queue`);
  `cleanup_owed=false`.
- Post-merge: the main-branch Maven Build and Integration Tests for `73233f1e` were not observed by this
  analysis. Whether `Http2EarlyResponseResetIT` stays green on `main` is now the only open evidence
  question. The sibling ledger already treats #466 as landed (#470: V02-25 now waits only for V02-44).

## Residue

- **#448's `SO_LINGER 0` stays on every close of the default upstream client.** The plan cleared it as
  the cause of the IT timeout, but did not revisit the cost PLAN-30 accepted for it (RST instead of FIN on
  every close, idle evictions included). That remains PLAN-30's recorded trade-off.
- The 10 s request-body idle bound and the stream cap of 100 are fixed values with no configuration key.
  If a downstream needs them tunable, that is new work.
- Functional review: 2 deliverables met with residuals (10 advisory notes, 0 blocking). They are in the
  plan's functional-review record, which this analysis did not re-read.
- Throwaway diag branches: the dispatch prompts made the orchestrator delete `diag/plan-32-main` if D4
  never ran. D4 ran. Whether every diag branch was deleted was not checked here.

## Parallelization Consequences

PLAN-32 touched `GatewayEdgeRoute.java` and `DispatchStage.java`, so the sibling's `PLAN-V02-25` (edge
decomposition), `PLAN-V02-23` and `PLAN-V02-12`, and this epic's PLAN-29, rebase onto `73233f1e` before
outlining or executing. No collision was reported: #466 merged between the sibling's #465 and #469, with
no rebase signal in the landing. The PLAN-32 half of V02-25's gate is now met, and V02-25 waits
only for `PLAN-V02-44` (sibling decision, #470). Operator ruling 2026-10-15: PLAN-20, -21, -26 and -29
stay held until the sibling's whole refactor chain lands. PLAN-31 is held over its multi-file overlap
with V02-12, V02-23 and V02-44.

## Reconciliation Actions

- Queue row PLAN-32: `running` → `shipped`, `pr` #466, `landing` `landings/PLAN-32.md`.
- 29 inbox messages drained: the landing reconciled, 4 candidates promoted locally as 3 lessons,
  24 routed to plan-marshall's `lessons-routing` inbox in 13 grouped messages.
- Open Defects: the #455 hang entry is resolved by #466 (issue closed).
- Watches: the cross-epic hold now follows the sibling's refactor chain; PLAN-31 is held.
