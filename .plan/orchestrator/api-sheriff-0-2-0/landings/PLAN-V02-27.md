# Landing Analysis: PLAN-V02-27 — Simplify the Tests After the Edge/Config Refactor

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #520 (https://github.com/cuioss/API-Sheriff/pull/520)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-25 from the inbox message
> `plan-v02-27-test-simplification-009` (the landing) and its eight candidate lessons. Checked against
> `origin/main` at `6b8d88f4`, the merged diff against `68481922`, and the CI runs. The plan ran once under
> its own plan id.

## Deliverable Fidelity vs Spec

#520 changed 35 files (+1,467 / −2,208): under the 100-file review-bot cap. Four are production files and
three are documentation pages. The operator cut the scope at outline: this PR covers `edge/` plus the five
test gaps, and the `config/validation/`, `config/` and `routing/` duplicates are follow-ups (see Residue).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — inventory | shipped, scope cut by the operator | The inventory covered the whole refactored area; the PR acts on `edge/` only. 17 `config/validation/` duplicates and the `config/` and `routing/` duplicates are deferred. |
| D2 — align classes, shared builders | shipped, one residual | Git reports three moves as renames: `GatewayEdgeRouteRetryAfterTest` → `edge/error/ErrorResponseWriterTest` (R094), `ReservedBodyCeilingTest` → `edge/reserved/` (R087), `EdgeHardeningTest` → `EdgeHardeningOptionsTest` (R088). `EdgeFixtures` and `testsupport/ConfigFixtures` are new. Seven classes the spec names still have no test class named for them: `TerminalDispatch`, `RejectionReporter`, `ConnectionRetirement`, `ResponseEnds`, `ReservedPathHandler`, `EdgeRequests`, `EgressClients`. The landing message does not mention this. |
| D3 — remove duplicates after carrying assertions | shipped for `edge/`, rest deferred | The plan reports 16 duplicates removed in `edge/` with assertions carried into the survivors. I did not re-derive the 16. `LimitRulesTest` is not in the diff, so the class-Javadoc fix (#467 advisory) is still open. |
| D4 — prove nothing was lost | shipped, one residual | The plan reports 6,186 tests before and 6,176 after (+4 gap tests, +1 `HealthProbe`, +1 gRPC cap-breach, −16 duplicates) and no compared class below its `68481922` baseline. The coverage figures came from a profile that appends to one `jacoco.exec`, so the plan compared its own probe data class by class. The landing gives no unit-test run time before and after. |
| D5 — five test gaps | shipped, two residuals | (1) GW-07: `onBuildsTheFixedFiveMinuteBound` and the run of `dispatchGrpc` through the package-private overload exist; the status stays PARTIAL because no test observes the public method's hand-over of the bound. (2) Late-timeout cases: the threat model says HTTP/1.x now runs through `retireAfterLateBodyTimeout` and `failRelay`; the HTTP/2 counterparts and the marked-request case still call the retirement methods directly. (3) Latch: `deadlineRecordIsLatchedPerRoute` and `idleExpiryRecordIsLatchedPerRoute` exist. (4) `EdgeAdmissionTest` asserts an admitted round; the throwaway red run is plan-reported, not re-run. (5) `HealthProbeTest` binds an ephemeral loopback port and uses `UnreachablePort`; nothing binds 9000. |

Production changes are test seams only (4 files, +55 / −11): `HealthProbe.probe(int)` with `PROBE_PORT`
package-private, a package-private `dispatchGrpc` overload taking the stream-idle bound (`relayGrpcCall` is now
private), a comment fix in `ErrorResponseWriter`, and `GrpcStreamIdleBound.timeoutMillis()`. The last one is a
new **public** method on a production type, added only so a test outside the package can read the bound.

## Metrics and Anomalies

- Tokens: 48,083,742. Wall time: 28,200 s (7.8 h). Model claude-opus-5-5, effort low. Plugin and executor 0.1.1986;
  the plugin cache moved to 0.1.2010 during the run and broke the worktree executor (lesson routed).
- Both finalize reviews were closed by operator override. The only blocking rows were 11 `weakened_test` rows: 3 are
  the renames above, 8 are the planned duplicate removals. The verifier refuted them in every round and they came
  back each time. I checked the three renames against git; the eight were not re-derived.
- The review loop re-ran simplify and security-audit four times, the functional review in 3 full rounds plus 1 delta
  round, and the code review in 1 full round plus 1 delta round. The closing full round was skipped by the operator.
- No security note was recorded by the plan.

## Routing and Merge Behavior

- Merged through the merge queue as `6b8d88f4` at 2026-10-25T21:42:24Z. The merge_group Maven Build (40235000385) and
  Integration Tests (40235000338) passed.
- Post-merge on `main` at `6b8d88f4`, all completed with success: Maven Build (40236743335), Integration Tests
  (40236743323), Config Validation, Demo Client E2E and Scorecard. The PR-attached Performance Benchmark run
  (40236744674, head `e10673b8`) passed.
- No collision with `PLAN-V02-04` (running, docs only) was reported.

## Residue and Owners

- **Two follow-ups owed by the scope cut,** merged into one plan because both are thin: `PLAN-V02-49`
  (`test-simplification-part-2`): the 17 `config/validation/` duplicates, the `config/` and `routing/` duplicates
  (C1–C3, R1–R2) with their helpers moved to `ConfigFixtures`, the `LimitRulesTest` Javadoc fix, and the seven classes
  without a test class of their own if the inventory still shows them.
- **GW-07 request body stays PARTIAL.** The operator chose to hold the public `dispatchGrpc` hand-over as the named
  residual. Owner: Watch in `epic.md`. The `gw-07-upstream-ports` matrix row reads COVERED while naming an unobserved
  effect; this PR did not author that row.
- **Late-timeout HTTP/2 and marked-request cases** still drive the retirement methods directly. Owner: `PLAN-V02-49`.
- **Public accessor `GrpcStreamIdleBound.timeoutMillis()`** exists for tests. Owner: `PLAN-V02-49` decides whether a
  package-private seam covers it; recorded in its spec.
- **Coverage profile appends to one exec file.** Promoted as lesson `2026-10-25-22-001`; `PLAN-V02-49` D4 measures with
  `clean`. A build change that resets the exec data would need its own plan.
- **Plan-marshall tooling lessons routed** to the plan-marshall `lessons-routing` inbox: `-062` (weakened_test and
  rename/dedup, recurrence of -036/-048), `-063` (qgate fold hides a re-filed finding), `-064` (finalize re-fire band,
  recurrence of -034/-043/-049/-054), `-065` (pre-push gate bundles, recurrence of -022/-037), `-066` (subagent edited
  a tool-result copy), `-067` (executor stale mid-run). The formatter-churn candidate was folded into the Spec of
  `PLAN-V02-49` and the local formatter note.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-27 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-27 --field pr --value 520`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-27 --field landing --value landings/PLAN-V02-27.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] epic.md narrative reconciled (GW-07 Watch, V02-27 rows retired)
- [x] follow-up staged as `PLAN-V02-49`
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- `PLAN-V02-49` (`test-simplification-part-2`), staged with this landing.
- GW-07 request-body PARTIAL (Watch).
