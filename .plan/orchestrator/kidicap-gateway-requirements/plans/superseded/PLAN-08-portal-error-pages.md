# PLAN-08: HTML Error Pages for Page Navigations

epic: kidicap-gateway-requirements
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-08-portal-error-pages.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

Gateway-originated errors always render as `application/problem+json`, even for a browser page
navigation, and a missing file on a directory asset route answers `404` without body or content type
(AS-2). This plan adds `portal.error_pages: true`: when the gateway ITSELF answers with an error and the
request accepts `text/html`, it renders the portal template (PLAN-07) with `error.status` and
`error.title`; every other request keeps `problem+json`, and an origin's own error responses are never
replaced.

## Source

KIDICAP Gateway requirements AS-2 (`archive/api-sheriff-aenderungen.adoc` § AS-2), priority medium.

- Baseline: gateway errors always come as `application/problem+json`, also for page navigations
  (measured: unknown route within the context). A missing file on a directory asset route answers `404`
  even without body and `Content-Type` (measured). If the login callback fails, the user sees JSON.
- Need: for page navigations a comprehensible page with a way back to the overview.
- Proposal: `portal.error_pages: true`: if the gateway itself answers with an error (no route, `403`,
  `413`, `502`/`503`/`504` from the circuit breaker, failed callback) and `Accept` offers `text/html`,
  render the AS-1 template with `error.status` and `error.title`. All other requests keep
  `problem+json`. Origin error responses are NOT replaced.
- Scope limits: no problem details (stack trace, upstream address) in the HTML.
- Acceptance: same request with `Accept: text/html` → HTML, with `Accept: application/json` →
  `problem+json`; status identical.

## Deliverables

1. `portal.error_pages` switch and content negotiation in the gateway's own error rendering
   (`renderProblem` / rejection paths, reserved-path and callback failures, directory-asset 404), reusing
   PLAN-07's renderer and envelope; only a fixed `error.title` per status, no problem detail.
2. An exhaustive, tested classification of gateway-originated vs. relayed-upstream errors so origin
   responses are never intercepted.
3. Tests for the acceptance set per error class (no route, 403, 413, breaker 5xx, failed callback,
   directory-asset 404), header/CSP presence on HTML errors, and docs in the portal user page and
   `doc/configuration.adoc`.

## Claim Labels

- OBSERVED: gateway-originated rejections render `application/problem+json` unconditionally, without content negotiation — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `renderProblem`
- HYPOTHESIS: the directory-asset `404` carries no body and no `Content-Type` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` § `serve` not-found branches (verify-at-outline)
- HYPOTHESIS: failed login callbacks render through a separate reserved-endpoint path, not `renderProblem` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` § callback endpoint error handling (verify-at-outline)
- Verify-first clause: enumerate every gateway-originated error exit before scoping; loop back if any relays an upstream status through the same renderer.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — `renderProblem`, rejection handling
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` — not-found response (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` — callback failure rendering (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/` — renderer reuse (package from PLAN-07) (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `portal.error_pages` (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — `portal.error_pages` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-07 (template mechanism and `portal` block)
- Overlaps with: PLAN-07, PLAN-02, PLAN-04, PLAN-06 (`GatewayEdgeRoute.java`); PLAN-12 (`DirectoryAssetSource.java`)
- Adjacent to: none

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-08-portal-error-pages.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
