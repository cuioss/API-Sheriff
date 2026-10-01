<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug kidicap-gateway-requirements`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug kidicap-gateway-requirements`, and `git add` the result. -->

# Queue view: KIDICAP Gateway requirements on API Sheriff (AS-1..AS-14)

## START HERE

**Resume anchor**: PLAN-23 (session-route-scope-parity) emitted + launched 2026-09-25, awaiting operator-confirmed start - the downstream's highest-priority defect, re-grounded at 1fa648da and widened with PLAN-19's session_fallback branch. PLAN-19 shipped #356/1fa648da, drained 8 msgs. Second slot EMPTY: PLAN-20/21 collide with PLAN-23. After PLAN-23 lands: re-ground PLAN-20 (deliverables 1-3 should narrow; it also owns the ADR-0053 renumber) then PLAN-21. OWED BY OPERATOR: /marshall-steward (executor 0.1.1719 vs workflow 0.1.1757 - this is now disabling the ADR gate on every plan); re-run Integration Tests 35917940826 (f6067588); Maven Builds for 3e3addc/69b322b/c1b09c7/f6067588/2f4254d3/1fa648da; org-level PR-Agent App fix; accept ADR-0050/0053/0054/0055
**Phase**: orchestrating
**Running**:
- PLAN-23 (WS-04) — plan=plan-23-session-route-scope-parity
**Queue** (staged, in order):
1. PLAN-20 (WS-04)
2. PLAN-21 (WS-04)
- PLAN-13 (WS-01) — plan=plan-13-defect-fixes — PR #334 — landing=landings/PLAN-13.md — status: shipped
- PLAN-14 (WS-04) — plan=plan-14-session-and-scopes — PR #337 — landing=landings/PLAN-14.md — status: shipped
- PLAN-15 (WS-02) — plan=routing-and-response-headers — PR #320 — landing=landings/PLAN-15.md — status: shipped
- PLAN-16 (WS-03) — plan=plan-16-application-portal — PR #343 — landing=landings/PLAN-16.md — status: shipped
- PLAN-17 (WS-01) — plan=jwks-egress-allowlist — PR #345 — landing=landings/PLAN-17.md — status: shipped
- PLAN-18 (WS-02) — plan=route-header-matcher-defects — PR #346 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-04) — plan=plan-19-session-fallback-on-bearer — PR #356 — landing=landings/PLAN-19.md — status: shipped
- PLAN-22 (WS-02) — plan=websocket-early-frame-race — PR #350 — landing=landings/PLAN-22.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| 1 | PLAN-20 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ReturnTargetScopes.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionRecord.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; doc/user/bff-session.adoc; integration-tests/src/main/docker/; integration-tests/src/test/java/ |
| 2 | PLAN-21 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/BindingCookieCodec.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/login/; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; doc/user/bff-session.adoc; integration-tests/src/test/java/ |
| 3 | PLAN-23 | WS-04 | running | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthBranch.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionRecord.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; doc/user/bff-session.adoc; integration-tests/src/main/docker/sheriff-config/; integration-tests/src/main/docker/sheriff-config/endpoints/bff-scoped.yaml; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionScopeParityIT.java |
