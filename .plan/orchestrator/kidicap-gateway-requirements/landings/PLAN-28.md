# Landing Analysis: PLAN-28 — What PLAN-24 Left Open, and Three Intermittent Failures

epic: kidicap-gateway-requirements
workstream: WS-01
pr: #437 (merged as 82f2d7d820ce1ee5f0cf2c80e7161962e66efb26)

> Landing record for one shipped plan. Lives at `landings/PLAN-28.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

**Source: the inbox landing message `plan-28-jwks-refresh-follow-ups-008.md`.** `inbox landing-check`
reports `complete: true`, no missing keys. Corroborated 2026-10-13 at `48e8d5a6`: `ci pr view 437` reports
`state: merged` with that merge commit, which is on `origin/main` one commit below its tip (#439, a
sibling epic's plan, landed after it). 14 files in the merged diff (+1161/−232).
`ci checks status --pr-number 437` reports `overall_status: success` over 35 checks, including the
PR-attached post-merge benchmark run. Zero resolvable review threads are open (the six rows
`pr comments --unresolved-only` returns all carry an empty `thread_id`).

## Deliverable Fidelity vs Spec

The landing reports 8 of 8 deliverables. Three of them are fixes, three are records, two are diagnoses
that ended without a fix.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 guard `runRetry` | shipped-as-specified | `RetryingJwksLoader.runRetry` now ends in a `finally` (line 433 at `48e8d5a6`) that keeps the retry sequence alive. The open question in the spec is settled the way the orchestrator's read suggested: what was lost was the retry sequence, not a flag (the PR title: "keep JWKS retries alive") |
| D2 a record when `runRefresh` fails unexpectedly | shipped-as-specified | `ConfigLogMessages.WARN.JWKS_REFRESH_FAILED`, identifier 137, emitted once from the same `finally`; row in `doc/LogMessages.adoc` |
| D3 the clock decision | shipped, resolved to "move" | The refresh window and the grace deadline are measured on a monotonic nanosecond source (`monotonicClock`); ADR-0063 Amendment A1 records why |
| D4 documentation gaps | shipped, with one addition | Rows for the three keys in `doc/configuration.adoc` and the threat model; ADR-0063 and ADR-0011 list formats. A review finding added a guard: the three keys are defined once in `ConfigValidator` and `DocumentedSetsContractTest` fails when the documented list drifts (+201 lines) |
| D5 three simplify leftovers | shipped | Folded into the `RetryingJwksLoaderTest` rework (+415 lines) |
| D6 diagnose `GatewayEdgeFramingCorpusTest` | **not resolved** | Neither failure reproduced locally (Java 25 only; Java 26 not run). The file is not in the diff. See Residue |
| D7 `BffReservedPathPrecedenceIT` | shipped: cause found and fixed | The cause was not the test: a test-started gateway's management port was docker-assigned on loopback. `OneOffGatewayContainers` now uses a fixed port (application port + 9000); ADR-0065 amended. Confirmed by one clean full integration run |
| D8 the failed `upload_small` benchmark | **diagnosed, not fixed, by the STOP rule** | Two different failures. The one this deliverable was written for (run 38307102339, HTTP/2 `GOAWAY`) was fixed from outside the plan by #431. The benchmark run on the merged branch then showed a second one, introduced by #431: see Residue. Filed as inbox finding `-001`, staged as PLAN-30 |

## Metrics and Anomalies

- **5,983,254 tokens and 7 h 03 m wall** (25,362 s). The wall-clock is PLAN-24's; the plan needed full
  integration runs and benchmark runs for its diagnoses.
- Every finalize step is recorded `done`. **Two of them did less than the word says** (the sender's own
  statement): `pre-submission-self-review` had no surfacer for this project and did no review;
  `pre-push-quality-gate` ran its whole-tree `verify -Ppre-commit` arm only. Lesson `2026-10-13-14-003`.
- The native integration job took 1,295 s (about 22 minutes) against its 60-minute limit.
- **A cause read from code was recorded as the diagnosis and a run refuted it.** D8 was first explained
  as "already fixed by #431"; the run on the merged branch showed a 40% error rate. Lesson
  `2026-10-13-14-001`. The orchestrator's own hypothesis in the spec (the client abandons early-answered
  uploads) named the right trigger and the wrong leg.
- **The branch carries a merge of `origin/main`** (#431, #435), made at the operator's request for the D8
  run.
- **Surface: declared 14 entries, realized 14 files — 6 undeclared, 6 declared-but-untouched.**
  Undeclared: `ConfigValidator.java`, `DocumentedSetsContractTest.java`, ADR-0065,
  `doc/development/integration-test-topology.adoc`, `OneOffGatewayContainers.java`,
  `OneOffGatewayContainersTest.java`. Untouched: `GatewayEdgeFramingCorpusTest.java`,
  `BffReservedPathPrecedenceIT.java`, `upload_small.js`, `httpbin.yaml`, `benchmarks/pom.xml`, the edge
  test directory. All six untouched entries belong to the three diagnosis deliverables: a diagnosis
  declares where the symptom is, and the cause was somewhere else each time.

## Routing and Merge Behavior

- Review: two CodeRabbit inline threads, both fixed and resolved (the unguarded documented list, commit
  5d9e0170; the "logged nowhere" wording, f33a18b0). Sonar: no new-code issue reported by the landing.
- CI/merge: squash through the merge queue; `cleanup_owed=false`.
- **Post-merge verification — the PR-attached run is green.** `Run Integration Benchmarks` (Performance
  Benchmark, run 38407570839) reports `SUCCESS`. It was still running when the plan stopped waiting, and
  the plan expected it might be red. ⚠ Green here does not clear the defect below: the sender's reading
  is that CI stays under the limit because its runner is slower.
- The main-branch Maven Build for 82f2d7d8 (including `deploy-snapshot`) is unobserved, as for every
  merge commit the CI abstraction cannot reach.

## Residue

- **An early upstream answer on an HTTP/1.1 upstream costs the gateway one local port for 60 seconds, and
  under load that opens the circuit breaker.** The sender's finding, measured in one local benchmark run:
  28,231 upstream resets in 46 s, then `ApiSheriff-103` and a 40% error rate. Corroborated by the
  orchestrator only as far as the code and the record go: ADR-0066 (lines 164 to 165) states that
  resetting an HTTP/1.1 upstream request closes the connection, and `GatewayEdgeRoute` and
  `DispatchStage` carry the reset calls. The port arithmetic and the log counts are the sender's.
  Staged as PLAN-30.
- **`GatewayEdgeFramingCorpusTest` is still unexplained.** Not reproduced on Java 25; Java 26, where the
  `502` occurred in CI, was not run. `bodylessMethodNeverCarriesABodyUpstreamOnHttp2[2]` runs through the
  path #431 reworked, so its timeout may be gone; nobody has shown that. Stays an Open Defect.
- **D7's fix rests on one clean run.** The stub identity provider's admin port in
  `StubIdentityProviderRig` is still docker-assigned and has not been seen failing.
- **Documentation left open**, the sender's statement: two stale `token_validation` mentions in
  `doc/configuration.adoc` (around line 2213 and the YAML example comment near line 380); the
  `IssuerConfig.Jwks` Javadoc repeats the three ranges with no guard; the new contract test checks the
  ranges and not the documented restriction to the `http` source.
- **The benchmark does not measure what its name says.** `nginx-static` answers uploads before reading
  the body, so since #431 `upload_small` and `upload_large` measure the early-answer path. Carried into
  PLAN-30.

## Parallelization Consequences

PLAN-28 ran alone in this epic. #431 landed while it ran: a spin-off plan outside both epics' queues, which
the sibling epic `api-sheriff-0-2-0` tracks as the fix for its Open Defect 16. It changed `GatewayEdgeRoute`, `DispatchStage` and the benchmark's behaviour under PLAN-28's deliverable
8, and took ADR ordinal 0066. Nothing conflicted in files; the plan merged `origin/main` to diagnose
against it. The sibling epic also watches `GatewayEdgeFramingCorpusTest` (a red main-branch build on
09edaea9), so that flake now has two observers and no owner. PLAN-29's line references into `GatewayEdgeRoute` are stale since #431.

## Reconciliation Actions

- Queue row PLAN-28: `running` → `shipped`, `pr` #437, `landing` `landings/PLAN-28.md`,
  `plan_marshall_plan_id` corrected to `plan-28-jwks-refresh-follow-ups` (the landing's own value).
- 8 inbox messages drained in the same pass: 5 lessons promoted (`2026-10-13-14-001` to `-005`), 1
  candidate folded into lesson `2026-10-13-06-004` as a recurrence, 1 finding staged as PLAN-30, the
  landing reconciled.
- PLAN-29 marked stale and sequenced after PLAN-30.
