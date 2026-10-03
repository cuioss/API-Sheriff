# PLAN-06: Anchor-Scoped `security_headers`, CSP Key and Precedence Mode

epic: kidicap-gateway-requirements
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-06-security-response-headers.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

`security_headers` on an anchor is documented as effective but never reaches the response (AS-8): the
route table resolves anchor-before-global per route, yet nothing reads that value — the runtime
`SecurityHeadersStage` is built once from the global block and runs at pipeline stage 0, before route
selection, and it overwrites same-named origin headers. This plan makes the anchor block effective
(anchor before global), adds a `content_security_policy` key, and adds a per-header mode `set`
(overwrite) / `default` (set only if the origin did not), so frontends can keep their own CSP while the
gateway still enforces a floor.

## Source

KIDICAP Gateway requirements AS-8 (`archive/api-sheriff-aenderungen.adoc` § AS-8), priority medium.

- Baseline: `security_headers` on the anchor is effective per documentation; in fact only the global
  block is applied (measured). Global overwrites same-named origin headers (measured). There is no key for
  `Content-Security-Policy`.
- Proposal: anchor block effective (precedence anchor over global); key `content_security_policy`; per
  header a mode `set` (overwrite) or `default` (only set if the origin has not set it).
- Acceptance: an anchor `frame_deny` applies only under that anchor; `default` leaves an origin header in
  place.
- Consumer migration (context only): common headers refined per anchor, `api` and `app` separated.

## Deliverables

1. Pipeline restructuring: apply security headers with the selected route's anchor context (after route
   selection) while keeping the CORS preflight short-circuit early enough to answer before
   authentication — likely split into two stages. Update the stage-order documentation in
   `GatewayEdgeRoute` and `doc/architecture.adoc`; ADR if the documented pipeline order changes.
2. Wire the already-resolved per-route headers (`RouteRuntime` security headers) into response
   application; rejections before route selection (no route) keep the global block.
3. `content_security_policy` key (schema + `SecurityHeadersConfig`) resolved anchor-before-global like
   the other headers.
4. Per-header precedence mode `set` | `default` applied in `ResponseStage` relative to origin headers.
5. Tests for the acceptance set plus: gateway-originated error responses still carry headers; preflight
   behaviour unchanged; docs in `doc/user/anchors.adoc` and `doc/configuration.adoc`.

## Claim Labels

- OBSERVED: `RouteTableBuilder.resolveSecurityHeaders` resolves anchor-before-global into the resolved route, carried to the route runtime — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` § `resolveSecurityHeaders` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/RouteRuntimeAssembler.java`
- OBSERVED: no main-code call site reads `RouteRuntime.getSecurityHeaders()` (grep over `api-sheriff/src/main`, re-confirmed by the orchestrator at fb9e774) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java` § `securityHeaders`
- OBSERVED: `SecurityHeadersStage` is constructed once from the global `gatewayConfig.securityHeaders()` and runs at stage 0 before route selection — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § constructor (≈ line 366) and class-level pipeline-order Javadoc
- OBSERVED: `ResponseStage.relay` sets gateway headers with `.set()` after copying upstream headers, so gateway values overwrite origin values — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` § `relay`
- OBSERVED: no `content_security_policy` key exists in the schema or `SecurityHeadersConfig` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityHeadersConfig.java`
- HYPOTHESIS: preflight short-circuit and header application can be split cleanly into two stages without making CORS anchor-scoped — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java` § `process` (verify-at-outline)
- Verify-first clause: settle the stage split first; loop back and re-scope deliverable 1 if CORS must move too (not requested by the source).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — pipeline construction and order
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/SecurityHeadersConfig.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` — `relay`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — `resolveSecurityHeaders`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — security headers definition
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/pipeline/` — stage tests (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/` — response tests (verify-at-outline)
- HYPOTHESIS: `doc/user/anchors.adoc` — anchor `security_headers` documentation (verify-at-outline)
- HYPOTHESIS: `doc/architecture.adoc` — pipeline order (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — header keys (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none
- Overlaps with: PLAN-07 (`gateway.schema.json`, `GatewayEdgeRoute.java` pipeline) — this plan first; PLAN-04 / PLAN-11 / PLAN-12 (`RouteTableBuilder.java`, other methods); PLAN-02 / PLAN-08 (`GatewayEdgeRoute.java`)
- Adjacent to: PLAN-07 sets its own CSP on the portal response and must compose with the mode introduced here

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-06-security-response-headers.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
