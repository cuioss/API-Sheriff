# PLAN-14: Session and Scopes — Token-Relay Opt-Out, Return URL, Endpoint Scopes with Step-Up

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-14-session-and-scopes.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Aggregates superseded specs PLAN-03, PLAN-05, PLAN-09 (see `plans/superseded/`).

## Objective

Rework BFF session and OIDC scope behaviour on one shared surface (`SessionAuthenticationStage`, `auth`
config, login flow): add `auth.token_relay` so session routes can enforce login without handing the
origin a token (AS-5); make the post-login return URL keep the query and make its default configurable
(AS-6); replace the single gateway-wide scope list and `auth.required_scopes` with additive
`oidc.scopes ∪ endpoint.scopes`, requested minimally at login (AS-14); and keep the session's active
scope set alive across refreshes. **Re-scoped 2026-09-17** from the downstream's decision: obtaining a
missing scope is driven by the upstream's own `insufficient_scope` challenge, not by a gateway-side
pre-check — that flow is PLAN-20 and is no longer part of this plan.

## Source

KIDICAP Gateway requirements (`archive/api-sheriff-aenderungen.adoc`), translated.

**AS-5 — check the session without relaying the bearer (priority high).** Baseline (measured): on a route
with `require: session` the gateway always injects the mediated bearer, even towards an origin that only
serves files; `forward.headers_deny: ["Authorization"]` does not prevent it. Need: UIs that let the gateway
enforce login without giving the frontend origin a token.

```yaml
auth:
  require: session
  token_relay: false        # default true
```

The session is checked (including redirect to login for page navigations) but no `Authorization` is set;
an inbound `Authorization` header is still not forwarded. Acceptance: an echo origin sees no
`Authorization`; without a session unchanged `302` / `401`.

**AS-6 — login return address with query, configurable default (priority medium).** Baseline: a page
navigation to a session route without session returns after login to the path WITHOUT query (measured);
the default `returnUrl` is fixed `/`, outside the context path (code: `LoginFlow.DEFAULT_RETURN_URL`).
Proposal: the remembered return address includes the query (same origin only);
`oidc.login.default_return_url` configurable, checked same-origin at boot, sensibly the portal path.
Acceptance: `…/liste/1?tab=a` without session → after login `…/liste/1?tab=a`; login without `returnUrl` →
the default.

**AS-14 — global plus endpoint scopes, scope step-up (priority high).**
- Baseline (code): `oidc.scopes` is ONE list for the gateway, passed unchanged into the client
  configuration (`BffRuntimeProducer.java:232`, `.scopes(oidc.scopes())`) and thus every authorization
  request, whichever application triggers the login. Endpoint and route only have `auth.required_scopes`,
  which CHECKS an existing token (`SessionAuthenticationStage` for session routes,
  `AuthenticationStage.java:148` for bearer routes) and rejects with `SCOPE_MISSING`; nothing is
  requested. Step-up (`session.step_up`, RFC 9470, `StepUpCoordinator`) requests only a higher
  authentication context (`acr_values`, `max_age`), not scopes.
- Consequence: every application's scopes sit in the shared `gateway.yaml`; every login requests all
  applications' scopes.
- Terminology: `oidc.scopes` are the scopes the gateway REQUESTS at login, not "pre-approved". What the
  IdP issues unrequested is a client setting (Keycloak Default vs Optional Client Scopes); real
  minimization needs application scopes to be optional at the client — must be documented.
- Need: global holds only `openid`, `profile`, `email`; an application's scopes live in its endpoint
  file; login requests the minimal set for the triggering request; later shortfalls use a scope step-up.

```yaml
# gateway.yaml — base of every authenticated request
oidc:
  scopes: [openid, profile, email]

# endpoints/k-beispiel-api.yaml
endpoint:
  id: k-beispiel-api
  anchor: api
  scopes: [roles, k_beispiel_token_permissions]   # new: applies to all routes of this endpoint
  routes:
    - id: k-beispiel-api
      match: { path_prefix: /KIDICAP.Gateway/api/K.Beispiel/api }
```

- `needed(request) = oidc.scopes ∪ endpoint.scopes` (endpoint of the matched route). **Additive**: the
  endpoint extends, never replaces, the global scopes — unlike the `auth` cascade (route → endpoint →
  anchor, block-wise replacement), hence a separate key `endpoint.scopes` outside `auth`; docs must state
  the additivity. Only two levels: anchors span applications (a scope there would be global); per-route
  scopes would only matter if scopes carried per-operation rights, but authorization lives in claims.
  `endpoint.scopes` replaces `auth.required_scopes`; the old key is removed without replacement.

| Situation | Behaviour |
|---|---|
| Login | Request `needed(request)` of the request triggering login — page navigation without session: the called route; `/auth/login`: the route behind `returnUrl`; no authenticated route (start page): only `oidc.scopes`. |
| Session route, token covers `needed(request)` | Pass through. |
| Session route, scopes missing | ⛔ **Superseded 2026-09-17** (inbox `kidicap-gateway-downstream-009.md`): the source document's gateway-side pre-check plus navigation-only step-up is NOT implemented by this plan. The upstream signals `403 insufficient_scope` and PLAN-20 obtains the scope (refresh first, browser step-up otherwise). Kept here as the retracted original proposal so a reader does not re-derive it. |
| Bearer route | Only check `needed(request)` against the incoming token, `403 insufficient_scope`. |

- Consequence of additivity (must be documented): granted scopes stay until the session ends and the
  session spans all applications — after visiting B, the token also carries B's scopes when A is called.
  Preventing that needs per-endpoint token narrowing (scope-restricted refresh RFC 6749 §6 or token
  exchange RFC 8693) — OUT OF SCOPE for this epic unless promoted by a decision.
- Scope limits: `oidc.user_info.allowed_claims` stays global. Endpoints whose routes are all
  `require: none` trigger neither login nor step-up; `endpoint.scopes` there is a boot error.
- Acceptance: login via page navigation → `scope` exactly `oidc.scopes ∪ endpoint.scopes`; via
  `/auth/login` with `returnUrl` to the start page → exactly `oidc.scopes`; an endpoint cannot remove global
  scopes; session missing another endpoint's scopes: navigation → silent step-up then `200`, API call →
  `403 insufficient_scope` with the missing scopes, `200` after step-up; bearer route without needed scope →
  `403 insufficient_scope`; boot error for `endpoint.scopes` without authenticated route;
  `auth.required_scopes` rejected by the schema.

Consumer migration (context only): session UI routes get `token_relay: false`; `default_return_url` set;
global scopes reduced; application scopes moved to endpoint files; `kidicap_*` scopes optional at Keycloak.

## Deliverables

1. AS-5 — `auth.token_relay` (schema `auth` definition + `AuthConfig`, default `true`, normal `auth`
   cascade); `false` checks the session but sets no `Authorization`; inbound `Authorization` stripping
   unchanged.
2. AS-6 — query-preserving return URL (raw query, byte-identical) and `oidc.login.default_return_url`
   (schema + `OidcConfig.Login`) with boot-time same-origin validation; hard-coded
   `LoginFlow.DEFAULT_RETURN_URL` removed.
3. AS-14 — settle ONE engine seam (verify-first): a per-request `scope` override on the authorization
   request (the login leg) and on the refresh leg; record the design as an ADR. The step-up entry point is
   PLAN-20's question, not this plan's.
4. AS-14 — `endpoint.scopes` (schema + `EndpointConfig`, additive, outside `auth`); `auth.required_scopes`
   removed from schema and `AuthConfig`; boot rule for `endpoint.scopes` on all-`require: none` endpoints.
5. AS-14 — per-request `needed(request)` resolution, including `/auth/login?returnUrl=` → route; login
   requests `needed(request)` instead of the fixed `.scopes(oidc.scopes())`.
6. AS-14 — enforcement on **bearer** routes only: `AuthenticationStage` checks `needed(request)` against
   the presented token and answers `403` + `WWW-Authenticate: Bearer error="insufficient_scope",
   scope="…"` (RFC 6750). ⛔ **No gateway-side pre-check on session routes**, and no navigation-only scope
   step-up here — per the downstream decision of 2026-09-17 (inbox `kidicap-gateway-downstream-009.md`)
   the backend signals a missing scope and the gateway obtains it; that whole flow is PLAN-20.
7. AS-14 — **active-scope refresh** (folded 2026-09-17 from inbox `kidicap-gateway-downstream-008.md`):
   the session record carries the active scope set `A` (what the access token should contain) beside the
   grant that lives in the refresh token; after login and after an authorization step-up `A = granted`.
   The refresh sends `scope = A` — never the static `oidc.scopes` — and the token response's `scope` is
   the source of truth for `A` afterwards, because Keycloak silently drops a requested scope outside the
   grant. Without this, PLAN-14 ships a regression: reducing `oidc.scopes` to `openid profile email` makes
   the first refresh narrow the access token to that set and every endpoint scope disappears for the rest
   of the session (measured downstream against Keycloak 26.7.4).
9. Tests AS-5 / AS-6 — unit and IT against the echo origin (no `Authorization`, `302`/`401` unchanged,
   query round-trip, default return URL).
10. Tests AS-14 — unit and IT against Keycloak with optional client scopes: the login's `scope` parameter
    is exactly `oidc.scopes ∪ endpoint.scopes`; `/auth/login` with a `returnUrl` to an unauthenticated
    route requests only `oidc.scopes`; a bearer shortfall answers `403 insufficient_scope`; boot errors;
    and, for deliverable 7, a refresh after login keeps the endpoint scopes in the access token (the
    regression this plan would otherwise introduce). Migrate in-repo IT configs off `required_scopes`.
11. Documentation — `doc/user/bff-session.adoc` (token relay, return URL, scopes, additivity, session-wide
    scope growth, IdP optional client scopes), `doc/user/endpoint-routes.adoc`, `doc/configuration.adoc`,
    `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`.

Split guard: 11 deliverables — within the operator-authorized 12 per plan.

## Claim Labels

- OBSERVED: no `token_relay` key exists; `SessionAuthenticationStage.process` unconditionally calls `request.mediatedBearer(session.accessToken())` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `process`
- OBSERVED: `SessionAuthenticationStage.returnUrl` returns only `canonicalPath()` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `returnUrl`
- OBSERVED: `LoginFlow.DEFAULT_RETURN_URL = "/"` and `LoginFlow.initiate` performs same-origin validation — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `DEFAULT_RETURN_URL`, `initiate`
- OBSERVED: `OidcConfig.Login` carries only `path` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` § `Login`
- OBSERVED: `oidc.scopes` flows into every authorization request via `.scopes(oidc.scopes())` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § BFF client configuration (line 232 at fb9e774)
- OBSERVED: `auth.required_scopes` is only enforced — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `enforceScopes` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` § `validateBearer`
- OBSERVED: `EndpointConfig` has no `scopes` field — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`
- OBSERVED: `StepUpCoordinator` is RFC 9470 shaped and triggered by parsing an upstream `WWW-Authenticate` challenge (`StepUpChallengeParser`) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` § `coordinate`
- HYPOTHESIS: the raw query is reachable from `SessionAuthenticationStage` without re-encoding — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/` § `PipelineRequest` (verify-at-outline)
- HYPOTHESIS: the external token client engine's authorization request accepts a per-request `scope` override — confirm/refute at the engine authorization-request API used from `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate` (verify-at-outline)
- OBSERVED: the refresh leg sends the static configured scope list (`RefreshFlow.refresh` in the external token client, fed by `.scopes(oidc.scopes())`), so narrowing `oidc.scopes` without deliverable 7 strips endpoint scopes from every refreshed access token — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § BFF client configuration, with the Keycloak behaviour measured downstream (inbox `kidicap-gateway-downstream-008.md`)
- HYPOTHESIS: the engine's refresh call accepts a per-request `scope` — confirm/refute at the token client's refresh API as wired from `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/` (verify-at-outline)
- HYPOTHESIS: `LoginInitiationEndpoint` can resolve `returnUrl` to a route runtime — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpoint.java` § request handling (verify-at-outline)
- Verify-first clause: settle the engine hypotheses before scoping deliverables 5, 7 and 8 — this is the largest open design question of the epic. If the engine needs a release, AS-5, AS-6 and the parts of AS-14 that do not depend on it still ship; the gated part is recorded as a documented gap in the PR plus an inbox `finding`, so the orchestrator can re-stage it. Never ship `endpoint.scopes` enforcement without the matching request side (requesting and checking must not diverge).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — `process`, `returnUrl`, `enforceScopes`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/` — refresh flow wiring for the active scope set (deliverable 7)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionRecord.java` — active scope set `A`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` — `validateBearer`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — scope wiring
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/AuthConfig.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `auth`, `endpoint.scopes`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `oidc.login`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpoint.java` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — resolved scope set per route (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rules (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/` — session, login, step-up tests (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/` — bearer scope tests (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/` — Keycloak realm scopes, IT endpoint configs (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — session and scope ITs (verify-at-outline)
- HYPOTHESIS: `doc/adr/` — scope step-up ADR (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-session.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-13 by surface (`BffRuntimeProducer.java`, docs) and by input (raw-query representation from AS-13)
- Overlaps with: PLAN-13 (`BffRuntimeProducer.java`, `integration-tests/src/test/java/`, docs); PLAN-15 (`endpoint.schema.json`, `RouteTableBuilder.java`, `doc/user/endpoint-routes.adoc`); PLAN-16 (`endpoint.schema.json`, `gateway.schema.json`)
- Adjacent to: per-endpoint token narrowing (AS-14 stage 2) — deliberately not staged

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-14-session-and-scopes.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
