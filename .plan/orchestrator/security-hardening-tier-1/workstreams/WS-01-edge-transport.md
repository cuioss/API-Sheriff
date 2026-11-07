# WS-01: Edge transport hardening

epic: security-hardening-tier-1

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-NN-{slug}.md` and is tracked in the
> `workstreams[]` field of the epic header, `status.json`. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Close the tier-1 gaps at the gateway's inbound transport edge: the SNI passthrough front listener
that holds undecided connections without limit, the WebSocket dispatch that dials the upstream
before validating the client's upgrade (and leaves HTTP/2 streams unanswered), and the relay
failure path that leaves the upstream exchange running after the client is gone.

## Scope

- In scope: `tls/SniFrontListener`, `tls/PassthroughRelay`, `tls/ClientHelloSniParser`, `edge/terminal/TerminalDispatch` (WebSocket dispatch, relay failure), `edge/relay/WebSocketRelayStage`, `edge/relay/ResponseStage` (relay failure), their tests, ADR-0066 and threat-model wording for these paths.
- Out of scope: the main listener's header-read deadline and connection cap (tier 2), the HTTP/2 early-response quiet rule (tier 4), anything under `edge/upstream/` (WS-02).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-SEC-01-edge-transport-hardening | staged | SNI ClientHello deadline + cap; WebSocket upgrade gate; relay-failure upstream reset |

## Sequencing and Surface Notes

- Shares only documentation with WS-02 and WS-03: `doc/security-threat-model.adoc`, and `doc/LogMessages.adoc` if a new LogRecord is needed.
- `TerminalDispatch` is also a hot spot for future tier-3 work (late-timeout response hardening); none of it is in this epic.
