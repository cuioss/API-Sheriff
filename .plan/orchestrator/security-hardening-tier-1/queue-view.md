<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-1`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-1`, and `git add` the result. -->

# Queue view: Security hardening - tier 1

## START HERE

**Resume anchor**: === 2026-11-09 PLAN-SEC-01 (PR #582) and PLAN-SEC-03 (PR #581) SHIPPED and analyzed (landings/). PLAN-SEC-04 staged (edge transport bounds follow-up, mechanism-level; details in git-ignored .plan/local/security_notes.adoc). PLAN-SEC-02 staged, previously held back for overlap with the now-merged PLAN-SEC-03. NEXT ACTION: land the ledger, then run next to emit PLAN-SEC-02 / PLAN-SEC-04. Open watches: main push runs for c85817d9 and 3eef748b unverified. ===
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-SEC-02 (WS-02)
2. PLAN-SEC-04 (WS-01)
- PLAN-SEC-01 (WS-01) — plan=plan-sec-01-edge-transport-hardening — PR 582 — landing=landings/PLAN-SEC-01.md — status: shipped
- PLAN-SEC-03 (WS-03) — plan=plan-sec-03-pending-authorization-store-fairness — PR 581 — landing=landings/PLAN-SEC-03.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC-02 | WS-02 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStageTest.java; doc/LogMessages.adoc; doc/adr/0003-forwarded-headers.adoc; doc/architecture/routing-and-forwarding.adoc; doc/configuration/routing-and-forwarding.adoc; doc/security-threat-model.adoc | plan-marshall |
| 2 | PLAN-SEC-04 | WS-01 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGate.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsLogMessages.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRelayFailureResetTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeSessionRelayTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeWebSocketUpgradeGateTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGateTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/PassthroughRelayTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java; doc/LogMessages.adoc; doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc; doc/configuration/; doc/development/tls-edge.adoc; doc/security-threat-model.adoc; doc/user/tls-edge.adoc; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/TlsPassthroughIT.java | plan-marshall |
