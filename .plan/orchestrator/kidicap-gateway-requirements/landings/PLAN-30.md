# Landing Analysis: PLAN-30 — Early Upstream Answers Exhaust the Gateway's Local Ports

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #448 (merged as b7b9869989656b9ee32c06ff80e05a930f4f7910)

> Landing record for one shipped plan. Lives at `landings/PLAN-30.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the inbox landing message `plan-30-early-answer-upstream-port-exhaustion-006.md`.**
`inbox landing-check` reports `complete: true`, no missing keys. Corroborated 2026-10-14 at `5490144f`:
`ci pr view 448` reports `state: merged` with that merge commit, which is on `origin/main` one commit
below its tip (#454, a sibling ledger land). 13 files in the merged diff (+706/−71).
`ci checks status --pr-number 448` reports `overall_status: success` over 35 checks, including the
PR-attached post-merge benchmark run. Zero resolvable review threads are open (all ten rows
`pr comments --unresolved-only` returns carry an empty `thread_id`).

## Deliverable Fidelity vs Spec

The landing reports 7 of 7 deliverables; functional review: 5 met, 2 met with residuals, 0 not met.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 reproduce in a test first | shipped | `GatewayEdgeEarlyResponseResetTest` (+241 lines), `GatewayEdgeRouteTest` (+41) pin the client option and the reset's connection cost. That the reproduction failed on `main` before the fix is the sender's statement |
| D2 stop the cost (operator decision) | shipped, resolved to **abortive close** (option b) | Operator answers at outline: abortive close, applied to the whole default HTTP/1.1 upstream client, recorded as an ADR-0066 amendment. Corroborated: `new HttpClientOptions().setSoLinger(0)` at `GatewayEdgeRoute.java:2382`; ADR-0066 **Amendment A1** (2026-10-14, line 242). The forced-HTTP/2 and WebSocket clients keep the transport default. ADR-0066's reset itself stays. Accepted cost, per the PR body: every connection this client closes, idle-pool evictions included, ends with a TCP reset instead of a FIN (and no TLS `close_notify`) |
| D3 does the gateway's own reset count as a breaker failure | shipped | A test pins that a gateway reset after a complete response does not move the breaker; ADR-0066 A1 places the reset outside the guarded call (ADR-0064 cross-reference) |
| D4 stop the Vert.x ERROR per reset | shipped, with a review residual | `DispatchStage.onUpstreamRequestFailure` drops every `StreamResetException` and logs other failures at DEBUG. A review showed the stated reason did not cover a late reset on the forced-HTTP/2 path; Javadoc fixed (bc002caf). Lesson `2026-10-14-13-002` |
| D5 make the upload benchmarks measure uploads | shipped | `integration-tests/nginx/nginx-static.conf` (+51, new body-reading upload locations), `body-limits.conf`, `docker-compose.yml`. Operator: no separate early-answer benchmark kept |
| D6 show the fix under load | shipped, local run | Before the backend change, one local run: `uploadSmall` 51,198 k6 iterations, error rate 0 (PLAN-28: 47,441, error rate 0.40). This passes the spec's bar (past ~28,000 iterations with 0 errors). The CI run cannot repeat it — see Routing |
| D7 documentation | shipped, with residuals | ADR-0066 (+124 lines), `doc/architecture.adoc`, `doc/security-threat-model.adoc`, `doc/development/integration-test-topology.adoc`. Stale comments left: see Residue |

## Metrics and Anomalies

- **6,454,680 tokens and 16 h 38 m wall** (59,876 s), the longest in this epic. Benchmark runs, an
  outline with four operator questions, and three CI attempts on the integration tests.
- ⛔ **The PR merged while this epic recorded it as "must not merge until fixed".** On 2026-10-14 the
  other orchestrator session recorded a top-priority Open Defect: `Http2EarlyResponseResetIT` timed out
  in attempts 1 and 2 of run 38538582322 at head bc002caf and passed on attempt 3, while `main` had been
  green on it since #431. The plan merged on the green merge-queue run, filed **#455** for the hang and
  wrote that the cause is not established and SO_LINGER 0 is "neither attributed nor cleared". The hold
  was written into `epic.md` only, which a running plan does not read, so it never reached the plan.
  Lesson `2026-10-14-13-003`.
- **An unrequested production change.** `GatewayEdgeRoute.guardFor` no longer registers the circuit
  breaker under a SmallRye name. With the abortive close, server-side TIME_WAIT is freed at once, an
  upstream port can be reused within one JVM, and the JVM-global SmallRye registry then rejected the
  reused name. The breaker's identity is now its guard, keyed by
  `scheme://host:port:h2=<flag>:retry=<flag>`. `UpstreamFailureMapper` changed with it (not declared).
  The operator deferred the ADR for this to a follow-up; the draft is at
  `.plan/temp/adr-propose-plan-30-breaker-identity-draft.adoc` in the main checkout (git-ignored), and
  nothing is in `doc/adr/`.
- Lessons: two candidates promoted (`2026-10-14-13-001`, `-002`); three folded as recurrences into
  `2026-10-12-16-001` (empty assessment store on the hand-traced outline path, second plan) and
  `2026-10-13-06-004` (guessed script verbs and flags; `qgate query` now in three consecutive plans).
- Every finalize step is recorded `done`; this plan ran the new `functional-review` and `code-review`
  steps (#441). Code review: 0 blocking, 3 advisory. The landing notes the metrics accumulator's
  `samples` counter is about 2 too high (a placeholder call reversed with negative values).
- **Surface: declared 21 entries, realized 13 files — 2 undeclared, 12 declared-but-untouched.**
  Undeclared: `UpstreamFailureMapper.java` (the breaker-identity change) and
  `integration-tests/nginx/nginx-static.conf` (the backend D5 actually changed; the spec named
  `body-limits.conf` only). Untouched: `EarlyResponseEnd`, `ResponseStage`, `ResponseEnd`,
  `ApiSheriffLogMessages`, `doc/LogMessages.adoc`, `Http2EarlyResponseResetIT`, `early-answer.yaml`,
  `docker-compose.benchmark.yml`, `httpbin.yaml`, `upload_small.js`, the k6 script directory,
  `declared-limit-assertion-coverage.adoc`. Ten of the twelve were HYPOTHESIS entries for options the
  operator did not choose (a drain bound, a new log record, a kept early-answer script).

## Routing and Merge Behavior

- Review: one CodeRabbit finding (the catch-all handler), fixed in bc002caf. CodeRabbit had no review
  left in that hour on a later head; Sourcery refused on quota (optional).
- CI/merge: squash through the merge queue; `cleanup_owed=false`. Integration Tests run 38538582322
  needed three attempts (see above).
- **Post-merge verification: the PR-attached run is green.** `Run Integration Benchmarks` (run
  38565585855) reports `SUCCESS`: `uploadSmall` error rate 0 (3,464 iterations, 57.16 MB/s),
  `uploadLarge` error rate 0 (79 iterations, 64.08 MB/s). ⚠ **This run no longer covers the defect.**
  Since D5 the fairness backend reads upload bodies, so CI's upload benchmarks never take the
  early-answer path, and the operator chose not to keep a separate early-answer measurement. The fix's
  only load evidence is the one local run under D6; a regression of the port cost would now show in no
  CI job. That was the operator's decision at outline, recorded here so it is not mistaken for coverage.
- The main-branch Maven Build and Integration Tests for b7b98699 are unobserved; whether #455's hang
  recurs on `main` is the open question.

## Residue

- **`Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway` hangs intermittently** —
  2 of 4 CI attempts at bc002caf, never on `main` before #448, not reproduced locally, cause not
  established. Tracked as **#455**. It guards the early-answer path, so re-running until green can hide
  a regression this plan introduced.
- **The breaker-identity decision has no ADR** (draft in `.plan/temp/`, operator-deferred).
- Advisory leftovers, the sender's list: stale breaker-name comments in `UnreachablePort.java` and
  `GatewayEdgeErrorPageTest.java`; the now-false comment in `early-answer.yaml` that the backend never
  reads upload bodies; text calling the ~51,200 `uploadSmall` iterations "derived, not measured" although
  k6 measured 51,198; `UpstreamFailureMapper.recordBreakerTransition`'s parameter still named `routeId`;
  the `api-sheriff/pom.xml` comment still advertising `CircuitBreakerMaintenance`; `DispatchStage`'s class
  Javadoc and ADR-0066 A1 decision 3 giving only the HTTP/1.x rationale for the drop.

## Parallelization Consequences

PLAN-30 ran beside the sibling epic's `PLAN-V02-12` (emitted over a 3-path overlap on the operator's
instruction). V02-12's first PR (#450) landed first; no conflict is reported, and ADR ordinal 0067 is
still free (ADR-0066 was amended, not added to). PLAN-29 (sequenced after this plan, same edge classes)
now re-grounds against the abortive-close lifecycle and the guard-keyed breaker: its D4 (DP-6, the 30 s
timeout stranding a pooled connection) must be read against both.

## Reconciliation Actions

- Queue row PLAN-30: `running` → `shipped`, `pr` #448, `landing` `landings/PLAN-30.md`.
- 6 inbox messages drained: 2 lessons promoted, 3 candidates folded as recurrences, the landing
  reconciled. One orchestrator lesson added from this analysis (`2026-10-14-13-003`).
- Open Defects: the port-exhaustion entry and the "must not merge" entry replaced by the #455 hang and
  the unrecorded breaker-identity decision.
