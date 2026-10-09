=== 2026-10-09 STATE at 386f3f74, after analyze + drain + cleanup. This anchor states current state only; the older dated paragraphs were removed on this pass and are in the ledger's git history. ===

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
