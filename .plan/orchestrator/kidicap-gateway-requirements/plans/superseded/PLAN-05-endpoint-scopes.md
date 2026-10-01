# PLAN-05: Additive Global-Plus-Endpoint OIDC Scopes, Replacing `auth.required_scopes`

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-05-endpoint-scopes.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

`oidc.scopes` is a single gateway-wide list sent in every authorization request, whichever application
triggered the login, and endpoints/routes can only CHECK scopes (`auth.required_scopes`), never request
them (AS-14). This plan introduces two additive levels — global `oidc.scopes` plus a new `endpoint.scopes`
— requests exactly `needed(request) = oidc.scopes ∪ endpoint.scopes` of the endpoint behind the request
that triggers the login, enforces the same set on session and bearer routes, and removes
`auth.required_scopes` outright (pre-1.0, no transition). Scope step-up on a later shortfall is PLAN-09.

## Source

KIDICAP Gateway requirements AS-14 (`archive/api-sheriff-aenderungen.adoc` § AS-14), part 1 of 2
(minimal scope set). Priority high.

- Baseline (code): `oidc.scopes` is one list for the whole gateway, passed unchanged into the client
  configuration (`BffRuntimeProducer.java:232`, `.scopes(oidc.scopes())`) and thus into every
  authorization request. At endpoint and route only `auth.required_scopes` exists; it checks an existing
  token (`SessionAuthenticationStage` for session routes, `AuthenticationStage.java:148` for bearer
  routes) and rejects with `SCOPE_MISSING`; it requests nothing. Step-up (`session.step_up`, RFC 9470,
  `StepUpCoordinator`) only requests a higher authentication context (`acr_values`, `max_age`), not scopes.
- Consequence for a multi-application gateway: every application's scopes sit in the shared
  `gateway.yaml`, every application edits the shared file, and every login requests all applications'
  scopes.
- Terminology: `oidc.scopes` are the scopes the gateway REQUESTS at login, not "pre-approved" scopes.
  What the IdP issues without request is a client setting at the IdP (Keycloak: Default Client Scopes;
  Optional Client Scopes only on request). Real minimization requires application-specific scopes to be
  optional at the client — must be stated in the docs.
- Need: global holds only application-neutral scopes (`openid`, `profile`, `email`); an application's
  scopes live in its endpoint file; login requests the minimal set for the triggering request.

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

- `needed(request) = oidc.scopes ∪ endpoint.scopes` (endpoint of the matched route). Both levels apply
  at once. **Additive**: the endpoint extends the global scopes and never replaces them. This deviates
  from the `auth` cascade (route → endpoint → anchor, replaced block-wise) and is therefore deliberately
  NOT inside `auth` but a separate key `endpoint.scopes`; the docs must state the additivity explicitly.
- Why only two levels: anchors span applications (`app`, `api`), so a scope there would be effectively
  global; per-route scopes only make sense if scopes carried per-operation rights, but authorization lives
  in the claims the backend evaluates. An endpoint belongs to exactly one application and purpose.
- `endpoint.scopes` replaces `auth.required_scopes`; the old key is removed without replacement so
  requesting and checking cannot drift apart.
- Login: request `needed(request)` of the request triggering the login — for a page navigation without
  session the called route; for `/auth/login` the route behind `returnUrl`; if that resolves to no
  authenticated route (start page), only `oidc.scopes`.
- Scope limits: `oidc.user_info.allowed_claims` stays global. Endpoints whose routes are all
  `require: none` trigger neither login nor step-up; `endpoint.scopes` there is a boot error.
- Acceptance: login via page navigation → `scope` parameter exactly `oidc.scopes ∪ endpoint.scopes` of the
  matched endpoint; via `/auth/login` with `returnUrl` to the start page → exactly `oidc.scopes`; an
  endpoint cannot remove global scopes; bearer route without a needed scope → `403`; boot error for
  `endpoint.scopes` on an endpoint without authenticated route; `auth.required_scopes` is no longer a valid
  key (schema).
- Consumer migration (context only): global scopes reduced to `openid profile email`; application scopes
  moved to endpoint files; `kidicap_*` scopes made optional on the Keycloak client.

## Deliverables

1. Schema + model: `endpoint.scopes` (additive, outside the `auth` cascade) on `EndpointConfig`;
   `auth.required_scopes` removed from schema and `AuthConfig` (pre-1.0 direct removal).
2. Per-request scope resolution: `needed(request)` computed per resolved route (route table / route
   runtime), including resolution of `/auth/login?returnUrl=` back to a route; authorization requests use
   that set instead of the fixed `.scopes(oidc.scopes())`.
3. Enforcement: session routes (`SessionAuthenticationStage`) and bearer routes (`AuthenticationStage`)
   check `needed(request)`; bearer shortfall answers `403` (the `WWW-Authenticate` `insufficient_scope`
   detail may land here or in PLAN-09 — decide at outline, never both).
4. Boot rule: `endpoint.scopes` on an endpoint with only `require: none` routes fails boot.
5. Tests for the acceptance set (unit + integration against Keycloak, asserting the `scope` parameter),
   docs (`doc/user/bff-session.adoc`, `doc/user/endpoint-routes.adoc`, `doc/configuration.adoc`) stating
   additivity, the IdP optional-client-scope requirement and the session-wide growth of granted scopes;
   threat-model update in `doc/security-threat-model.adoc`.

## Claim Labels

- OBSERVED: `oidc.scopes` flows unchanged into every authorization request via `.scopes(oidc.scopes())` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § BFF client configuration (line 232 at fb9e774)
- OBSERVED: `auth.required_scopes` is enforced (never requested) in two places — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `enforceScopes` (lines 158-164, source cited 158-163) and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` § `validateBearer` (line 148)
- OBSERVED: `EndpointConfig` has no `scopes` field; `endpoint.schema.json` `auth` definition carries `required_scopes` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java` and `api-sheriff/src/main/resources/schema/endpoint.schema.json` § `auth`
- HYPOTHESIS: the BFF authorization request builder in the external token client engine accepts a per-request `scope` override (the client is configured once at boot with fixed scopes) — confirm/refute at the engine's authorization-request API used from `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate` (verify-at-outline)
- HYPOTHESIS: `LoginInitiationEndpoint` can resolve `returnUrl` to a route runtime — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpoint.java` § request handling (verify-at-outline)
- Verify-first clause: settle both hypotheses before scoping deliverable 2. If the engine has no per-request scope override, loop back: either the plan adds a thin in-repo request customization or it is gated on an engine release (record which).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `endpoint.scopes` (new), `auth.required_scopes` (removed)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/EndpointConfig.java` — `scopes`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/AuthConfig.java` — `requiredScopes` removed
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — scope wiring
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — `enforceScopes`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` — `validateBearer`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` — per-request scopes (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/LoginInitiationEndpoint.java` — `returnUrl` → route resolution (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — carry resolved scope set on the route (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rule (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/` — IT config using `required_scopes` today must migrate (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-session.adoc` — scope documentation (verify-at-outline)
- HYPOTHESIS: `doc/user/endpoint-routes.adoc` — `required_scopes` documentation (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — scope keys (verify-at-outline)
- HYPOTHESIS: `doc/security-threat-model.adoc` — scope minimization (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-03 (shared `AuthConfig.java`, `SessionAuthenticationStage.java`, `auth` schema definition — sequenced, not a functional dependency)
- Overlaps with: PLAN-03, PLAN-09 (same files); PLAN-01 (`BffRuntimeProducer.java`, other region); PLAN-04 / PLAN-06 / PLAN-11 / PLAN-12 (`RouteTableBuilder.java`, if touched)
- Adjacent to: PLAN-09 depends on this plan; `oidc.user_info.allowed_claims` stays global and untouched

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-05-endpoint-scopes.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
