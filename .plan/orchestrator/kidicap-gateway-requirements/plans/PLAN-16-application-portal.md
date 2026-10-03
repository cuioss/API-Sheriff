# PLAN-16: Application Portal — Catalog, Overview Page and HTML Error Pages

epic: kidicap-gateway-requirements
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-16-application-portal.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Aggregates superseded specs PLAN-07, PLAN-08 (see `plans/superseded/`).

## Objective

API Sheriff knows endpoints but no "application", has no template engine and no gateway-owned HTML page
besides asset routes, cannot claim a context root without a catch-all prefix route, and answers every
gateway error with `problem+json`, even for browser navigations. This plan adds a reserved, exactly
matched `portal.path` rendering an escaped overview of active `endpoint.catalog` entries from a built-in
or operator-supplied template (AS-1), and HTML error pages for gateway-originated errors on page
navigations reusing that template (AS-2).

## Source

KIDICAP Gateway requirements (`archive/api-sheriff-aenderungen.adoc`), translated.

**AS-1 — application catalog and overview page (priority high).**
- Baseline: no "application" concept; the endpoint schema forbids extra keys (`additionalProperties: false`),
  so titles or entry addresses cannot be recorded. No template engine, no gateway-owned HTML except asset
  routes. The context root `/KIDICAP.Gateway/` answers `404` and cannot be claimed without a prefix route
  swallowing every unknown address (measured). `enabled` is switchable per deployment via
  `${ENDPOINT_<ID>_ENABLED}` (`doc/user/environment-variable-overrides.adoc`) — a build-time overview would
  drift.
- Need: a start page listing all active applications, also the post-logout target, under a fixed short
  address — ideally the context root.

```yaml
# gateway.yaml
portal:
  path: /KIDICAP.Gateway/            # exact match, reserved like the OIDC paths
  title: KIDICAP
  template_dir: /app/sheriff-config/portal   # optional; without it: built-in template
  cache_seconds: 0

# endpoints/<code>-app.yaml
endpoint:
  id: k-beispiel-app
  enabled: ${ENDPOINT_K_BEISPIEL_APP_ENABLED:-true}
  catalog:
    title: K.Beispiel
    description: Short description for the overview
    entry: /KIDICAP.Gateway/app/K.Beispiel/
    order: 10
```

- Reserved path: `portal.path` checked exactly and before the route table, like the OIDC paths
  (`ReservedPathRegistry`).
- Catalog: a `catalog` block makes an endpoint an entry, active when the endpoint is `enabled` after
  placeholder resolution; sort by `order`, then `title`.
- Template: built-in; optionally a directory with an own template and static files. Values ALWAYS
  HTML-escaped. Fixed data model (a contract):

| Field | Content |
|---|---|
| `title` | `portal.title` |
| `apps[]` | `title`, `description`, `entry` |
| `session.authenticated`, `session.username` | from the session, `username` from `preferred_username` |
| `links.login`, `links.logout` | the reserved `oidc` paths, `login` with `returnUrl` to the overview |
| `notice` | hint from a fixed list, e.g. `logged-out`; never free text from the request |
| `context_path` | the built context path |

- Logout: `oidc.logout.final_redirect` may point at `portal.path?notice=logged-out`; the page shows
  "logged out".
- Response: `text/html; charset=utf-8`, own `Content-Security-Policy` (`default-src 'self'`, no inline
  scripts), `nosniff`, `Cache-Control: no-store` once session data is in the HTML; `GET`/`HEAD` only.
- Scope limits: second stage, NOT in this plan — per-user visibility (`catalog.visible_when`, needs AS-7)
  and circuit-breaker application state (discloses internals).
- Acceptance: boot error on `portal.path` overlapping other reserved paths or `entry` outside the own
  origin; overview lists exactly the active entries, also after toggling `ENDPOINT_<ID>_ENABLED`; escaping
  test with special characters in the title; CSP header present; native image.

**AS-2 — HTML error pages for page navigations (priority medium).**
- Baseline: gateway errors always `application/problem+json`, also for page navigations (measured:
  unknown route in the context); a missing file on a directory asset route answers `404` without body and
  `Content-Type` (measured); a failed login callback shows JSON.
  ⛔ **Corrected 2026-09-21 (cleanup re-grounding at 3abc370):** a failed callback does not show JSON —
  `CallbackEndpoint.CallbackOutcome.error(status)` carries a null location and the render path passes a
  null body, so it answers a bare status code with NO body at all. The AS-2 need stands; the baseline
  sentence was wrong.
- Proposal: `portal.error_pages: true`: when the gateway ITSELF errors (no route, `403`, `413`,
  `502`/`503`/`504` from the circuit breaker, failed callback) and `Accept` offers `text/html`, render the
  AS-1 template with `error.status` and `error.title`; all other requests keep `problem+json`; origin error
  responses are NEVER replaced. No problem details (stack trace, upstream address) in the HTML.
- Acceptance: same request with `Accept: text/html` → HTML, with `Accept: application/json` →
  `problem+json`; status identical.

Consumer migration (context only): static start page removed, `catalog` blocks added, `final_redirect` to
`portal.path`, old start address redirected.

## Deliverables

1. Config and boot validation — `portal` block (`gateway.schema.json`, config record incl. `path`, `title`,
   `template_dir`, `cache_seconds`, `error_pages`) and `endpoint.catalog` (`endpoint.schema.json`,
   `EndpointConfig`); boot errors for reserved-path overlap and cross-origin `entry`.
2. Reserved path — exact pre-route-table dispatch of `portal.path`; decide whether the portal works
   without `oidc` (no login/logout links, `session.authenticated=false`) and needs its own registration
   seam.
3. Catalog resolution — active entries from post-placeholder `enabled`, sorted by `order`, `title`.
4. Template rendering — built-in template plus optional `template_dir` with static files; mandatory HTML
   escaping; the fixed data model. Engine must be native-image compatible; a new dependency needs explicit
   user approval (prefer a minimal in-repo renderer or an extension already in the dependency tree).
5. Response envelope — content type, CSP composed with PLAN-15's precedence mode, `nosniff`, `no-store`
   with session data, `GET`/`HEAD` only (`405` otherwise), fixed-vocabulary `notice`, logout landing.
6. AS-2 — `portal.error_pages` and content negotiation in the gateway's own error rendering
   (`renderProblem` / rejection paths, callback failures, directory-asset 404) with a fixed `error.title`
   per status and no problem detail.
7. AS-2 — exhaustive, tested classification of gateway-originated vs relayed-upstream errors so origin
   responses are never intercepted.
8. Tests — AS-1 acceptance (unit, IT, native image, escaping with special characters, toggling via
   `ENDPOINT_<ID>_ENABLED`) and AS-2 acceptance per error class, headers/CSP on HTML errors.
9. Documentation — new portal page under `doc/user/`, `doc/configuration.adoc`, `doc/LogMessages.adoc`,
   `doc/security-threat-model.adoc` (new HTML surface).

Split guard: 9 deliverables — within the operator-authorized 12 per plan.

## Claim Labels

- OBSERVED: no `catalog` / `portal` key exists in either schema and `additionalProperties: false` is enforced — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` and `api-sheriff/src/main/resources/schema/gateway.schema.json`
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: endpoint and gateway schema diff 3abc370..3e3addc adds no catalog/portal key; additionalProperties false still enforced
- OBSERVED: `EndpointConfig` has no catalog field — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: EndpointConfig.java diff only adds the PLAN-14 scopes field; no catalog component
- OBSERVED: `ReservedPathRegistry` is the exact-match, pre-route-table mechanism, built from `OidcConfig` into a closed `ReservedEndpoint` set — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java`
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: ReservedPathRegistry.java unchanged 3abc370..3e3addc
- OBSERVED: gateway-originated rejections render `application/problem+json` without content negotiation — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `renderProblem`
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: GatewayEdgeRoute.java unchanged; renderProblem still unconditional problem+json
- HYPOTHESIS: an unmatched request (e.g. the bare context root) yields `NO_ROUTE_MATCHED` `404` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `process` no-route branch (verify-at-outline)
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: GatewayEdgeRoute.java unchanged; NO_ROUTE_MATCHED to 404 path stands
- HYPOTHESIS: no template engine is on the runtime classpath — confirm/refute at `api-sheriff/pom.xml` § dependencies (verify-at-outline)
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: api-sheriff/pom.xml unchanged; still no template/qute/freemarker/thymeleaf dependency
- HYPOTHESIS: the directory-asset `404` carries no body and no `Content-Type` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` § `serve` not-found branches (verify-at-outline)
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: DirectoryAssetSource.java unchanged; not-found response unchanged
- OBSERVED: a failed callback renders through `BffRuntime.render(CallbackEndpoint.CallbackOutcome)` → `GatewayEdgeRoute.renderReserved`, a path wholly separate from `renderProblem`, and answers a bare status with no body — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java` § `render(CallbackOutcome)`
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntime.java unchanged (only package-info touched); render(CallbackOutcome) unaffected
- OBSERVED (superseded, kept so it is not re-derived): failed login callbacks render outside `renderProblem` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` § callback error handling (verify-at-outline)
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntime.java unchanged; callback rendering still separate from renderProblem
- Verify-first clause: settle the portal-without-OIDC question and enumerate every gateway-originated error exit before scoping; loop back if any exit relays an upstream status through the same renderer.
  - verdict: corroborated | checked_at: 3e3addc | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: GatewayEdgeRoute.java unchanged; renderProblem vs ResponseStage.relay split unaffected

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `portal`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `endpoint.catalog`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — reserved dispatch, `renderProblem`, `renderReserved`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/SecurityHeadersStage.java` — deliverable 5 composes the portal CSP with PLAN-15's precedence mode (added 2026-09-21)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java` — callback rendering
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java` — not-found response (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` — callback failure rendering (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/` — new package (name decided at outline) (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/resources/portal/` — built-in template (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rules (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/` — CDI wiring (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/` — IT portal config (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — portal ITs (verify-at-outline)
- HYPOTHESIS: `doc/user/` — new portal page (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-15 (schema and pipeline surface; the portal CSP composes with PLAN-15's header precedence mode)
- Overlaps with: PLAN-13, PLAN-14, PLAN-15 (`GatewayEdgeRoute.java`, schemas, docs) — queue tail
- Adjacent to: PLAN-14's `default_return_url` may point at `portal.path`; AS-1 second stage (`visible_when`) not staged

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-16-application-portal.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
