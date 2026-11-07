envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-14T14:01:33Z
revision=2
amended=2026-10-14T14:04:58Z

# PLAN-V02-23 paused — major refactor of the edge pipeline and policy-block resolution needed first

**Operator decision (2026-10-14):** PLAN-V02-23 (cookie forwarding policy) is PAUSED at phase 4-plan
(outline written, no tasks, no code, no branch). The operator ruled that a major rework must come
first: "GatewayEdgeRoute is a nightmare. We need a major rework here." The two drivers are the
size of V02-23 and, above all, that **policy blocks have no shared mechanism**.

## Why V02-23 surfaced it

The V02-23 outline came to 8 deliverables, ~67 files and an estimated 170,000–230,000 diff
characters (over the 150,000 review-bot limit). Roughly half of that is cost the structure charges
for any new per-route policy, not cookie logic.

## Finding 1 — policy blocks have no shared resolution mechanism (primary)

- `config/RouteTableBuilder.java` resolves every policy block with its own hand-written method and
  its own chain: `resolveEffectiveAuth` (route → endpoint → anchor), `resolveSecurityFilter`
  (route → anchor), `resolveSecurityHeaders` (anchor → gateway), `effectiveAllowedMethods`
  (gateway/endpoint/anchor), `effectiveAllowedOrigins` (route only), `resolveDefaults`,
  `resolveRetryEnabled`, `resolveNotModifiedEnabled`, `resolveWebSocketIdleTimeout`; `forward` is
  route-only. Relaxing-override WARNs are also hand-coded per block.
- The rule `doc/user/anchors.adoc` states once ("gateway → anchor → endpoint → route, every step
  replaces the whole block") is therefore re-implemented, partially, per block.
- Adding one block at all four scopes (`cookies`) costs ~15 files before any feature logic: four
  config records, both JSON schemas, `ConfigModelReflection`, `DocumentedSetsContractTest`, the
  array-key inventory in `doc/configuration.adoc`, plus unrelated tests
  (`ConfigModelContractTest`, `SheriffMetricsTest`, `GatewayEdgePipelineTest`) that break only
  because they call the records' canonical constructors positionally. Every future block pays the
  same again.

## Finding 2 — `edge/GatewayEdgeRoute.java` is an oversized wiring hub

- 2,664 lines, ~78 method declarations. Every per-route setting is threaded by hand from this class
  into each stage and relay call site; V02-23 deliverables 1, 3, 4 and 5 all edit it.
- `config/validation/ConfigValidator.java` (3,273 lines) is the same pattern on the boot side:
  every new rule lands in one class.

## Finding 3 — documentation restates invariants instead of referencing one source

V02-23 D6/D7 edit 2 ADRs, 14 docs and the root README, mostly to correct copies of one sentence
("the browser `Cookie` never reaches an upstream"). There is no single canonical section for the
never-forward set that other pages link to.

## Asked of the orchestrator

1. Stage a refactor plan (or plans) ahead of V02-23:
   - one generic policy-block resolution mechanism (declared scopes, whole-block replacement,
     relaxing-override WARN derived generically), so a new block is declared once;
   - config records that tests do not construct positionally (builders / test fixtures);
   - decompose `GatewayEdgeRoute` (per-route policy reaches stages through a resolved route context
     rather than hand-threaded parameters) and split `ConfigValidator` by concern.
2. Re-sequence V02-23 after the refactor and re-ground its spec against the new seams; the operator
   rulings already taken for V02-23 stand (see below).
3. Weigh overlap: PLAN-V02-12's second PR, V02-05, -06, -07, -20 all touch `edge/**` / `config/**`.
## V02-23 state to resume from

- Local plan `plan-v02-23-cookie-forwarding-policy`, phase 4-plan, outline written
  (`solution_outline.md`), outline q-gate validation stopped mid-run, no tasks, no worktree.
- Operator rulings taken during refine/outline (carry into the re-grounded spec):
  - default `none` also relays NO upstream `Set-Cookie` (D4 wins over D1; breaking, must be stated);
  - same-origin baseline without a BFF = received scheme + host, plus exact `additional_origins`;
  - WebSocket upgrade relays no upstream header today → pin by test, no relay added;
  - gRPC trailers pass the shared response-header filter;
  - execution profile `full`.
- Note: the critical-bug ruling of 2026-10-13 (blanket Cookie strip) remains open until V02-23 ships.
