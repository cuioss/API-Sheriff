<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-2`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-2`, and `git add` the result. -->

# Queue view: Security hardening - tier 2 (follow-ups)

## START HERE

**Resume anchor**: PLAN-SEC2-01 shipped (#623, analyzed). PLAN-SEC2-02 and PLAN-SEC2-03 emitted and recorded launched (auto_emit); awaiting operator-confirmed start (launched -> running) for each, then their PR landings. Open: landing-effort defect and three Watches in epic.md (review-body count mismatch, Sonar undecidable, force-override push). After each lands: analyze; epic closes when both ship and the private notes hold no planned item.
**Phase**: orchestrating
**Queue** (staged, in order):
- (empty)
- PLAN-SEC2-01 (WS-01) — plan=sec2-01-doc-honesty-and-config-guards — PR 623 — landing=landings/PLAN-SEC2-01.md — status: shipped
- PLAN-SEC2-02 (WS-02) — status: launched
- PLAN-SEC2-03 (WS-03) — status: launched

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC2-02 | WS-02 | launched | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuardsTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamPoolSaturationTest.java; doc/LogMessages.adoc; doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc; doc/security-threat-model.adoc; doc/user/edge-security.adoc | plan-marshall |
| 2 | PLAN-SEC2-03 | WS-03 | launched | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpoint.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/PendingQuotaKeys.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceKeyResolver.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/detection/SourceLedger.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeys.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpointTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/detection/SourceLedgerTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/SourceKeysTest.java; doc/LogMessages.adoc; doc/adr/0076-One_per-source_detection_substrate_counts_pre-trust_rejections_and_answers_a_tripped_source.adoc; doc/configuration/security-and-edge-hardening.adoc; doc/security-threat-model.adoc; doc/user/bff-cookie.adoc; doc/user/bff-session.adoc; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ | plan-marshall |
