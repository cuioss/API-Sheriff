# Epic: Security hardening - tier 2 (follow-ups)

slug: security-hardening-tier-2

> Hand-written narrative for one epic under `.plan/orchestrator/{slug}/`. The layout and
> authority contract live in the central standard — see
> `persona-plan-orchestrator/standards/orchestration-model.md`. The ledger JSON files
> (`status.json`, the `queue/{PLAN-ID}.json` rows) and `resume_anchor.md` are the machine
> authority; any statement here that conflicts with them is stale prose.
>
> START HERE and the Ordered Queue are not in this file. They live in the generated,
> git-tracked `queue-view.md` next to it (see the Persist / Stop-Resume Contract in that
> standard). `queue-view.md` is never hand-edited. A merge conflict in it is never merged by
> hand: merge the source files, run
> `python3 .plan/execute-script.py plan-marshall:plan-orchestrator:orchestrator regenerate-view --slug security-hardening-tier-2`
> on the merged tree, and `git add` the result.

## Vision

Close the follow-up findings left after epic `security-hardening-tier-1` (archived 2026-11-14) and
the operator's follow-up PR #620. Input is the 2026-11-14 ground-truth check of the operator's
private verified-findings notes at `main` `6ee137df`, which separated real gaps from theoretical
ones. Order, on the operator's direction: the simple and documentation changes ship first
(PLAN-SEC2-01), then the private notes are re-checked against the result, then the two code
plans run (upstream breaker accounting, PLAN-SEC2-02; BFF logout and source keys, PLAN-SEC2-03).
Done when all four have shipped (the three above plus the leftover plan PLAN-SEC2-04) with failing-first tests and the notes hold no planned item.

Not in this epic (kept in the operator's private notes): the request-head deadline (needs a
design decision; no supported Quarkus hook), the early-response quiet rule, back-channel logout
across replicas, a management boot refusal, and the Info items.

Disclosure is mechanism-level, as in tier 1: the triggering detail of each item lives only in the
git-ignored `.plan/local/security_notes_verified.adoc` and `.plan/local/security_notes.adoc`.

## Queue annotations

- PLAN-SEC2-01 — shipped as #623 (`31595bca`), landing `landings/PLAN-SEC2-01.md`. Simple and documentation changes; ran alone first. 6 specified deliverables (the lane counts 8).
- The post-landing re-check of `.plan/local/security_notes_verified.adoc` against `main` was done at `31595bca` (2026-11-16): the items still listed there are all unplanned ones, and nothing PLAN-SEC2-01 shipped reopens or closes one. The PLAN-SEC2-02/03 claims were re-grounded at the same HEAD (every `OBSERVED` claim holds; line numbers moved only in `DispatchStage`/`GrpcDispatchStage` Javadoc) and the verdicts are persisted on the specs.
- PLAN-SEC2-03 — shipped as #626 (`fa8dd54f`), landing `landings/PLAN-SEC2-03.md`. 4 specified deliverables (the lane counts 5); 55 files against 20 declared. The reserved-slice clause of deliverable 3 was not built (the spec clause was incoherent; `source-detection-substrate.adoc:87` says why) and a test cited as its proof passes against the base. PLAN-SEC2-02 re-reads `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` at `fa8dd54f` before it edits them.
- PLAN-SEC2-04 — staged 2026-11-17 on the operator's word as the one plan for every leftover of this epic (private-notes entries SEC2-01-A/B and SEC2-03-A to D, plus the layer-3 and minor findings of the two landing reports). 9 deliverables, kept as one plan on the operator's direction (the 12-deliverable ceiling). Sequenced after PLAN-SEC2-02: it shares `doc/security-threat-model.adoc`, `doc/LogMessages.adoc` and `edge/upstream/UpstreamFailureMapper.java` with it (6 files) and `doc/security-threat-model.adoc` plus two integration-test files with kidicap PLAN-31 (3 files). When PLAN-SEC2-02 lands, `analyze` folds its leftovers into this spec before it is emitted. ADR-0060 is already over the 500-line ceiling (513), so the plan adds nothing to it.
- PLAN-SEC2-02 / PLAN-SEC2-03 — disjoint production surfaces (`edge/upstream/**` vs `bff/**`, `edge/reserved/**`, `edge/admission/Source*`, `detection/**`); they share only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, so `next` puts the two-file overlap to the operator. Scope 2 allows them side by side. PLAN-SEC2-01 rewrote ADR-0060 and edited ADR-0076, which PLAN-SEC2-02 and PLAN-SEC2-03 amend again: each must check the ADR body ceiling (500 lines) before the amendment.

## Decisions

- 2026-11-14 — Epic created on the operator's word after the tier-1 epic was closed ("eventually we closed the orchestrator too early"); the follow-ups get their own epic rather than reopening the archived one. Parallelization scope 2 (operator).
- 2026-11-14 — Decomposition: three workstreams / three plans along code ownership — WS-01 doc honesty and config guards (simple first), WS-02 upstream breaker accounting, WS-03 BFF logout and source keys. Plans kept large on the operator's direction; every planned item was removed from the private verified-findings notes, which keep only unplanned items.

- 2026-11-17 — Operator confirmed the CodeRabbit review-body findings of #623 (landing claimed 2, provider shows 10) and #626 (2 inline, undisposed in the provider's record) were handled; the two Watches for them are retired. The 2-vs-10 mismatch stays recorded as a false claim in `landings/PLAN-SEC2-01.md`.
- 2026-11-17 — Leftovers of the epic are collected in ONE plan, PLAN-SEC2-04 (workstream WS-04), on the operator's word; layer-3 items the landing reads raised are carried as `HYPOTHESIS` claims in its spec, mechanism-level, with the triggering detail only in the private notes.

## Open Defects

- 2026-11-16 — Incomplete landing `sec2-01-doc-honesty-and-config-guards-001.md` (PLAN-SEC2-01): `inbox landing-check` reports `complete: false`, missing key `effort` (the facts block carries `effort=unknown`). The landing was still reconciled and its facts used as far as they go; a manual paste from that plan may still surface the effort level.

- 2026-11-17 — Incomplete landing `sec2-03-bff-logout-and-source-keys-001.md` (PLAN-SEC2-03): `inbox landing-check` reports `complete: false`, missing keys `total_tokens`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`, `sonar_open_new_issues` (all `unknown`; the lane names the missing finalize-step producers). The landing was still reconciled; same producer gap as the 2026-11-16 entry for PLAN-SEC2-01, so one plan-marshall defect may cover both.

## Watches

- 2026-11-16 — PLAN-SEC2-01 `sonar_open_new_issues=0` is not re-verified: the Sonar read on the merged PR is `undecidable` (`head_revision_unavailable`), so the figure is unknown. Retire on the operator's confirmation or a Sonar read that decides.
- 2026-11-16 — PLAN-SEC2-01 pushed on the logged `force-override` basis three times because the pre-push freshness gate refused every pass with `worktree_root_unreadable` (null worktree root in every build row of the change ledger). The lane attributes it to a plan-marshall build-recording defect that was not investigated. Operator to route it to the plan-marshall lessons inbox; retire once routed.

- 2026-11-17 — PLAN-SEC2-03: the Sonar read on merged PR 626 is `undecidable` (`head_revision_unavailable`); the lane's own `new_code_issue_count=0` is unconfirmed here. Same cause as the PLAN-SEC2-01 watch; retire both together.

- Pre-commit formatter churn (carried from tier 1): #618 aligned `main` with the local formatter; a plan that reports churn again re-opens it.
