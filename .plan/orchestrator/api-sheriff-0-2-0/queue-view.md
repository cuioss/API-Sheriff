<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug api-sheriff-0-2-0`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug api-sheriff-0-2-0`, and `git add` the result. -->

# Queue view: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

## START HERE

**Resume anchor**: === 2026-11-05 STATE at 7a5d52ad (PARALLELISM n=3, operator 2026-10-21; auto_emit true). V02-53 landed as #565 and was analyzed 2026-11-05 (verified, D2 cut, #324 open). V02-55 is now fully verified (c settled on the main IT runs at 954d7d88 and 7a5d52ad). NOTHING RUNNING. PLAN-V02-56 final-cleanup is the only staged row, EMITTED 2026-11-05. It is the epic's last plan. This anchor states current state only; per-plan history is in landings/PLAN-*.md, epic.md Decisions and the ledger's git history. ===

SHIPPED (44, queue rows are the authority): newest V02-53 (#565 7a5d52ad, landings/PLAN-V02-53.md: 6 of 8 deliverables, D2 cut under its split guard so #324 stays open (Watch); #323, #325-#328 closed; the operator owes the #325 title edit), V02-55 (#558 d70d2f16, landings/PLAN-V02-55.md: partly verified, (a),(b),(d) hold on the merge-head archive integration-tests-2026-11-02-1733-2780; (c) settled 2026-11-05, now verified; D2 took option (b), a gateway per suite, no production change; #423 closed; the PR-attached benchmark 41489422457 failed on a Maven Central 502 before any benchmark ran, see Watch), V02-52 (#554 48715de4, landings/PLAN-V02-52.md: 0022 and 0030 relocated, ADR-0079 oidc_discovery written, 71 records, zero amendment-label citations left, ADR-0008 timeout sentence corrected so kidicap PLAN-29 D6 is already done on our side; 26 undeclared files, every hunk comment or string text; the Integration Tests PUSH run on 48715de4 is RED in generate-mtls-certificates, Broken pipe, before any test, merge_group run green -> Watch), V02-54 (#553 267a285d, measured: inside the noise; #422 closed), V02-49 (#547 19ad7a8f, #548 2ceb1384, #549 9e95440a, #550 5589ff33), V02-48 (#544 6f06e7b2), then V02-07 #537, V02-51 #538, V02-50 #532, V02-11 #534, V02-09 #530, V02-05 #526, V02-06 #528, V02-20 #527, V02-04 #521, V02-27 #520, V02-15 #516, V02-23 #515, V02-22 #512, V02-46 #513, V02-43 #505, V02-45 #506, V02-47 #504, V02-42 #502, V02-12 #450+#492, V02-41 #489, V02-25 #487, V02-40 #485, V02-24 #465, V02-44 #476, V02-28 #467, V02-26 #463, and V02-01, -02, -03, -08, -10, -13, -14, -16, -17, -18, -19, -21 earlier. SUPERSEDED: V02-29 .. V02-39.
RUNNING: none (0 of 3 slots).
STAGED: PLAN-V02-56 final-cleanup (WS-01, staged 2026-11-05 on operator instruction; 9 deliverables): D1 V02-12's held D3 (/auth-fronted Keycloak sample), D2 config errors name the loaded file, D3 the BFF-21 controls and the V02-01 D7 residue, D4 the uniform-404 floor beyond eight paths, D5 bind the hand-maintained inventories, D6 the anchor-overlap operand orders, D7 the V02-23 advisories, D8 the V02-49 advisories and the DispatchStage re-review, D9 doc and comment residue. It shares nine files with V02-53, so it is its dependency is met (#565 merged), and it starts from main at or after 7a5d52ad. The corpus cross-check on 2026-11-05 is clean: 0 live plans, 0 in-flight sibling rows, 56 of 56 specs comparable. Split guard: D1 goes in its own PR if the caps are exceeded. EMIT COMMAND: /plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-56-final-cleanup.md" plan_id=plan-v02-56-final-cleanup kidicap ledger is read-only for this session.

INBOX: empty after the V02-53 landing drain 2026-11-05 (10 messages: the landing, which was complete; 001 and 002 recorded as Standing Rule 3 recurrences; 003-006, 008 and 009 routed to plan-marshall lessons-routing as api-sheriff-api-sheriff-0-2-0-081; 007 folded into V02-56 D9). Lessons: plan-marshall findings are ROUTED to the plan-marshall repo's lessons-routing inbox (sender api-sheriff-api-sheriff-0-2-0) and any local copy removed (operator standing rule 2026-10-15); only project-local findings are promoted here. Older plan-marshall lessons filed before 2026-10-14 are not yet swept.

DEDUP 2026-10-13 (operator: no duplicates across epics). Each partial overlap has ONE owner, written into the specs: per-source limiter, block/ban and delay primitive V02-06 (consumers V02-07, V03-01 D2/D3, kidicap PLAN-21 options b/c); response-header predicate V02-23 D4 (V02-05 D1 reuses); BFF sample + CI login proof V02-15 (V02-12 D3 reuses); ADR-0008 timeout sentence kidicap PLAN-29 D6; reserved-path boot-rule ADR kidicap PLAN-26 D5; GatewayEdgeFramingCorpusTest flake tracked only in this epic's Watch. rate_limit (operator ruling 2026-10-13): planned for later.
TOKEN-SHERIFF 0.9.8 (#491). backChannelOrigin is ORIGIN ONLY.

READ BEFORE EMITTING:
- DUPLICATE-RUN GUARD (lesson 2026-10-13-18-001): check gh PRs, landings and live worktrees for the plan id or slug; transition the row to running at start.
- Next free ADR ordinal: 0080 (71 records on main at 48715de4; ADR-0079 is the oidc_discovery record; derive at write time AND against origin/main before the push, lesson from #528).
- PRs over 100 files or 150,000 diff characters are refused by the review bots (CodeRabbit 100 files, Sourcery 150k chars): size at spec time. V02-49 needed four serial PRs for this reason.
- Never write to the kidicap-gateway-requirements ledger (operator, 2026-10-14). SECURITY RULE (operator 2026-10-22): unfixed security findings go only to git-ignored .plan/local/security_notes.adoc; every spec carries a Security Findings section.
- Plan-marshall findings: route to plan-marshall lessons-routing (orchestrator inbox write --slug lessons-routing from that repo root), then manage-lessons remove --force any local copy.

OPEN (full text in epic.md): Open Defects 6 (RESOLVED by #534) and 18 (ANSWERED) to strike at the next compaction; 4, 8, 10 carried. Watch added 2026-11-05: #324 open (key-level inventory, a 0.3.0 candidate). Struck 2026-11-05: V02-55 (c), the #558 benchmark, formatter churn, ADR-0059, kidicap PLAN-29 D6. Watches new or changed 2026-10-31: formatter churn CLOSED by operator 2026-11-05 (both Watches struck); V02-49 residue (JaCoCo comparison pre-dates restored tests; GatewayEdgeEarlyResponseResetTest failed once in a unit run under load 30; advisories owned by V02-56 D8); CodeRabbit's merge-risk lead on #544 is carried into V02-52 D5 (the benchmark half closed: run 41091771555 success). Older Watches unchanged: Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength (a recurrence is a suspected production defect: reproduce under ActiveProcessorCount=1 and stage a fix plan), FIRST REAL RELEASE AFTER #509 (first multi-arch publish, owes the compose-sample .env pin bump and a check-bff-login.sh run), GW-07 request-body PARTIAL (operator-named residual), ADR-0066 untested abortive-close triggers, the APISIX bearer lane, per-route connect/read timeouts inert (kidicap PLAN-29, must relate to V02-45's fixed 25 s body deadline). REVIEWERS since 2026-10-20: CodeRabbit required, Sourcery optional.

OUTSIDE PR #555 (outside contributor, "Fixes #329"): TAKEN by operator ruling 2026-11-04; re-hosted as #561 (eaf93b7c) because fork PRs get no SONAR_TOKEN; #555 closed, #329 closed. V02-53 D7 covers the remainder: endpoint-routes.adoc, the schema description, the YAML sweep, and a check of the merged rule.

NEXT ACTION (RESTART HERE): 1) PLAN-V02-56 is EMITTED. When the operator confirms it started, transition its row to running and stamp plan_marshall_plan_id plan-v02-56-final-cleanup. Duplicate-run guard: check gh PRs and worktrees first. 2) When it lands, run analyze and verify the nine deliverables against the Watches tagged [OWNER PLAN-V02-56 Dn]; strike each one that is satisfied. 3) Then: the cleanup verb, the release dry run (direct session, brief in this tree), the 0.2.0 cut (the FIRST REAL RELEASE Watch checklist), land, then close. Carry Open Defects 4 and 10 and the #324 Watch into api-sheriff-0-3-0. 4) Owed by the operator: the #325 title edit ("restated in four places"); the Open Defect 8 live Sonar check. 5) Open PR #563 (cuioss-organization workflows v0.44.0) may be the first release-guard pin bump: read the RELEASE-GUARD DOCS Watch when it runs. 6) If Http2StreamAbuseIT or GatewayEdgeEarlyResponseResetTest goes red again, stop calling it a flake.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-V02-56 (WS-01)
- PLAN-V02-01 (WS-01) — plan=plan-v02-01-adr-0005-reversal-quarkus-adoption — PR 409 — landing=landings/PLAN-V02-01.md — status: shipped
- PLAN-V02-02 (WS-01) — plan=plan-v02-02-java-idiom-sweep — PR 198 — landing=landings/PLAN-V02-02.md — status: shipped
- PLAN-V02-03 (WS-02) — plan=plan-v02-03-documentation-restructure — PR 197 — landing=landings/PLAN-V02-03.md — status: shipped
- PLAN-V02-04 (WS-02) — plan=plan-v02-04-adr-corpus-cleanup — PR 521 — landing=landings/PLAN-V02-04.md — status: shipped
- PLAN-V02-05 (WS-03) — plan=plan-v02-05-response-hygiene — PR 526 — landing=landings/PLAN-V02-05.md — status: shipped
- PLAN-V02-06 (WS-03) — plan=plan-v02-06-enumeration-hardening — PR 528 — landing=landings/PLAN-V02-06.md — status: shipped
- PLAN-V02-07 (WS-03) — plan=plan-v02-07-threat-classification — PR 537 — landing=landings/PLAN-V02-07.md — status: shipped
- PLAN-V02-08 (WS-04) — plan=plan-v02-08-fapi-2-0-conformance — PR 377 — landing=landings/PLAN-V02-08.md — status: shipped
- PLAN-V02-09 (WS-05) — plan=plan-v02-09-token-sheriff-integration-fidelity — PR 530 — landing=landings/PLAN-V02-09.md — status: shipped
- PLAN-V02-10 (WS-05) — plan=plan-v02-10-per-client-tls-trust — PR 382 — landing=landings/PLAN-V02-10.md — status: shipped
- PLAN-V02-11 (WS-05) — plan=plan-v02-11-resource-exhaustion-test-home — PR 534 — landing=landings/PLAN-V02-11.md — status: shipped
- PLAN-V02-12 (WS-04) — plan=plan-v02-12-idp-addressing-model — PR 492 — landing=landings/PLAN-V02-12.md — status: shipped
- PLAN-V02-13 (WS-03) — plan=plan-v02-13-terminal-rejection-contract — PR 383 — landing=landings/PLAN-V02-13.md — status: shipped
- PLAN-V02-14 (WS-01) — plan=plan-v02-14-offline-config-validation — PR 387 — landing=landings/PLAN-V02-14.md — status: shipped
- PLAN-V02-15 (WS-02) — PR 516 — landing=landings/PLAN-V02-15.md — status: shipped
- PLAN-V02-16 (WS-01) — plan=plan-v02-16-image-metadata-fidelity — PR 199 — landing=landings/PLAN-V02-16.md — status: shipped
- PLAN-V02-17 (WS-02) — plan=plan-v02-17-lessons-into-source — PR 200 — landing=landings/PLAN-V02-17.md — status: shipped
- PLAN-V02-18 (WS-02) — plan=retire-doc-plan-survivors — PR 368 — landing=landings/PLAN-V02-18.md — status: shipped
- PLAN-V02-19 (WS-01) — plan=plan-v02-19-orphaned-guard-hygiene — PR 367 — landing=landings/PLAN-V02-19.md — status: shipped
- PLAN-V02-20 (WS-04) — plan=plan-v02-20-cookie-key-file-model — PR 527 — landing=landings/PLAN-V02-20.md — status: shipped
- PLAN-V02-21 (WS-05) — plan=plan-v02-21-unread-review-findings — PR 439 — landing=landings/PLAN-V02-21.md — status: shipped
- PLAN-V02-22 (WS-02) — plan=plan-v02-22-demo-client-and-doc-leftovers — PR 512 — landing=landings/PLAN-V02-22.md — status: shipped
- PLAN-V02-23 (WS-03) — plan=plan-v02-23-cookie-forwarding-policy-r2 — PR 515 — landing=landings/PLAN-V02-23.md — status: shipped
- PLAN-V02-24 (WS-01) — plan=plan-v02-24-policy-block-resolution — PR 465 — landing=landings/PLAN-V02-24.md — status: shipped
- PLAN-V02-25 (WS-01) — plan=plan-v02-25-edge-route-decomposition — PR 487 — landing=landings/PLAN-V02-25.md — status: shipped
- PLAN-V02-26 (WS-01) — plan=plan-v02-26-config-validator-split — PR 463 — landing=landings/PLAN-V02-26.md — status: shipped
- PLAN-V02-27 (WS-01) — plan=plan-v02-27-test-simplification — PR 520 — landing=landings/PLAN-V02-27.md — status: shipped
- PLAN-V02-28 (WS-01) — plan=plan-v02-28-validator-limit-rules — PR 467 — landing=landings/PLAN-V02-28.md — status: shipped
- PLAN-V02-29 (WS-01) — status: superseded
- PLAN-V02-30 (WS-01) — status: superseded
- PLAN-V02-31 (WS-01) — status: superseded
- PLAN-V02-32 (WS-01) — status: superseded
- PLAN-V02-33 (WS-01) — status: superseded
- PLAN-V02-34 (WS-01) — status: superseded
- PLAN-V02-35 (WS-01) — status: superseded
- PLAN-V02-36 (WS-01) — status: superseded
- PLAN-V02-37 (WS-01) — status: superseded
- PLAN-V02-38 (WS-01) — status: superseded
- PLAN-V02-39 (WS-01) — status: superseded
- PLAN-V02-40 (WS-01) — plan=plan-v02-40-validator-terminal-edge-protocol-rules — PR 485 — landing=landings/PLAN-V02-40.md — status: shipped
- PLAN-V02-41 (WS-01) — plan=plan-v02-41-validator-asset-forwarding-anchor-rules — PR 489 — landing=landings/PLAN-V02-41.md — status: shipped
- PLAN-V02-42 (WS-01) — plan=plan-v02-42-validator-auth-oidc-rules — PR 502 — landing=landings/PLAN-V02-42.md — status: shipped
- PLAN-V02-43 (WS-01) — plan=plan-v02-43-validator-reserved-header-topology-rules — PR 505 — landing=landings/PLAN-V02-43.md — status: shipped
- PLAN-V02-44 (WS-01) — plan=plan-v02-44-route-type-merge — PR 476 — landing=landings/PLAN-V02-44.md — status: shipped
- PLAN-V02-45 (WS-03) — plan=plan-v02-45-request-body-and-h2-buffer-bounds — PR 506 — landing=landings/PLAN-V02-45.md — status: shipped
- PLAN-V02-46 (WS-03) — plan=plan-v02-46-upstream-close-after-early-answer — PR 513 — landing=landings/PLAN-V02-46.md — status: shipped
- PLAN-V02-47 (WS-05) — plan=plan-v02-47-multi-arch-image — PR 504 — landing=landings/PLAN-V02-47.md — status: shipped
- PLAN-V02-48 (WS-02) — plan=plan-v02-48-adr-corpus-cleanup-part-2 — PR 544 — landing=landings/PLAN-V02-48.md — status: shipped
- PLAN-V02-49 (WS-01) — plan=plan-v02-49-test-simplification-part-2 — PR 550 — landing=landings/PLAN-V02-49.md — status: shipped
- PLAN-V02-50 (WS-03) — plan=plan-v02-50-enumeration-detection-substrate — PR 532 — landing=landings/PLAN-V02-50.md — status: shipped
- PLAN-V02-51 (WS-05) — plan=plan-v02-51-lane-conditional-guards — PR 538 — landing=landings/PLAN-V02-51.md — status: shipped
- PLAN-V02-52 (WS-02) — plan=plan-v02-52-adr-relocations-and-citation-rewrite — PR 554 — landing=landings/PLAN-V02-52.md — status: shipped
- PLAN-V02-53 (WS-01) — plan=plan-v02-53-plan-15-follow-up-issues — PR 565 — landing=landings/PLAN-V02-53.md — status: shipped
- PLAN-V02-54 (WS-05) — plan=plan-v02-54-token-sheriff-lazy-generators-measured — PR 553 — landing=landings/PLAN-V02-54.md — status: shipped
- PLAN-V02-55 (WS-05) — plan=plan-v02-55-it-concurrency-and-local-image-hazard — PR 558 — landing=landings/PLAN-V02-55.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-V02-56 | WS-01 | staged | .github/workflows/*; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/UpstreamAssetSource.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ReturnTargetScopes.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/PortalConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/RuleSupport.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/GatewayCookies.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/LocationPathReview.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/PathSlashes.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/package-info.java; api-sheriff/src/main/resources/schema/endpoint.schema.json; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/topology/TopologyResolverTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/TerminalActionRulesRedirectTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/DocumentedHeaderSetsContractTest.java; api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/ConfigFixtures.java; demo-client/scripts/start-dev-environment.sh; deployment/compose-sample/**; doc/adr/0073-Untrusted_callers_get_the_unrouted_404_for_every_rejection_raised_before_the_trust_boundary.adoc; doc/configuration.adoc; doc/quality-report/*.adoc; doc/quality-report/build-and-peripherals.adoc; doc/quality-report/test-quality.adoc; doc/security-threat-model.adoc; doc/user/**; integration-tests/src/main/docker/sheriff-config-ws-admission/gateway.yaml; integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/EnumerationOracleIT.java | plan-marshall |
