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
Done when all five have shipped (the three above plus the leftover plan PLAN-SEC2-04 and the early-response plan PLAN-SEC2-05) with failing-first tests and the notes hold no planned item.

Not in this epic (kept in the operator's private notes): the request-head deadline (needs a
design decision; no supported Quarkus hook), the early-response quiet rule, back-channel logout
across replicas, a management boot refusal, and the Info items.

Disclosure is mechanism-level, as in tier 1: the triggering detail of each item lives only in the
git-ignored `.plan/local/security_notes_verified.adoc` and `.plan/local/security_notes.adoc`.

## Queue annotations

- PLAN-SEC2-01 — shipped as #623 (`31595bca`), landing `landings/PLAN-SEC2-01.md`. Simple and documentation changes; ran alone first. 6 specified deliverables (the lane counts 8).
- The post-landing re-check of `.plan/local/security_notes_verified.adoc` against `main` was done at `31595bca` (2026-11-16): the items still listed there are all unplanned ones, and nothing PLAN-SEC2-01 shipped reopens or closes one. The PLAN-SEC2-02/03 claims were re-grounded at the same HEAD (every `OBSERVED` claim holds; line numbers moved only in `DispatchStage`/`GrpcDispatchStage` Javadoc) and the verdicts are persisted on the specs.
- PLAN-SEC2-03 — shipped as #626 (`fa8dd54f`), landing `landings/PLAN-SEC2-03.md`. 4 specified deliverables (the lane counts 5); 55 files against 20 declared. The reserved-slice clause of deliverable 3 was not built (the spec clause was incoherent; `source-detection-substrate.adoc:87` says why) and a test cited as its proof passes against the base. PLAN-SEC2-02 re-reads `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` at `fa8dd54f` before it edits them.
- PLAN-SEC2-02 — shipped as #627 (`14741380`), landing `landings/PLAN-SEC2-02.md`. 5 specified deliverables, all done (the lane counts 5); 31 files against 13 declared (15 undeclared: the gateway-owned `UpstreamCircuitBreaker` that replaced the SmallRye breaker, three ADRs, five architecture and user docs, one diagram). Deliverable 2 replaced the breaker instead of taking either branch of its verify-first clause; deliverable 3 met only the at-once end of a departed dispatch, not the freed wait-queue slot. The post-merge runs the plan left to the orchestrator (PR-attached benchmark, main-branch Maven Build and Integration Tests for `14741380`) were read and are green. Its leftovers are folded into PLAN-SEC2-04.
- PLAN-SEC2-04 — staged 2026-11-17 on the operator's word as the one plan for every leftover of this epic (private-notes entries SEC2-01-A/B, SEC2-03-A to D and SEC2-L-01 to SEC2-L-15, plus the findings of the three landing reports). 12 deliverables (the ceiling), kept as one plan on the operator's direction; deliverables 10 and 11 were added on the operator's design decisions of 2026-11-17 and deliverable 12 and the additions to 9 are PLAN-SEC2-02's leftovers. It shares `doc/security-threat-model.adoc` plus two integration-test files with kidicap PLAN-31 (3 files) and three files with PLAN-SEC2-05. ADR-0060 is over the 500-line ceiling (517), so the plan edits it by replacement only.
- PLAN-SEC2-05 — staged 2026-11-17: confirmed quiet exit for the early-response end (private-notes entry C2). Own plan: L-sized with a framing-test flake history. Depends on PLAN-SEC2-02; overlaps PLAN-SEC2-04 on `EdgeHardeningOptions.java`, `doc/security-threat-model.adoc` and `doc/user/edge-security.adoc`.
- PLAN-SEC2-02 / PLAN-SEC2-03 — disjoint production surfaces (`edge/upstream/**` vs `bff/**`, `edge/reserved/**`, `edge/admission/Source*`, `detection/**`); they share only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, so `next` puts the two-file overlap to the operator. Scope 2 allows them side by side. PLAN-SEC2-01 rewrote ADR-0060 and edited ADR-0076, which PLAN-SEC2-02 and PLAN-SEC2-03 amend again: each must check the ADR body ceiling (500 lines) before the amendment.

## Decisions

- 2026-11-14 — Epic created on the operator's word after the tier-1 epic was closed ("eventually we closed the orchestrator too early"); the follow-ups get their own epic rather than reopening the archived one. Parallelization scope 2 (operator).
- 2026-11-14 — Decomposition: three workstreams / three plans along code ownership — WS-01 doc honesty and config guards (simple first), WS-02 upstream breaker accounting, WS-03 BFF logout and source keys. Plans kept large on the operator's direction; every planned item was removed from the private verified-findings notes, which keep only unplanned items.

- 2026-11-17 — Operator confirmed the CodeRabbit review-body findings of #623 (landing claimed 2, provider shows 10) and #626 (2 inline, undisposed in the provider's record) were handled; the two Watches for them are retired. The 2-vs-10 mismatch stays recorded as a false claim in `landings/PLAN-SEC2-01.md`.
- 2026-11-17 — Leftovers of the epic are collected in ONE plan, PLAN-SEC2-04 (workstream WS-04), on the operator's word; layer-3 items the landing reads raised are carried as `HYPOTHESIS` claims in its spec, mechanism-level, with the triggering detail only in the private notes.
- 2026-11-17 — Routed to the plan-marshall `lessons-routing` inbox (sibling repo `plan-marshall`, message `api-sheriff-security-hardening-tier-2-001.md`) as one finding grouping three clusters: the `worktree_root_unreadable` pre-push gate failure behind the three `force-override` pushes, the `unknown` required keys of both incomplete landings (PLAN-SEC2-01 `effort`; PLAN-SEC2-03 five keys), and the review-body count / Sonar `head_revision_unavailable` reads. The two Open Defects for the incomplete landings and the `force-override` Watch are retired on that routing; the facts stay recorded in the two landing reports.
- 2026-11-17 — Operator dropped the two Sonar Watches (`sonar_open_new_issues` of #623 and #626 stay unknown; the read cannot decide on a merged PR, root cause routed to the plan-marshall lessons inbox). Operator also authorized writing the orchestrator-found leftovers into the private notes: entries SEC2-L-01 to SEC2-L-11 in `.plan/local/security_notes.adoc`, each pointing at its PLAN-SEC2-04 deliverable.
- 2026-11-17 — Operator decided the five items the Vision had kept out, one by one: (1) request-head deadline (M1): document the front-proxy header-timeout requirement and draft an upstream request, no code now, as PLAN-SEC2-04 deliverable 10; (2) early-response quiet rule (C2): its own plan, PLAN-SEC2-05; (3) back-channel logout on one replica (C1): a boot WARN whenever server mode and back-channel logout are both enabled (no replica count is declared, so it also fires for single-replica deployments), as PLAN-SEC2-04 deliverable 11, no fan-out; (4) management plain-HTTP port (T6): accepted, the existing WARN stays, no boot refusal; (5) the ten Info items: closed as recorded in the private notes, none becomes a deliverable. PLAN-SEC2-04 now has 11 deliverables (ceiling 12), kept as one plan on the operator's direction.

- 2026-11-17 — PLAN-SEC2-02 landing (#627) analyzed and reconciled; its three-layer leaf read found items outside the spec's own deliverables (the attribution of an expired connection wait, the attribution of a client departure, a latent listener path, stale comments and ADR statements). They are folded into PLAN-SEC2-04 as deliverable 12 and into deliverable 9 under the operator's standing decision that this plan collects every leftover; the trigger detail went into `.plan/local/security_notes.adoc` as SEC2-L-12 to SEC2-L-15 under the operator's authorization of 2026-11-17, and the weakness-class items were reported to the operator in the session only.

## Open Defects

- None open.



## Watches

- CodeRabbit review-body findings of #627: the landing claimed 3 total / 3 handled, the provider shows 4 inline findings with no disposition (`review-gate`: `body_findings_undisposed`); the count is recorded as a false claim in `landings/PLAN-SEC2-02.md` and the handling is not re-verified. Retired on the operator's confirmation, as for #623 and #626.


- Pre-commit formatter churn (carried from tier 1): #618 aligned `main` with the local formatter; a plan that reports churn again re-opens it.
