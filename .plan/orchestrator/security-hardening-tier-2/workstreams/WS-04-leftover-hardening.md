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

- In scope: the leftovers of PLAN-SEC2-01 and PLAN-SEC2-03 (and, folded in when it lands, PLAN-SEC2-02), as recorded in `.plan/local/security_notes.adoc` entries SEC2-01-A, SEC2-01-B, SEC2-03-A to D, and in `landings/PLAN-SEC2-01.md` and `landings/PLAN-SEC2-03.md`.
- Also in scope, decided by the operator on 2026-11-17: the request-head deadline gap is documented and back-channel logout gets a boot WARN (both in PLAN-SEC2-04), and the early-response quiet rule is its own plan, PLAN-SEC2-05.
- Out of scope: a management boot refusal (accepted with its existing WARN), the Info items (closed as recorded in the private notes), and anything PLAN-SEC2-02 owns (upstream breaker accounting, E1 to E3).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC2-04-leftover-hardening | staged | One plan for every leftover; runs after PLAN-SEC2-02 so it can fold that plan's leftovers in |
| PLAN-SEC2-05-early-response-quiet-exit | staged | Confirmed quiet exit for the early-response end; own plan because of its size and the framing-test flake history |

## Sequencing and Surface Notes

- Depends on PLAN-SEC2-02 shipping: the plan touches `edge/upstream/UpstreamFailureMapper.java`, `doc/security-threat-model.adoc` and `doc/LogMessages.adoc`, all of which PLAN-SEC2-02 edits.
- PLAN-SEC2-05 depends on PLAN-SEC2-02 and overlaps PLAN-SEC2-04 on `EdgeHardeningOptions.java`, `doc/security-threat-model.adoc` and `doc/user/edge-security.adoc`; sequenced after it or put to the operator by the gate.
- Its other surfaces (`bff/**`, `detection/**`, `edge/admission/**`, `forward/**`, `config/**`, `tls/**`, `pipeline/**`) are disjoint from PLAN-SEC2-02's `edge/upstream/**`.
