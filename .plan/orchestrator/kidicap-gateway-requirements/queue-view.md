<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug kidicap-gateway-requirements`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug kidicap-gateway-requirements`, and `git add` the result. -->

# Queue view: KIDICAP Gateway requirements on API Sheriff (AS-1..AS-14)

## START HERE

**Resume anchor**: PLAN-23 SHIPPED #369/e445e299 (downstream's highest-priority defect closed; ADR-0057 supersedes ADR-0049's session half). Nothing in flight. Queue: PLAN-24 (jwks-unknown-kid-refresh, WS-01, downstream HIGH), PLAN-25 (integration-test-evidence, WS-02), PLAN-26 (logout-truth-up, WS-04), PLAN-20, PLAN-21. PAIRING NOW POSSIBLE: PLAN-24 and PLAN-25 are each disjoint from WS-04, so pair one WS-04 plan with one of them - never two WS-04 plans. PLAN-20 deliverable 9 RETIRED (ADR-0053 fixed by #367). DO NOT relocate the ledger while a plan is in flight - that orphaned PLAN-23's outbox. OWED BY OPERATOR: PR for the ledger branch chore/orchestrator-ledger (commit 5fe7319f pushed, no PR); delete the parked pre-move copy; org-level PR-Agent App fix; re-run Integration Tests 35917940826 (f6067588); Maven Builds for 3e3addc/69b322b/c1b09c7/f6067588/2f4254d3 (1fa648da and e445e299 reported green)
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-20 (WS-04)
2. PLAN-21 (WS-04)
3. PLAN-24 (WS-01)
4. PLAN-25 (WS-02)
5. PLAN-26 (WS-04)
- PLAN-13 (WS-01) — plan=plan-13-defect-fixes — PR #334 — landing=landings/PLAN-13.md — status: shipped
- PLAN-14 (WS-04) — plan=plan-14-session-and-scopes — PR #337 — landing=landings/PLAN-14.md — status: shipped
- PLAN-15 (WS-02) — plan=routing-and-response-headers — PR #320 — landing=landings/PLAN-15.md — status: shipped
- PLAN-16 (WS-03) — plan=plan-16-application-portal — PR #343 — landing=landings/PLAN-16.md — status: shipped
- PLAN-17 (WS-01) — plan=jwks-egress-allowlist — PR #345 — landing=landings/PLAN-17.md — status: shipped
- PLAN-18 (WS-02) — plan=route-header-matcher-defects — PR #346 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-04) — plan=plan-19-session-fallback-on-bearer — PR #356 — landing=landings/PLAN-19.md — status: shipped
- PLAN-22 (WS-02) — plan=websocket-early-frame-race — PR #350 — landing=landings/PLAN-22.md — status: shipped
- PLAN-23 (WS-04) — plan=plan-23-session-route-scope-parity — PR #369 — landing=landings/PLAN-23.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| 1 | PLAN-20 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ReturnTargetScopes.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionRecord.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; doc/user/bff-session.adoc; integration-tests/src/main/docker/; integration-tests/src/test/java/ |
| 2 | PLAN-21 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/BindingCookieCodec.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/login/; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; doc/user/bff-session.adoc; integration-tests/src/test/java/ |
| 3 | PLAN-24 | WS-01 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; integration-tests/src/main/docker/sheriff-config/; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/JwksKeyRotationIT.java |
| 4 | PLAN-25 | WS-02 | staged | benchmarks/src/main/resources/k6-scripts/; doc/quality-report/; integration-tests/src/main/docker/; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ |
| 5 | PLAN-26 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/; doc/adr/; doc/configuration.adoc; doc/security-threat-model.adoc; doc/user/bff-cookie.adoc; doc/user/bff-session.adoc |
