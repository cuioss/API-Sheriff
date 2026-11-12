# WS-05: TokenSheriff Separation

epic: kidicap-gateway-requirements

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-05-token-sheriff-separation.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Operator goal (2026-11-12): full separation of concerns between API Sheriff and TokenSheriff. API
Sheriff must not duplicate functionality TokenSheriff provides, and generic functionality that
belongs in a token library moves to TokenSheriff. A read-only audit of main `26e34cd4` against
TokenSheriff 0.9.9 (see the epic Watch "TokenSheriff separation audit") found that the remaining
work splits in two: what API Sheriff can adopt with the 0.9.9 it already pins (PLAN-33), and what
needs a new TokenSheriff seam first, shipped in 0.9.10 by TokenSheriff's own epic
`api-sheriff-seams` (PLAN-34 adopts it). The workstream closes when both plans have landed.

## Scope

- In scope: deleting or replacing API Sheriff code that duplicates a TokenSheriff API, dropping
  dependencies the audit showed unused (with the operator's approval), and the correctness defects
  the audit found on the way (JWKS status masking).
- Out of scope: TokenSheriff changes themselves (TokenSheriff epic `api-sheriff-seams`); API
  Sheriff's internal duplicates that have no TokenSheriff counterpart (CIDR parsing, the
  Cookie-header parse loop, the trust-all guard); wiring step-up (PLAN-20).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-33-adapt-to-token-sheriff-0-9-9 | staged | Next plan to run; 0.9.9 as pinned. |
| PLAN-34-adapt-to-token-sheriff-0-9-10 | staged | Blocked until a released 0.9.10 is pinned. |

## Sequencing and Surface Notes

- PLAN-33 shares `RetryingJwksLoader` and its test with the running PLAN-31: it starts after PLAN-31
  merges, or the operator accepts the overlap at the gate.
- PLAN-34 depends on PLAN-33, on TokenSheriff's 0.9.10 release and on the release workflow's bump
  PR landing; it also shares `BffRuntimeProducer` with PLAN-20.
