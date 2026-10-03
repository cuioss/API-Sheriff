# PLAN-11: Align `upstream.path` Semantics With Its Documentation

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-11-upstream-path-alignment.md` and is queued in the epic `status.json` `plans[]`
> field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

A route's `upstream.path` REPLACES the alias base path (`RouteTableBuilder.applyRouteUpstreamPath`), while
the documentation describes it as appended (AS-9). This plan decides the semantics and aligns code, tests
and documentation. The source recommends the documented behaviour — `base_url` path + `upstream.path` +
remainder — so aliases carry environments and routes carry paths; the method's own Javadoc justifies
replace semantics for gRPC and benchmark routes, so the decision must be made against those usages.

## Source

KIDICAP Gateway requirements AS-9 (`archive/api-sheriff-aenderungen.adoc` § AS-9), priority low.

- Baseline: `upstream.path` replaces the alias base path (code: `RouteTableBuilder.applyRouteUpstreamPath`);
  the documentation describes appending. Without a base path in the alias, `upstream.path` works as
  expected (measured).
- Proposal: decide and align. Recommendation: behaviour as documented (`base_url` path + `upstream.path`
  + remainder), because aliases then carry environments and routes carry paths.
- Consumer migration (context only): none; the consumer avoids `upstream.path` today and can relax that
  rule afterwards.

## Deliverables

1. Decision (ADR if semantics change): append vs. replace, evaluated against every in-repo route using
   `upstream.path` (gRPC, benchmark, integration-test configs); pre-1.0 — no dual mode unless a real
   usage requires it (then an explicit mode key, never a silent heuristic).
2. Code: `RouteTableBuilder.applyRouteUpstreamPath` and its Javadoc aligned with the decision; affected
   route configs in `integration-tests/` and `benchmarks/` migrated.
3. Tests and documentation aligned (`RouteTableBuilderTest`, `doc/user/endpoint-routes.adoc`,
   `doc/configuration.adoc`).

## Claim Labels

- OBSERVED: `applyRouteUpstreamPath` replaces the alias-derived base path wholesale when `upstream.path` is non-blank — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `applyRouteUpstreamPath`
- OBSERVED: the method's Javadoc documents replace as deliberate for bare-service-path routing (gRPC `/{package}.{Service}`, benchmark `/anything/<aspect>`) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `applyRouteUpstreamPath` Javadoc
- HYPOTHESIS: user documentation describes append semantics — confirm/refute at `doc/user/endpoint-routes.adoc` § `upstream.path` and `doc/configuration.adoc` § `upstream.path` (verify-at-outline)
- HYPOTHESIS: in-repo gRPC / benchmark route configs depend on replace semantics — confirm/refute at `integration-tests/src/main/docker/sheriff-config/endpoints/` and `benchmarks/` route YAML declaring `upstream.path` (verify-at-outline)
- Verify-first clause: if append breaks a real usage that cannot be re-expressed via the alias `base_url`, loop back and re-scope (keep replace and fix the docs instead).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — `applyRouteUpstreamPath`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/RouteTableBuilderTest.java`
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` — routes using `upstream.path` (verify-at-outline)
- HYPOTHESIS: `benchmarks/` — benchmark route configs (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` — `upstream.path` docs (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — `upstream.path` docs (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none
- Overlaps with: PLAN-04, PLAN-06, PLAN-12 (`RouteTableBuilder.java`, other methods)
- Adjacent to: PLAN-12's `rewrite_location` maps upstream base path to route prefix — its mapping depends on the semantics decided here; if both are pending, land this plan first

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-11-upstream-path-alignment.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
