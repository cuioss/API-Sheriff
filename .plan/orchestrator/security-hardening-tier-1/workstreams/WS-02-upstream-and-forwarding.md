# WS-02: Upstream dispatch attribution and forward policy

epic: security-hardening-tier-1

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-NN-{slug}.md` and is tracked in the
> `workstreams[]` field of the epic header, `status.json`. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Stop client-controlled input from reaching places the gateway is supposed to own: client-sent
provenance and URL-override headers that cross to the upstream under the default forward mode,
and an idle gRPC client whose guard timeout is charged to the upstream's circuit breaker.

## Scope

- In scope: `forward/ForwardPolicyStage`, `http/ConnectionHeaders`, `edge/upstream/DispatchStage` (attempt attribution only), `edge/upstream/GrpcDispatchStage`, their tests, ADR-0003, the forwarding architecture/configuration docs and the threat-model statements for these paths.
- Out of scope: wiring `forwarded.trust_scheme_host` (tier 3, must follow this plan), the upstream pool wait bound and departure wake-up (tier 2/4), breaker neutrality for client-ended dispatches (tier 4), `edge/terminal/TerminalDispatch` (WS-01).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC-02-upstream-attribution-and-forward-headers | staged | gRPC never-written head is client-attributed; provenance/override header class withheld |

## Sequencing and Surface Notes

- `DispatchStage` is the epic-wide concurrency hot spot (PR #532 shipped three race fixes there). This plan's change is confined to attempt attribution; it must not restructure the hand-over/departure code.
- Shares only documentation with WS-01 and WS-03: `doc/security-threat-model.adoc`, and `doc/LogMessages.adoc` if a new LogRecord is needed.
