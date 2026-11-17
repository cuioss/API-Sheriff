<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-2`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-2`, and `git add` the result. -->

# Queue view: Security hardening - tier 2 (follow-ups)

## START HERE

**Resume anchor**: PLAN-SEC2-01 (#623) and PLAN-SEC2-03 (#626) shipped and analyzed. PLAN-SEC2-02 running (operator-confirmed 2026-11-16); when its landing arrives run analyze, then land. Open defects (incomplete landings 01, 03) and watches in epic.md. Epic closes when 02 ships and the private notes hold no planned item.
**Phase**: orchestrating
**Running**:
- PLAN-SEC2-02 (WS-02)
**Queue** (staged, in order):
- (empty)
- PLAN-SEC2-01 (WS-01) — plan=sec2-01-doc-honesty-and-config-guards — PR 623 — landing=landings/PLAN-SEC2-01.md — status: shipped
- PLAN-SEC2-03 (WS-03) — plan=sec2-03-bff-logout-and-source-keys — PR 626 — landing=landings/PLAN-SEC2-03.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC2-02 | WS-02 | running | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/EgressClients.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamFailureMapper.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuardsTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamPoolSaturationTest.java; doc/LogMessages.adoc; doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc; doc/security-threat-model.adoc; doc/user/edge-security.adoc | plan-marshall |
