=== 2026-10-14 STATE at 5c2a8143 (token-sheriff 0.9.7 integrated into the specs). This anchor states current state only; older paragraphs are in the ledger's git history. ===

SHIPPED (12): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19, -21 (#439, landings/PLAN-V02-21.md). RUNNING: V02-12 (plan-v02-12-idp-addressing-model; first PR #450 open = /bff/* relocation and canonical check; held half = back-channel addressing, identity repoint, ADR - UNBLOCKED by token-sheriff 0.9.7, runs as its second PR after #450; its D3 sample after V02-23 and V02-15). STAGED (10): V02-23 (cookie forwarding policy - NEXT after V02-12's first PR lands), V02-04, -05, -06, -07, -09, -11, -15, -20, -22. kidicap PLAN-28 shipped (#437); its duplicate run (jwks-refresh-follow-ups) was closed unmerged and its worktree removed.

INBOX: empty (114 archived). kidicap inbox drained 2026-10-13 too (duplicate-run report -> lesson 2026-10-13-18-001).

DEDUP 2026-10-13 (operator: no duplicates across epics). Every staged spec in this epic, kidicap-gateway-requirements and api-sheriff-0-3-0 was audited against each other, main 28ccebbc and running V02-12. No full duplicate; each partial overlap now has ONE owner, written into the specs:
- per-source limiter, block/ban and delay primitive: V02-06 (consumers V02-07, V03-01 D2/D3, kidicap PLAN-21 options b/c). V02-06's trip is an input to V02-07's one ALERT. V02-06 D8: h2c Upgrade/Connection strip already shipped (#185); remaining is HTTP2-Settings plus stream bounds.
- response-header predicate over every relay path: V02-23 D4 builds it; V02-05 D1 reuses it.
- BFF sample + CI login proof: V02-15; V02-12's held D3 is an overlay of it.
- ADR-0008 timeout sentence: kidicap PLAN-29 D6 (V02-04 D3 dropped it). Reserved-path boot-rule ADR: kidicap PLAN-26 D5, after V02-12's first PR.
- V02-22 D2 scoped to the two PLAYWRIGHT_BASE_URL_* variables (V02-12's held half deletes the third).
- GatewayEdgeFramingCorpusTest flake: tracked only in this epic's Watch (kidicap's entry is a pointer).
rate_limit (operator ruling 2026-10-13): planned for later, kept reserved; V02-06 D9 adds a boot WARN when it is set and aligns every doc on "planned, not implemented". Its implementation is a future plan on V02-06's mechanism, not staged.

TOKEN-SHERIFF 0.9.7 (2026-10-14): #449 bumped it (release bot + RetryingJwksLoader.getKeyIds() delegate; CI green incl. integration lane). Upstream shape: backChannelOrigin is ORIGIN ONLY, no discoveryUrl, no per-endpoint override, JWKS not covered; endpoints on the issuer origin (discovery, token, PAR, userinfo, revocation) are retargeted. Specs adapted: V02-12 D1 (one oidc back-channel-origin key, http refused unless opted in, static Keycloak hostname, /auth variant needs http-relative-path=/auth), D5 rejected alternatives; V02-09 at 0.9.7 (#795 now registers the extension health checks as unremovable beans: the health exclusion is load-bearing only now, and the rationale block's DOWN story is suspect; owes the pom BOM re-measure); V02-15 points at the mechanism. kidicap: PLAN-31 staged (exact JWKS publish rule via getKeyIds, removes the ADR-0063 / GW-07 residual); PLAN-26 revocation note updated; PLAN-30 marked running (PR #448).
OPEN DECISION: V02-12's second PR and V02-23 both write gateway.schema.json, ConfigValidator, ConfigModelReflection and doc/configuration.adoc. Operator direction is V02-23 next after #450; decide whether V02-12's second PR waits for V02-23 or runs alongside.

READ BEFORE EMITTING:
- DUPLICATE-RUN GUARD (lesson 2026-10-13-18-001): before emitting a spec, check gh for an open or merged PR naming its plan id or slug, landings/PLAN-NN.md, and a live worktree for it; transition the row to running at emission, not on report-back.
- Sequencing still binding: V02-12 -> V02-09 (oidc block / BffRuntimeProducer); V02-06 -> V02-07 (substrate); V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-06, -07, -09, -11, -12); V02-22 never with V02-12 (demo client). Cross-epic: V02-06 before kidicap PLAN-21 (if b/c) and V03-01; V02-12 first PR before kidicap PLAN-26 D5; kidicap PLAN-30 before PLAN-29 D4.
- V02-01's real footprint reached edge/, pipeline/, events/, config/validation/ and quarkus/. Specs touching those re-read them at outline.
- ADR-0062 (platform-first) is in force: any new hand-rolled component needs a recorded reason (written into V02-06, -07, -09).
- New test conventions from #424 bind every plan that adds tests (surefire @Tag fork groups, failsafe concurrency group, UnreachablePort): see the epic.md queue annotation. #427 split BffKeycloakLoginFlow into open()/complete(); V02-12 (new Keycloak users) builds on that.
- Next free ADR ordinal: 0067 (derive at write time; other epics consume ordinals too).
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at outline.

OPEN: Open Defect 14 (#201, V02-11). Open Defect 12 (RouteRuntimeAssembler dead allocation; no owner). Watches: V02-21 doc leftovers (ADR-0031 "seven", start-dev-environment.sh literal 120 -> V02-22); HTTP/1.1 drain 502 flake (GatewayEdgeFramingCorpusTest, all sightings across both epics); V02-01 D7 residue; test-cycle rework residue (#422, #423, shared-wait sampling, ConfigValidator anchor order). A /marshall-steward run is owed. Upstream: TokenSheriff#800 and #801 closed, released in 0.9.7 (2026-10-14); main pins 0.9.7 since #449 (5c2a8143).

NEXT ACTION: when V02-12's first PR lands, run analyze, then emit PLAN-V02-23 first (operator direction), before any other staged plan; do not fill free slots ahead of it without operator say-so. V02-21 has landed, so V02-12 rebases over its 3 shared files (start-sample.sh, ADR-0031, integration-tests/docker-compose.yml). Held: V02-22 (never with V02-12), V02-15 and V02-09 (after V02-12), V02-07 (after V02-06).
