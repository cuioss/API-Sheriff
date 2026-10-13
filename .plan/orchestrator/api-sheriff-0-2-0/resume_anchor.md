=== 2026-10-13 STATE at 09edaea9 (cleanup grounded at 2657be7c; main moved 6 commits since). This anchor states current state only; older paragraphs are in the ledger's git history. ===

SHIPPED (11): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19. LAUNCHED 2026-10-13 (auto_emit, awaiting operator-confirmed start): V02-21 and V02-12. STAGED (9): V02-04, -05, -06, -07, -09, -11, -15, -20, -22. Third slot deliberately empty while kidicap PLAN-28 is in flight (operator choice).

INBOX: empty (108 archived).

CLEANUP 2026-10-12: delta since 386f3f74 is #425 (org workflows v0.41.0, pins only) and #427 (pending-login flood moved from -Pbenchmark to BffPendingLoginBoundIT). One claim restated (V02-11: 12 k6 goals, lane green, #427 as D1 precedent). 22/22 rows and specs reconcile, 0 blocking, 0 indeterminate surfaces. Restart verdict: ready.

SINCE CLEANUP: #431 (spin-off, outside the queue; ADR-0066) resets early-answered HTTP/2 streams with NO_ERROR - Open Defect 16's source, first benchmark run after it shows 0 GOAWAY. kidicap-gateway-requirements merged #430 (JWKS refresh bound, ADR-0063), #433 and ADR-0064/0065; specs touching auth/, edge/ or route config re-read them at outline. V02-06 D8 text and surface updated for #431.

READ BEFORE EMITTING:
- Sequencing still binding: V02-12 -> V02-09 (oidc block / BffRuntimeProducer); V02-06 -> V02-07 (substrate); V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-06, -07, -09, -11, -12); V02-22 never with V02-12 (demo client).
- V02-01's real footprint reached edge/, pipeline/, events/, config/validation/ and quarkus/. Specs touching those re-read them at outline.
- ADR-0062 (platform-first) is in force: any new hand-rolled component needs a recorded reason (written into V02-06, -07, -09).
- New test conventions from #424 bind every plan that adds tests (surefire @Tag fork groups, failsafe concurrency group, UnreachablePort): see the epic.md queue annotation. #427 split BffKeycloakLoginFlow into open()/complete(); V02-12 (new Keycloak users) builds on that.
- Next free ADR ordinal: 0067 (derive at write time; other epics consume ordinals too).
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at outline.

OPEN: Open Defect 16 (fix landed via #431, confirming: relocate after two more 0-GOAWAY benchmark runs). Open Defect 14 (#201, V02-11). Open Defect 12 (RouteRuntimeAssembler dead allocation; no owner). Open Defect 17 resolved by #427 (relocated to settled.md). Watches: HTTP/1.1 drain 502 flake (GatewayEdgeFramingCorpusTest, red main push on 09edaea9, merge-group green); V02-01 D7 residue; test-cycle rework residue (#422, #423, shared-wait sampling, ConfigValidator anchor order). A /marshall-steward run is owed.

NEXT ACTION: when the operator confirms each start, transition V02-21 / V02-12 to running and stamp the plan id. V02-21 and V02-12 share 3 files (deployment/compose-sample/scripts/start-sample.sh, ADR-0031, integration-tests/docker-compose.yml): whichever merges second rebases. Held: V02-22 (never with V02-12), V02-15 and V02-09 (after V02-12), V02-07 (after V02-06), V02-04/-05/-06/-11/-20 (multi-file overlap with kidicap PLAN-28). Re-run next once PLAN-28 or either launched plan lands.
