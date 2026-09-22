# Epic: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

slug: api-sheriff-0-2-0

> Ledger document for one epic under `.plan/local/orchestrator/api-sheriff-0-2-0/`. The layout and
> authority contract live in the central standard — see
> `persona-marshall-orchestrator/standards/orchestration-model.md`. `status.json` is the
> machine authority; any statement here that conflicts with it is stale prose. This document
> describes the epic's **current state** only — no history is retained.

## Vision

Take what 0.1.0 shipped and make it **honest, current and defensible** — without adding capability.
"Done" means: the framework-agnostic architecture question is settled rather than worked around; the
code reads as current Java; the documentation and ADR corpus say what is true, once; the
reconnaissance surface is closed; the BFF meets the FAPI 2.0 Security Profile; and the
`token-sheriff` integration runs through one trust mechanism rather than a process-global override.

**Scope**: consolidation and hardening only. Every net-new capability — the deception layer, the
inventory endpoint, the Helm chart — belongs to `api-sheriff-0-3-0`. The one deliberate exception is
stated in WS-03: the per-client detection substrate is *built* here because its consumer in 0.3.0
would otherwise force a retrofit.

## START HERE

<!-- GENERATED BLOCK — never hand-write or hand-edit this section.
     Regenerate after every queue-touching state change via:
     python3 .plan/execute-script.py plan-marshall:marshall-orchestrator:orchestrator resume-summary --slug api-sheriff-0-2-0
     Paste the returned block verbatim between the markers. -->

<!-- BEGIN GENERATED: resume-summary -->
**Resume anchor**: === RESTART-READY. Full cleanup pass 2026-09-22 at af63895 (117 commits / 253 files since 95dd566). NEXT ACTION: emit. ===

STATE: 4 shipped (V02-02 #198, V02-03 #197, V02-16 #199, V02-17 #200) / 0 running / 0 launched / 14 staged. All three parallel slots free. Inbox empty, 25 archived. Tree clean. EPIC MIGRATED to the git-tracked .plan/orchestrator/api-sheriff-0-2-0/ address (was .plan/local/orchestrator/, PR pending). The project's plan-marshall executor was found badly stale (embedded version 0.1.1292, ~450 releases behind) and a full /marshall-steward upgrade landed (PR #339, merged) before any of this pass could run.

=== 2026-09-22, SAME DAY, POST-CLEANUP ADDITIONS === Operator asked whether doc/plan/ could be absorbed. Finding: 01-base-implementation.adoc is pure historical record; 09-release-readiness.adoc's six items are mostly delivered/superseded elsewhere (operator guide -> doc/user/ 13 pages; ADR-0005 checkpoint -> superseded by V02-01's stronger reversal decision; benchmark consolidation -> delivered, 12-execution CI lane + APISIX comparison lane; native/container hardening -> delivered, SBOM/Trivy/Cosign in release.yml), except three doc/security-threat-model.adoc GAP rows (gw-02, gw-08, gw-09) untracked anywhere, and the 1.0.0-cut/pre-1.0-rule-flip milestone itself, not yet done. GW-02 and gw-09 folded into PLAN-V02-13 as new D6/D7 (clean fit: V02-13 already owns the taxonomy+render contract both need). gw-08 folded into PLAN-V02-06 as new D8 over operator objection-noted weak fit (re-scope escape hatch recorded in the spec); V02-06 is now 8 deliverables, past the ~6 guard a second time, rationale recorded. PLAN-V02-18 (retire-doc-plan-survivors, WS-02) staged: deletes the three doc/plan/ survivors, repairs the sole outside reference (doc/quality-report/documentation.adoc), and requires preserving the 1.0-cut milestone fact somewhere durable in doc/ or CLAUDE.md before deletion. This explicitly reverses PLAN-V02-03's landing ruling that kept both files open (01 as the only published record of its work, 09 until the actual 1.0 cut) -- operator reviewed both reasons and instructed retirement anyway. Verbatim content archived by the orchestrator at archive/doc-plan-{01-base-implementation,09-release-readiness,README}.adoc before staging, so nothing is lost even though the repository copies are slated for deletion.

=== HEAD ADVANCED MID-SESSION, SAME DAY: af63895 -> 69b322b via PR #343 ("feat(portal): application catalog, overview page, HTML error pages", 148 files) === Caught by a pre-commit contradiction check before landing this session's edits, not by a routine re-grounding pass. Consequences for PLAN-V02-13, all recorded in its own Re-Grounded (3): D2/D3 (content-negotiated terminal rejections) are SUBSTANTIALLY DISCHARGED by a new, independent portal.ErrorPageClassifier/GatewayEdgeRoute.answeredWithErrorPage() mechanism -- NO_ROUTE_MATCHED (the plan's own flagship misdiagnosis story) is already HTML-negotiated; D2 is narrowed to a one-line reclassification decision for METHOD_NOT_ALLOWED/PASSTHROUGH_HOST_SMUGGLED, not a build. D1's taxonomy work stands independent of that narrative and is unaffected. D7 (this same day's GW-09 fold) was WRONG AS AUTHORED -- a fold-time miss, not caused by this PR: pipeline/OriginValidationStage already implements GW-09 generically and predates af63895; corrected to the real, narrower gap (ConfigValidator's allowlist requirement is bearer-only, should extend to Require.SESSION). D6 (GW-02) and PLAN-V02-06's D8 (GW-08) are unaffected by this PR (D8 only provisionally, time-boxed check). ADR corpus is now 50 (was 49) -- PR #343 landed escape-bypass_constructs_are_refused_at_boot. Lesson for future folds: verify threat-model-derived deliverables against the actual pipeline/config code before staging, not against the threat-model prose alone -- D7's error and this PR's discovery are two independent instances of the same failure mode landing in one session.

=== ALL 13 STAGED SPECS RE-GROUNDED AGAINST af63895, EVERY CLAIM VERDICT PERSISTED. 46 claims scanned, 0 blocking. Read each spec's own Re-Grounded/Claim-Labels sections for full detail; this is the headline-only index. ===

=== TWO PREMISE INVERSIONS — READ BEFORE TOUCHING V02-08 OR V02-09 === token-sheriff is now pinned 0.9.6 (was 0.9.4). (1) V02-08 (fapi-2-0-conformance): the route calculus FLIPPED. mTLS client-auth is now FORECLOSED (TokenSheriff PR #731 made MtlsClientAuth throw unconditionally at construction — "alpha", not selectable); DPoP is now FULLY OPEN (PR #640 fix for #618 is released in 0.9.5/0.9.6 — RS256/384/512, PS256/384, ES256, EdDSA all admitted). D1/D3/D4/Split-Guard all assumed the OLD calculus and need re-derivation, not re-verification. (2) V02-09 (token-sheriff-integration-fidelity): BOTH named upstream blockers are closed-and-released (#641 via PR#645, #617 via PR#642). D6 (moved in from V02-10) is ALREADY LANDED — BffRuntimeProducer.java:522 already wires the egress SSLContext (PR #306, unrelated). D3's readiness half is ALREADY LANDED (GatewayReadinessCheck reads live IssuerKeySetStatus, PR #334). Open Defect (13) applyJwks is GONE (method removed by PR #334's rewrite). Re-scope D1-D5 against "adopt upstream fix, delete exclusion" as the live default, not a contingency. V02-10's own re-grounding independently confirmed the same D6 finding.

=== V02-15 (bff-compose-sample): compose-sample/ file count corrected to 14 (was 10, then 12) — scripts/wait-for-ready.sh no longer exists (#230 folded it into start-sample.sh). D1 may already be partially satisfied — sample-realm.json already ships a fully-formed confidential client. ===

=== ADR CORPUS GREW AGAIN: 49 records / 10,543 lines (was 37/6,905), 29 Proposed (was 21). Next free number is 0050, not 0038. V02-04's audit scope is bigger than its own text says — re-derive at outline, do not trust any number in any spec literally (this hit V02-04, V02-08, V02-09, V02-11, V02-12 alike). ===

=== LINE-NUMBER DRIFT IS PERVASIVE AND EXPECTED — every spec's re-grounding found call-site/anchor line numbers drifted (files grew from six weeks of shipped work: BFF, WebSocket, gRPC, asset routes, CORS, per-route anchor wholesale-replace). Re-anchor by content, not by line, at outline — this is now the norm, not the exception, across all 13 specs. doc/user/ has grown from "ten pages" to 13; three plans (V02-08, V02-12, V02-14, V02-15) cite the stale count. ===

=== SURFACE-COMPLETENESS FINDINGS (Expected Surface understated) — TWO ALREADY APPLIED, REST NOTED HERE FOR OUTLINE TO PICK UP === Applied: V02-09 gained BffRuntimeProducer.java/SheriffMetrics.java; V02-05 gained http/ConnectionHeaders.java (the actual shared RESPONSE_STRIP policy site) and doc/architecture.adoc. NOT yet applied, verify at outline: V02-06 — a fifth pre-auth 404 event (PASSTHROUGH_HOST_SMUGGLED) the uniform-404 scope should probably fold in alongside NO_ROUTE_MATCHED. V02-07 — GatewayEdgeRoute.java itself (the WARN dispatch site) plus a second WARN case (PASSTHROUGH_HOST_SMUGGLED) alongside SECURITY_FILTER_VIOLATION. V02-10 — the compose tree's 13th override site at NoCertificatePlainHttpOptInIT.java:745-747, outside docker-compose.yml entirely (already documented in V02-10's own Re-Grounded (3)). V02-12 — doc/user/anchors.adoc, doc/user/endpoint-routes.adoc, and re-count of gateway.yaml overlay directories (now 9, not 5). V02-13 — the two existing test files (GatewayEdgeRouteTest.java, EventTypeTest.java) that D5's test obligation should target.

=== REFUTATIONS THAT REMOVE WORK — CONFIRMED AGAIN, STILL DO NOT RE-ATTEMPT === V02-12's topology-SVG obligation is STILL discharged (now "variant instances (11)", explicitly "closed, not deferred" in the topology doc — gap widened from (6) since 2026-08-08, never re-opens). V02-11's benchmark-lane-not-gating hypothesis reconfirmed (pull_request types:[closed], merged==true guard). V02-01's synchronized-HashMap premise stays refuted (PLAN-49).

=== STILL OPEN, NOT PLAN WORK === Open Defect (8) needs an operator live-gate check. Issue #174 (GatewayReadinessCheck false issuer_reachability) has its underlying bug fixed (PR #334) but the GitHub issue itself is still OPEN — no active discovery probe was added, only the false claim stopped; V02-09 should close the loop or say why not. ADR-0027 still carries no reference to #641/#617 — the "#641 goes in ADR-0027" obligation from the 2026-08-08 pass is recorded as an instruction in V02-09, not yet enacted.

=== WHAT next MUST HONOUR — SEQUENCING UNCHANGED FROM 2026-08-09, RE-VERIFIED === Seven chains hold: oidc/BffRuntimeProducer (V02-08 -> V02-12 -> V02-09), rejection taxonomy (V02-13 -> V02-06 -> V02-07, both still unstarted, V02-13's ROUTING category does not exist yet on main), V02-01 RUNS ALONE. FREEST CANDIDATES: V02-16/-17 (shipped, ignore), V02-14, V02-05, V02-10 (now genuinely single-deliverable). V02-04's scope grew (bigger corpus) but is still unblocked. Prefer a doc-only or build-light plan for slots 2/3 alongside a Docker-IT-heavy one — contention-driven IT flakes are still a live, documented repo property.

*** THIS ANCHOR IS DENSE BY DESIGN, NOTHING WAS DISCARDED. *** Every finding above has its full evidence trail in the owning spec's own Re-Grounded/Claim-Labels section (verdict field, `corpus verdicts --slug api-sheriff-0-2-0`) or in epic.md's own sections below. Read epic.md, THEN the spec. Do not re-inflate this field further without a reason.
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 25 archived
**Queue** (staged, in order):
1. PLAN-V02-01 (WS-01)
2. PLAN-V02-04 (WS-02)
3. PLAN-V02-05 (WS-03)
4. PLAN-V02-06 (WS-03)
5. PLAN-V02-07 (WS-03)
6. PLAN-V02-08 (WS-04)
7. PLAN-V02-09 (WS-05)
8. PLAN-V02-10 (WS-05)
9. PLAN-V02-11 (WS-05)
10. PLAN-V02-12 (WS-04)
11. PLAN-V02-13 (WS-03)
12. PLAN-V02-14 (WS-01)
13. PLAN-V02-15 (WS-02)
14. PLAN-V02-18 (WS-02)
- PLAN-V02-02 (WS-01) — plan=plan-v02-02-java-idiom-sweep — PR 198 — landing=landings/PLAN-V02-02.md — status: shipped
- PLAN-V02-03 (WS-02) — plan=plan-v02-03-documentation-restructure — PR 197 — landing=landings/PLAN-V02-03.md — status: shipped
- PLAN-V02-16 (WS-01) — plan=plan-v02-16-image-metadata-fidelity — PR 199 — landing=landings/PLAN-V02-16.md — status: shipped
- PLAN-V02-17 (WS-02) — plan=plan-v02-17-lessons-into-source — PR 200 — landing=landings/PLAN-V02-17.md — status: shipped
<!-- END GENERATED: resume-summary -->

## Ordered Queue

<!-- GENERATED BLOCK — never hand-write or hand-edit this section.
     Regenerate after every queue-touching state change via:
     python3 .plan/execute-script.py plan-marshall:plan-orchestrator:orchestrator resume-summary --slug api-sheriff-0-2-0
     Paste the returned ordered_queue block verbatim between the markers. -->

<!-- BEGIN GENERATED: ordered-queue -->
| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| 1 | PLAN-V02-01 | WS-01 | staged | api-sheriff/pom.xml; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/JsonWriter.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/FrameworkAgnosticArchTest.java; doc/adr/; doc/architecture.adoc |
| 2 | PLAN-V02-04 | WS-02 | staged | api-sheriff/src/**; benchmarks/**; doc/; doc/adr/**; integration-tests/**; pom.xml |
| 3 | PLAN-V02-05 | WS-03 | staged | api-sheriff/src/main/resources/application.properties; api-sheriff/src/test/**; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/user/ |
| 4 | PLAN-V02-06 | WS-03 | staged | api-sheriff/src/test/**; doc/configuration.adoc; doc/development/; doc/security-threat-model.adoc; doc/user/ |
| 5 | PLAN-V02-07 | WS-03 | staged | doc/LogMessages.adoc |
| 6 | PLAN-V02-08 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/JwksTrustProfileResolver.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/config/load/ConfigLoader.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java; doc/configuration.adoc; doc/fapi_next_steps.adoc; doc/fapi_status.adoc; doc/features-analysis.adoc; doc/user/; integration-tests/ |
| 7 | PLAN-V02-09 | WS-05 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java; api-sheriff/src/main/resources/application.properties; doc/ |
| 8 | PLAN-V02-10 | WS-05 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java; doc/user/; integration-tests/docker-compose.yml |
| 9 | PLAN-V02-11 | WS-05 | staged | .github/workflows/benchmark.yml; benchmarks/**; doc/adr/; doc/development/; integration-tests/** |
| 10 | PLAN-V02-12 | WS-04 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java; demo-client/README.adoc; demo-client/doc/integration-sample.adoc; demo-client/doc/playwright-suite.adoc; demo-client/playwright.config.js; demo-client/src/main/resources/spa/app.js; demo-client/tests/*.spec.js; demo-client/utils/constants.js; demo-client/utils/keycloak-login.js; deployment/compose-sample/**; deployment/compose-sample/docker/keycloak/sample-realm.json; doc/adr/; doc/configuration.adoc; doc/development/integration-test-topology.adoc; doc/user/bff-cookie.adoc; doc/user/bff-session.adoc; doc/variants/02-bff-session.adoc; doc/variants/03-bff-cookie.adoc; integration-tests/docker-compose.yml; integration-tests/src/main/docker/keycloak/*.json; integration-tests/src/main/docker/sheriff-config*/gateway.yaml |
| 11 | PLAN-V02-13 | WS-03 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/OriginValidationStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/ErrorPageClassifier.java; doc/adr/00NN-*.adoc; doc/architecture.adoc; doc/security-threat-model.adoc |
| 12 | PLAN-V02-14 | WS-01 | staged | .github/workflows/**; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/**; doc/user/** |
| 13 | PLAN-V02-15 | WS-02 | staged | .github/workflows/**; deployment/compose-sample/docker-compose.yml; deployment/compose-sample/docker/keycloak/sample-realm.json; deployment/compose-sample/docker/sheriff-config/endpoints/**; deployment/compose-sample/docker/sheriff-config/gateway.yaml; doc/user/compose-sample.adoc |
| 14 | PLAN-V02-18 | WS-02 | staged | CLAUDE.md; doc/plan/01-base-implementation.adoc; doc/plan/09-release-readiness.adoc; doc/plan/README.adoc; doc/quality-report/documentation.adoc |
<!-- END GENERATED: ordered-queue -->

### Queue annotations

_None yet — per-row narrative that the generator cannot derive goes here, keyed by plan id._

## Workstreams

| Workstream | Title | Plans |
|---|---|---|
| WS-01 | Architecture and Code Quality | PLAN-V02-01, PLAN-V02-02, PLAN-V02-14, PLAN-V02-16 |
| WS-02 | Documentation and Records | PLAN-V02-03, PLAN-V02-04, PLAN-V02-15, PLAN-V02-17, PLAN-V02-18 |
| WS-03 | Threat Hardening | PLAN-V02-05, PLAN-V02-06, PLAN-V02-07, PLAN-V02-13 |
| WS-04 | OIDC Client Conformance | PLAN-V02-08, PLAN-V02-12 |
| WS-05 | Integration and Test Fidelity | PLAN-V02-09, PLAN-V02-10, PLAN-V02-11 |

**PLAN-V02-12 through PLAN-V02-17 are NOT in the renumbering map below.** They were authored fresh
(V02-12 on 2026-08-04 from an operator-directed analysis; V02-13/14/15 on 2026-08-07 from the
open-issue triage; V02-16/17 on 2026-08-08 from the `api-sheriff-roadmap` close-out) and carry no
`api-sheriff-next` predecessor — so an in-body `PLAN-NN` reference inside them means what it says and
needs no translation.

## Renumbering Map — `api-sheriff-next` → this epic

The `api-sheriff-next` backlog was split by target version on **2026-08-04** and retired. Plan specs
were renumbered; **their in-body `PLAN-NN` references were deliberately NOT rewritten**, because many
point at `api-sheriff-roadmap` plans that keep their own numbers. Resolve any in-body reference
through this map first; if the number is not listed here, it belongs to `api-sheriff-roadmap` or to
`api-sheriff-0-3-0` and is unchanged.

| Was | Now | Plan |
|---|---|---|
| PLAN-38 | **PLAN-V02-01** | ADR-0005 reversal / Quarkus adoption |
| PLAN-39 | **PLAN-V02-02** | Java idiom sweep |
| PLAN-40 | **PLAN-V02-03** | Documentation restructure |
| PLAN-47 | **PLAN-V02-04** | ADR corpus cleanup |
| PLAN-17 | **PLAN-V02-05** | Response hygiene |
| PLAN-18 | **PLAN-V02-06** | Enumeration hardening |
| PLAN-19 | **PLAN-V02-07** | Threat classification |
| PLAN-49 | **PLAN-V02-08** | FAPI 2.0 conformance |
| PLAN-46 | **PLAN-V02-09** | Token-Sheriff integration fidelity |
| PLAN-48 | **PLAN-V02-10** | Per-client TLS trust |
| PLAN-44 | **PLAN-V02-11** | Resource-exhaustion test home |

Moved to `api-sheriff-0-3-0`: PLAN-20 → PLAN-V03-01, PLAN-21 → PLAN-V03-02, PLAN-22 → PLAN-V03-03,
PLAN-41 → PLAN-V03-04.

## Decisions

- **2026-08-04 — the backlog is split by target version.** `api-sheriff-next` was a single
  undifferentiated backlog; it is replaced by this epic and `api-sheriff-0-3-0`, and removed.
- **2026-08-04 — enumeration hardening lands here, not in 0.3.0.** Operator decision. The plan
  closes an existing status-code oracle, which is hardening. The recorded and accepted cost: the
  per-client detection substrate it builds ships a release *ahead* of its principal consumer
  (honeypot, `PLAN-V03-01`). Design the substrate general-purpose here — retrofitting it later is
  the more expensive order.
- **2026-08-04 — FAPI 2.0 conformance lands here.** Operator decision. It closes a gap against a
  security profile in an already-shipping flow, and it carries a breaking `oidc` configuration
  change that is better taken earlier than deferred.
- **2026-08-04 — workstreams re-cut from WS-01.** The old WS-05..WS-09 split across both new epics,
  so carrying the numbers would have left gaps in each and put WS-05 in both.
- **2026-08-08 — `parallelization_scope` = 3, `auto_emit` = true.** Operator decision at `decompose`,
  discharging the opening anchor's obligation (3). Both were previously unset at epic level while
  `.plan/marshal.json` carried `2` / `true`, so the epic was inheriting by accident — which is what
  the anchor warned against. **Carrier note**: `marshal.json` still reads `parallelization_scope: 2`
  and was deliberately **not** changed — `.plan/` config is outside the orchestrator's write boundary
  per Watch (31), and it is the *epic metadata* that `orchestrate.md` Step 4 reads. The `2` is a
  dormant project default nothing in this epic consumes. A `marshall-steward` run owns reconciling it.
- **2026-08-08 — the `PLAN-V02-07` → `PLAN-V02-06` merge is RETIRED**, superseding the 2026-07-27
  ruling and discharging obligation (2). Two facts absent when that ruling was taken changed the
  balance: `PLAN-V02-13` now re-categorises the `EventType`/`EventCategory` taxonomy V02-07 weights
  (so V02-07 must follow it, a dependency the substrate work does not need), and a merged spec would
  carry ~11 deliverables — far past the split guard. **The original intent is preserved by contract
  rather than by merger**: V02-06 gains a new deliverable 7, a written general-purpose substrate
  contract naming V02-07 as its first consumer. The substrate is still designed once. A substrate
  V02-07 must generalise on arrival is V02-06 D7 failing, and is a finding against V02-06.
- **2026-08-08 — the epic is re-grounded at `963e422`**, discharging obligation (1). All 17 specs
  carry a `## Re-Grounded` section that outranks their stale bodies. Per-plan carries now live in the
  specs, per the decompose contract, rather than being duplicated in the resume anchor.
- **2026-08-08 — the lessons corpus was audited and `PLAN-V02-17` re-clustered.** Operator-requested
  read-only audit of all 25 archived lessons (the live `manage-lessons` store is empty — the
  close-out drained it). Six defects found and all six resolved **directly in the spec**, not
  deferred:
  - **A contradiction between two active lessons is resolved toward REVERT.** `2026-07-27-09-001`
    said *keep the formatter's output, commit it as-is*; `2026-08-02-17-001` said *revert the
    unrelated churn wholesale*. Same situation, opposite prescriptions, and the original clustering
    put them in different groups so nobody would have seen the conflict at landing. Resolved toward
    revert on the operator's standing note and `2026-08-08-11-001`'s corroborating aside; `09-001`'s
    counter-argument (the diff reappears) is true and is recorded as the cost of `main` not being at
    the formatter's fixed point.
  - **`2026-08-02-15-003`'s prescription is refuted by shipped code** and is re-scoped to its
    diagnosis. *"Never bind teardown to `post-integration-test`"* is contradicted by
    `demo-client/pom.xml`:144–159 plus a 15-line rationale, the `if: always()` CI teardown at
    `demo-client-e2e.yml`:83, and `playwright-suite.adoc`:269–276. **This is the second refuted
    prescription in the corpus; the plan previously knew about only one.**
  - **`2026-08-02-15-004` is already in repository source** (`start-dev-environment.sh`:31–37, :221)
    → disposition changed to already-covered.
  - **`2026-08-05-10-001` is settled as a discard with evidence** — the unqualified-refspec construct
    it prescribes a fix for no longer exists in the release skill.
  - **`2026-08-02-15-002` was double-dispositioned** (successor ledger *and* named by V02-17).
    Adopted into V02-17 **deliberately**, with the reason recorded: the ledger copy is
    orchestrator-facing, the `CLAUDE.md` copy is implementer-facing and is the parent of D3's
    specific case.
  - **Three lessons (`2026-08-07-18-001/-002/-003`) have no body** while claiming one. All are
    correct discards, so no work is owed; recorded in V02-17's Appendix because the reasoning behind
    two durable release-lane rules is now unrecoverable, and the files are in a closed epic's frozen
    tree that no write boundary permits editing.

  **The central re-clustering**: `2026-08-02-17-001` was filed as a false-green lesson and is not one
  — the gate *passes*, it just mutates the tree while exiting 0. Its real siblings are
  `2026-07-27-09-001` and standing rule (4) below. One mechanism, three faces, and the most
  enforceable group in the corpus: a post-gate `git status --porcelain` assertion covers all three.

## Sequencing Constraints

Derived at the 2026-08-08 re-grounding. These bound every `next` emit; disjointness alone is not
sufficient.

| Chain | Order | Why |
|---|---|---|
| oidc / `BffRuntimeProducer` | **V02-08 → V02-12 → V02-09** | All three write the `oidc` block and the BFF assembly point. V02-08 first: it is the larger reshaping and settles the RFC 9207 `iss` constraint V02-12 inherits. |
| rejection taxonomy | **V02-13 → V02-06 → V02-07** | V02-13 re-cuts `EventCategory` and makes `renderProblem` content-negotiating at all five call sites. V02-06's uniform-404 branch edits those same sites; V02-07 weights the resulting taxonomy. |
| sample vs. model | **V02-12 → V02-15** | V02-15 D5 defers the browser-vs-container issuer address to V02-12 rather than letting a sample become the de-facto specification. |
| doc structure | **V02-03 before or after V02-17, chosen deliberately** | Both write `doc/development/**`. Landing prose into documents V02-03 is about to split wastes both. |
| ADR-authoring exclusion | **V02-04 runs against none of** V02-01, V02-06, V02-07, V02-11, V02-12, V02-13 | V02-04's spec forbids concurrency with any ADR-authoring plan. Also excluded: V02-09, whose ADR-0027 re-opening V02-04 is told not to pre-empt. |
| broad Java sweeps | **V02-01 and V02-02 never concurrent** | Both sweep `api-sheriff/src/main/java/**`. |
| runs alone | **V02-01** | Retiring an arch gate mid-flight changes the gate set every concurrent plan is verified against. |
| ~~trust wiring~~ | **EDGE REMOVED 2026-08-09** | V02-10's D1 moved into V02-09 as its D6, so the conditional now lives inside the plan that takes the decision. **V02-10 is reduced to the compose cleanup and is UNBLOCKED.** |

**Freest candidates, revised 2026-08-09 after four landings** (V02-16 and V02-17 have shipped):
**V02-10** (now a single-deliverable compose cleanup, unblocked by the redistribution below),
**V02-14**, **V02-05**, **V02-04**. **V02-13** is the head of the WS-03 chain and unblocks two plans
behind it, so it has the highest downstream value of any staged plan.

**Updated 2026-08-09 after the first two landings.** Three constraints in the table above are now
settled by events rather than pending:

- **`V02-01` is unblocked.** Its only live blocker was concurrency with `V02-02`, which has shipped.
  It is the epic's load-bearing plan and it **runs alone**, so emitting it consumes the whole board.
- **The doc-structure row is discharged.** `V02-03` landed first, so `V02-17` now writes into the
  settled `doc/development/**` layout rather than racing it — the deliberate choice the row demanded
  was made by the landing order.
- **`V02-13`'s `doc/plan/04-request-pipeline.adoc` reference is discharged** — that file is among the
  nine `V02-03` deleted. The directory survives (three deliberate keepers) and **must not be
  resurrected**.

**Second-order cost of a filled slot**: concurrent Docker IT suites contend for local CPU in a repo
with documented contention-driven IT startup flakes, and `main` is merge-queue-gated. Prefer a
doc-only or build-light plan for the second and third slots.

## Open Defects

14. **HIGH, SECURITY — OWNER: `PLAN-V02-11` (adopted 2026-08-09) — `MtlsHandshakeIT` fails 2/3 under `-Pjfr` only: FAIL-OPEN ON HANDSHAKE
    REJECTION.** GitHub issue **[#201](https://github.com/cuioss/API-Sheriff/issues/201)**, verified
    OPEN. Opened 2026-08-09 by `PLAN-V02-16` and **correctly not fixed there** — it sits outside that
    plan's declared boundary, and reporting rather than absorbing is the standing rule. The same tree
    passes 3/3 under `-Pintegration-tests`, so the defect is lane-conditional and was **invisible for
    as long as the `-Pjfr` lane was unrunnable**. **This one must not sit unowned**: it is fail-open
    behaviour on a security control. Candidate homes: `PLAN-V02-11` (which owns the *what are the
    test lanes for* question and now has a concrete instance to answer it with) or `PLAN-V02-10`
    (per-client TLS trust). **HOME DECIDED 2026-08-09: `PLAN-V02-11`.** Its question is *which lane owns which defect class*,
    and #201 is a defect **invisible in one lane and visible in another** — the first
    lane-conditional instance beside its two time-or-volume-conditional ones, and therefore new
    information for that decision rather than extra scope. **It is admissible as D2's demonstrated
    catch. Fixing the fail-open behaviour itself is a security fix belonging to whichever plan owns
    mTLS handshake behaviour — say explicitly which you did.**

10. **CARRIED PAST THIS EPIC — `doc/plan/09-release-readiness.adoc` becomes actionable AT THE 1.0
    CUT.** Opened 2026-08-09 by `PLAN-V02-03` D3. The file is **not superseded**: its defining
    deliverable is the 1.0.0 cut and the flip of the pre-1.0 rules, which has not happened — the
    project is 0.1.1 alpha and `CLAUDE.md` still carries the Pre-1.0 Rules. The outline had listed it
    for deletion; **verification refused, because an expectation is a prior, not a licence.**
    `01-base-implementation.adoc` likewise survives (no counterpart across all 41 landing records,
    enumerated rather than sampled) and needs no action. **This entry must survive into the successor
    epic** — 0.2.0 cannot close it, and closing it here would silently convert "not yet due" into
    "done".
11. **LOW, traceability — the deleted `doc/archive/others/excluded.adoc` traces to nothing live.**
    Opened 2026-08-09 by `PLAN-V02-03` D1. Its content (Apiman, WSO2 and other
    considered-but-not-evaluated gateways) has no live design document behind it —
    `doc/features-analysis.adoc` distils only the six *evaluated* gateways. Zero inbound refs and git
    history preserves it, so the deletion stood, but **`doc/README.adoc`'s "fully adapted" claim
    over-reaches for that one file.** Either narrow the claim or restore the content somewhere live.
12. **MEDIUM, dead allocation — OWNER: `PLAN-V02-01` (conditional) — `RouteRuntimeAssembler` allocates a per-tuple `HttpClient` and a
    resilience `Guard` for `WEBSOCKET` routes that no longer read them.** Opened 2026-08-09 by
    `PLAN-V02-02`, **reported rather than swept** exactly as the standing rule requires. Collapsing
    it is a behavioural change to boot-time allocation plus a nullability-contract change reaching
    `RouteRuntime` and `DispatchStage` — a design task, not a sweep edit, so it needs a home rather
    than a follow-up commit.
13. **LOW — CLOSED AS AN ORPHAN 2026-08-09: FOLDED INTO `PLAN-V02-09`.** `TokenValidatorProducer.applyJwks` qualifies for a switch conversion but sat outside
    `PLAN-V02-02` D5's declared surface and was correctly left alone. **Natural home: `PLAN-V02-09`**,
    which already owns that file. Fold it there rather than carrying it as standalone work.

1. **MEDIUM — upstream stack-identity leak.** `edge/ResponseStage` `isForwardableResponseHeader`
   (:63) filters only hop-by-hop and conditional headers, so an upstream emitting `Server` or
   `X-Powered-By` leaks its stack identity through the gateway. The orchestrator recommended fixing
   it inside 0.1.0; the operator ruled it out of the release track 2026-07-27. **0.1.0 and 0.1.1 both
   ship with this leak.** OWNER: `PLAN-V02-05` D1. *Re-grounded 2026-08-08: still present, and the
   fix must cover **both** relay paths — `relay`:84 and `relayWithTrailers`:118.*
2. **MEDIUM, developer trap.** `demo-client/playwright.config.js` routes three variables through
   `required()` at module scope while `demo-client/pom.xml` supplies them only for the Maven
   execution, so the copy-pasteable `cd demo-client && npm run test` aborts at config load. The
   README was fixed to export them first, but `start-dev-environment.sh`:289 still prints that exact
   command. **Keep the generalizable check**: for any doc offering run-it-directly beside
   run-it-through-the-build, enumerate what the build supplies that the direct path does not —
   environment, working directory, classpath.
3. **MEDIUM — divergent readiness contract.** `demo-client/scripts/start-dev-environment.sh` gates on
   `/q/health/live` with an unmeasured 30-attempt budget while `start-integration-container.sh` gates
   on readiness with a measured one (PLAN-42 D2 proved the live→ready delta is 0.00s across six
   instances under contention). Deliberate scoping at the time and disclosed in-tree, but it is the
   one place the recorded readiness contract is not honoured. Route with (2) — same file.
4. **LOW, not release-gating** — the test-corpus integrity backlog PLAN-43 left as a countable
   residual: 43 files / 140 marker occurrences, enumerated in
   `doc/development/test-corpus-integrity.adoc`. Reported as a count rather than as prose.
5. ~~**MEDIUM, documentation — the BFF secrets missing from the env-var page.**~~ **CLOSED 2026-08-09
   by `PLAN-V02-03` D2 (PR #197, `89a3cfe`).** Verified first-party: `environment-variable-overrides.adoc`
   now carries `SHERIFF_CLIENT_SECRET` and `SHERIFF_SESSION_KEY`, against a previous count of zero
   with a passing control. 0.1.0 and 0.1.1 both shipped with the gap; it is closed for 0.2.0. The
   original entry follows for the audit record.
   <details><summary>original entry</summary>

   **MEDIUM, documentation — RE-VERIFIED LIVE 2026-08-08, DO NOT STRIKE.**
   `doc/user/environment-variable-overrides.adoc` omits `SHERIFF_CLIENT_SECRET` and
   `SHERIFF_SESSION_KEY` although `doc/user/README.adoc` tells operators to read that page before
   assuming an env var exists. The ledger made striking this conditional on PLAN-08B D1 closing it;
   **it did not** — the page carries 18 `SHERIFF_`/`QUARKUS_` entries and neither of those two, with
   a passing control query, while both names appear in five other files. OWNER: `PLAN-V02-03`.
   Preserve the distinction the fix must not lose: fixed runtime keys (`QUARKUS_*`) versus
   author-chosen placeholders — `SHERIFF_CLIENT_SECRET` is a convention shown in examples, not a
   fixed name. Do **not** "fix" the page's deliberate exclusion of issuer identity, audience and
   JWKS location; that is policy, not a deployment-bound value.
6. **STRUCTURAL** — the IT suite cannot detect resource-lifecycle or layer-boundary defect classes.
   Its stated owner was roadmap PLAN-34, **which shipped**, so it arrived here orphaned rather than
   fixed. Not strikeable on sight: it carries independently CI-observed defects and a deferral
   transmitted to a public PR thread. **Re-verify against the landed PLAN-42 work before striking** —
   PLAN-34's D4/D5 left in the 2026-08-02 three-way split and PLAN-42 shipped both, so the gap may be
   narrower than this entry says. Read the landed code, not the split decision. Home: `PLAN-V02-11`.
7. **STRUCTURAL, an open operator decision never taken** — the k6 benchmark scripts are an unguarded
   consumer of gateway posture. `docker-compose.benchmark.yml` overlays the base compose and mounts
   the same `./src/main/docker/sheriff-config`, so the benchmark and the IT suite share one
   `gateway.yaml`. A plan changing gateway behaviour updates the IT suite in lockstep, but nothing
   requires it to update the k6 scripts. **The general shape, and it will recur**: a behavioural
   default flip is the class where "we updated the tests" hides "we did not update the other
   consumers". Enumerate the consumers of a default, not just its tests. Home: `PLAN-V02-11`.
8. **Unverified Sonar claim** — PLAN-06's uncorroborated Sonar claim is still unverified with no
   recovered evidence, and needs an **operator live-gate check** rather than plan work. Carried
   rather than closed because "we could not recover the evidence" is not "the claim was false", and
   closing it would silently convert one into the other.
9. **MEDIUM** — `K6BenchmarkLogMessages` sits outside the catalogue guard that shipped beside it.
   PLAN-08A (#154) added `LogMessagesCatalogueTest`, but it anchors on
   `ApiSheriffLogMessages.class.getProtectionDomain()` and therefore walks `api-sheriff/target/classes`
   **only** — so the benchmarks module's catalogue, including PLAN-46's `K6Benchmark-211`, is not
   machine-protected against a duplicate id. **The generalisable shape: a guard anchored on one
   module's protection domain silently excludes every other module, and its green is
   indistinguishable from coverage.** When adopting it, check whether the fix generalises to every
   module carrying a catalogue rather than special-casing benchmarks. Home: `PLAN-V02-11`.

## Watches

- **INHERITED TOOLING AND PROJECT-CONFIG CONSTRAINTS — not fixable by any plan here, and every plan
  in this epic runs under them.** Carried as one watch rather than five duplicated entries so the
  copies cannot drift; full text and evidence live in the `api-sheriff-roadmap` ledger under the
  numbers given.
  - **(31) HIGH, the dangerous one**: `q_gate_validation: once` routes Q-Gate findings to a review
    gate that `plan_without_asking: true` **disables**, so BLOCKING findings reach task planning
    ungated and nothing reports it. The pairing occurs twice — `phase-3-outline` and `phase-4-plan`.
    On PLAN-27 it let through a hardening block that would not have started and a bucket
    misassignment that would have stripped the verify lane, both caught only by manual operator
    intervention. The two settings are individually reasonable and **jointly unsound**. OWNER: a
    `marshall-steward` run — `.plan/` config is outside every plan's write boundary **and the
    orchestrator's**.
  - **(22) HIGH**: no plan runs the Docker IT suite pre-push, so IT feedback comes only from CI after
    push. `verification_steps` holds quality-gate, module-tests and coverage only, and the root
    resolve fails. **Do not work around it via `per_deliverable_build`**: the config layer accepts
    the entry but the standard forbids the placement and a non-zero exit is a hard STOP, so a
    deliverable touching only `api-sheriff` would halt the plan. Attempted and reverted 2026-07-30.
  - **(25)**: `documentation.skills_by_profile.module_testing` is empty, so a task legitimately
    domained documentation under module_testing resolves the persona floor alone — no doc testing
    standards, no verification recipes — and nothing reports it.
  - **(30)**: the deep-lane component-assessment sink is empty (a two-plan recurrence), so section
    2.2 assessment-coverage is unevaluable on any deep-lane plan; an empty sink is indistinguishable
    from "the pass ran and matched nothing".
  - **(16)+(20) — PARTLY DISCHARGED 2026-08-09, and the correction matters.** The *mechanism* half is
    confirmed by exhausting the surface: `ci checks status` accepts only `--pr-number` / `--head`,
    `--head` resolves as a **branch name** rather than a SHA, `checks wait` takes only
    `--pr-number`, and `checks rerun` / `checks logs` take a `--run-id` that **no verb in the
    abstraction can discover from a commit**. `ci repo` offers only merge-queue and label. The
    by-merge-commit lookup is genuinely unreachable through the abstraction.
    **What was WRONG is the conclusion drawn from it.** "No sanctioned execution path" was read as
    "unverifiable", and the axis then went unchecked across four merge commits. It is verifiable in
    one read-only `gh` call, and on operator instruction it was done: **all four are green** — see
    § Post-Merge Verification. The standing rule is corrected: a landing report records the
    post-merge check as OWED **until it is checked**, not indefinitely, and the check is an
    operator-authorised `gh` read rather than a tooling change.

  All five are delivered upstream as bundle material. **Re-check by executing the mechanism, never by
  reading a version number.**

- **STANDING CLAUSES, still binding**: three-layer docs in the same PR; Sonar zero-findings with red
  a HARD STOP; **named line items survive outlining** (this project has twice had named deliverables
  abstracted away); activate-it-in-an-IT (lesson `2026-07-25-15-001`, which already recurred verbatim
  once); **the plan stops at the merge** and the post-merge check is the orchestrator's; every emit
  carries an explicit `plan_id`; mark a claim OBSERVED only with a read behind it; **a green suite is
  not evidence for a behavioural claim** until its assertions are read.
- **A capability present in a dependency is not a capability of the product.** Earned on the FAPI
  claim: `doc/features-analysis.adoc` asserted a PAR-driven sender-constrained flow because the
  engine shipped `ParClient` and `SenderConstraint` while the gateway wired neither. The
  native-image reflection registration is what made it look evidenced — it lists the dependency's
  whole DTO surface, so grepping for a class name finds a feature that is never invoked. **Settle
  every completion claim at the call site, never at the import.** *Re-confirmed 2026-08-08: the only
  `ParResponse` mentions in the tree are still reflection registrations.*
- **In-body `PLAN-NN` references in these specs were deliberately not rewritten** at the 2026-08-04
  renumbering, because many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve
  through the renumbering map above; a number absent from that map belongs to another epic and is
  unchanged. Each spec's `## Re-Grounded` section now translates the ones that matter to it.
- **A spec's asserted surface absence can be overturned by its own outline**, so a disjointness check
  against a RUNNING plan reads that plan's **current outline**, never its staged spec.
- **A `/marshall-steward` RUN IS NOW OWED BY TWO INDEPENDENT ROUTES.** Added 2026-08-09. Beside
  Watch (31)'s config pairing, `PLAN-V02-03` D3 left the architecture inventory's project description
  reading *"…follow the plans under `doc/plan/`"*, which is stale now that nine of those twelve files
  are deleted. It is **steward-regenerated, not hand-editable**, so the plan flagged it rather than
  patching it — the correct call. Two unrelated findings now point at the same un-run mechanism.
- **A SPEC-ASSERTED COUNT IS A CLAIM, NOT A MEASUREMENT — and the orchestrator is the author who owes
  the re-derivation.** Earned 2026-08-09 on `PLAN-V02-02` D3: a headline occurrence count propagated
  spec → clarified request → outline unchallenged, arriving at implementation as three
  mutually-corroborating statements of one unverified number. **The contested instance is itself the
  second data point** — the plan measured 20, this epic's own spec table sums to 19, and a
  re-measurement at `e343404` returns 19. Two parties each derived a confident total from the same
  document and disagreed. Re-derive every count in a staged spec from its own breakdown before the
  spec is emitted; the check needs no codebase access.
- **EVERY SPEC SENTENCE ASSERTING CURRENT REPOSITORY STATE IS A MEASUREMENT WITH A TIMESTAMP — the
  consuming phase re-establishes it rather than inheriting it.** Earned 2026-08-09 on `PLAN-V02-17`,
  and it completes a three-instance pattern in four landings, all in specs the orchestrator authored:

  | Plan | The defect | Found by |
  |---|---|---|
  | `V02-02` | an occurrence count **asserted, not derived** | measuring during the run |
  | `V02-16` | an Expected Surface **accurate but insufficient** — named the consumer, not the channel | attempting to implement it |
  | `V02-17` | a premise about current repo state **stale and false** | running the gate |

  The V02-17 case is the sharpest because the orchestrator supplied the reasoning: resolving the
  formatter contradiction it asserted that *"bringing `main` to the fixed point is the durable fix
  nobody has done"*. **A full-reactor gate run produced zero rewrites — the repository is already at
  the fixed point.** The verdict (*revert unrelated churn*) survived; the reason did not, and the
  shipped page carries a better one: the fixed point is a property of the tree at a point in time,
  not a guarantee. **The common shape is structural, not carelessness** — a spec asserts facts at
  authoring time and executes later, and nothing in between re-measures unless someone does.
- **AN EXPECTED SURFACE MUST BE CHECKED FOR SUFFICIENCY, NOT ONLY ACCURACY — and this one is the
  orchestrator's own.** Earned 2026-08-09 on `PLAN-V02-16`. Its spec named `Dockerfile.native` +
  `.github/workflows/release.yml` for the revision-label deliverable. Every claim in that surface was
  **accurate**, and the surface was **not implementable**: `release.yml` performs no `docker build`
  and forbids adding one, so the only channel into the image is `integration-tests/docker-compose.yml`'s
  `build.args` — a file the spec never named. **Implementing the literal surface would have shipped an
  empty `revision` label on every published image**, a new falsehood inside the plan whose objective
  was to stop the image lying. At the 2026-08-08 re-grounding the orchestrator read this spec and
  recorded *"every claim holds; no correction is owed"* — verifying the claims made, never asking
  whether the surface reached the deliverable. **The distinction is consumer vs. channel**: naming the
  consumer reads like naming the surface and does not build. Trace the value from producer to
  artifact and name every hop. Sixth instance of the enumerate-every-carrier rule, and the first
  inside a spec the orchestrator wrote itself.
- **A TEST LANE NOBODY CAN RUN IS NOT A PASSING LANE, IT IS AN UNMEASURED ONE.** Earned 2026-08-09:
  the `-Pjfr` lane could never start (root-owned bind mount vs. a uid-1001 container), so its green
  was the **absence of execution**. Repairing it surfaced Open Defect (14) — a fail-open handshake
  rejection — within the same run. Where a lane is broken, prefer fixing it to scoping around it; the
  cost is bounded and the yield is whatever the lane has silently not been checking.
- **ASSERTED ABSENCES NEED A CONTROL QUERY — three known false-negative mechanisms, not one.**
  `architecture search --content` returns a clean `count: 0` over compose/infra YAML and
  `src/main/resources`; `git grep <pattern> origin/main -- 'path/**'` returns a clean zero for every
  query in this tree while the same search without the pathspec returns hits; and — **added
  2026-08-08, found during this epic's own re-grounding** — an **unquoted `--include=*` in `grep -r`
  is shell-expanded into a filename list**, silently dropping real hits. That third one is a
  different mechanism from the other two (shell expansion, not tool behaviour), so knowing the first
  two does not predict it. **Run a control query before trusting any asserted absence.**

## Post-Merge Verification — `deploy-snapshot`

**Performed 2026-08-09 on operator instruction. All four merge commits are GREEN.**

| Merge commit | Plan | Maven Build run | `build / deploy-snapshot` |
|---|---|---|---|
| `89a3cfe` | PLAN-V02-03 (#197) | 31289270907 | **success** |
| `e343404` | PLAN-V02-02 (#198) | 31294044453 | **success** |
| `aeb80c5` | PLAN-V02-16 (#199) | 31327146763 | **success** |
| `95dd566` | PLAN-V02-17 (#200) | 31330642464 | **success** |

Every other job in each run is `success` too, including `build (25)`, `build (26)`, `sonar-build`
and `conclusion`.

**Where the job actually lives, because this is what made it look unreachable.** `deploy-snapshot`
is **not defined in this repository**. `.github/workflows/maven.yml` declares only `build`,
`supply-chain-scan` and `rewrite-report`; its `build` job delegates to the organisation's reusable
workflow (`cuioss/cuioss-organization/.github/workflows/reusable-maven-build.yml@v0.18.0`), and
`deploy-snapshot` is a job *inside* that. It surfaces as **`build / deploy-snapshot`** within the
**Maven Build** run — so grepping this repo for the job name returns nothing, which is why the axis
read as structurally unreachable rather than merely awkward.

**Method, and the false negative it survived.** `gh run list --commit <sha>` returns an **empty
array for an ABBREVIATED sha** and the correct runs for the full 40-character one. The first pass
returned `[]` for all four commits and looked like proof that no runs existed. **A control query —
`gh run list` with no `--commit` — reached the repo and returned a run whose `headSha` was the full
form of one of those very commits**, which is what exposed the truncation. **Fifth member of this
project's clean-looking-zero family, and the second caught by control-query discipline in two days.**

**Standing method for the next landing:** `git rev-parse <sha>` for the full form → `gh run list
--repo cuioss/API-Sheriff --commit <full-sha>` → take the **Maven Build** run whose `event` is
`push` → `gh run view <id> --json jobs` and read `build / deploy-snapshot`. The `issue_comment` runs
share the commit and crowd the default `--limit`, so filter by event.

## Release Pre-Flight Knowledge for the 0.2.0 Cut

Harvested from the predecessor epic, which cut **both** 0.1.0 and 0.1.1. **This is the only place
this knowledge exists.** These are instance-specific cautions; the process itself lives in
`.claude/skills/release/`.

- **Re-run the Trivy gate immediately before the cut.** A green is worth **days, not weeks** — it was
  RED on 2026-08-04 and green on the 5th only because a Dependabot base bump landed between. The DB
  snapshot carries a `NextUpdate` ~24h out: cut that day, or re-run.
- **Rebuild the scan target from the tree under test.** A stale image's green looks **identical** to
  a real one — the 0.1.0 rehearsal's first target predated its code by hours, and at 0.1.1 a
  tag-keyed reuse would have scanned an image built *before* the commit under test. Scan by
  content-derived image ID, as the workflow does.
- **The Trivy gate runs AFTER Maven Central publication.** `publish-image` is `needs: [release]`, so
  jars are irrevocably on Central before the scan or the signature happens. **That ordering is why
  the pre-cut dry run exists, and it is not optional.**
- **Assert GHCR `visibility == 'public'` via `gh api`, never by pulling as an org member.** The 0.1.0
  cut landed on INTERNAL first, which keeps anonymous pulls broken while every member check passes.
  At 0.1.1 the release skill checked **anonymously** throughout (no `ghcr.io` credential) — that is
  the standing method, and authenticating the verification would reintroduce the blind spot.
- **Never pass `--delete-branch` on a queue-gated merge.** It closed PR #167 **unmerged** while
  returning `merged: true`. Verify via the GitHub API and `mergeQueue.entries`, never the tool's
  return.
- **The merge of the version bump IS the release.** `.github/project.yml` `current-version` is the
  trigger; editing that file is by construction a release trigger.
- **The guard is proven in both directions but NOT isolated.** It fired correctly at 0.1.1 (run
  31157727796) and refused correctly at PLAN-51 (run 31256991225) — **but the refusal observation
  cannot distinguish a working guard from one stuck on `unchanged` OR stuck on `changed`**, because
  `current-version` was tagged and both refusal predicates were simultaneously true. **Still owed**:
  read the pinned reusable workflow's checkout depth. That, not another control run, settles the
  mechanism.

## Standing Rules Inherited from the `api-sheriff-roadmap` Close-Out

Five corpus lessons were dispositioned into this ledger because they govern **how this epic is run**
rather than how the code is built. Verbatim copies live at
`.plan/local/archived-orchestrators/api-sheriff-roadmap/archive/lessons-consumed/{id}.md`.

1. **`2026-08-02-15-002` — BUILD SUCCESS IS NOT EVIDENCE THAT WORK HAPPENED.** Assert positive
   evidence, never the exit code. This is the single most-recurring shape of the predecessor epic:
   a clean result nobody computed, nine separate instances in one bundle round.
2. **`2026-08-05-05-001` — for a path-triggered check-run, absence of a failure is not a pass.** Only
   presence-and-success **on the commit that changed the file** is.
3. **`2026-08-06-08-002` — a fix to a duplicated value is not done until every carrier is enumerated
   mechanically.** Learned when a Cosign identity change hit two of three carriers; recurred within
   hours of being promoted (a four-carrier config value), and again at PLAN-50 where a spec's own
   carrier list was stale and a published SVG was missed. **Enumerate, never recall.** *This epic's
   own re-grounding found four more instances — see the decompose decision log.*
4. **`2026-08-08-11-001` — a review-bot fix can be reverted by the project's own quality gate.**
   OpenRewrite `SimplifyTestThrows` re-widens narrowed test `throws` clauses **and exits 0 while
   rewriting the file**, so an accepted bot suggestion re-dirties the tree on every future gate run.
   **A bot suggestion is not verified by implementing it, only by surviving the gate.**
   *Re-verified 2026-08-08: `JUnit5BestPractices` is in the `pre-commit` recipe list (`pom.xml`:436)
   and `BuildGateCoverageContractTest`:142/:171 still read `throws Exception` — the gate won and the
   narrowing did not survive.* **This rule is one of three faces of a single mechanism** — the other
   two are `2026-08-02-17-001` (non-idempotent import churn, which also poisons the change-ledger
   freshness stamp) and `2026-07-27-09-001` (the formatter is non-idempotent and `main` is not its
   fixed point). `PLAN-V02-17` D2 lands all three as one class, and a post-gate
   `git status --porcelain` assertion is the check that covers them.
5. **`2026-08-04-05-001` — out-of-scope Sonar findings on a bounded-write-boundary plan are reported,
   not absorbed.**

## Deferred Item — the release dry run is NOT a queue item

**Operator ruling 2026-08-05**: the release dry-run standardisation runs as a **direct Claude Code
session**, not a plan-marshall plan. The brief is `release-dry-run-standardisation-claude-brief.md`
in this tree. A WS-06 workstream and a `PLAN-V02-13` spec were staged and then **removed** when the
ruling landed — do not be surprised by the gap, and **do not re-add them** (the `PLAN-V02-13` number
was subsequently reused for terminal-rejection-contract).

**Its hard gate has opened**, and the thing it gated on moved further than the gate anticipated.
Roadmap PLAN-48 shipped (#171 / `b5ce4b6`) and the release trigger is re-armed — but its D4 did not
ship with it: the release-skill work went to PLAN-50, and PR #179 (`9a8dc3c`) then **rewrote**
`.claude/skills/release/SKILL.md` substantially (a two-path release, Step 1 restructured so the
safety work precedes the merge, Step 3(i) replaced, the frontmatter description rewritten), after
PR #169 had already renumbered its steps. **So the brief's own instruction — re-read `release.yml`
and the skill on `main` and RE-DERIVE rather than patch — is now load-bearing rather than cautious.**
PLAN-50 has since landed, so the Cosign-identity collision that deferred this behind the 0.1.1 cut is
cleared.

**The form decision is taken and argued in the brief**: a dry-run **mode** on the existing `/release`
skill, not a sibling skill, because a dry run is worth exactly its fidelity to the real steps and a
second document drifts — three first-party drift defects in this same lane on one day are the
evidence. Overturnable only on evidence; record the rationale either way.
