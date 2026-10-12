=== 2026-10-12 STATE at 2657be7c (cleanup grounded at 2657be7c). This anchor states current state only; older paragraphs are in the ledger's git history. ===

SHIPPED (11): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19. RUNNING: none. STAGED (11): V02-04, -05, -06, -07, -09, -11, -12, -15, -20, -21, -22. All three slots are free; nothing runs alone any more.

INBOX: empty (108 archived).

CLEANUP 2026-10-12: delta since 386f3f74 is #425 (org workflows v0.41.0, pins only) and #427 (pending-login flood moved from -Pbenchmark to BffPendingLoginBoundIT). One claim restated (V02-11: 12 k6 goals, lane green, #427 as D1 precedent). 22/22 rows and specs reconcile, 0 blocking, 0 indeterminate surfaces. Restart verdict: ready.

READ BEFORE EMITTING:
- Sequencing still binding: V02-12 -> V02-09 (oidc block / BffRuntimeProducer); V02-06 -> V02-07 (substrate); V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-06, -07, -09, -11, -12); V02-22 never with V02-12 (demo client).
- V02-01's real footprint reached edge/, pipeline/, events/, config/validation/ and quarkus/. Specs touching those re-read them at outline.
- ADR-0062 (platform-first) is in force: any new hand-rolled component needs a recorded reason (written into V02-06, -07, -09).
- New test conventions from #424 bind every plan that adds tests (surefire @Tag fork groups, failsafe concurrency group, UnreachablePort): see the epic.md queue annotation. #427 split BffKeycloakLoginFlow into open()/complete(); V02-12 (new Keycloak users) builds on that.
- Next free ADR ordinal: 0063.
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at outline.

OPEN: Open Defect 16 (upload GOAWAY, V02-06 D8; observable again, 104 resets on the green run 38073671464, still near threshold). Open Defect 14 (#201, V02-11). Open Defect 12 (RouteRuntimeAssembler dead allocation; no owner). Open Defect 17 resolved by #427 (relocated to settled.md). Watches: V02-01 D7 residue; test-cycle rework residue (#422, #423, shared-wait sampling, ConfigValidator anchor order). A /marshall-steward run is owed.

NEXT ACTION: /plan-orchestrator next slug=api-sheriff-0-2-0 to fill the three slots.
