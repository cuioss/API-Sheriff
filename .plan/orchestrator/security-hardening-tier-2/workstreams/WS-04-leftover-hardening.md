# WS-04: Leftover hardening

epic: security-hardening-tier-2

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-04-leftover-hardening.md` and is tracked in the
> `workstreams[]` field of the epic header, `status.json`. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

The one place the epic collects what its other plans found and did not fix. PLAN-SEC2-01, -02 and
-03 each shipped a bounded slice and left observations behind: items the implementing plans recorded
in the operator's private notes, and items the orchestrator's three-layer landing reads raised. This
workstream turns them into one plan, so no leftover lives only in a landing report or a note. It
closes when that plan has shipped and the private notes hold no item this epic left open.

## Scope

- In scope: the leftovers of PLAN-SEC2-01, PLAN-SEC2-02 and PLAN-SEC2-03, as recorded in `.plan/local/security_notes.adoc` entries SEC2-01-A, SEC2-01-B, SEC2-03-A to D and SEC2-L-01 to SEC2-L-15, and in `landings/PLAN-SEC2-01.md`, `landings/PLAN-SEC2-02.md` and `landings/PLAN-SEC2-03.md`.
- Also in scope, decided by the operator on 2026-11-17: the request-head deadline gap is documented and back-channel logout gets a boot WARN (both in PLAN-SEC2-04), and the early-response quiet rule is its own plan, PLAN-SEC2-05.
- Out of scope: a management boot refusal (accepted with its existing WARN) and the Info items (closed as recorded in the private notes).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC2-04-leftover-hardening | staged | One plan for every leftover, 12 deliverables; PLAN-SEC2-02's leftovers are folded in |
| PLAN-SEC2-05-early-response-quiet-exit | staged | Confirmed quiet exit for the early-response end; own plan because of its size and the framing-test flake history |

## Sequencing and Surface Notes

- PLAN-SEC2-02 has shipped (#627, `14741380`); PLAN-SEC2-04 re-reads `edge/upstream/**`, `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` at the `main` it starts from, and carries 02's leftovers as deliverable 12 and in deliverable 9.
- PLAN-SEC2-05 overlaps PLAN-SEC2-04 on `EdgeHardeningOptions.java`, `doc/security-threat-model.adoc` and `doc/user/edge-security.adoc`; sequenced after it or put to the operator by the gate.
- PLAN-SEC2-04 also edits `edge/upstream/**` (deliverable 12) and `doc/adr/0060-…`, which is over the 500-line ceiling and is edited by replacement only.
