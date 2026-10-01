envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-24T10:00:00Z

## Defect (PRIORITY: HIGHEST): a session route relays a token lacking the route's needed scopes instead of obtaining them

Filed by the downstream deployment `kidicap-gateway` while adopting 0.2.3. Classified by the downstream as a
**defect, highest priority** — ahead of PLAN-19 (`session_fallback`) and PLAN-21. It blocks the downstream
from using `endpoint.scopes` (AS-14) as designed. Relates to PLAN-20 (`kidicap-gateway-downstream-006` /
`-009`), but is narrower and must not wait for the upstream-signalled step-up.

### Observed (0.2.3, ADR-0049)

One scope set per route, `needed = oidc.scopes ∪ endpoint.scopes`, but two different outcomes when a token
lacks a member:

| Posture | Token lacks a needed scope | Measured / read |
|---|---|---|
| `bearer` | `403 insufficient_scope` at the gateway, upstream never contacted | measured in the downstream Helm IT, 2026-09-24 |
| `session` | token relayed unchanged; the upstream has to refuse | `AuthenticationStage` / `SessionAuthenticationStage` Javadoc and ADR-0049 "session routes only request them" |

A session reaches a route with an incomplete token whenever the login did NOT start on that route:
the portal (`none`), an application UI under a `none` app route, `/auth/login?returnUrl=` pointing at a
route of another endpoint, or a session established through a different endpoint. With `endpoint.scopes`
per application this is the normal case, not an edge case: a user signs in on the portal and then opens
application A — every session call of A carries a token without E(A).

### Why this is a defect, not a policy choice

* The rules must be the same for both access paths to the same route. The same path answers `403` at the
  gateway for a bearer caller and forwards an under-scoped token for a session caller — the gateway's own
  declaration (`endpoint.scopes`) is enforced for one and silently ignored for the other.
* For the session path the gateway is the party that obtained the token; nobody else can repair it. The
  upstream's refusal is a dead end for a browser user: there is no path back to a login with the wider set.
* ADR-0049 defers a session pre-check "until a live session can be widened". The downstream needs exactly
  that mechanism; its absence makes `endpoint.scopes` unusable for more than one application.

### Required behaviour

On a `require: session` route, before relaying:

1. Compare the session's active/granted scope set (already carried since PLAN-14: session record and sealed
   cookie) with the route's `neededScopes`.
2. Missing scopes inside the grant S → refresh with A ∪ missing (see the measured Keycloak behaviour in
   `-009`: a refresh can restore any scope of S), then relay the refreshed token.
3. Missing scopes outside S →
   * top-level navigation: authorization request for S ∪ needed, then back to the original URL (the
     existing re-drive), the session is **widened, not replaced**; `prompt=none` first where the IdP
     session allows it;
   * API call (non-navigation): a machine-readable answer that a step-up is required, with a step-up URL
     under the reserved auth paths (shape as proposed in `-009`), never a silent relay of the incomplete
     token.
4. An IdP refusal (`error=invalid_scope`, `login_required`) leaves the existing session unchanged; the
   request is answered `403` — never an under-scoped relay, no loop.

### Acceptance

* Login on the portal (`oidc.scopes` only), then a session call to a route of an endpoint with
  `endpoint.scopes: [x]`, x assigned to the client: the upstream sees a token containing x.
* Same with x inside S but outside A: no browser round trip, one refresh.
* Same with x not assigned to the client: `403`, session still valid for routes that do not need x.
* A route whose needed set is satisfied: no additional IdP call (no regression in latency).

### Downstream workaround until then

The downstream has one application today and puts that application's scopes into `oidc.scopes`, so every
login requests the full set and every session is complete. That does not scale to a second application
(every login would request every application's scopes — the over-request ADR-0049 set out to avoid).
Downstream reference: `doc/open-issues.adoc#umgehungen`, `doc/autorisierung/scopes.adoc`.
