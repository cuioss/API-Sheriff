# Landing Analysis: PLAN-02 — Configurable Context Path

epic: deployment-configurability
workstream: WS-02
pr: 248 (merged as `b200bed`)

> Landing record. Corroborated against git, the CI abstraction, the lessons corpus and the working
> tree before writing; the operator's paste supplied the leads.

## Deliverable Fidelity vs Spec

⚠ **The spec declared 5 deliverables; 10 shipped.** That is not drift — the plan decomposed the
spec's five into ten implementable units and the mapping is clean. Recorded because the counts differ
and a reader comparing them would otherwise assume scope creep.

| Deliverable (spec) | Shipped as | Verdict | Evidence |
|---|---|---|---|
| 1. Research and record the mechanism | 1 (declare keys + Maven seam) | shipped-as-specified | `application.properties:60-62`, `api-sheriff/pom.xml` seam, ADR-0038 |
| 2. Configure it, defaulting to today's behaviour | 1 | shipped-as-specified | all three keys at stock defaults; no consumer change |
| 3. De-hardcode every consumer | 2,3,4,5,6 | shipped-**widened** | Compose label + four host-side derivers (k6, compose-sample, demo-client, release workflow) |
| 4. Verify at a documented-procedure bar | 8 | shipped-as-specified | manual verification procedure written and executed |
| 5. Document it | 7,10 | shipped-**widened** | mechanism + management interaction, plus downstream extension procedure |
| — | 9 (downstream build-parent POM) | **added-unplanned** | `build-parent/pom.xml`, `build-parent/example/pom.xml` |

⛔ **Deliverable 9 was not in the spec at all.** A published downstream build parent is a new
distributable artifact, not a refinement of "make the context path configurable". It is the single
largest scope addition this epic has absorbed without a fold being recorded at the time.

## The carrier-key resolution

✅ **The orchestrator's 2026-09-02 recommendation was adopted.** The seam as first committed was
inert — POM profiles mapped onto a Maven project property at SmallRye ordinal ~100 while
`application.properties` declared the same keys at 250, which always wins. The plan took the carrier-key
indirection over dropping the declarations, and recorded the constraint as **ADR-0038**.

⚠ The load-bearing assumption flagged at the time — that a Maven CLI `-D` reaches augmentation as a
system property at ordinal 400 — was settled by the plan, not by this orchestrator. Lesson
`2026-09-01-15-001` (*"`ConfigValue.getSourceName()` cannot prove a Quarkus build-time key is
declared"*) records that verifying it was harder than assumed.

## Metrics and Anomalies

- Tokens: 18,500,678 (`n=5/6`) — **the epic's most expensive plan by ~3.4×** (PLAN-11: 1.0 M)
- Duration: 59h16m wall — ⚠ of which **53h35m idle**; worked time **5h41m**. The wall figure
  overstates effort ~10×, the same gap PLAN-11 showed at 4×. Read worked time, not wall.
- Footprint: **54 files** changed, +4,725/−235
- Anomalies: **three PR wrappers**. #246 and #247 were closed unmerged because CodeRabbit — a required
  reviewer — was rate-limited against the then-current HEAD. ✅ Each reopen bought a fresh full-diff
  review that found real work: 13 findings on #247, 9 on #248, and **four defects the plan itself had
  shipped**, including a CVE-scanning remedy whose documented re-enable route was inert (the plugin is
  unbound in the parent chain) and a `BuildParentContractTest` that would have fired on the next
  release. The wrapper churn was a cost that bought genuine defect detection.

## Routing and Merge Behavior

- Review: CodeRabbit clean pass on `1cf0e48..46c23cb`; self-review 11 findings, 10 fixed; Sonar 0
  new-code issues, gate OK; security-audit 2 hardening edits.
- CI/merge: green at `46c23cb`, merged `b200bed`. `main` now `1c7308c` (#249 landed after).
- ⚠ `pre-push-quality-gate` passed with **1 accepted test failure** (`1e1720`) — the macOS
  live-Vert.x flake, which ran **8 gate rounds at 2 green / 6 red with eight disjoint failure sets,
  every failing file untouched by the branch, against CI green at every pushed HEAD**. That is a
  textbook application of lesson `2026-09-01-14-002`, and the acceptance is sound. PLAN-12 owns the
  diagnosis.

## ⛔ Defect: `emit-landing` skipped as "not orchestrated" — and the plan IS orchestrated

The finalize report records `emit-landing … skipped -- not orchestrated`. That is **false**, and it is
checkable:

- the archived `request.md` carries `source_id: .plan/local/orchestrator/deployment-configurability/plans/PLAN-02-configurable-context-path.md`, unchanged since launch;
- `inbox detect` on that exact value returns `orchestrated: true`, `epic: deployment-configurability`, `detection: orchestrated`.

**Consequence:** no inbox message was filed, the epic learned nothing from the drain, and this landing
reached the ledger only because the operator pasted it.

🔑 **This corrects an earlier ledger conclusion.** The PLAN-01 watch recorded the same skip and
concluded *"the cause was a hand-supplied value, not a broken seam … no bundle fix is owed — do not
re-derive it."* Two occurrences now share the shape, and in **both** the persisted `source_id` detects
as orchestrated. So the defect is **not** in `inbox detect`; it is in whatever the finalize step
actually consults, which is demonstrably not that seam. The earlier "no bundle fix is owed" is
withdrawn.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `248`; `landing` `landings/PLAN-02.md`; `plan_marshall_plan_id` `configurable-context-path`
- [x] Open Defect *"No context-path configuration exists"* — **RESOLVED**, retired
- [x] PLAN-10 ordinal re-resolved: ADR-0038 is now taken on `main`, so **0039** is the next free
- [x] PLAN-09 sequencing note: `doc/user/README.adoc` and `environment-variable-overrides.adoc` rewritten
- [x] Two owed items opened as defects (below)
- [x] `emit-landing` defect opened; PLAN-01 watch corrected
- [x] START-HERE and Ordered Queue regenerated; `resume_anchor` updated

## Follow-Ups

Two items the plan **disclosed rather than dropped**, both verified present:

1. ⚠ **`deployment/compose-sample/.env` line 4 names the renamed `wait-for-ready.sh`.** Confirmed: the
   comment says *"the same derive-don't-restate rule wait-for-ready.sh follows"*, and
   `deployment/compose-sample/scripts/` now holds only `start-sample.sh` and `stop-sample.sh`. Tooling
   prohibits committing `.env`. ✅ Documentation-only and `.env` is a non-build-input, so whoever fixes
   it skips both gates.
2. ⚠ **The two new `ManagementRootPathLabelIT` legs are compile-verified only** — CI is their first
   real execution. File confirmed present.

Neither is staged as a plan: item 1 is a one-line comment fix and item 2 resolves itself on the next
integration run. Both ride as Open Defects.
