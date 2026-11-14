# WS-03: BFF logout and source keys

epic: security-hardening-tier-2

## Charter

Require a same-origin signal before the BFF logout endpoint acts, and make the shared `unknown`
source key and the detection ledger's capacity safe under the actions that consume them.

## Scope

- In scope: `bff/**` logout and pending-quota keying, `edge/reserved/**`, `edge/admission/SourceGate`
  and `SourceKeys`, `detection/**`, their tests and docs.
- Out of scope: upstream dispatch (WS-02), documentation-only fixes (WS-01).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC2-03-bff-logout-and-source-keys | staged | logout same-origin gate, `unknown` key handling, ledger pressure |

## Sequencing and Surface Notes

- After PLAN-SEC2-01 and the notes re-check. May run beside PLAN-SEC2-02.
