# Landing Analysis: PLAN-29 — Four Upstream Keys That Parse and Act on Nothing

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #586 (https://github.com/cuioss/API-Sheriff/pull/586), squash commit `ab568dab`

> Landing record for one shipped plan. Source: inbox message `plan-29-upstream-inert-keys-005.md`
> (`landing-check`: complete, no missing keys) plus four candidate-lesson messages `-001`..`-004`.
> The message is a lead, not a fact: the PR state, CI, the diff and the merged code were read
> independently below. Analysed 2026-11-09 at `origin/main` `ab568dab`.

## Deliverable Fidelity vs Spec

The spec was staged with six deliverables and decided by the operator on 2026-10-26 (remove all four keys,
keep `retry.enabled`, record the removal as an ADR-0064 amendment). The plan outlined it as three deliverables
(config model + schema + tests; ADR-0064 amendment; documentation) and reported 3 of 3 done, 18 criteria met.
Read against the spec's decided deliverables:

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 record the decision as an ADR-0064 amendment, no new ADR | shipped-as-specified | "Amendment 2026-10-26" added in place to `doc/adr/0064-…adoc`; summary line :9, accepted costs :156, a rejected alternative added; the stale "open in the code-correctness report" clause is gone (no hit for `code-correctness` in the merged ADR) |
| D2 remove the timeout pair from model, schema and registration | shipped-as-specified | `UpstreamConfig` now `(path, rewriteLocation, retry, notModified)`; `endpoint.schema.json` `upstream` object carries no timeout key; `ConfigModelReflection.java:120` still registers `Retry`, correctly, since the record stays |
| D3 remove the retry pair | shipped-as-specified | `UpstreamConfig.Retry(Boolean enabled)` single component; schema `retry` lists only `enabled` |
| D4 close DP-6 in the record | dropped (moot) | `doc/quality-report/` was deleted by #575; the spec's re-grounding banner already said so |
| D5 load-refusal test per removed key; remove binding tests | shipped-as-specified | four rows in `ConfigLoaderTest` (`:787-805`) consumed by `rejectsRefusedEndpointDocument`; `ConfigValidatorTest.shouldAcceptMillisecondPrecisionTimeout` deleted; `ConfigModelContractTest` builder calls dropped; no coverage-table rows added for removed keys |
| D6 documentation states the fixed behaviour | shipped-modified | the spec's edit list was partly wrong (see L1); the diff edits ten doc files instead, listed in the PR body and matching the diffstat exactly |

Unplanned additions, all documentation only: `doc/architecture/terminal-actions-and-protocols.adoc`,
`doc/features-analysis.adoc` (operator confirmed inclusion at the outline Q-Gate, finding 04c35d) and
`doc/architecture/tls-and-inbound-security.adoc:203` (a wording change, not in the landing narrative).
Realized footprint: 16 files (6 code and test, 10 doc) against 37 declared entries.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, the
`orchestrator.analyze` surface), by reading only: no build, no test run, no write. The orchestrator re-read the
points recorded below as `checked` at their `file:line` in the merge tree and records which it re-read. Anything
only a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf — reason: reading merged code across the model, the schema, the loader,
  the tests, the guard and ten documents is the depth the dispatch rule reserves for a leaf, and it keeps this
  landing's read out of the drain's context.
- **Verdict:** layer 1 findings (1, minor); layer 2 findings (2, minor); layer 3 findings (1, minor, not shown
  by reading).

### Layer 1 — Specified

| Finding | Claim | Verdict | Evidence (file:line) |
|---------|-------|---------|----------------------|
| none | the four keys were read nowhere at the parent | checked, re-read | `git grep` at `ab568dab` for the eight spellings finds only ADR-0064, `routing-and-forwarding.adoc:527-528`, the new refusal rows and `RetryingJwksLoaderTest.java:178` (an unrelated JWKS `RetryConfig.maxAttempts`) |
| none | no stack descriptor, benchmark, deployment or demo file used a key | checked, re-read | the same grep matches nothing under `integration-tests/`, `benchmarks/`, `deployment/`, `demo-client/` or `api-sheriff/src/main` |
| none | `Retry` stays registered | checked, re-read | `ConfigModelReflection.java:120` |
| L1-1 (minor) | the spec's banner said `doc/user/endpoint-routes.adoc`, ADR-0008 and the 504 / `DEADLINE_EXCEEDED` rows needed no edit | checked, refuted | `endpoint-routes.adoc:158` carried "Timing and retry keys: see upstream" (parent), ADR-0008:48 said ADR-0064 "leaves them open" (parent), and the 504 row had no per-attempt statement; the diff edits all three (`endpoint-routes.adoc:155-160`, `adr/0008:48`, `observability-and-errors.adoc:141-146`) |

**Layer 1 verdict:** findings — 1 (minor). The spec's "no edit needed" list was wrong, and only the code review
loop-back that caught ADR-0008 kept a stale sentence out of `main`. The spec is shipped, so no correction is owed;
the lesson is routed (see Follow-Ups). No spec-claim row corresponds to the banner statement, so no
`corpus set-verdict` was persisted.

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D2/D3 refusal at load | `endpoint.schema.json:256-264` (`upstream` and `retry` both `additionalProperties: false`, one inline definition) | `ConfigLoaderTest.java:787-805` | none | checked, re-read |
| Jackson backstop | `ConfigLoader.java:928-936` does not disable `FAIL_ON_UNKNOWN_PROPERTIES` | the same rows | none | checked, re-read |
| `retry.enabled` still binds | `UpstreamConfig.java:64` | `ConfigLoaderTest.java:638` strengthened to assert `retry().enabled()` is true | none | checked (binding under SNAKE_CASE for a single-component record: not shown by reading) |
| D1/D6 docs | ADR-0064 amendment; ten doc files | n/a | `tls-and-inbound-security.adoc:203` is in the diff and the PR body but not in the lane's narrative | checked, re-read |

- **L2-1 (minor):** the four refusal rows assert a `ConfigLoadException` whose error is in `endpoints/orders.yaml`
  with a pointer starting `/endpoint/routes/0/upstream` (timeouts) or `…/upstream/retry` (retry keys). They do
  not assert that the error names the key. By reading, no other cause can make a row pass: the fixture builder
  is the same shape as the happy-path fixture, and each retry row keeps a valid `enabled: true`. Re-admitting a
  schema entry turns each row red. The rows are pointer-scoped like the pre-existing `circuit_breaker` row.
- **L2-2 (minor):** the landing narrative lists two docs outside the request; the diff carries a third,
  `tls-and-inbound-security.adoc:203`. Accurate and docs-only.

**Layer 2 verdict:** findings — 2 (both minor).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 (not shown by reading) | `UpstreamGuards.java:114-117` and ADR-0064:86 | a retried dispatch | the docs state "2 retries, 100 ms apart"; the guard sets `.delay(100, MILLIS)` with no `.jitter(...)`. Whether the SmallRye programmatic retry applies a default jitter is library behaviour, not readable in this repository, so the gap may not be a flat 100 ms | minor |

**Layer 3 not shown by reading:** L3-1 (the library's default jitter; settled by one test that measures the gap
between attempts, or the library's source). Also not shown by reading: that `-Werror` passed and that the
single-component `Retry` record binds under SNAKE_CASE. CI evidence stands in for both (below), but CI is a run
result, not a reading.

**Layer 3 verdict:** not shown by reading — 1 (minor). No defect was found in the merged code. The guard,
`StreamAwareRetryGate`, `EgressClients` and the #506 constants are byte-identical to the parent
(`git diff c86ee5b9 ab568dab` over `edge/` touches only `DispatchStage.java`, two Javadoc lines).

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Pre-1.0 | never deprecate, remove directly | conforms | four fields and four schema entries deleted; no `@Deprecated`, no alias |
| CLAUDE.md § Testing | a key that parses is not a key that acts; deleting a key must turn a test red | conforms | `ConfigLoaderTest.java:787-805`; no coverage rows for removed keys; the kept `retry.enabled` is asserted |
| CLAUDE.md § Security | fail-closed, validate inputs | conforms | unknown keys refused at boot; the retry gate still fails closed (`DispatchStage.java:446-451`) |
| CLAUDE.md § Javadoc | public Javadoc, `@since`, `package-info` | conforms | `UpstreamConfig.java:23-62`, `DispatchStage.java:162-164`; no package added |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito or Hamcrest | conforms | rows are `@ParameterizedTest` arguments; plain static fixture |
| CLAUDE.md § Logging | no new log lines | conforms | no logging change |
| CLAUDE.md § Pre-Commit | zero warnings, `-Werror` | not_shown_by_reading | no unused import or dead code left; the build itself is a run result |
| spec scope fence | stop if work reaches beyond guard and client | conforms | runtime code beyond model, schema and one Javadoc untouched |

## Metrics and Anomalies

- Tokens: 15,582,782 total. Wall time 13,744 s (3 h 49 m).
- Loop-backs: one (code review found ADR-0008:48 stale; fix, an operator-authorized reword of criterion D3.C6,
  and a re-run of the HEAD-dependent finalize steps).
- Anomalies, all from the lane's own residue and not independently verified except where stated:
  - the pre-commit quality gate deleted one blank line in 448 `api-sheriff/src` files on every run and had to be
    reverted each time (a known mechanism, `doc/development/build-gate-discipline.adoc`); not filed as a lesson by the lane;
  - a metrics `accumulate` call with invented figures was issued in error and reversed with negated values;
  - `effort=medium` is the stamp's self-report, not a measurement.

## Routing and Merge Behavior

- Review: CodeRabbit (required) passed with no actionable comments. Sourcery (optional) skipped on its weekly
  quota. `pr comments --unresolved-only` lists three plain PR comments with no review thread, so
  `unresolved_review_threads` is 0 (the lane's claim; the three rows were not re-listed here).
- CI/merge: through the platform merge queue, squash commit `ab568dab`. `ci checks status --pr-number 586`:
  24 checks, overall success (Maven Build 25 and 26, quarkus-alignment, Integration Tests, SonarCloud, CodeRabbit,
  Run Integration Benchmarks, `deploy-snapshot` skipped as on every PR). The post-merge runs the lane left owed
  were checked: the PR-attached Performance Benchmark run `42620028579` is `success`, and every `push` run for
  `ab568dab` (Maven Build `42620023495`, Integration Tests `42620023660`, Config Validation, Demo Client E2E,
  Scorecard) is `success`. The commit-keyed lookup was read with `gh run list` because the ci abstraction has no
  by-commit run listing; that is outside the "ci abstraction only" rule and is recorded here rather than hidden.
  The squash message could not be set by the lane, so the landed message carries the CodeRabbit summary; cosmetic.
- Sonar: `sonar_open_new_issues=0` claimed by the lane; the SonarCloud check is `success` on the PR. The merge-head
  figure was not read separately.
- Collisions: none observed. Measured by `git show --name-only` against this diff: PLAN-SEC-03 (#581, `3eef748b`)
  shares exactly one file, `doc/architecture/observability-and-errors.adoc`, and PLAN-SEC-01 (#582, `c85817d9`)
  shares none. The operator-accepted overlap named `doc/security-threat-model.adoc` for SEC-03, which this plan
  never touched, so the file that actually overlapped was a different one; it merged through the queue without a
  conflict (no conflict is reported in the lane's residue; that is not an independent check). Declared
  surface 37 entries against realized 16 files; three realized docs (`terminal-actions-and-protocols`,
  `features-analysis`, `tls-and-inbound-security`) were not declared. The spec is shipped, so no declaration is corrected.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-29 --status shipped`
- [x] row `pr` stamped `#586`
- [x] row `landing` stamped `landings/PLAN-29.md`
- [x] row `plan_marshall_plan_id` stamped `plan-29-upstream-inert-keys`
- [x] epic.md narrative reconciled (Decisions entry, queue annotation, one Watch)
- [x] four candidate-lesson messages dispositioned `discarded` from this epic's corpus after routing outward
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **Routed to plan-marshall's `lessons-routing` inbox** (standing operator rule 2026-10-15), messages
  `api-sheriff-kidicap-gateway-requirements-014` to `-017`: a test removed with a deleted key is not declared in
  the outline's Tests-to-remove form (`-014`); an ADR amendment's inbound references are not swept before the
  outline declares a document untouched (`-015`); an out-of-fence file recorded as "see the operator question"
  (`-016`, the lane says this may confirm the gate works); one invented `manage-findings` verb, recurrence
  count only (`-017`). Not filed in this repository's corpus: the corpus refuses `plan-marshall:*` components
  as `wrong_store`.
- **Watch (L3-1):** the "100 ms apart" retry-gap statement in ADR-0064:86, `routing-and-forwarding.adoc` and
  `UpstreamGuards.java:85` is unverified against SmallRye's default jitter. A one-line doc softening or a
  measuring test settles it; the operator decides whether it earns a plan.
- **Unblocked:** PLAN-26, PLAN-20 and PLAN-21 no longer share files with a running plan in this epic, but each
  needs a re-ground against `main` first (PR #581 landed after the last cleanup).
