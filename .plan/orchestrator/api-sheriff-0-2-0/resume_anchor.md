=== 2026-10-13 STATE at 28ccebbc (cross-epic dedup cleanup). This anchor states current state only; older paragraphs are in the ledger's git history. ===

SHIPPED (12): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19, -21 (#439, landings/PLAN-V02-21.md). RUNNING: V02-12 (plan-v02-12-idp-addressing-model, in 5-execute, no commits yet; first PR = D1, D2, D4; D3 held until after V02-23 and V02-15; D5-D7 held for TokenSheriff#800). STAGED (10): V02-23 (cookie forwarding policy - NEXT after V02-12's first PR lands), V02-04, -05, -06, -07, -09, -11, -15, -20, -22. kidicap PLAN-28 shipped (#437); its duplicate run (jwks-refresh-follow-ups) was closed unmerged and its worktree removed.

INBOX: empty (114 archived). kidicap inbox drained 2026-10-13 too (duplicate-run report -> lesson 2026-10-13-18-001).

DEDUP 2026-10-13 (operator: no duplicates across epics). Every staged spec in this epic, kidicap-gateway-requirements and api-sheriff-0-3-0 was audited against each other, main 28ccebbc and running V02-12. No full duplicate; each partial overlap now has ONE owner, written into the specs:
- per-source limiter, block/ban and delay primitive: V02-06 (consumers V02-07, V03-01 D2/D3, kidicap PLAN-21 options b/c). V02-06's trip is an input to V02-07's one ALERT. V02-06 D8: h2c Upgrade/Connection strip already shipped (#185); remaining is HTTP2-Settings plus stream bounds.
- response-header predicate over every relay path: V02-23 D4 builds it; V02-05 D1 reuses it.
- BFF sample + CI login proof: V02-15; V02-12's held D3 is an overlay of it.
- ADR-0008 timeout sentence: kidicap PLAN-29 D6 (V02-04 D3 dropped it). Reserved-path boot-rule ADR: kidicap PLAN-26 D5, after V02-12's first PR.
- V02-22 D2 scoped to the two PLAYWRIGHT_BASE_URL_* variables (V02-12's held half deletes the third).
- GatewayEdgeFramingCorpusTest flake: tracked only in this epic's Watch (kidicap's entry is a pointer).
Open gap, no owner: rate_limit is another accepted-and-ignored key (documented as such); no spec decides wire-or-remove.

READ BEFORE EMITTING:
- DUPLICATE-RUN GUARD (lesson 2026-10-13-18-001): before emitting a spec, check gh for an open or merged PR naming its plan id or slug, landings/PLAN-NN.md, and a live worktree for it; transition the row to running at emission, not on report-back.
- Sequencing still binding: V02-12 -> V02-09 (oidc block / BffRuntimeProducer); V02-06 -> V02-07 (substrate); V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-06, -07, -09, -11, -12); V02-22 never with V02-12 (demo client). Cross-epic: V02-06 before kidicap PLAN-21 (if b/c) and V03-01; V02-12 first PR before kidicap PLAN-26 D5; kidicap PLAN-30 before PLAN-29 D4.
- V02-01's real footprint reached edge/, pipeline/, events/, config/validation/ and quarkus/. Specs touching those re-read them at outline.
- ADR-0062 (platform-first) is in force: any new hand-rolled component needs a recorded reason (written into V02-06, -07, -09).
- New test conventions from #424 bind every plan that adds tests (surefire @Tag fork groups, failsafe concurrency group, UnreachablePort): see the epic.md queue annotation. #427 split BffKeycloakLoginFlow into open()/complete(); V02-12 (new Keycloak users) builds on that.
- Next free ADR ordinal: 0067 (derive at write time; other epics consume ordinals too).
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at outline.

OPEN: Open Defect 14 (#201, V02-11). Open Defect 12 (RouteRuntimeAssembler dead allocation; no owner). Watches: V02-21 doc leftovers (ADR-0031 "seven", start-dev-environment.sh literal 120 -> V02-22); HTTP/1.1 drain 502 flake (GatewayEdgeFramingCorpusTest, all sightings across both epics); V02-01 D7 residue; test-cycle rework residue (#422, #423, shared-wait sampling, ConfigValidator anchor order). A /marshall-steward run is owed. Upstream: TokenSheriff#800 (back-channel override incl. revocation; gates V02-12 D5-D7, V02-09, V02-15).

NEXT ACTION: when V02-12's first PR lands, run analyze, then emit PLAN-V02-23 first (operator direction), before any other staged plan; do not fill free slots ahead of it without operator say-so. V02-21 has landed, so V02-12 rebases over its 3 shared files (start-sample.sh, ADR-0031, integration-tests/docker-compose.yml). Held: V02-22 (never with V02-12), V02-15 and V02-09 (after V02-12), V02-07 (after V02-06).
