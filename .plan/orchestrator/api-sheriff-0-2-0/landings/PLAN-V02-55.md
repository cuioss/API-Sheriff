# Landing Analysis: PLAN-V02-55 — Run More of the Integration Suite Concurrently, and Close the Local Echo-Image Hazard

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #558 (https://github.com/cuioss/API-Sheriff/pull/558), merged through the merge queue as `d70d2f164a6d9cca295b0fe935611e0ce56cf650` on 2026-11-02

> Written 2026-11-04 by `analyze` (inbox drain, message `plan-v02-55-it-concurrency-and-local-image-hazard-001.md`,
> `landing-check` complete: true). Every figure below was re-read by the orchestrator, not copied from the message.

## Verdict: verified (settled 2026-11-05)

(a) to (d) hold on every shipped deliverable. (c) was settled on 2026-11-05 by the two `main` Integration Tests runs after `d70d2f16`:
`954d7d88` (run `41984810982`, archive `integration-tests-2026-11-05-0726-3130`, 382 completed, 0 failures) and `7a5d52ad` (run
`42088184509`, archive `integration-tests-2026-11-05-1909-0550`, 385 completed, 0 failures). The five moved classes and the control pass
on both, inside their baselines; the per-class times are in `landings/PLAN-V02-53.md` § Orchestrator verification.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 overlap the two `BffSessionIdleTimeoutIT` legs | shipped-as-specified (plain overlap, no login lock) | `junit.jupiter.execution.parallel.enabled=true` on the `sleep-bound-concurrent` execution only (`integration-tests/pom.xml` at `d70d2f16`, l. 388-402). Merge-head class time **162.9 s** against a baseline of 194.6 / 196.3 / 196.3 s, so about 32-33 s less, which is the leg the spec expected to overlap. 2 tests, 0 failures |
| D2 make the three log-counting suites attributable, then move them | shipped-modified: option **(b)**, one gateway per suite on the test side | `OneOffGatewayContainers` (+ its test) is new. `BffTokenRefreshIT`, `BffClientSecretModeIT` and `BffFapiControlsIT` carry `@Tag("sleep-bound")`. No production file and no `doc/LogMessages.adoc` change is in the diff, so the D2(a) security review was not needed |
| D3 `BffTlsRelaxationIT` stops sharing the echo container | shipped-as-specified | `@Tag("sleep-bound")` at `d70d2f16`. It now has its own echo container (PR body). Merge-head time 34.8 s against 33.5 / 34.4 / 35.1 s |
| D4 close the local echo-image hazard | shipped, and wider than the spec | Both lanes build `grpc-echo` **and** `passthrough-backend` (`integration-tests/pom.xml` l. 463-464 and l. 685-686, the `jfr` lane included). The new `ComposeFixtureImageBuildWiringTest` pins this |
| D5 keep the partition honest | shipped | The pom comment names the classes that stay sequential. `FailsafeConcurrencyContractTest` was not edited and stays green in CI (`build / build (25)`, `(26)` success on the PR) |
| D6 close #423 after the landing | shipped | #423 is `closed`. The PR names it without a closing keyword ("Refs #423") |

## Orchestrator verification, against the pre-registered baselines

Source: `cuioss/cuioss.github.io`, `api-sheriff/integration-tests-2026-11-02-1733-2780/failsafe-reports/`, the archive of the
`main` push run `41489416591` on `d70d2f16`. That run's log names this directory. Each class's `testsuite` `time`:

| Class | Baseline (s) | Merge head (s) | Tests / failures | Reading |
|---|---|---|---|---|
| `BffSessionIdleTimeoutIT` | 194.6 / 196.3 / 196.3 | 162.9 | 2 / 0 | about 32-33 s less, the overlapped leg (D1 holds) |
| `BffTokenRefreshIT` | 46.9 / 48.7 / 49.1 | 48.3 | 6 / 0 | within baseline, now concurrent |
| `BffFapiControlsIT` | 24.6 / 25.8 / 26.0 | 27.7 | 6 / 0 | about 2 s slower under contention, far from doubled |
| `BffClientSecretModeIT` | 24.2 / 25.2 / 25.6 | 26.9 | 4 / 0 | about 1-2 s slower, far from doubled |
| `BffTlsRelaxationIT` | 33.5 / 34.4 / 35.1 | 34.8 | 3 / 0 | within baseline, now concurrent |
| `BffRefreshReuseIT` (control) | 69.0 / 76.4 / 77.7 | 72.7 | 3 / 0 | within baseline |

`failsafe-summary.xml`: 382 completed, 0 errors, 0 failures, 0 skipped, 0 flakes. No moved class more than doubled, so there is no
finding under (b). The spec ruled the workflow duration out as evidence, and no gain or loss is read from it here.

- (a) **holds.** All five moved classes carry `@Tag("sleep-bound")` at `d70d2f16`, and the pom comment was updated.
- (b) **holds** for the merge-head run and both later `main` runs (2026-11-05).
- (c) **holds** (settled 2026-11-05, see the verdict above).
- (d) **holds.** D2(a) was not chosen. For D4, the changed build steps are in the pom.

## Metrics and Anomalies

- Tokens: 26,775,137 (landing fact). Effort `low`, model `claude-opus-5-5`, plugin/executor 0.1.2036.
- Duration: 257,941 s wall (about 71.6 h, start 2026-11-01 to landing 2026-11-04). This includes the post-merge D6 wait and the
  operator-paced functional-review closure.
- Anomalies:
  - **Declared surface 8, realized 16** (`landing-check` `surface_delta`: `expansion_detected`, 9 added, 1 declared-and-untouched,
    `FailsafeConcurrencyContractTest`). The added files are `OneOffGatewayContainers` and its test, `ComposeFixtureImageBuildWiringTest`,
    `ImageLabelActivationWiringTest`, `BffRefreshReuseIT`, `BffSessionAbsoluteTtlIT`, `doc/development/integration-test-topology.adoc`,
    `doc/development/release-process.adoc` and ADR-0070. The spec's split guard (fewer than 20 files) held.
  - The PR-attached post-merge benchmark (`Run Integration Benchmarks`, run `41489422457`) **failed before any benchmark ran**:
    Maven Central returned a 502 resolving `jakarta.xml.bind-api:pom:4.0.5`, and the manifest-summary step then reported the manifest
    absent. This is an infrastructure failure, not a change effect. It means no benchmark figure exists for #558 (Watch).

## Routing and Merge Behavior

- Review: CodeRabbit took four rounds on `ComposeFixtureImageBuildWiringTest`. One point (ordering against the native build) was
  declined as out of scope. The code review found 0 blocking and 5 advisory points (see Follow-up candidates). The functional review
  was closed by the operator, because D6 could only run after the merge. The review verdicts at `ebdb80e6` were carried to `b9f814f1`
  by operator decision (the delta was test hardening on one file).
- CI/merge: all required checks passed on the PR, and it merged through the merge queue. The `main` push run `41489416591` on
  `d70d2f16` is green (Integration Tests). The resume anchor also recorded the Maven Build, Demo Client E2E and Scorecard runs as
  success. Only the PR-attached benchmark is red, for the reason above.
- Sonar: the `SonarCloud` check on the PR passed. The landing carries no `step.sonar-roundtrip.open_issue_count`, so the open count at
  the head is `unknown` here, not `0`.
- Formatter churn: the pre-commit rewrite of about 380 files went in separately as #559 (`7fde3dae`, `skip-bot-review`), which landed
  before #558. This plan's diff contains no unrelated file.
- Collision: #558's footprint overlaps 11 files with `PLAN-V02-53`'s declared surface (all under
  `integration-tests/src/test/java/…/integration/`). `PLAN-V02-53` was already sequenced after this plan, so this is no unpredicted
  collision. `PLAN-V02-53` must start from `main` at or after `d70d2f16` and re-read those files.

## Follow-up candidates (code-review advisories, nothing blocking)

1. The `OneOffGatewayContainers` guards have no tests.
2. Stale javadoc at `BffTokenRefreshIT` l. 121-122.
3. A build option is read as a service name in `ComposeFixtureImageBuildWiringTest`.
4. Descriptor derivation is duplicated between that test and `BffSessionIdleTimeoutIT`.
5. No failing test covers the parallel parameter at `integration-tests/pom.xml` l. 401-403.

None of these is staged. They are recorded as one Watch for the operator. Several touch files `PLAN-V02-53` also edits, so folding them
there is the cheap option, if the operator wants that.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-55 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-55 --field pr --value 558`
- [x] row `landing` stamped — `--field landing --value landings/PLAN-V02-55.md`
- [x] row `plan_marshall_plan_id` stamped — `--field plan_marshall_plan_id --value plan-v02-55-it-concurrency-and-local-image-hazard`
- [x] `epic.md`: queue annotation updated, a decision added, and Watches added (verification (c) open, benchmark not measured, advisory follow-ups)
- [x] inbox message archived after persistence
- Lessons: the plan already routed its plan-marshall findings to `lessons-routing` (api-sheriff-api-sheriff-0-2-0-079, -080). The local lesson
  2026-11-03-08-001 stays local.
