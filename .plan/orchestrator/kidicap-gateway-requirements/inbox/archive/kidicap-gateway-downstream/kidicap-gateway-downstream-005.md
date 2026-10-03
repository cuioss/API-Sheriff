envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-17T15:00:00Z

## Requirement: `auth.session_fallback` on a `require: bearer` posture (BFF as an additive opt-in)

Filed by the downstream deployment `kidicap-gateway`. Analysed against the 0.2.1 sources (unchanged at
`main`), measured in an experiment build of the 0.2.1 native image on 2026-09-17. Supersedes the earlier
draft of `kidicap-gateway-downstream-004.md` (audience per route / `accept_bearer`); the downstream has
decided that audience, scopes and permissions are checked by the services, and the gateway only asserts
"valid token from the configured trust anchor".

### Model decided downstream

The shared backend anchor `api` requires **bearer**. An application whose browser UI uses the BFF adds the
session **additively**: a request *without* `Authorization` is treated as BFF.

| Request on a route with `require: bearer` + `session_fallback: true` | Wanted |
|---|---|
| `Authorization` present, token valid for the trust anchor | accept, forward the token unchanged; no session lookup, no CSRF, no `Set-Cookie` |
| `Authorization` present but invalid / not `Bearer` / empty | `401` + `WWW-Authenticate: Bearer`; no login redirect, no fallback even with a valid session cookie |
| no `Authorization` | session branch as a `require: session` route today: CSRF on unsafe methods, `302` for navigation / `401`, mediated bearer |

A route without `session_fallback` stays a plain bearer route (no OIDC needed).

### Today (0.2.1)

One posture per route (`Require`), CSRF keyed on the route posture and run before authentication, inbound
`Authorization` stripped on session routes. The downstream therefore implements the model as a route pair
on the same prefix (`match.headers: authorization present: true` + inherited bearer, declared first;
`present: false` + `auth.require: session`, second). Measured to meet the table above (19 checks), but it
doubles every BFF route and depends on the matcher defects reported in `kidicap-gateway-downstream-004.md`.

### Proposal

1. Schema (`gateway.schema.json`, `endpoint.schema.json`) and `AuthConfig`: `session_fallback` (boolean,
   default `false`), normal anchor → endpoint → route cascade. `ConfigValidator`: only with
   `require: bearer`; requires an `oidc` block; the anchor floor comparison is unchanged (bearer); the
   effective-auth rules for `token_validation` and `oidc` both apply.
2. Resolve the effective branch before the CSRF check: `BEARER` when an `Authorization` header is present
   (any scheme) or `session_fallback` is off; otherwise `SESSION`. `GatewayEdgeRoute` invokes
   `CsrfDefence` for the `SESSION` branch only (today: for `require: session` routes).
3. `BEARER` branch: existing `validateBearer`. `SESSION` branch: existing `SessionAuthenticationStage`.
4. Forwarding: on the `BEARER` branch set the validated token through `request.mediatedBearer(token)`, so
   `ForwardPolicyStage` writes exactly the validated token last and `headers_allow: ["Authorization"]` is no
   longer needed for it; on the `SESSION` branch unchanged.
5. The chosen branch in the access log and as a label on the auth metrics.
6. ITs for the table, both branches with and without cookie, boot-validation errors; documentation in
   `doc/configuration.adoc` (`auth`) and `doc/user/bff-session.adoc`.

Security rationale: the branch depends only on header presence, so sending any `Authorization` can never
fall back to the session; the bearer branch needs no CSRF check because browsers never attach
`Authorization` ambiently and cross-origin scripts need a CORS preflight; the cookie is stripped on that
branch; nothing is accepted that a `require: bearer` route does not already accept.

### Downstream migration

Each route pair collapses into one route with `session_fallback: true`; the downstream integration checks
stay unchanged as proof of equivalence.
