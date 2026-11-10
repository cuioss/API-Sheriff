<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-1`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-1`, and `git add` the result. -->

# Queue view: Security hardening - tier 1

## START HERE

**Resume anchor**: === 2026-11-10 PLAN-SEC-02 SHIPPED (PR #590, 81b46785; landings/PLAN-SEC-02.md). PLAN-SEC-01/-02/-03 shipped; PLAN-SEC-04 (edge transport bounds follow-up) RUNNING, PR #589 open. Inbox drained (empty). Open: operator decision on staging a fix plan for the PLAN-SEC-02 layer-3 M6 residual (first-message bound ends at the first byte; details in .plan/local/security_notes.adoc). NEXT ACTION: when PLAN-SEC-04 lands run /plan-marshall:plan-orchestrator analyze slug=security-hardening-tier-1 (check its rebase vs kidicap PLAN-29 and PLAN-SEC-02 on the shared doc pages); then land the ledger and close the epic unless a fix plan was staged. Watches: main push runs c85817d9/3eef748b/81b46785 unverified ===
**Phase**: orchestrating
**Running**:
- PLAN-SEC-04 (WS-01)
**Queue** (staged, in order):
- (empty)
- PLAN-SEC-01 (WS-01) — plan=plan-sec-01-edge-transport-hardening — PR 582 — landing=landings/PLAN-SEC-01.md — status: shipped
- PLAN-SEC-03 (WS-03) — plan=plan-sec-03-pending-authorization-store-fairness — PR 581 — landing=landings/PLAN-SEC-03.md — status: shipped
- PLAN-SEC-02 (WS-02) — plan=sec-02-upstream-attribution-forward-headers — PR 590 — landing=landings/PLAN-SEC-02.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC-04 | WS-01 | running | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGate.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsLogMessages.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRelayFailureResetTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeSessionRelayTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeWebSocketUpgradeGateTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGateTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/PassthroughRelayTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java; doc/LogMessages.adoc; doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc; doc/configuration/; doc/development/tls-edge.adoc; doc/security-threat-model.adoc; doc/user/tls-edge.adoc; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/TlsPassthroughIT.java | plan-marshall |
