# WS-04: Session and Scopes

epic: kidicap-gateway-requirements

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-04-session-and-scopes.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Rework BFF session and OIDC scope behaviour: `token_relay: false` for session routes (AS-5), a
query-preserving login return URL with configurable default (AS-6), and additive global-plus-endpoint
scopes replacing `auth.required_scopes`, with scope step-up (AS-14). Closed when PLAN-14 lands, with any
engine-gated part recorded as a documented gap and re-staged. Per-endpoint token narrowing (AS-14
"stage 2") is out of scope unless promoted by a decision.

## Scope

- In scope: `AuthConfig`, `OidcConfig`, `EndpointConfig` scopes, `SessionAuthenticationStage`,
  `AuthenticationStage`, `LoginFlow` / `LoginInitiationEndpoint`, `BffRuntimeProducer` scope wiring,
  `StepUpCoordinator`, schema `auth` / `oidc` definitions, Keycloak IT realm, `doc/user/bff-session.adoc`.
- Out of scope: userinfo claim serialization (WS-01), portal (WS-03), routing/headers (WS-02).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-14-session-and-scopes | staged | AS-5, AS-6, AS-14; 11 deliverables; engine seam is the epic's largest open question |

Superseded at aggregation (2026-09-15): PLAN-03, PLAN-05, PLAN-09 — see `plans/superseded/README.md`.

## Sequencing and Surface Notes

- Second in queue: AS-13 (PLAN-13) + AS-5 (this plan) together unblock the consumer's `require: session`
  UI routes. Overlaps PLAN-13 (`BffRuntimeProducer.java`), PLAN-15 / PLAN-16 (schemas, docs).
