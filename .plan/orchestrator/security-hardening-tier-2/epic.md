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
Done when all three have shipped with failing-first tests and the notes hold no planned item.

Not in this epic (kept in the operator's private notes): the request-head deadline (needs a
design decision; no supported Quarkus hook), the early-response quiet rule, back-channel logout
across replicas, a management boot refusal, and the Info items.

Disclosure is mechanism-level, as in tier 1: the triggering detail of each item lives only in the
git-ignored `.plan/local/security_notes_verified.adoc` and `.plan/local/security_notes.adoc`.

## Queue annotations

- PLAN-SEC2-01 — shipped as #623 (`31595bca`), landing `landings/PLAN-SEC2-01.md`. Simple and documentation changes; ran alone first. 6 specified deliverables (the lane counts 8).
- The post-landing re-check of `.plan/local/security_notes_verified.adoc` against `main` was done at `31595bca` (2026-11-16): the items still listed there are all unplanned ones, and nothing PLAN-SEC2-01 shipped reopens or closes one. The PLAN-SEC2-02/03 claims were re-grounded at the same HEAD (every `OBSERVED` claim holds; line numbers moved only in `DispatchStage`/`GrpcDispatchStage` Javadoc) and the verdicts are persisted on the specs.
- PLAN-SEC2-02 / PLAN-SEC2-03 — disjoint production surfaces (`edge/upstream/**` vs `bff/**`, `edge/reserved/**`, `edge/admission/Source*`, `detection/**`); they share only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, so `next` puts the two-file overlap to the operator. Scope 2 allows them side by side. PLAN-SEC2-01 rewrote ADR-0060 and edited ADR-0076, which PLAN-SEC2-02 and PLAN-SEC2-03 amend again: each must check the ADR body ceiling (500 lines) before the amendment.

## Decisions

- 2026-11-14 — Epic created on the operator's word after the tier-1 epic was closed ("eventually we closed the orchestrator too early"); the follow-ups get their own epic rather than reopening the archived one. Parallelization scope 2 (operator).
- 2026-11-14 — Decomposition: three workstreams / three plans along code ownership — WS-01 doc honesty and config guards (simple first), WS-02 upstream breaker accounting, WS-03 BFF logout and source keys. Plans kept large on the operator's direction; every planned item was removed from the private verified-findings notes, which keep only unplanned items.

## Open Defects

- 2026-11-16 — Incomplete landing `sec2-01-doc-honesty-and-config-guards-001.md` (PLAN-SEC2-01): `inbox landing-check` reports `complete: false`, missing key `effort` (the facts block carries `effort=unknown`). The landing was still reconciled and its facts used as far as they go; a manual paste from that plan may still surface the effort level.

## Watches

- 2026-11-16 — PLAN-SEC2-01 landing claims `review_body_findings_total=2` but `runbook_lane review-gate --pr-number 623` on the merged head reports 10 review-body findings (all CodeRabbit, none dispositioned in the provider's own record). Recorded as a false claim, not a rounding difference. `review_body_findings_handled=2` is not re-verified (the dispositions live in the lane's own record). Retire once the operator confirms the ten were handled.
- 2026-11-16 — PLAN-SEC2-01 `sonar_open_new_issues=0` is not re-verified: the Sonar read on the merged PR is `undecidable` (`head_revision_unavailable`), so the figure is unknown. Retire on the operator's confirmation or a Sonar read that decides.
- 2026-11-16 — PLAN-SEC2-01 pushed on the logged `force-override` basis three times because the pre-push freshness gate refused every pass with `worktree_root_unreadable` (null worktree root in every build row of the change ledger). The lane attributes it to a plan-marshall build-recording defect that was not investigated. Operator to route it to the plan-marshall lessons inbox; retire once routed.

- Pre-commit formatter churn (carried from tier 1): #618 aligned `main` with the local formatter; a plan that reports churn again re-opens it.
