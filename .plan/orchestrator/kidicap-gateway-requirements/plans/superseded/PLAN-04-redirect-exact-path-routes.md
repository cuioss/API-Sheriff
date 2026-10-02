# PLAN-04: Redirect Action, Exact-Path Routes and Optional `base_url`

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-04-redirect-exact-path-routes.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

Routes only know `path_prefix` and only the terminal actions `asset` and `upstream`, and every endpoint
must declare a resolvable `base_url` even when no route proxies to it (AS-3, AS-4). This plan adds an
exact `match.path` (winning over a prefix route for the same address), a `redirect` terminal action with
same-origin enforcement, and makes `base_url` required only for endpoints that have at least one proxy
route — so entry addresses without trailing slash, short addresses and moved addresses can be redirected,
and asset/redirect-only endpoints boot without a borrowed alias.

## Source

KIDICAP Gateway requirements AS-3 (priority high) and AS-4 (priority medium)
(`archive/api-sheriff-aenderungen.adoc` § AS-3, § AS-4).

**AS-3 — redirect action and exact-path routes.** Baseline: routes know only `path_prefix`; no action
answers a redirect (code: route schema). A static HTML page cannot set `Location` — it arrives as `200`
(measured); only `meta refresh` works. `/app/<code>` and `/app/<code>/` go to different targets (measured);
without the trailing slash, relative URLs resolve outside the application. Need: steer the no-slash entry
to the slash form, short addresses, address moves without breakage.

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

- `match.path` compares exactly; for the same address an exact route wins over a prefix route.
- `redirect` is a terminal action like `upstream` or `asset`, allowed under every anchor type.
- `location` is a same-origin path; absolute URLs only with explicit `allow_external: true` (no open
  redirect). No request-derived placeholders except the query under `keep_query`.
- Acceptance: exact route beats prefix route; query is kept; boot failure for an external `location`
  without the opt-in.

**AS-4 — `base_url` optional for endpoints without upstream.** Baseline: every endpoint needs a resolvable
`base_url` even if no route goes there (code: schema, required). Proposal: `base_url` required only when a
route of the endpoint needs an upstream (proxy); directory-source asset routes and redirect routes do
without.

Consumer migration (context only): per-application entry route without trailing slash; old start-page
address redirects to the portal; the borrowed alias is removed from the gateway-pages endpoint.

## Deliverables

1. Schema + model: `match.path` (mutually exclusive with `match.path_prefix`), `redirect` terminal action
   (`location`, `status` ∈ {301, 302, 303, 307, 308}, `keep_query`, `allow_external`), `base_url` no longer
   unconditionally required (`endpoint.schema.json`, `RouteConfig`, `EndpointConfig`).
2. Route table: `RouteTableBuilder` resolves the third terminal action and exact matches; route selection
   gives exact matches precedence over prefix matches for the same address; `base_url` is required at
   boot exactly when an endpoint has a proxy route (clear boot error otherwise).
3. Edge dispatch: redirect responses (status, `Location`, optional query carry-over) without upstream
   contact and without request-derived placeholders.
4. Boot validation: external `location` without `allow_external: true` fails boot; invalid status fails.
   Security review of open-redirect vectors (scheme-relative `//host`, backslashes, encoded slashes).
5. Tests for the AS-3 acceptance set and base_url-less endpoints (unit + integration), native-image
   coverage, and user docs in `doc/user/endpoint-routes.adoc` and `doc/configuration.adoc`.

## Claim Labels

- OBSERVED: route `match` supports only `path_prefix` and terminal actions are `asset` XOR `upstream`; no `redirect` exists — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `routes[].match`, `routes[].asset`, `routes[].upstream`
- OBSERVED: `base_url` is unconditionally required — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` § endpoint `required` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java` § canonical constructor (`Objects.requireNonNull(baseUrl, "baseUrl")`)
- OBSERVED: no `redirect` handling exists in route resolution — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRoute`
- HYPOTHESIS: the "exactly one terminal action" invariant (ADR-0014) is enforced in code with a two-way branch that needs a third arm, not only a schema change — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveRoute` and `doc/adr/` ADR-0014 (verify-at-outline)
- HYPOTHESIS: route selection is longest-prefix based and has no exact-match concept — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/` § route selection / `RouteSelectionStage` (verify-at-outline)
- Verify-first clause: settle how exact-match precedence integrates with route selection and whether the terminal-action discriminator needs structural change before scoping; also confirm the context-path handling (`doc/user/context-path.adoc`) for same-origin `location` validation.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `match.path`, `redirect`, `base_url` required-ness
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — `resolveRoute`, alias resolution
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/` — `RouteConfig`, `EndpointConfig`, new redirect config record
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — redirect dispatch branch
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/` — exact-match precedence in route selection (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rules (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/RouteTableBuilderTest.java`
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` — IT redirect endpoint (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` — route documentation (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — route keys (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none
- Overlaps with: PLAN-12 (same schema file, `RouteTableBuilder.java`, config model) — this plan first; PLAN-07 (`endpoint.schema.json`) — this plan first; PLAN-06 / PLAN-11 (`RouteTableBuilder.java`, other methods); PLAN-02 (`GatewayEdgeRoute.java`, other region)
- Adjacent to: PLAN-07's consumer migration uses redirects, but PLAN-07's implementation does not require this plan

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-04-redirect-exact-path-routes.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
