envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-10-06T20:00:00Z

## Adoption of 0.2.4 — two workarounds removed; integration tests requested for two properties

Filed by the downstream deployment `kidicap-gateway` after raising its parent to API Sheriff 0.2.4. Evidence
ids (`IT-…`, `M-…`) refer to the downstream's protocols under `doc/protokolle/`.

### Adopted, no action needed

* **`kidicap-gateway-downstream-005` / PLAN-19 (`auth.session_fallback`)** — the route pair with
  `Authorization` header matchers is gone; one route with `auth: { require: bearer, session_fallback: true }`
  at the endpoint. The downstream's checks for both branches pass unchanged (IT-KORGA-API, IT-KORGA-BEARER,
  IT-KORGA-SESSION), including "no fallback to the session for an invalid bearer" and the lower-case header
  name.
* **`kidicap-gateway-downstream-010` / PLAN-23 (session routes obtain their scopes)** — the application's
  scopes moved from `oidc.scopes` to `endpoint.scopes`; `oidc.scopes` is `openid` only, `oidc.step_up.path`
  is set. Observed against Keycloak 26.7.4: a session from a login through the portal gets `403` with
  `missing_scopes` and `step_up_url` on an API call (IT-KORGA-SESSION-17 to -19, no `WWW-Authenticate`:
  -22), following `step_up_url` widens it silently (-20), a navigation is widened directly (-21).
* **#377 (PAR, DPoP)** — the downstream stays on `oidc.client_secret` by operator decision (ApiSheriff-133
  at every boot). PAR and DPoP work against Keycloak 26.7.4 without any change to the client; the
  downstream's test realm now also sets `require.pushed.authorization.requests` and
  `dpop.bound.access.tokens`. The relayed session token carries `cnf.jkt` and arrives as a plain bearer
  (IT-KORGA-SESSION-25).

### Request: integration tests for two properties the downstream states but cannot measure

Same form as `kidicap-gateway-downstream-012`; both are unit-level only at tag `0.2.4` as far as the
downstream could find.

| Downstream id | Property | State at 0.2.4 |
|---|---|---|
| M-62 | a token response that is not bound to the gateway's DPoP proof key is refused: `400` at the callback, no session, ApiSheriff-134 | `BffFapiControlsIT` and `BffClientSecretModeIT` assert the positive case (the token *is* bound); no IT drives an unbound response |
| M-64 | a session lacking a route scope that lies inside its granted set obtains it by a refresh, without a browser round trip | `BffSessionScopeParityIT.refreshRestoresAScopeOfTheGrantAfterANarrowedRefresh` is a probe of the identity provider, not of the gateway's scope-driven refresh |

Now cited by the downstream as upstream evidence, listed so the test is not removed unnoticed:

| Downstream id | Property | Upstream IT at 0.2.4 |
|---|---|---|
| M-61 | a scope the IdP refuses leaves the session unchanged: navigation `502` at the pushed request, XHR `403` | `BffSessionScopeParityIT.navigationNeedingARefusedScopeIsRefusedAtThePush`, `.xhrNeedingARefusedScopeIsRefusedAndTheSessionSurvives` |

### Observations, no request

* `step_up_url` returns to the refused API URL (IT-KORGA-SESSION-20), because the step-up path takes the
  scopes from the route behind `returnUrl` and returns to that same `returnUrl`. For an SPA this is not a
  usable landing. The downstream does not rely on it: the surface route carries `require: session`,
  `token_relay: false` and the same `endpoint.scopes` as the API, so the navigation to the surface widens
  the session and lands on the page (IT-APP-SESSION-03 to -07, a test-stack-only route). It only matters
  for a backend-signalled scope, which is `kidicap-gateway-downstream-009` / PLAN-20.

* The default `oidc.client_authentication.jwks_path` (`/auth/jwks`) lies outside a build-time context path,
  like the default of `login.default_return_url`. The downstream sets it explicitly
  (`/KIDICAP.Gateway/auth/jwks`); in client-secret mode it answers the unrouted `404` (IT-AUTH-18).
* Keycloak 26.7.4 refuses a pushed request naming an unknown or unassigned scope with
  `400 invalid_request`, "Invalid scopes" (IT-SCOPES-20 to -23), not with `invalid_scope`.
* With a pushed request, Keycloak answers the authorization endpoint with a further `302` to its own
  `login-actions` address before the form and before an SSO redirect; the downstream's curl-driven login
  round trip had to follow it.
