# PLAN-15: Routing and Response Headers — Redirects, Optional `base_url`, `upstream.path`, Location Rewrite, Asset Fallback, Anchor Security Headers

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-15-routing-and-headers.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Aggregates superseded specs PLAN-04, PLAN-06, PLAN-11, PLAN-12 (see `plans/superseded/`).

## Objective

Extend the route table and response path on their shared surface (`endpoint.schema.json`,
`RouteTableBuilder`, `ResponseStage`, pipeline order): exact-path routes with a `redirect` terminal action
(AS-3); `base_url` required only for endpoints with proxy routes (AS-4); aligned `upstream.path`
semantics (AS-9); opt-in rewrite of upstream `Location` headers (AS-11); directory-asset index and SPA
fallback (AS-12); and anchor-scoped `security_headers` that actually apply, with a
`content_security_policy` key and a `set`/`default` precedence mode (AS-8).

## Source

KIDICAP Gateway requirements (`archive/api-sheriff-aenderungen.adoc`), translated.

**AS-3 — redirect action and exact-path routes (priority high).** Baseline: routes know only `path_prefix`;
no action answers a redirect (code: route schema). A static HTML page cannot set `Location` — it arrives
as `200` (measured); only `meta refresh` works. `/app/<code>` and `/app/<code>/` go to different targets
(measured); without the trailing slash relative URLs resolve outside the application. Need: steer the
no-slash entry to the slash form, short addresses, moves without breakage.

```yaml
routes:
  - id: k-beispiel-entry
    match:
      path: /KIDICAP.Gateway/app/K.Beispiel      # exact; excludes path_prefix
    redirect:
      location: /KIDICAP.Gateway/app/K.Beispiel/
      status: 308                                # 301, 302, 303, 307, 308
      keep_query: true
```

`match.path` compares exactly; for the same address an exact route beats a prefix route. `redirect` is a
terminal action like `upstream`/`asset`, allowed under every anchor type. `location` is a same-origin path;
absolute URLs only with explicit `allow_external: true` (no open redirect); no request placeholders except
the query under `keep_query`. Acceptance: exact beats prefix; query kept; boot error for external
`location` without the opt-in.

**AS-4 — `base_url` optional for endpoints without upstream (priority medium).** Baseline: every endpoint
needs a resolvable `base_url`, even if no route goes there (code: schema, required). Proposal: required only
when a route of the endpoint needs an upstream (proxy); directory-source asset routes and redirect routes
do without.

**AS-8 — `security_headers` per anchor, CSP, precedence (priority medium).** Baseline: anchor
`security_headers` is effective per documentation; in fact only the global block applies (measured); global
overwrites same-named origin headers (measured); no key for `Content-Security-Policy`. Proposal: anchor
block effective (anchor before global); key `content_security_policy`; per header mode `set` (overwrite)
or `default` (only if the origin did not set it), so frontends keep their CSP while the gateway sets a
floor. Acceptance: anchor `frame_deny` applies only under that anchor; `default` leaves an origin header.

**AS-9 — `upstream.path` and alias base path (priority low).** Baseline: `upstream.path` REPLACES the alias
base path (code: `RouteTableBuilder.applyRouteUpstreamPath`); the documentation describes appending; without
a base path in the alias it works as expected (measured). Proposal: decide and align; recommendation:
documented behaviour (`base_url` path + `upstream.path` + remainder), because aliases then carry
environments and routes carry paths.

**AS-11 — rewrite `Location` on proxy routes (priority low).** Baseline: an origin `Location` is relayed
unchanged (measured); a frontend redirecting to its own absolute path sends the browser off the gateway.
Proposal: optional per route `upstream.rewrite_location: true`: a `Location` starting with the upstream base
path is mapped onto the route prefix.

**AS-12 — index and fallback file for asset routes (priority low).** Baseline: no directory index, no SPA
fallback; without file extension `application/octet-stream` is served (measured). Proposal:
`asset.index: index.html` for directory addresses, `asset.fallback: index.html` for unknown extensionless
paths.

Consumer migration (context only): entry routes without trailing slash, old start address redirected,
borrowed alias removed, headers refined per anchor, a self-imposed ban on `upstream.path` relaxed.

## Deliverables

1. AS-3/AS-4 schema + model — `match.path` (exclusive with `path_prefix`), `redirect` terminal action
   (`location`, `status` ∈ {301, 302, 303, 307, 308}, `keep_query`, `allow_external`), `base_url` no longer
   unconditionally required (`endpoint.schema.json`, `RouteConfig`, `EndpointConfig`).
2. AS-3/AS-4 route table — third terminal action and exact matches in `RouteTableBuilder`; exact beats
   prefix in route selection; `base_url` required at boot exactly when an endpoint has a proxy route.
3. AS-3 dispatch and validation — redirect responses without upstream contact; boot errors for external
   `location` without `allow_external` and invalid status; open-redirect review (`//host`, backslashes,
   encoded slashes, context path).
4. AS-9 — decide append vs replace against every in-repo `upstream.path` usage (gRPC, benchmarks, IT
   configs); align `applyRouteUpstreamPath` and its Javadoc; ADR if semantics change; migrate configs.
5. AS-11 — `upstream.rewrite_location`: in `ResponseStage`, map a `Location` whose path starts with the
   upstream base path onto the route prefix; never rewrite foreign origins; keep query/fragment.
6. AS-12 — `asset.index` / `asset.fallback` in `DirectoryAssetSource` (fallback only for unknown
   extensionless paths), traversal and symlink protections unchanged.
7. AS-8 pipeline — apply security headers with the selected route's anchor context (after route
   selection) while keeping the CORS preflight short-circuit before authentication; update the stage-order
   docs (ADR if the documented pipeline order changes); rejections before route selection keep the global
   block.
8. AS-8 headers — wire the already-resolved per-route headers (`RouteRuntime`) into response application;
   add `content_security_policy` (schema + `SecurityHeadersConfig`), resolved anchor-before-global.
9. AS-8 precedence — per-header mode `set` | `default` relative to origin headers in `ResponseStage`.
10. Tests — acceptance sets of AS-3, AS-4, AS-8, AS-9, AS-11, AS-12 (unit + IT), native-image coverage of
    the new config records, preflight behaviour unchanged.
11. Documentation — `doc/user/endpoint-routes.adoc`, `doc/user/anchors.adoc`, `doc/configuration.adoc`,
    `doc/architecture.adoc` (pipeline order), `doc/LogMessages.adoc` for new records.

Split guard: 11 deliverables — within the operator-authorized 12 per plan.

## Claim Labels

- OBSERVED: route `match` supports only `path_prefix`; terminal actions are `asset` XOR `upstream`; no `redirect` exists — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `routes[].match`, `routes[].asset`, `routes[].upstream` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRoute`
- OBSERVED: `base_url` is unconditionally required — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § endpoint `required` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java` § canonical constructor
- OBSERVED: `applyRouteUpstreamPath` replaces the alias base path; its Javadoc justifies replace for gRPC and benchmark routes — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `applyRouteUpstreamPath`
- OBSERVED: `ResponseStage.relay` copies `Location` unchanged and sets gateway headers with `.set()` after upstream headers (gateway overwrites origin) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` § `relay`
- OBSERVED: `DirectoryAssetSource.serve` requires `Files.isRegularFile`, no index/fallback; extensionless default content type is `application/octet-stream` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` § `serve` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/AssetResponseEnvelope.java` § `DEFAULT_CONTENT_TYPE`
- OBSERVED: `RouteTableBuilder.resolveSecurityHeaders` resolves anchor-before-global per route, but no main-code call site reads `RouteRuntime.getSecurityHeaders()` (orchestrator grep at fb9e774) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveSecurityHeaders` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java`
- OBSERVED: `SecurityHeadersStage` is built once from the global block and runs at stage 0 before route selection — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § constructor and class-level pipeline Javadoc
- HYPOTHESIS: the ADR-0014 terminal-action invariant is enforced by a two-way code branch needing a third arm — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRoute` (verify-at-outline)
- HYPOTHESIS: route selection has no exact-match concept — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/` § route selection (verify-at-outline)
- HYPOTHESIS: preflight short-circuit and header application split cleanly into two stages without making CORS anchor-scoped — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java` § `process` (verify-at-outline)
- HYPOTHESIS: in-repo gRPC / benchmark routes depend on replace semantics — confirm/refute at `integration-tests/src/main/docker/sheriff-config/endpoints/` and `benchmarks/` route YAML declaring `upstream.path` (verify-at-outline)
- Verify-first clause: settle the terminal-action arm, exact-match precedence, the stage split and the `upstream.path` decision before scoping; AS-11's rewrite uses the AS-9 decision, so deliverable 4 precedes deliverable 5. If the stage split forces CORS to become anchor-scoped (not requested), loop back and re-scope deliverable 7.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — security headers definition
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` — `RouteConfig`, `EndpointConfig`, upstream/asset/redirect configs, `SecurityHeadersConfig`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — pipeline order, redirect dispatch
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` — `relay`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/` — exact-match precedence (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rules (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/RouteTableBuilderTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/pipeline/` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/asset/` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` — IT route configs (verify-at-outline)
- HYPOTHESIS: `benchmarks/` — benchmark route configs using `upstream.path` (verify-at-outline)
- HYPOTHESIS: `doc/adr/` — ADRs for pipeline order / `upstream.path` (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/user/anchors.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/architecture.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none. Launched first on 2026-09-15 by operator decision while PLAN-13 waits for cuioss/cui-http#236; PLAN-13 and PLAN-14 are sequenced after this plan because they share `GatewayEdgeRoute.java`, `config/model/`, `endpoint.schema.json` and docs
- Overlaps with: PLAN-16 (`endpoint.schema.json`, `gateway.schema.json`, `GatewayEdgeRoute.java`, `DirectoryAssetSource.java`) — this plan first, so the portal composes its CSP with the new precedence mode
- Adjacent to: PLAN-16's consumer migration uses the redirect action

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-15-routing-and-headers.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
