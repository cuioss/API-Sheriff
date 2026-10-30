# Landing Analysis: PLAN-V02-51 — Settle #201 and Close the Two Guard Gaps

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #538 (https://github.com/cuioss/API-Sheriff/pull/538)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-30 from the inbox messages
> `plan-v02-51-lane-conditional-guards-001` to `-004` (four candidate lessons) and `-005` (the landing). Checked against
> `origin/main` at `7755b948`, the merge commit's file list and stat, the PR body, issue #201 and its closing comment, and the
> CI runs (the PR checks, the merge-group runs, the push runs and the PR-attached benchmark). The plan ran once under its own
> plan id. `landing-check` reported `complete: false` (missing key `effort`, carried as `unknown`).

## Deliverable Fidelity vs Spec

#538 changed 7 files (+4,401 / −231), all test code, one POM comment and developer documentation. **No production file changed**, which
the spec allowed only on D1's fail-open branch; that branch was not taken. CodeRabbit was the only reviewer that completed
(`SUCCESS`); Sourcery declined the PR as over its 150,000-character limit (optional bot, not blocking). The plan counts three
deliverables, as the spec does.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — Open Defect 14, settle #201 with a run, then fix or close | shipped, **branch 2 of 3 ("the lane is the cause")** | Nine counted `MtlsHandshakeIT` runs, three per lane configuration, every run executing all three methods: `-Pintegration-tests` 3/3, 3/3, 3/3; `-Pjfr` with `-Dtest.mtls.port=10444` and both keystores 3/3, 3/3, 3/3; `-Pjfr` without them 3/3 (trusted), **0/3 (`noClientCertRejected`), 0/3 (`wrongCaClientCertRejected`)**. So the rejection legs pass when the properties are supplied and fail when they are not: the gateway is correct, the lane is misconfigured. `MtlsServerCustomizer` is untouched. Issue #201 is `CLOSED` (2026-10-29T13:48:16Z) with the counts quoted in the closing comment, which I read. `MtlsLaneSelectionContractTest` (new, 1,599 lines, no Docker) fails when a failsafe lane that selects `MtlsHandshakeIT` lacks any `test.mtls.*` property it reads or a published mTLS port; `integration-tests/pom.xml` now states the cause in the `jfr` exclusion comment and names the guard; the exclusion itself is unchanged. `TlsEdgeActivationWiringTest.failsafeWiresMtlsSystemProperties` was **removed on purpose** (−51 lines in that file) and replaced by the per-lane check; the operator accepted the removal in review. Demonstrated catch per the PR body: removing the `jfr` exclusion turned the guard red naming lane `jfr/default` and the five missing properties. |
| D2 — Open Defect 7, k6 consumers of the shared gateway configuration | shipped | `BenchmarkScriptConfigContractTest` (new, 2,048 lines) binds every file under `benchmarks/src/main/resources/k6-scripts/` to the mounted-configuration keys it depends on, resolving routes (longest prefix), upstreams, body caps, methods and scopes the way the gateway does. Mechanism chosen: a contract test in the surefire lane of `integration-tests`, not a check in `benchmark-manifest.py`. Demonstrated catch: renaming `/upload/small` turned it red naming `upload_small.js`. |
| D3 — Open Defect 9, catalogue guard past one module | shipped | `LogMessagesCatalogueTest` (+868 / −, in `api-sheriff`) derives the module set from the root `pom.xml` and checks duplicate identifiers and bands in every reactor module, so `benchmarks` is covered without being named. Demonstrated catch: a planted duplicate `K6Benchmark-210` turned it red naming both fields. The spec asked whether `integration-tests/` carries a catalogue; the derivation makes the question moot, and I did not re-search for it. |
| `doc/development/` — the developer layer | shipped | `README.adoc` (Open Defects 7, 9 and 14 and the strengthen/census notes) and `test-corpus-integrity.adoc` are in the diff. |

**Two things the spec did not predict.** (a) The three guards are very large for what they guard: about 4,500 added lines of
hand-written readers for POM, glob, k6 script and Java-source forms. The plan paid for this with four code-review rounds and two
CodeRabbit rounds (fix tasks `TASK-9` to `TASK-23`), each round finding another input form a reader silently misread; it
converged only after the readers became narrow allowlists (candidate lesson `-002`). (b) The mTLS exclusion cause is now
recorded and guarded, but #201 was **closed about ten hours before the guard reached `main`** (closing comment 2026-10-29T13:48Z;
merge 2026-10-30T00:22Z), so for that window the issue said "settled" while nothing on `main` enforced it. Not a defect now.

## Metrics and Anomalies

- Tokens: 36,963,221. Wall time: 60,291 s (about 16.7 h) for 3 deliverables. Model claude-sonnet-5-5, **effort `unknown`** (the
  reason `landing-check` reports `complete: false`; V02-50's landing carried `medium`). Plugin 0.1.2028, executor 0.1.2028. About
  40% of V02-50's tokens and 41% of its wall time.
- Loop-backs: four code-review rounds plus two CodeRabbit rounds, 23 fix tasks in all.
- Two stalls the plan did not cause: about 40 minutes (13:05 to 13:46) on `Not authenticated` from the CI router before `TASK-8`
  could comment on and close #201 (candidate lesson `-004`), and about 7 minutes in branch-cleanup's post-merge CI wait on the
  PR-attached benchmark run (candidate lesson `-003`). The merge queue itself took 1,363 s.
- Step list: every finalize step `done` (parsed with a last-colon split; no namespaced element carried a non-`done` outcome);
  `step.branch-cleanup.merge_mechanism=merge_queue`; `cleanup_owed=false`.

## Routing and Merge Behavior

- Review: CodeRabbit `SUCCESS`; Sourcery `SKIPPED` (oversize). PR checks green: `build / build (25)` and `(26)`, `sonar-build`,
  `quarkus-alignment`, `integration-tests / test`, the two `conclusion` checks, offline configuration check, dependency review,
  license/cla. Sonar: the landing reports `new_code_issue_count=0` and the `sonar-build` check is green; I did not read the count
  from Sonar itself, so the figure is the plan's.
- CI: the pull-request Integration Tests run `40968785321` is at **attempt 2**. Attempt 1 failed
  `Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength` at line 212 ("the h2 -> HTTP/1.1 framing of a declared body (4
  failures)"), `Tests run: 358, Failures: 1`; attempt 2 on the same head `917b4797` passed. The branch changes no production code, so
  this failure sits on code already on `main`. See the Watch added at this landing.
- Merge: through the merge queue as `7755b948` (merge-group runs `40976801643` and `40976801678` green; merged about 00:22Z).
- Post-merge on `main` at `7755b948`, read 2026-10-30: Maven Build (`40979218597`, incl. `deploy-snapshot`), Integration Tests
  (`40979218582`), Config Validation (`40979218080`), Demo Client E2E (`40979218057`) and Scorecard (`40979218587`) completed
  `success`; the PR-attached Performance Benchmark (`40979220423`, job "Run Integration Benchmarks") completed `success`. **All
  verified green.** The plan's branch-cleanup skipped its own post-merge wait, as the contract says it should hand this to the
  orchestrator. (The CI abstraction has no run-by-commit verb, so the push and merge-group runs were listed with a read-only
  `gh run list --commit`.)

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-51 --status shipped`
- [x] row `pr` stamped — `538`
- [x] row `landing` stamped — `landings/PLAN-V02-51.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-v02-51-lane-conditional-guards`
- [x] epic.md narrative: Open Defects 14, 7 and 9 relocated to `settled.md`; a Watch added for the `Http2StreamAbuseIT` h2 -> h1 framing
  failure and one for the guards' untested refusal branches; Open Defect 18 recorded for the incomplete landing; the `Http2StreamAbuseIT`
  `PROTOCOL_ERROR` Watch and the `RelayStartFailure` Watch checked (see below)
- [x] `PLAN-V02-07` spec: nothing to retire (the overlap with `PLAN-V02-51` was recorded in the V02-51 spec and the queue annotation only); no spec needed a surface correction, because the landing reported no collision
- [x] four candidate lessons dispositioned (see Follow-Ups): one promoted as `2026-10-30-05-001`, three routed to `lessons-routing` as `-071`, `-072` and `-073`; resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **`Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength` is the second `Http2StreamAbuseIT` failure CI has shown in
  two landings, in a different test method from the first.** The plan calls it "the known HTTP/2 framing flake class". The standing
  rule is that a CI-red integration test is a suspected production defect until a root cause is named, and "4 failures" in one
  attempt means the test's own retries failed too, then the whole suite passed on the re-run. Held as a Watch, not closed.
- **`PLAN-V02-07` is no longer overlapped** by a running plan on `integration-tests/**` and `doc/**`; the operator-accepted overlap
  with `PLAN-V02-51` is gone with the landing.
- **Candidate lessons `-001` to `-004`:** `-002` promoted as project lesson `2026-10-30-05-001` (guard tests that parse a source
  language state a narrow accepted shape and refuse the rest); `-001` routed to plan-marshall `lessons-routing` as `-071`, a
  recurrence of `api-sheriff-api-sheriff-0-2-0-049` and `-062` (an operator-accepted weakened-test removal re-opens at every head,
  here with the verifier refusing a grading because of it); `-003` routed as `-072`, a recurrence of `-032` (branch-cleanup's
  post-merge wait on the PR-attached benchmark run); `-004` routed as `-073`, a new candidate related to `-040` and `-061` (check
  CI-provider authentication before dispatching a task whose steps call the CI router).
- **Unowned and unchanged:** `gw-02` stays `PARTIAL`; the uniform-404 floor is measured on eight paths; the `rate_limit` and
  `observe`-default notes from `PLAN-V02-50` stand.
