# WS-04: Tier-2 quick wins

epic: security-hardening-tier-1

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-NN-{slug}.md` and is tracked in the
> `workstreams[]` field of the epic header, `status.json`. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Take the cheap, low-risk fixes from the operator's private 2026-11-07 assessment that remain after
tier 1: small bounded code changes that need no design decision, the compose sample's IdP fronting,
and the tracked documentation that overclaims what the gateway does. Added on the operator's word
on 2026-11-10.

## Scope

- In scope: the main-listener connection cap; the `/auth`-fronted compose sample; the JWKS refresh
  window's key pickup; a boot rule for unauthenticated WebSocket routes that re-admit
  `Authorization`; removing the inert `forwarded.trust_scheme_host` key; doc and Javadoc corrections
  for statements that are false at HEAD.
- Out of scope: everything that needs a design decision or touches a class a running plan holds —
  the request-head deadline, the upstream pool wait bound and departure wake-up (`DispatchStage`,
  after PLAN-SEC-05), the late-timeout answer in `TerminalDispatch`, back-channel logout across
  replicas, the PING-confirmed early-response exit, a management boot refusal, logout CSRF, and the
  detection-ledger items. They stay in the operator's private assessment.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC-06-tier-2-quick-wins | staged | Connection cap, sample IdP fronting, JWKS key pickup, WebSocket origin rule, delete `trust_scheme_host`, doc corrections |

## Sequencing and Surface Notes

- Disjoint from the running PLAN-SEC-05 in production code (`edge/upstream/**`, `tls/**`,
  `edge/terminal/**`). Shares documentation: `doc/security-threat-model.adoc`, and
  `doc/user/tls-edge.adoc` / `doc/architecture/tls-and-inbound-security.adoc` in different sections.
