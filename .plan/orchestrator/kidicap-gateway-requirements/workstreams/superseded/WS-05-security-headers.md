# WS-05: Security Response Headers

epic: kidicap-gateway-requirements

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-05-security-headers.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Make anchor-scoped `security_headers` actually apply (today only the global block reaches the
response), add a `content_security_policy` key, and add a per-header `set` / `default` precedence mode
(AS-8). Closed when the single plan lands.

## Scope

- In scope: `SecurityHeadersStage` placement in the pipeline, `SecurityHeadersConfig`, `ResponseStage`
  header application, the currently unread `RouteRuntime` security-header resolution,
  `gateway.schema.json` security-header definitions, `doc/user/anchors.adoc`.
- Out of scope: the portal page's own CSP (WS-03 PLAN-07 sets it on its own response), CORS behaviour
  beyond keeping preflight short-circuit working.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-06-security-response-headers | staged | AS-8 — pipeline-stage restructuring is the central decision |

## Sequencing and Surface Notes

- Overlaps PLAN-07 (`gateway.schema.json`, `GatewayEdgeRoute.java` pipeline wiring) — run before PLAN-07.
- Overlaps PLAN-04 / PLAN-11 / PLAN-12 on `RouteTableBuilder.java` (different methods).
