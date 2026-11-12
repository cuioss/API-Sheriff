<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug security-hardening-tier-1`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug security-hardening-tier-1`, and `git add` the result. -->

# Queue view: Security hardening - tier 1

## START HERE

**Resume anchor**: 2026-11-12: PLAN-SEC-01..05 shipped (#582, #581, #590, #589, #600). PLAN-SEC-06 (tier-2-quick-wins, WS-04, 6 deliverables) RUNNING since 2026-11-12 (start confirmed by the operator). Its doc targets were touched by PLAN-SEC-05 (8 paths) and are re-read at its outline. Open with the operator: PLAN-SEC-05 layer-3 minor points (local notes) — fix plan only on the operator's word; M3 part a and R3 now stageable. When PLAN-SEC-06's PR merges run /plan-marshall:plan-orchestrator analyze slug=security-hardening-tier-1. The epic closes when PLAN-SEC-06 has shipped.
**Phase**: orchestrating
**Running**:
- PLAN-SEC-06 (WS-04)
**Queue** (staged, in order):
- (empty)
- PLAN-SEC-01 (WS-01) — plan=plan-sec-01-edge-transport-hardening — PR 582 — landing=landings/PLAN-SEC-01.md — status: shipped
- PLAN-SEC-03 (WS-03) — plan=plan-sec-03-pending-authorization-store-fairness — PR 581 — landing=landings/PLAN-SEC-03.md — status: shipped
- PLAN-SEC-02 (WS-02) — plan=sec-02-upstream-attribution-forward-headers — PR 590 — landing=landings/PLAN-SEC-02.md — status: shipped
- PLAN-SEC-04 (WS-01) — plan=plan-sec-04-edge-transport-bounds-follow-up — PR 589 — landing=landings/PLAN-SEC-04.md — status: shipped
- PLAN-SEC-05 (WS-02) — plan=plan-sec-05-grpc-request-stream-attribution — PR 600 — landing=landings/PLAN-SEC-05.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-SEC-06 | WS-04 | running | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ForwardedConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ManagementConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/CookieRules.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ProtocolRules.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeAdmission.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java; api-sheriff/src/main/resources/application.properties; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/pipeline/IdpFrontingCanonicalPathTest.java; api-sheriff/src/test/resources/; deployment/compose-sample/docker-compose.auth-fronted.yml; deployment/compose-sample/docker/keycloak/sample-realm.json; deployment/compose-sample/docker/sheriff-config-auth-fronted/endpoints/idp.yaml; deployment/compose-sample/scripts/check-bff-login.sh; deployment/compose-sample/scripts/start-sample.sh; doc/LogMessages.adoc; doc/adr/; doc/adr/0003-forwarded-headers.adoc; doc/adr/0008-data-plane-transport.adoc; doc/adr/0043-Compress-then-encrypt_in_the_sealed_session_cookie_and_the_CRIME_BREACH_discriminator.adoc; doc/architecture/routing-and-forwarding.adoc; doc/architecture/tls-and-inbound-security.adoc; doc/configuration.adoc; doc/configuration/routing-and-forwarding.adoc; doc/configuration/security-and-edge-hardening.adoc; doc/configuration/tls-and-egress.adoc; doc/security-threat-model.adoc; doc/user/bff-session.adoc; doc/user/compose-sample.adoc; doc/user/edge-security.adoc; doc/user/tls-edge.adoc; doc/variants/02-bff-session.adoc | plan-marshall |
