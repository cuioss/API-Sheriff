# WS-03: Application Portal

epic: kidicap-gateway-requirements

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-03-application-portal.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Give API Sheriff a notion of an "application": an `endpoint.catalog` block, a reserved `portal.path`
rendering an HTML overview of active applications (AS-1), and HTML error pages for gateway-originated
errors on page navigations reusing that template (AS-2). Closed when PLAN-16 lands. The AS-1 second stage
(`catalog.visible_when`, circuit-breaker state) stays out of scope unless promoted by a decision.

## Scope

- In scope: `portal` and `catalog` config, reserved-path registration, template rendering and escaping,
  the portal response envelope, error-rendering content negotiation, portal user documentation.
- Out of scope: redirects, optional `base_url`, security-header precedence (WS-02); login default return
  URL (WS-04).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-16-application-portal | staged | AS-1 + AS-2; 9 deliverables |

Superseded at aggregation (2026-09-15): PLAN-07, PLAN-08 — see `plans/superseded/README.md`.

## Sequencing and Surface Notes

- Queue tail: depends on PLAN-15 (schemas, edge pipeline, header precedence mode).
