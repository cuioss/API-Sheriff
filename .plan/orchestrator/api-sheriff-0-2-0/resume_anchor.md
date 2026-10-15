=== 2026-10-15 STATE at 9e05a3d6 (V02-24 shipped as #465: one PolicyBlock resolver, endpoint scope for security_filter and forward, ADR-0067; validator split re-packed 2026-10-15 into V02-28 + V02-40..43; V02-25 split, V02-44 route-type merge emitted). This anchor states current state only; older paragraphs are in the ledger's git history. ===

SHIPPED (14): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19, -21 (#439), -24 (#465, 9e05a3d6, landings/PLAN-V02-24.md: 7/7 in one PR; breaking: security_filter and forward declarable on an endpoint; ApiSheriff-101 derived from the descriptor opt-in {allowed_methods, security_filter}; post-merge main CI green), -26 (#463, 7e5eb579, landings/PLAN-V02-26.md: slice 1 only, RouteMatchingRules + TokenValidationRules + RuleSupport + boot-validation-rules.adoc; ConfigValidator 3,273 -> 2,750 lines; post-merge main CI green).
RUNNING: V02-44 route-type-merge (emitted 2026-10-15; concurrent with kidicap PLAN-32 #466 under the rebase rule; carries V02-24's residue: ApiSheriff-101 scope naming + two doc fixes). V02-12 (plan-v02-12-idp-addressing-model; first PR #450 merged as eff0724c; its second PR = back-channel origin, identity repoint, ADR, unblocked by token-sheriff 0.9.7, concurrent under the rebase rule; its D3 sample after V02-23 and V02-15). V02-28 validator-limit-rules (emitted 2026-10-14; PR #467 is CONFLICTING with main after #465 and rebases at sync-baseline). kidicap PLAN-32 runs on this machine (#455; its PR #466 open, mergeable, at 2026-10-15); the kidicap ledger is read-only for this session.
PARKED: V02-23 (2026-10-14, operator; resumes re-grounded after V02-25 and V02-43; rulings carried in its spec).
STAGED: V02-25 edge-route-decomposition (GatewayEdgeRoute into edge/ sub-packages + arch test + ADR; after V02-44 and kidicap PLAN-32), validator slices V02-40..43 (V02-29..39 superseded), V02-27 test-simplification (after the whole refactor and V02-23), V02-04, -05, -06, -07, -09, -11, -15, -20, -22.

VALIDATOR SPLIT (operator: all before V02-23, 2026-10-14; re-packed 2026-10-15). Move-only work has the 150k-char cap lifted (Sourcery is optional); the 100-file cap binds; split above ~350 KB or if CodeRabbit declines. Order: V02-28 LimitRules (#467) -> V02-40 TerminalAction+Edge+Protocol -> V02-41 AssetDefaults+Forwarding+Anchor (also derives the auth floor via PolicyBlock.AUTH, amends ADR-0067) -> [V02-12 second PR merged] -> V02-42 Auth+OidcClient+OidcSession -> V02-43 ReservedPath+Header+Topology, end of split, ADR. Serial; two at once under the rebase rule; disjoint from V02-44/V02-25.

INBOX: empty (129 archived). Lessons promoted: V02-26 -> 2026-10-14-20-001..005; V02-24 -> 2026-10-15-09-001 (never re-enumerate a code-owned set; written into the slice specs) and -002 (Sonar S2637/S2638 under @NullMarked).

DEDUP 2026-10-13 (operator: no duplicates across epics). Every staged spec in this epic, kidicap-gateway-requirements and api-sheriff-0-3-0 was audited; each partial overlap has ONE owner, written into the specs:
- per-source limiter, block/ban and delay primitive: V02-06 (consumers V02-07, V03-01 D2/D3, kidicap PLAN-21 options b/c). V02-06 D8: h2c Upgrade/Connection strip already shipped (#185); remaining is HTTP2-Settings plus stream bounds.
- response-header predicate over every relay path: V02-23 D4 builds it; V02-05 D1 reuses it.
- BFF sample + CI login proof: V02-15; V02-12's held D3 is an overlay of it.
- ADR-0008 timeout sentence: kidicap PLAN-29 D6. Reserved-path boot-rule ADR: kidicap PLAN-26 D5.
- V02-22 D2 scoped to the two PLAYWRIGHT_BASE_URL_* variables.
- GatewayEdgeFramingCorpusTest flake: tracked only in this epic's Watch.
rate_limit (operator ruling 2026-10-13): planned for later; V02-06 D9 adds a boot WARN and aligns the docs.

TOKEN-SHERIFF 0.9.7: main pins it since #449. backChannelOrigin is ORIGIN ONLY (no discoveryUrl, JWKS not covered); specs adapted (V02-12 D1/D5, V02-09, V02-15).

READ BEFORE EMITTING:
- DUPLICATE-RUN GUARD (lesson 2026-10-13-18-001): check gh PRs, landings and live worktrees for the plan id or slug; transition the row to running at emission.
- Sequencing: refactor chain in epic.md (V02-24 -> V02-25; validator slices in parallel; then V02-23, then V02-27, then other edge/config plans V02-05, -06, -07, -20 and kidicap PLAN-20, -21, -26, -29). V02-12 -> V02-09; V02-06 -> V02-07; V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-25, V02-41 amends, V02-43 writes one); V02-22 never with V02-12. Cross-epic: V02-06 before kidicap PLAN-21 (if b/c) and V03-01; kidicap PLAN-30 before PLAN-29 D4.
- New test conventions from #424 bind every plan that adds tests. Next free ADR ordinal: 0068 (derive at write time).
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at spec time (lesson 2026-10-14-20-004), not only at outline.
- Never write to the kidicap-gateway-requirements ledger (operator, 2026-10-14).

OPEN: Open Defect 14 (#201, V02-11). Open Defect 12 (RouteRuntimeAssembler dead allocation; V02-44 removes the assembler's copy). Watches: V02-21 doc leftovers; HTTP/1.1 drain 502 flake (GatewayEdgeFramingCorpusTest); V02-01 D7 residue; test-cycle rework residue. A /marshall-steward run is owed.

NEXT ACTION: On each landing message run analyze. When V02-28 lands, emit V02-40 (or emit V02-40 now alongside V02-28 under the rebase rule). Emit V02-25 once V02-44 and kidicap PLAN-32 (#466) have both landed. Slices V02-34..38 wait for V02-12's second PR. V02-23 re-grounded after V02-25 and V02-43, then V02-27. Until PLAN-32 lands, a red Http2EarlyResponseResetIT on any PR is that flake. Off-surface plans free meanwhile: V02-11, V02-22 (not with V02-12).
