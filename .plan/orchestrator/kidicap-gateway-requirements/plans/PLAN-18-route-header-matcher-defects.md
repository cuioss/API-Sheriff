# PLAN-18: Route Header Matchers — Case-Insensitive Names and a Real `present: false`

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-18-route-header-matcher-defects.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-17 from inbox message `kidicap-gateway-downstream-004.md`; both defects were
> re-confirmed at HEAD by the orchestrator before staging.

## Objective

Two defects make `match.headers` route pairs fail silently. Header names are compared case-sensitively
against a lower-cased request map, so the legitimate spelling `name: Authorization` never matches; and
`present: false` constrains nothing, so such a route matches every request while the boot-time
disjointness check accepts the pair as distinguishable. Together they make "the more permissive-looking
but wrong route wins", with the outcome depending on declaration order and no signal anywhere.

## Source

Inbox `kidicap-gateway-downstream-004.md` (finding, 2026-09-17), filed by the downstream deployment
`kidicap-gateway`, measured against a 0.2.1 experiment build in its Helm integration test.

- **Defect 1 — case-sensitive header names.** `PipelineRequest` lower-cases inbound header names and
  `RouteSelectionStage` passes those lower-cased keys to `RouteMatcher.matches`, while
  `RouteMatcher.headersMatch` looks the name up exactly as configured. HTTP field names are
  case-insensitive (RFC 9110 §5.1). Measured: with `name: Authorization, present: true` on a bearer
  route, a request carrying a valid bearer token is routed to the sibling session route (`401`, and `200`
  with the session's token when a cookie is present). No boot warning.
- **Defect 2 — `present: false` does not mean "absent".** `headersMatch` evaluates `present` only when it
  is `TRUE`, so a `present: false` matcher constrains nothing and its route matches every request;
  `ConfigValidator.validateRouteDisjointness` (`presenceDistinguishes`) nevertheless treats
  `present: true` vs `present: false` as disjoint and accepts the pair. With equal prefixes the stable
  sort in `RouteTableBuilder` keeps declaration order. Measured: declaring the `present: false` route
  first makes the `present: true` route unreachable.
- **Expected by the reporter:** (1) header matcher names compared case-insensitively (normalise at
  binding or in `RouteMatcher.from`); (2) `present: false` matches only when the header is absent, so the
  disjointness check's assumption holds and declaration order stops mattering; (3) unit tests for both
  cases plus an integration test with a present/absent pair declared in both orders.
- Downstream weight: the gateway separates bearer clients from BFF browsers on one path with exactly such
  a pair (see PLAN-19), so both defects are load-bearing there until `session_fallback` exists.

## Deliverables

1. Compare header matcher names case-insensitively — normalise the configured name once (binding or
   `RouteMatcher.from`), never by lower-casing at every request — and keep the value comparison's current
   semantics explicit (value equality stays case-sensitive unless the outline decides otherwise, with the
   decision recorded).
2. Make `present: false` mean "header absent": the matcher matches only when the header is not present,
   so `ConfigValidator`'s `presenceDistinguishes` assumption becomes true rather than assumed.
3. Re-check the disjointness validation against the corrected semantics — including a pair that is now
   genuinely disjoint, and any pair the old semantics accepted that is not.
4. Tests: unit tests in the route-matcher and route-selection test classes for mixed-case names and for
   present/absent matching; an integration test with a present/absent route pair declared in both orders,
   proving order-independence.
5. Documentation: `doc/user/endpoint-routes.adoc` and `doc/configuration.adoc` — state the
   case-insensitivity and the absent semantics of `present: false`.

## Claim Labels

- OBSERVED: `RouteMatcher.headersMatch` looks up `requestHeaders.get(header.name())` with the configured spelling and evaluates `present` only on `Boolean.TRUE` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteMatcher.java` § `headersMatch` (lines 164-177 at 3abc370; re-grounded after PLAN-15 rewrote this file — both defects survive unchanged)
  - verdict: corroborated | checked_at: c1b09c7 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RouteMatcher.headersMatch does requestHeaders.get(header.name()) with no case folding; unchanged 3e3addc..c1b09c7
- OBSERVED: `PipelineRequest` lower-cases header names when building its map — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/PipelineRequest.java` § header map construction (line 83)
  - verdict: corroborated | checked_at: c1b09c7 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: PipelineRequest still lower-cases the request header map; unchanged since the last check
- OBSERVED: `ConfigValidator.presenceDistinguishes` treats two non-null, unequal `present` values as disjoint — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` § `presenceDistinguishes` (lines 627-631)
  - verdict: corroborated | checked_at: c1b09c7 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: ConfigValidator.presenceDistinguishes (now line 908) still returns true for differing present flags while headersMatch constrains only present:true
- HYPOTHESIS: `RouteSelectionStage` passes `singleValueHeaders()` (lower-cased keys) into the matcher — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/RouteSelectionStage.java` § route selection (verify-at-outline)
  - verdict: corroborated | checked_at: c1b09c7 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RouteSelectionStage.java unchanged 3e3addc..c1b09c7
- HYPOTHESIS: `RouteTableBuilder`'s ordering is a stable sort that preserves declaration order for equal prefixes — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § route ordering (verify-at-outline)
  - verdict: corroborated | checked_at: c1b09c7 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RouteTableBuilder ROUTE_ORDER at line 134 and resolved.sort(ROUTE_ORDER) at 168 unchanged; List.sort still stable
- Verify-first clause: before scoping, settle whether any existing route config or test relies on the current `present: false` no-op semantics (in-repo IT configs, `benchmarks/`), since the fix makes such a route stop matching requests that carry the header; a hit re-scopes the change into a migration of those configs.
  - verdict: corroborated | checked_at: c1b09c7 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: re-measured at c1b09c7: zero present:false occurrences under integration-tests/ and benchmarks/

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteMatcher.java` — `headersMatch`, `from`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/MatchConfig.java` — `HeaderMatcher`, the binding-time normalisation site deliverable 1 offers (added 2026-09-21)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — `presenceDistinguishes`, `validateRouteDisjointness`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/RouteSelectionStage.java` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — route ordering (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/routing/` — matcher tests (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/` — disjointness tests (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` — present/absent route pair (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — order-independence IT (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-15 (running) by surface — `RouteTableBuilder.java`, the routing package and the same docs; start after PLAN-15 lands
- Overlaps with: PLAN-15, PLAN-16 (routing and docs), PLAN-17 (validation package, docs)
- Adjacent to: PLAN-19 (`session_fallback`) removes the downstream's need for the route pair but does not replace these fixes

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-18-route-header-matcher-defects.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
