# Landing Analysis: PLAN-V02-18 — Retire the `doc/plan/` Survivors

epic: api-sheriff-0-2-0
workstream: WS-02
pr: #368 (https://github.com/cuioss/API-Sheriff/pull/368)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-01 from the
> inbox message `retire-doc-plan-survivors-001.md` (inbox-scan mode, `landing-check`
> `complete: true`), after verifying each material claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against merge commit `4228d42fc8f95e44a0798ae8d9df0af326d4742a` on `origin/main`
(6 files) and the PR state read through the CI abstraction (`state: merged`, same commit). The
plan executed the spec's four items as two deliverables (`deliverables_total=2`, both done); the
table below is keyed on the spec.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — delete the three `doc/plan/` files | shipped-as-specified | `D doc/plan/01-base-implementation.adoc`, `D doc/plan/09-release-readiness.adoc`, `D doc/plan/README.adoc`; `git ls-tree origin/main doc/plan/` is empty. |
| D2 — preserve the 1.0-cut milestone durably | shipped-modified | `CLAUDE.md` § Pre-1.0 Rules gained a "When these rules end" paragraph; `AGENTS.md` gained the same statement (outside the declared surface). The wording is sharper than the spec's "1.0 not yet cut": it names the 1.0 release of the current `de.cuioss.sheriff.gateway` line and states that the abandoned 2026-07-12 `de.cuioss.sheriff.api` 1.0.0 publication is not that cut, pointing at the `release.yml` header and ADR-0035. The spec's shorter phrasing would have contradicted that recorded publication. The six-item work breakdown was not restated, as required. |
| D3 — repair the one outside reference | shipped-as-specified | `doc/quality-report/documentation.adoc` gained a NOTE that `doc/plan/` was retired, names the two cited paths, and points at the ledger archive; the report's historical findings are left as written. A search of `origin/main` outside `.plan/` finds `doc/plan` only in that file. |
| D4 — documentation-only commit path | shipped-as-specified | Footprint is `*.md` / `*.adoc` only. `pre-push-quality-gate` was skipped on operator instruction (build decision "not necessary"); no Maven build ran for the plan. |

**Surface delta** (`inbox landing-check`, declared 5 vs realized 6): `expansion_detected`, one
added path — `AGENTS.md`. Nothing declared was left untouched. No collision with the running
`PLAN-V02-08`.

## Metrics and Anomalies

- Tokens: 3,956,398 total.
- Duration: 3h06m48s wall (11,208 s).
- Anomalies: none in the lifecycle. The `landing-facts` block reports `archive-plan:pending`
  because the message is written before that step runs; the archived plan directory
  `2026-10-01-retire-doc-plan-survivors` exists. The plan id is `retire-doc-plan-survivors`,
  without the `plan-v02-18-` prefix, because this spec's hand-off command carries no explicit
  `plan_id`.

## Routing and Merge Behavior

- Review: CodeRabbit reviewed `e3db868` in full and raised one inline comment, fixed in
  `1a33cd6d`. For the fix commit it posted a "review limit reached" notice and only marked its
  thread addressed — the final head did not get a full CodeRabbit review (the plan's claim, not
  re-read by the orchestrator). `cuioss-review-bot` was re-requested by a hand-posted `/review`.
  `pr comments --unresolved-only` returns 8 rows, all with an empty `thread_id` — zero
  unresolved resolvable threads.
- CI/merge: merged through the merge queue. 32 of 33 PR checks green at analysis time.
- Post-merge, checked by the orchestrator 2026-10-01:
  - PR-attached `Run Integration Benchmarks` (run 36879327716): **still in progress** at
    analysis time — carried as a Watch.
  - Main-branch `Maven Build` for the merge commit (run 36879323055, event `push`): success;
    `build`, `sonar-build` and `deploy-snapshot` were **skipped** by `check-changes`, as expected
    for a documentation-only commit. `Integration Tests`, `Demo Client E2E` and `Scorecard` push
    runs: success.
  - Consequence: neither the plan nor CI ran `ReactorModuleListContractTest` against the edited
    `CLAUDE.md` / `AGENTS.md`. The diff adds one paragraph to each file's Pre-1.0 Rules section
    and does not touch a module list, so the risk is low; the next build-triggering change is the
    first run that proves it.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-18 --status shipped`
- [x] row `pr` stamped — `368`
- [x] row `landing` stamped — `landings/PLAN-V02-18.md`
- [x] row `plan_marshall_plan_id` stamped — `retire-doc-plan-survivors`
- [x] epic.md narrative reconciled: the `PLAN-V02-18` queue annotation is replaced by its landing
      note; two Watches added (benchmark run pending; stale architecture descriptors)
- [x] `archive/doc-plan-README.adoc` refreshed to the final revision of `doc/plan/README.adoc`
      (`4228d42f^`), closing the residue the plan could not touch across its write boundary
- [x] inbox message `retire-doc-plan-survivors-001.md` archived (`reconciled`)
- [x] resume anchor updated; `queue-view.md` regenerated and committed with the row change

## Follow-Ups

- **Benchmark run 36879327716** — outcome not yet observed. Watch; retire once green.
- **Stale architecture descriptors.** `.plan/project-architecture/_project.json` and
  `documentation/enriched.json` still describe "remaining implementation plans under doc/plan/"
  (the plan's claim; outside the epic tree, not edited by the orchestrator). Needs a
  `/marshall-steward` pass. Watch; no plan staged.
- **Hand-off commands without `plan_id`.** This spec's command produced a plan id that does not
  carry the `PLAN-V02-NN` prefix. Not a defect in the landing; noted so a later emit includes an
  explicit `plan_id` where the spec provides one.
