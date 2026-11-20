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
- PLAN-SEC2-04 — staged 2026-11-17 on the operator's word as the one plan for every leftover of this epic (private-notes entries SEC2-01-A/B, SEC2-03-A to D and SEC2-L-01 to SEC2-L-15, plus the findings of the three landing reports). 12 deliverables (the ceiling), kept as one plan on the operator's direction; deliverables 10 and 11 were added on the operator's design decisions of 2026-11-17 and deliverable 12 and the additions to 9 are PLAN-SEC2-02's leftovers. It shares `doc/security-threat-model.adoc` plus two integration-test files with kidicap PLAN-31 (3 files) and three files with PLAN-SEC2-05. ADR-0060 is over the 500-line ceiling (517), so the plan edits it by replacement only. **Shipped as #635 (`00ebea64`)**, landing `landings/PLAN-SEC2-04.md` (analyzed 2026-11-19). 12 specified deliverables, 12 done (the lane counts 12); 92 files against 55 declared entries (22 undeclared, all explained by a deliverable; 7 declared untouched, each a refutation or an unreachable outcome). The three-layer read found no blocker or major; the landing's "D4 documentation only" and "wording agrees everywhere" claims are not borne out, two wording/Javadoc faults and one Javadoc-rule deviation are in the landing report, and three weakness-class reads went to the operator and the private notes only. The landing facts are incomplete (three review-thread keys `unknown`). The PR-attached post-merge benchmark run is green; the main-branch runs for `00ebea64` were not read (no lookup by merge commit in the CI abstraction).
- PLAN-SEC2-06 — staged 2026-11-19 on the operator's decision of 2026-11-18: the one follow-up plan for the minor PLAN-SEC2-05 leftovers, PLAN-SEC2-04's leftovers and the operator-only reads as mechanism-level hypotheses. 4 deliverables (one wording sweep applied in one commit, early-response test blind spots, timing-test attribution plus two untested answers, two mechanism-level questions); 30 declared surface entries, no overlap with another plan in this epic (kidicap PLAN-31 shares only `doc/security-threat-model.adoc` with the shipped 04). The epic closes when it ships.
- PLAN-SEC2-05 — shipped as #633 (`7f09b60b`), landing `landings/PLAN-SEC2-05.md`. Staged 2026-11-17: confirmed quiet exit for the early-response end (private-notes entry C2). Own plan: L-sized with a framing-test flake history. Depends on PLAN-SEC2-02; overlaps PLAN-SEC2-04 on `EdgeHardeningOptions.java`, `doc/security-threat-model.adoc` and `doc/user/edge-security.adoc`. 2 specified deliverables (the lane counts 3); 14 files against 9 declared (8 undeclared: the `InboundStreamWindow` PING seam and its test, a replacement trickle test, a comment-only test edit, ADR-0066, the architecture page, the SVG and the native IT; 3 declared untouched: `ResponseEnds.java`, `GatewayEdgeHttp2AbuseTest.java`, `doc/LogMessages.adoc`). The three-layer read found no blocker or major; two minor documentation faults and three test blind spots (see the landing). The PR-attached post-merge benchmark run is green; the main-branch runs for `7f09b60b` were not read (no lookup by merge commit in the CI abstraction).
- PLAN-SEC2-02 / PLAN-SEC2-03 — disjoint production surfaces (`edge/upstream/**` vs `bff/**`, `edge/reserved/**`, `edge/admission/Source*`, `detection/**`); they share only `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, so `next` puts the two-file overlap to the operator. Scope 2 allows them side by side. PLAN-SEC2-01 rewrote ADR-0060 and edited ADR-0076, which PLAN-SEC2-02 and PLAN-SEC2-03 amend again: each must check the ADR body ceiling (500 lines) before the amendment.

## Decisions

> ↪ Relocated to `settled.md` § "Decisions 2026-11-14 to 2026-11-19 (settled)" — every entry through the PLAN-SEC2-04 landing analysis concerns closed subjects (shipped plans, executed operator decisions, retired leads); moved verbatim on 2026-11-20.

- 2026-11-19 — PLAN-SEC2-06 staged on the operator's decision of 2026-11-18 (the one follow-up plan, collected after 04 landed). Split guard: 4 deliverables, under the ~6 ceiling, proceed as one plan. The epic closes when it ships; the Vision's "done when all five have shipped" is read as including it.
- 2026-11-20 — Operator wound the epic down: every remaining Open Defect, Watch and operator action was transferred to the `kidicap-gateway-requirements` epic as inbox messages `security-hardening-tier-2-001` to `-005` (the operator named that epic as the write target; no spec, queue or `epic.md` of it was touched), and the settled narrative was relocated to `settled.md`. This epic holds no open item of its own now; PLAN-SEC2-06 is its last plan, and any leftover of its landing is forwarded the same way before the epic closes. The operator's private notes are not transferred.
- 2026-11-20 — On the operator's word the formatter-churn Watch was withdrawn from the transfer (the class is closed: #242, #415, #559, #618, no recurrence in PLAN-SEC2-01 to 05), and the review-body Watches of #633 and #635 and the incomplete-landing Open Defect were retired after the orchestrator re-verified every finding against `main` at `00ebea64` (#633: 3 findings, all handled; #635: 6 findings, 4 fixed, 1 declined with a reason, 1 deferred to PLAN-SEC2-06) and removed messages `-002` and `-003` from the kidicap inbox; dated addenda are in `landings/PLAN-SEC2-04.md` and `landings/PLAN-SEC2-05.md`. Only `-004` (the upstream request the operator files) remains live there.

## Open Defects

> ↪ Relocated to `settled.md` § "Open Defects and Watches transferred to kidicap-gateway-requirements (2026-11-20)" — the incomplete-landing defect of PLAN-SEC2-04 was transferred (message `-003`) and then retired and withdrawn on 2026-11-20 after its figures were re-verified; none open here.

## Watches

> ↪ Relocated to `settled.md` § "Open Defects and Watches transferred to kidicap-gateway-requirements (2026-11-20)" — the upstream-request operator action was transferred to the kidicap epic inbox (message `-004`, the one live item there); the #633 and #635 review-body Watches were retired and withdrawn on 2026-11-20 after every finding was re-verified against `main`, and the formatter-churn Watch was withdrawn on the operator's correction (message `-001` amended: the class is closed, no Watch); none open here.
