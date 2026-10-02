# Landing Analysis: PLAN-V02-19 — Orphaned Guard Hygiene

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #367 (https://github.com/cuioss/API-Sheriff/pull/367)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-01 from the
> operator's pasted finalize report and the inbox message
> `plan-v02-19-orphaned-guard-hygiene-001.md`, after verifying each material claim against
> ground truth. Both sources agree; where they are quoted below the claim was checked.

## Deliverable Fidelity vs Spec

Checked against the squash commit `6bb9076562b3a680060bfc26180362ed4b5727cd` on `origin/main`
(7 files, +781/−37) and the PR state read through the CI abstraction (`state: merged`,
`merge_commit_sha` equal to that commit).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — resolve the duplicate ADR ordinal `0053` | shipped-as-specified | `R099 doc/adr/0053-A_header_matchers… → doc/adr/0056-A_header_matchers…`; the one inbound reference in `0054-…adoc` repaired. `doc/adr/` on `origin/main` now carries no duplicated four-digit prefix; the portal record keeps `0053`. The ordinal was re-derived at implementation time as the spec required: `0055` was already taken, so the record is `0056`. |
| D2 — ADR ordinal-uniqueness contract test | shipped-as-specified | `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/AdrOrdinalUniquenessContractTest.java` (new, 241 lines): `noOrdinalIsClaimedByTwoDecisionRecords` plus the non-vacuity control `detectorReportsASharedOrdinalAndNotADistinctOne` over a `@TempDir` fixture. |
| D3 — reactor module-list contract test | shipped-as-specified | `…/config/ReactorModuleListContractTest.java` (new, 503 lines): one test per document, a discrimination control (`extractionAndComparisonDiscriminate`: duplicated bullet, missing module, correct block) and `reactorExtractionIgnoresProfileScopedModules`. Neither `AGENTS.md` nor `CLAUDE.md` is in the diff — both already matched the reactor. |
| D4 — Sonar `java:S3398` on `defaultSslContext()` | shipped-as-specified | `UpstreamAssetSource.java`: the method is now `private static` inside nested `HttpUpstreamFetcher` (declared line 375, called line 313); no `NOSONAR`. |
| (unplanned) move `UpstreamTimeoutException` out of the `UpstreamFetcher` interface | added-unplanned | Same file: `public static final class UpstreamTimeoutException` is now a direct member of `UpstreamAssetSource` (line 560). Forced by the Sonar gate on the first PR head (`java:S9398`); two test files in `…/asset/` changed one line each to follow the rename. The qualified name changed from `UpstreamAssetSource.UpstreamFetcher.UpstreamTimeoutException` to `UpstreamAssetSource.UpstreamTimeoutException`. |

**Surface delta** (`inbox landing-check`, declared 6 vs realized 8): `expansion_detected`.
Added, never declared: the two `…/asset/` test files and the `0056-…` rename target. Declared,
untouched: `AGENTS.md`, `CLAUDE.md` (read by the new test, not edited). The expansion did not
collide with the concurrently running `PLAN-V02-08`.

## Metrics and Anomalies

- Tokens: 5,002,915 total; 6-finalize 2.04M and 5-execute 1.38M are the two largest phases.
- Duration: 5h25m wall, 1h41m worked (n=5/6 phases), 3h43m idle.
- Anomalies: one loop-back in finalize (iteration 2). The first PR head `534f449a` failed the
  Sonar quality gate on `java:S9398` — pre-existing code counted as new because the file was
  touched — fixed by commit `ffca5593`. The `landing-facts` block reports `archive-plan:pending`
  because the message was written before that step ran; the archived plan directory
  `2026-10-01-plan-v02-19-orphaned-guard-hygiene` exists.

## Routing and Merge Behavior

- Review: CodeRabbit posted one inline suggestion (derive the module lists from the POM instead
  of testing them); declined with a reply and resolved, because the spec asks for a contract test
  over hand-written lists. Sourcery approved twice. `cuioss-review-bot` was re-requested by a
  hand-posted `/review` after the fix commit. `pr comments --unresolved-only` returns 8 rows, all
  with an empty `thread_id` (review bodies and issue comments) — zero unresolved resolvable threads.
- CI/merge: all 34 PR checks green; merged through the merge queue as a squash. No rebase
  conflict and no re-verify signal.
- Post-merge, verified by the orchestrator 2026-10-01:
  - PR-attached `Run Integration Benchmarks` (run 36853680718): success.
  - Main-branch `Maven Build` for the merge commit (run 36853679578, event `push`): every job
    success, including `build / deploy-snapshot`. `Integration Tests`, `Demo Client E2E` and
    `Scorecard` push runs on the same commit: success.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-19 --status shipped`
- [x] row `pr` stamped — `367`
- [x] row `landing` stamped — `landings/PLAN-V02-19.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-v02-19-orphaned-guard-hygiene`
- [x] epic.md narrative reconciled: the `PLAN-V02-19` queue annotation is replaced by its landing
      consequences (V02-04 and V02-18 unblocked; header-matcher ADR is `0056`; next free ordinal
      re-derived at implementation time)
- [x] Watch added for the three tooling gaps the plan reported (unverified leads, owned upstream)
- [x] inbox message `plan-v02-19-orphaned-guard-hygiene-001.md` archived (`reconciled`)
- [x] resume anchor updated; `queue-view.md` regenerated and committed with the row change

## Follow-Ups

- **ADR ordinal for later plans.** Any plan citing the header-matcher record uses `0056`. The next
  free ordinal on `origin/main` at `6bb90765` is `0057`; every ADR-authoring plan still re-derives
  it on its own branch, and the new uniqueness test now fails the build if two claim the same one.
  Recorded in the queue annotations.
- **Residual gap, by design, not closed.** Both contract tests run only when a Maven build runs,
  so a documentation-only change can still land a duplicate ordinal or a drifted module list until
  the next build-triggering change. Recorded as a Watch; no plan staged.
- **Tooling gaps reported by the plan** (`ci-verify` producer name rejected by the triage
  workflow; pre-push gate derives no Maven bundle and the module-tests canonical does not resolve
  at the reactor root; `manage-status transition` and `inbox detect` disagree on orchestration).
  Not corroborated by the orchestrator — recorded as one Watch, owned upstream in plan-marshall.
