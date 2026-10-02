# WS-02: Routing Primitives and Response Headers

epic: kidicap-gateway-requirements

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-02-routing-primitives.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Extend the route table and response path: exact-path routes with a `redirect` action (AS-3), optional
`base_url` (AS-4), aligned `upstream.path` semantics (AS-9), upstream `Location` rewrite (AS-11),
directory-asset index/fallback (AS-12), and anchor-scoped security headers with CSP and precedence mode
(AS-8). Closed when PLAN-15 lands. Absorbed the former WS-05 (security headers) at aggregation.

## Scope

- In scope: `endpoint.schema.json` route/asset/upstream definitions, `gateway.schema.json` security
  headers, `RouteTableBuilder`, route selection, config model records, redirect dispatch and pipeline order
  in the edge, `ResponseStage`, `SecurityHeadersStage`, `DirectoryAssetSource`, boot rules,
  `doc/user/endpoint-routes.adoc`, `doc/user/anchors.adoc`.
- Out of scope: the portal and its reserved path (WS-03), session/scope behaviour (WS-04), CORS semantics
  beyond keeping the preflight short-circuit working.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-15-routing-and-headers | staged | AS-3, AS-4, AS-8, AS-9, AS-11, AS-12; 11 deliverables |

Superseded at aggregation (2026-09-15): PLAN-04, PLAN-06, PLAN-11, PLAN-12 — see `plans/superseded/README.md`.

## Sequencing and Surface Notes

- Runs after PLAN-13 and PLAN-14 (surface overlap on `GatewayEdgeRoute.java`, schemas, docs).
- Runs before PLAN-16 (portal CSP composes with the precedence mode; shared schemas and edge).
