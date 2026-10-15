=== 2026-10-15 STATE at 9e05a3d6 (V02-24 shipped as #465: one PolicyBlock resolver, endpoint scope for security_filter and forward, ADR-0067; validator split running slice by slice, V02-28 in flight as #467). This anchor states current state only; older paragraphs are in the ledger's git history. ===

SHIPPED (14): V02-01 (#410, #412, #409, #415, #417; ADR-0062), -02, -03, -08, -10, -13, -14, -16, -17, -18, -19, -21 (#439), -24 (#465, 9e05a3d6, landings/PLAN-V02-24.md: 7/7 in one PR; breaking: security_filter and forward declarable on an endpoint; ApiSheriff-101 derived from the descriptor opt-in {allowed_methods, security_filter}; post-merge main CI green), -26 (#463, 7e5eb579, landings/PLAN-V02-26.md: slice 1 only, RouteMatchingRules + TokenValidationRules + RuleSupport + boot-validation-rules.adoc; ConfigValidator 3,273 -> 2,750 lines; post-merge main CI green).
RUNNING: V02-12 (plan-v02-12-idp-addressing-model; first PR #450 merged as eff0724c; its second PR = back-channel origin, identity repoint, ADR, unblocked by token-sheriff 0.9.7, concurrent under the rebase rule; its D3 sample after V02-23 and V02-15). V02-28 validator-limit-rules (emitted 2026-10-14; PR #467 is CONFLICTING with main after #465 and rebases at sync-baseline). kidicap PLAN-32 runs on this machine (#455; its PR #466 open, mergeable, at 2026-10-15); the kidicap ledger is read-only for this session.
PARKED: V02-23 (2026-10-14, operator; resumes re-grounded after V02-25 and V02-39; rulings carried in its spec).
STAGED: V02-25 edge-route-decomposition (V02-24 landed; waits only for kidicap PLAN-32 / #466; carries V02-24's residue as D6), validator slices V02-29..39, V02-27 test-simplification (after the whole refactor and V02-23), V02-04, -05, -06, -07, -09, -11, -15, -20, -22.

VALIDATOR SPLIT (operator ruling 2026-10-14, AskUserQuestion at V02-26 landing: all before V02-23). 12 slice specs, serial by default, at most two at once under the rebase rule (all edit ConfigValidator, ConfigValidatorTest and the concern map); they run alongside V02-24 and V02-25 (disjoint). Order: V02-28 LimitRules -> -29 TerminalActionRules -> -30 Edge+Protocol -> -31 AssetDefaults+Forwarding -> -32 AnchorRules part 1 (moves validateAnchorAuthFloor AND derives it via PolicyBlock.AUTH, amending ADR-0067; V02-24 merged second and left it) -> -33 access/auth matrix (may fold into -32 if it measures small) -> [V02-12 second PR merged] -> -34 Auth+OidcClient -> -35/-36 OidcSession -> -37/-38 ReservedPath -> -39 Header+Topology, end of split, ADR. Size estimates are the V02-26 outline's x1.39.

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
- Sequencing: refactor chain in epic.md (V02-24 -> V02-25; validator slices in parallel; then V02-23, then V02-27, then other edge/config plans V02-05, -06, -07, -20 and kidicap PLAN-20, -21, -26, -29). V02-12 -> V02-09; V02-06 -> V02-07; V02-12 -> V02-15 (D5); V02-20 never with V02-12 or V02-09; V02-04 never with an ADR-authoring plan (V02-25 and V02-39 write one); V02-22 never with V02-12. Cross-epic: V02-06 before kidicap PLAN-21 (if b/c) and V03-01; kidicap PLAN-30 before PLAN-29 D4.
- New test conventions from #424 bind every plan that adds tests. Next free ADR ordinal: 0068 (derive at write time).
- PRs over 100 files or 150,000 diff characters are refused by the review bots: size at spec time (lesson 2026-10-14-20-004), not only at outline.
- Never write to the kidicap-gateway-requirements ledger (operator, 2026-10-14).

OPEN: Open Defect 14 (#201, V02-11). Open Defect 12 (RouteRuntimeAssembler dead allocation; V02-25 removes the assembler's copy). Watches: V02-21 doc leftovers; HTTP/1.1 drain 502 flake (GatewayEdgeFramingCorpusTest); V02-01 D7 residue; test-cycle rework residue. A /marshall-steward run is owed.

NEXT ACTION: On each landing message run analyze. When V02-28 lands, emit V02-29 (optionally two slices at once). Emit V02-25 as soon as kidicap PLAN-32 (#466) has landed. Slices V02-34..38 wait for V02-12's second PR. V02-23 re-grounded after V02-25 and V02-39, then V02-27. Until PLAN-32 lands, a red Http2EarlyResponseResetIT on any PR is that flake. Off-surface plans free meanwhile: V02-11, V02-22 (not with V02-12).
