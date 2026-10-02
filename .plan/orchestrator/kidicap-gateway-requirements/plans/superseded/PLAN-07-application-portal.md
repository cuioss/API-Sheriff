# PLAN-07: Application Catalog and Portal Page

epic: kidicap-gateway-requirements
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-07-application-portal.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

API Sheriff knows endpoints but no "application", has no template engine and no gateway-owned HTML page
besides asset routes, and a context root cannot be claimed without a catch-all prefix route (AS-1). This
plan adds a reserved, exactly matched `portal.path` rendering an overview of all active applications
(`endpoint.catalog` entries whose endpoint resolves `enabled` after placeholder resolution) from a
built-in or operator-supplied template with a fixed, escaped data model and a hardened HTML response
envelope — usable as start page and as the post-logout landing page.

## Source

KIDICAP Gateway requirements AS-1 (`archive/api-sheriff-aenderungen.adoc` § AS-1), priority high.

- Baseline: no "application" concept, only endpoints; the endpoint schema forbids extra keys
  (`additionalProperties: false`), so titles or entry addresses cannot be recorded. No template engine,
  no gateway-owned HTML except asset routes. The context root `/KIDICAP.Gateway/` answers `404` and cannot
  be claimed without a prefix route that swallows every unknown address (measured). An endpoint's
  `enabled` is switchable per deployment via `${ENDPOINT_<ID>_ENABLED}` (`doc/user/environment-variable-overrides.adoc`)
  — a build-time overview would drift from that.
- Need: a start page listing all active applications, also the target after logout, under a fixed short
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

- Reserved path: `portal.path` is checked exactly and before the route table, like the OIDC paths
  (`ReservedPathRegistry`) — the context root becomes claimable without a catch-all.
- Catalog: a `catalog` block makes an endpoint an entry; the entry is active when the endpoint is
  `enabled` after placeholder resolution. Sort by `order`, then `title`.
- Template: built-in template; optionally a directory with an own template and static files. Values are
  ALWAYS HTML-escaped. Fixed data model (a contract):

| Field | Content |
|---|---|
| `title` | `portal.title` |
| `apps[]` | `title`, `description`, `entry` |
| `session.authenticated`, `session.username` | from the session, `username` from `preferred_username` |
| `links.login`, `links.logout` | the reserved `oidc` paths, `login` with `returnUrl` pointing at the overview |
| `notice` | a hint from a fixed list, e.g. `logged-out`; never free text from the request |
| `context_path` | the built context path |

- Logout: `oidc.logout.final_redirect` may point at `portal.path` with `?notice=logged-out`; the page then
  shows "logged out".
- Response: `text/html; charset=utf-8`, own `Content-Security-Policy` (`default-src 'self'`, no inline
  scripts), `nosniff`, `Cache-Control: no-store` as soon as session data is in the HTML. `GET` and `HEAD`
  only.
- Scope limits: second stage, NOT part of this plan — per-user visibility (`catalog.visible_when` over
  claims or roles, requires AS-7 / PLAN-01) and application state from the circuit breaker (caution:
  discloses internals).
- Acceptance: boot error when `portal.path` overlaps other reserved paths or when `entry` is outside the
  own origin; the overview lists exactly the active entries, also after toggling via
  `ENDPOINT_<ID>_ENABLED`; escaping test with special characters in the title; CSP header present;
  native image.
- Consumer migration (context only): the static start page is removed, `catalog` blocks added,
  `final_redirect` pointed at `portal.path`, the old start address redirected via AS-3 (PLAN-04).

## Deliverables

1. Config + boot validation: `portal` block (`gateway.schema.json`, new config record) and
   `endpoint.catalog` (`endpoint.schema.json`, `EndpointConfig`); boot errors for reserved-path overlap and
   cross-origin `entry`; decide at outline whether the portal requires the `oidc` block (see claims).
2. Reserved path + catalog resolution: register `portal.path` for exact pre-route-table dispatch;
   resolve active entries from post-placeholder `enabled`, sorted by `order`, `title`.
3. Template rendering: built-in template and optional `template_dir` with static files; mandatory
   HTML escaping; the fixed data model above; native-image compatible choice of engine (a new dependency
   needs explicit user approval per CLAUDE.md — prefer a minimal in-repo renderer or an existing Quarkus
   extension already in the dependency tree).
4. Response envelope: content type, CSP, `nosniff`, `no-store` with session data, `GET`/`HEAD` only
   (`405` otherwise), `notice` from a fixed vocabulary only; logout landing via `?notice=logged-out`.
5. Tests for the acceptance set (unit, integration, native image, escaping with special characters,
   toggling via `ENDPOINT_<ID>_ENABLED`), user documentation (new portal page under `doc/user/`,
   `doc/configuration.adoc`), `doc/LogMessages.adoc` records, threat-model entry for the HTML surface.

Split-guard: five deliverables — proceeds unsplit (recorded as epic decision): reserved path, catalog,
rendering and envelope cannot ship independently to any observable benefit.

## Claim Labels

- OBSERVED: no `catalog` / `portal` key exists in either schema and `additionalProperties: false` is enforced — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json` and `api-sheriff/src/main/resources/schema/gateway.schema.json`
- OBSERVED: `EndpointConfig` has no catalog field — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`
- OBSERVED: `ReservedPathRegistry` is the exact-match, pre-route-table mechanism for OIDC paths, built from `OidcConfig` into a closed `ReservedEndpoint` set — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java`
- HYPOTHESIS: an unmatched request (e.g. the bare context root) yields `NO_ROUTE_MATCHED` `404` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § `process` no-route branch (verify-at-outline)
- HYPOTHESIS: no template engine is on the runtime classpath today — confirm/refute at `api-sheriff/pom.xml` § dependencies (verify-at-outline)
- Verify-first clause: `ReservedPathRegistry` exists only when BFF/OIDC is configured. Settle whether the portal must work without `oidc` (bearer-only gateway: no login/logout links, `session.authenticated=false`) and therefore needs its own registration seam, before scoping deliverable 2.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `portal`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `endpoint.catalog`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java` — `catalog`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — reserved dispatch
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/portal/` — new package for catalog + rendering (name decided at outline) (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/resources/portal/` — built-in template (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rules (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/` — CDI wiring (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/` — IT portal config (verify-at-outline)
- HYPOTHESIS: `doc/user/` — new portal page (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — `portal` and `catalog` keys (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` — new records (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` — HTML surface (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-04 and PLAN-06 by surface only (shared `endpoint.schema.json`, `gateway.schema.json`, `GatewayEdgeRoute.java` pipeline wiring) — sequenced after both; no functional dependency
- Overlaps with: PLAN-04, PLAN-06, PLAN-08 (`GatewayEdgeRoute.java`), PLAN-02 (`GatewayEdgeRoute.java`)
- Adjacent to: PLAN-08 reuses this plan's template mechanism; PLAN-03's `default_return_url` may point here

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-07-application-portal.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
