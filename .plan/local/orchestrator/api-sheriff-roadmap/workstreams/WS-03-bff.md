# WS-03: BFF Track

epic: api-sheriff-roadmap

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-03-bff.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-marshall-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Deliver the headline differentiator: the BFF foundation with the stateful server-session shape
first (roadmap 07, operator decision 2026-07-15), then the stateless encrypted-cookie variant
as its delta (roadmap 08). Closes when Variants 2 and 3 are both delivered on the stabilized
data plane.

## Scope

- In scope: token-sheriff-client 0.9.2 OIDC confidential-client wiring, reserved gateway paths,
  pending-authorization record, in-memory session store + `__Host-` cookie, `require: session`
  runtime, CSRF defence, transparent refresh, RP-initiated + back-channel logout, RFC 9470
  step-up, AES-256-GCM token cookie + `previous_key` rotation + single-flight refresh.
- Out of scope: data-plane transport concerns (WS-02), release hardening (WS-04).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-06-bff-server-session | staged | Roadmap plan 07 — BFF foundation + Variant 2 (server session) |
| PLAN-07-bff-cookie | staged | Roadmap plan 08 — Variant 3 (cookie, stateless) — delta on 07 |

## Sequencing and Surface Notes

- Strictly sequential within the workstream: 08 depends on 07.
- Roadmap 07 depends on roadmap 03 + 04 (consumes `oidc` config from delivered plan 02).
- Largest, riskiest block of the epic — lands on a stabilized data plane by design.
