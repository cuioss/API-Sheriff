<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-1`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-1`, and `git add` the result. -->

# Queue view: Security hardening - tier 1

## START HERE

**Resume anchor**: 2026-11-13: PLAN-SEC-01..06 shipped (#582, #581, #590, #589, #600, #607). PLAN-SEC-07 (tier-2 follow-ups, runbook lane) RUNNING since 2026-11-13 (start confirmed by the operator). JWKS points S6-E/S6-F were handed to the operator for kidicap PLAN-31 (hands-off). Still open with the operator: PLAN-SEC-05 minor layer-3 points; M3 part a and R3 stageable; formatter style Watch. When PLAN-SEC-07's PR merges run /plan-marshall:plan-orchestrator analyze slug=security-hardening-tier-1.
**Phase**: orchestrating
**Running**:
- PLAN-SEC-07 (WS-04)
**Queue** (staged, in order):
- (empty)
- PLAN-SEC-01 (WS-01) — plan=plan-sec-01-edge-transport-hardening — PR 582 — landing=landings/PLAN-SEC-01.md — status: shipped
- PLAN-SEC-03 (WS-03) — plan=plan-sec-03-pending-authorization-store-fairness — PR 581 — landing=landings/PLAN-SEC-03.md — status: shipped
- PLAN-SEC-02 (WS-02) — plan=sec-02-upstream-attribution-forward-headers — PR 590 — landing=landings/PLAN-SEC-02.md — status: shipped
- PLAN-SEC-04 (WS-01) — plan=plan-sec-04-edge-transport-bounds-follow-up — PR 589 — landing=landings/PLAN-SEC-04.md — status: shipped
- PLAN-SEC-05 (WS-02) — plan=plan-sec-05-grpc-request-stream-attribution — PR 600 — landing=landings/PLAN-SEC-05.md — status: shipped
- PLAN-SEC-06 (WS-04) — plan=plan-sec-06-tier-2-quick-wins — PR 607 — landing=landings/PLAN-SEC-06.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC-07 | WS-04 | running | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java; api-sheriff/src/main/resources/application.properties; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRulesTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ShippedApplicationPropertiesTest.java; doc/LogMessages.adoc; doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc; doc/architecture/observability-and-errors.adoc; doc/architecture/tls-and-inbound-security.adoc; doc/configuration/security-and-edge-hardening.adoc; doc/resources/diagrams/management-metrics-and-health.svg; doc/resources/diagrams/tls-sni-split-and-seams.svg; doc/security-threat-model.adoc; doc/user/container-image.adoc; doc/user/edge-security.adoc; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/MainListenerConnectionCapIT.java | runbook |
