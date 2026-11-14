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

- PLAN-SEC2-01 — simple and documentation changes; runs alone first. 6 deliverables, kept as one plan on the operator's "not too small" direction.
- After PLAN-SEC2-01 lands: re-check `.plan/local/security_notes_verified.adoc` against `main` (orchestrator analysis, not a plan) BEFORE emitting PLAN-SEC2-02/03; re-ground their claims at the new HEAD.
- PLAN-SEC2-02 / PLAN-SEC2-03 — disjoint production surfaces (`edge/upstream/**` vs `bff/**`, `edge/reserved/**`, `edge/admission/Source*`, `detection/**`); they share only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, so `next` puts the two-file overlap to the operator. Scope 2 allows them side by side.

## Decisions

- 2026-11-14 — Epic created on the operator's word after the tier-1 epic was closed ("eventually we closed the orchestrator too early"); the follow-ups get their own epic rather than reopening the archived one. Parallelization scope 2 (operator).
- 2026-11-14 — Decomposition: three workstreams / three plans along code ownership — WS-01 doc honesty and config guards (simple first), WS-02 upstream breaker accounting, WS-03 BFF logout and source keys. Plans kept large on the operator's direction; every planned item was removed from the private verified-findings notes, which keep only unplanned items.

## Open Defects

- none

## Watches

- Pre-commit formatter churn (carried from tier 1): #618 aligned `main` with the local formatter; a plan that reports churn again re-opens it.
