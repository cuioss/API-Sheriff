# PLAN-19: `auth.session_fallback` — BFF Session as an Additive Opt-In on a Bearer Route

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-19-session-fallback-on-bearer.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-17 from inbox message `kidicap-gateway-downstream-005.md`.

## Objective

A route carries exactly one authentication posture today, so a shared backend path that must serve both
bearer clients and BFF browsers needs two routes distinguished by header matchers. This plan adds
`auth.session_fallback` (default `false`) on a `require: bearer` posture: a request carrying any
`Authorization` header takes the bearer branch, a request without one takes the session branch — CSRF,
login redirect and mediated bearer included — so one route serves both callers and the branch never
depends on anything an attacker can omit in order to reach the weaker path.

## Source

Inbox `kidicap-gateway-downstream-005.md` (finding, 2026-09-17), filed by the downstream deployment
`kidicap-gateway`, analysed against 0.2.1 sources and measured in an experiment build.

Wanted behaviour on a route with `require: bearer` + `session_fallback: true`:

| Request | Wanted |
|---|---|
| `Authorization` present, token valid for the trust anchor | accept, forward the token unchanged; no session lookup, no CSRF, no `Set-Cookie` |
| `Authorization` present but invalid / not `Bearer` / empty | `401` + `WWW-Authenticate: Bearer`; no login redirect, no fallback even with a valid session cookie |
| no `Authorization` | session branch as a `require: session` route today: CSRF on unsafe methods, `302` for navigation / `401`, mediated bearer |

A route without `session_fallback` stays a plain bearer route and needs no OIDC block.

Proposal as filed:
1. Schema (`gateway.schema.json`, `endpoint.schema.json`) and `AuthConfig`: `session_fallback` (boolean,
   default `false`) with the normal anchor → endpoint → route cascade. `ConfigValidator`: valid only with
   `require: bearer`; requires an `oidc` block; the anchor floor comparison stays bearer; the
   effective-auth rules for `token_validation` and `oidc` both apply.
2. Resolve the effective branch BEFORE the CSRF check: `BEARER` when an `Authorization` header is present
   (any scheme) or `session_fallback` is off, else `SESSION`; `GatewayEdgeRoute` invokes `CsrfDefence` for
   the `SESSION` branch only.
3. `BEARER` branch: existing `validateBearer`. `SESSION` branch: existing `SessionAuthenticationStage`.
4. Forwarding: on the `BEARER` branch set the validated token through `request.mediatedBearer(token)`, so
   `ForwardPolicyStage` writes exactly the validated token last and `headers_allow: ["Authorization"]` is
   no longer needed for it; the `SESSION` branch is unchanged.
5. The chosen branch in the access log and as a label on the auth metrics.
6. Integration tests for the table, both branches with and without a cookie, plus boot-validation errors;
   documentation in `doc/configuration.adoc` (`auth`) and `doc/user/bff-session.adoc`.

Security rationale as filed: the branch depends only on header presence, so sending any `Authorization`
can never fall back to the session; the bearer branch needs no CSRF check because browsers never attach
`Authorization` ambiently and cross-origin scripts need a preflight; the cookie is stripped on that
branch; nothing is accepted that a `require: bearer` route does not already accept.

Downstream context: the model is implemented today as a route pair on one prefix (`Authorization`
present → bearer, absent → session), measured to meet the table in 19 checks, but it doubles every BFF
route and depends on the matcher defects in PLAN-18. The downstream also decided that audience, scopes
and permissions are checked by the services; the gateway only asserts "valid token from the configured
trust anchor" — an earlier draft asking for per-route audience / `accept_bearer` is withdrawn.

## Deliverables

1. `auth.session_fallback` in both schemas and `AuthConfig`, default `false`, normal `auth` cascade.
2. Boot validation: only with `require: bearer`; an `oidc` block required; anchor floor unchanged;
   `token_validation` and `oidc` effective-auth rules both apply; clear errors for each refusal.
3. Branch resolution before the CSRF check, with CSRF, session lookup and `Set-Cookie` confined to the
   `SESSION` branch, and an invalid or non-`Bearer` `Authorization` answering `401` +
   `WWW-Authenticate: Bearer` with no session fallback.
4. Forwarding on the `BEARER` branch through `request.mediatedBearer(token)` so the forwarded header is
   exactly the validated token.
5. Observability: the chosen branch in the access log and as an auth-metric label.
6. Tests: unit tests per branch, integration tests for every row of the table with and without a cookie,
   boot-validation errors, and a test that a `session_fallback` route strips the session cookie on the
   bearer branch.
7. Documentation: `doc/configuration.adoc` (`auth`), `doc/user/bff-session.adoc`, `doc/user/anchors.adoc`
   if the anchor floor wording needs it, and `doc/security-threat-model.adoc` for the branch rationale;
   ADR for the posture change (a route can now carry two authentication branches).

## Claim Labels

- OBSERVED: no `session_fallback` key exists anywhere in the schemas or the code at HEAD 799976a — read at `api-sheriff/src/main/resources/schema/endpoint.schema.json`, `api-sheriff/src/main/resources/schema/gateway.schema.json` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/AuthConfig.java`
  - verdict: corroborated | checked_at: 84e07c7c | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: zero session_fallback/sessionFallback hits across gateway sources and both schemas at 84e07c7c
- HYPOTHESIS: CSRF runs before authentication and is keyed on the route's declared posture, so branch resolution must move ahead of it — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` § pipeline order and the CSRF stage (verify-at-outline)
  - verdict: corroborated | checked_at: 84e07c7c | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: GatewayEdgeRoute:833 csrfDefence().enforce(request) precedes authenticationStage.process at :835; ordering unchanged
- HYPOTHESIS: inbound `Authorization` is stripped on session routes and `mediatedBearer` is the single seam that writes the forwarded token — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `process` and the forward-policy stage (verify-at-outline)
  - verdict: corroborated | checked_at: 84e07c7c | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: SessionAuthenticationStage:159-160 still the single mediatedBearer seam, gated by route.getEffectiveAuth().effectiveTokenRelay()
- HYPOTHESIS: the bearer path can be entered per request without re-deriving route state — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` § `validateBearer` (verify-at-outline)
  - verdict: corroborated | checked_at: 84e07c7c | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: AuthenticationStage:129 case BEARER -> validateBearer(request, route) per request; no cached route state
- Verify-first clause: settle the branch-resolution seam (where the posture is read today, and whether CSRF can be made branch-aware without reordering the whole pipeline) before scoping deliverable 3; a refutation re-scopes the change and may require an ADR on pipeline order. Also confirm the interaction with PLAN-14's `token_relay` and `endpoint.scopes` if that plan has landed: a `session_fallback` route's session branch must honour both.
  - verdict: corroborated | checked_at: 84e07c7c | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: PLAN-14 prerequisites (tokenRelay, neededScopes, ResolvedRoute.effectiveAuth) present and unchanged by PLAN-16/17/18

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/AuthConfig.java` — `sessionFallback`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `auth` definition
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — anchor `auth` defaults
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — branch resolution, CSRF invocation
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` — bearer branch
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — session branch
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — boot rules
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/csrf/CsrfDefence.java` — CSRF enforcement (corrected 2026-09-21: not under `pipeline/`)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java` — renders the mediated bearer (corrected 2026-09-21: not under `pipeline/`)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java` — `resolveEffectiveAuth`, the anchor → endpoint → route `auth` cascade the new key rides (added 2026-09-22 re-grounding at 3e3addc)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ResolvedRoute.java` — `effectiveAuth` record field the cascade materializes into (added 2026-09-22)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java` — carries `effectiveAuth` / `getNeededScopes()` read by branch resolution and both auth stages (added 2026-09-22)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/` — a `session_fallback` route (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — branch ITs (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc`, `doc/user/bff-session.adoc`, `doc/user/anchors.adoc`, `doc/security-threat-model.adoc`, `doc/adr/` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-14 by surface and semantics (`AuthConfig`, `SessionAuthenticationStage`, `AuthenticationStage`, the `auth` schema definition) — land PLAN-14 first so `token_relay` and `endpoint.scopes` exist when the session branch is wired
- Overlaps with: PLAN-14, PLAN-13 (`auth/`, docs), PLAN-18 (`ConfigValidator` route rules)
- Adjacent to: PLAN-18 fixes the matchers the downstream's interim route pair depends on; this plan removes the need for that pair but does not supersede the fixes

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-19-session-fallback-on-bearer.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
