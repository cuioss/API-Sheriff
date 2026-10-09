<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug api-sheriff-0-2-0`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug api-sheriff-0-2-0`, and `git add` the result. -->

# Queue view: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

## START HERE

**Resume anchor**: === 2026-10-09 STATE at 386f3f74, after analyze + drain + cleanup. This anchor states current state only; the older dated paragraphs were removed on this pass and are in the ledger's git history. ===

SHIPPED (11): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19. RUNNING: none. STAGED (11): V02-04, -05, -06, -07, -09, -11, -12, -15, -20, -21, -22. All three slots are free; nothing runs alone any more.

INBOX: empty (108 archived). The V02-01 drain: 19 messages, 10 lessons promoted (2026-10-09-13-001..010), 7 folded, 1 discarded, landing reconciled (landings/PLAN-V02-01.md).

CLEANUP at 386f3f74: all 11 staged specs re-grounded, 69 claims: 55 corroborated, 9 contradicted and re-scoped in place, 5 unverifiable; 0 blocking. Every staged spec's surface is declarative. Restart verdict: ready.

READ BEFORE EMITTING:
- Sequencing still binding: V02-12 -> V02-09 (oidc block / BffRuntimeProducer); V02-06 -> V02-07 (substrate); V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-06, -07, -09, -11, -12); V02-22 never with V02-12 (demo client).
- V02-01's real footprint reached edge/, pipeline/, events/, config/validation/ and quarkus/. Specs touching those re-read them at outline.
- ADR-0062 (platform-first) is in force: any new hand-rolled component needs a recorded reason (written into V02-06, -07, -09).
- V02-22 shrank to three deliverables (readiness gate and the README claim were already fixed). V02-04 now treats ADR-0005 as superseded. V02-20 must keep the activity-MAC key derivation. V02-12's reserved-path move covers seven BFF paths plus JWKS.
- Next free ADR ordinal: 0063.
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at outline.

OPEN: Open Defect 17 (post-merge benchmark red on every merge since #408: pending-login-flood gets 404 on /auth/login; no owner). Open Defect 12 (RouteRuntimeAssembler dead allocation; no owner since V02-01 declined it). Open Defect 16 (upload GOAWAY, V02-06 D8; not observable while 17 is red). Open Defect 14 (#201, V02-11). Watch: V02-01 D7 residue. A /marshall-steward run is owed (three routes). The Integration Tests push run for 386f3f74 was still in progress at this write; re-read it.

OUT OF QUEUE: test-duration-reduction-claude-brief.md (direct Claude Code brief). Its analysis landed as #414; its Phase 4 is no longer blocked by V02-01.

NEXT ACTION: /plan-orchestrator next slug=api-sheriff-0-2-0 to fill the three slots.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-V02-04 (WS-02)
2. PLAN-V02-05 (WS-03)
3. PLAN-V02-06 (WS-03)
4. PLAN-V02-07 (WS-03)
5. PLAN-V02-09 (WS-05)
6. PLAN-V02-11 (WS-05)
7. PLAN-V02-12 (WS-04)
8. PLAN-V02-15 (WS-02)
9. PLAN-V02-20 (WS-04)
10. PLAN-V02-21 (WS-05)
11. PLAN-V02-22 (WS-02)
- PLAN-V02-01 (WS-01) — plan=plan-v02-01-adr-0005-reversal-quarkus-adoption — PR 409 — landing=landings/PLAN-V02-01.md — status: shipped
- PLAN-V02-02 (WS-01) — plan=plan-v02-02-java-idiom-sweep — PR 198 — landing=landings/PLAN-V02-02.md — status: shipped
- PLAN-V02-03 (WS-02) — plan=plan-v02-03-documentation-restructure — PR 197 — landing=landings/PLAN-V02-03.md — status: shipped
- PLAN-V02-08 (WS-04) — plan=plan-v02-08-fapi-2-0-conformance — PR 377 — landing=landings/PLAN-V02-08.md — status: shipped
- PLAN-V02-10 (WS-05) — plan=plan-v02-10-per-client-tls-trust — PR 382 — landing=landings/PLAN-V02-10.md — status: shipped
- PLAN-V02-13 (WS-03) — plan=plan-v02-13-terminal-rejection-contract — PR 383 — landing=landings/PLAN-V02-13.md — status: shipped
- PLAN-V02-14 (WS-01) — plan=plan-v02-14-offline-config-validation — PR 387 — landing=landings/PLAN-V02-14.md — status: shipped
- PLAN-V02-16 (WS-01) — plan=plan-v02-16-image-metadata-fidelity — PR 199 — landing=landings/PLAN-V02-16.md — status: shipped
- PLAN-V02-17 (WS-02) — plan=plan-v02-17-lessons-into-source — PR 200 — landing=landings/PLAN-V02-17.md — status: shipped
- PLAN-V02-18 (WS-02) — plan=retire-doc-plan-survivors — PR 368 — landing=landings/PLAN-V02-18.md — status: shipped
- PLAN-V02-19 (WS-01) — plan=plan-v02-19-orphaned-guard-hygiene — PR 367 — landing=landings/PLAN-V02-19.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| 1 | PLAN-V02-04 | WS-02 | staged | .claude/skills/release/SKILL.md; .github/workflows/**; CLAUDE.md; README.adoc; api-sheriff/src/**; benchmarks/**; doc/; doc/adr/**; integration-tests/**; pom.xml |
| 2 | PLAN-V02-05 | WS-03 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityHeadersConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/ConnectionHeaders.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java; api-sheriff/src/main/resources/application.properties; api-sheriff/src/test/**; doc/adr/; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/user/ |
| 3 | PLAN-V02-06 | WS-03 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/EdgeHardeningOptions.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GrpcDispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/GatewayEventCounter.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/PipelineRequest.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/RouteSelectionStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/ErrorPageClassifier.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java; api-sheriff/src/test/**; doc/LogMessages.adoc; doc/adr/; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/security-threat-model.adoc; doc/user/ |
| 4 | PLAN-V02-07 | WS-03 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/GatewayEventCounter.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java; doc/LogMessages.adoc |
| 5 | PLAN-V02-09 | WS-05 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java; api-sheriff/src/main/resources/application.properties; api-sheriff/src/test/**; doc/; pom.xml |
| 6 | PLAN-V02-11 | WS-05 | staged | .github/workflows/benchmark.yml; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/LogMessagesCatalogueTest.java; benchmarks/**; benchmarks/pom.xml; benchmarks/scripts/benchmark-manifest.py; benchmarks/src/main/java/**; doc/adr/; doc/development/; integration-tests/** |
| 7 | PLAN-V02-12 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java; demo-client/README.adoc; demo-client/doc/integration-sample.adoc; demo-client/doc/playwright-suite.adoc; demo-client/playwright.config.js; demo-client/src/main/resources/spa/app.js; demo-client/tests/*.spec.js; demo-client/utils/constants.js; demo-client/utils/keycloak-login.js; deployment/compose-sample/**; deployment/compose-sample/docker/keycloak/sample-realm.json; doc/adr/; doc/configuration.adoc; doc/development/integration-test-topology.adoc; doc/user/bff-cookie.adoc; doc/user/bff-session.adoc; doc/variants/02-bff-session.adoc; doc/variants/03-bff-cookie.adoc; integration-tests/docker-compose.yml; integration-tests/src/main/docker/keycloak/*.json; integration-tests/src/main/docker/sheriff-config*/gateway.yaml |
| 8 | PLAN-V02-15 | WS-02 | staged | .github/workflows/**; deployment/compose-sample/docker-compose.plain-http.yml; deployment/compose-sample/docker-compose.yml; deployment/compose-sample/docker/keycloak/sample-realm.json; deployment/compose-sample/docker/nginx/tls-terminator.conf; deployment/compose-sample/docker/sheriff-config/endpoints/**; deployment/compose-sample/docker/sheriff-config/gateway.yaml; doc/user/compose-sample.adoc |
| 9 | PLAN-V02-20 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieKeyMaterial.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SessionActivityCookieCodec.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/**; doc/LogMessages.adoc; doc/adr/; doc/configuration.adoc; doc/development/bff-cookie.adoc; doc/development/test-corpus-integrity.adoc; doc/fapi_next_steps.adoc; doc/quality-report/code-correctness.adoc; doc/quality-report/security-posture.adoc; doc/resources/diagrams/tls-key-material.svg; doc/security-threat-model.adoc; doc/user/bff-cookie.adoc; doc/user/environment-variable-overrides.adoc; doc/user/tls-scenarios.adoc; doc/variants/03-bff-cookie.adoc; integration-tests/docker-compose.yml; integration-tests/src/main/docker/sheriff-config-cookie-refresh/gateway.yaml; integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffCookieActivationWiringTest.java |
| 10 | PLAN-V02-21 | WS-05 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieSessionBinding.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/cookie/; demo-client/scripts/start-dev-environment.sh; deployment/compose-sample/scripts/start-sample.sh; doc/LogMessages.adoc; doc/adr/0031-Host-side_readiness_gates_derive_the_probe_URL_from_the_resolved_Compose_model_and_assert_readiness.adoc; integration-tests/docker-compose.yml; integration-tests/pom.xml; integration-tests/scripts/lib-docker-compose.sh; integration-tests/scripts/start-integration-container.sh; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ImageMetadataIT.java; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ImageMetadataJfrIT.java; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ItProfileConfigBindingWiringTest.java |
| 11 | PLAN-V02-22 | WS-02 | staged | demo-client/doc/integration-sample.adoc; demo-client/doc/playwright-suite.adoc; demo-client/playwright.config.js; demo-client/scripts/start-dev-environment.sh; demo-client/src/main/resources/spa/app.js; demo-client/src/main/resources/spa/index.html; demo-client/tests/*.spec.js; doc/development/release-process.adoc; doc/variants/01-base-gateway.adoc |
