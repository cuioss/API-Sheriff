envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-17T14:46:26Z

## Requirement: upstream-signalled step-up — refresh first with replay, browser step-up without page change

Filed by the downstream deployment `kidicap-gateway`. Aimed at PLAN-14 (AS-14): it replaces the planned
per-endpoint pre-check plus navigation-only step-up with a step-up driven by the upstream's challenge.
Downstream reference: `doc/autorisierung/step-up.adoc`, `aufgaben.adoc` AU-2 to AU-6. Depends on the
active-scope refresh in `kidicap-gateway-downstream-008`.

### Decided downstream

* **The backend signals, the gateway obtains.** Which operation needs which scope is known only to the
  backend's code — in particular to façade backends that call further backends with the user's token.
  A second per-route scope list in the gateway configuration would drift silently, so the downstream
  declares **no per-route scopes**. `endpoint.scopes` stays coarse: what the login requests for an
  application.
* **Contract for backends:** a missing scope is answered with
  `403` + `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"` (RFC 6750) **before any side
  effect**; façades pass a downstream backend's challenge through unchanged. On this contract the gateway
  may replay any method.
* **The IdP is the allowlist.** Keycloak rejects scopes not assigned to the gateway client (measured below),
  so the gateway needs no list of its own.

### Measured Keycloak behaviour that shapes the flow (Keycloak 26.7.4, 2026-09-17)

* A refresh can never add a scope outside the grant S; it answers `200` and silently omits it.
* A refresh without `scope` restores the whole grant; a narrowed refresh keeps S in the refresh token.
* A token request with an existing scope not assigned to the client: `400 invalid_scope`.
* An authorization request with an unknown scope: `302` to the `redirect_uri` with `error=invalid_scope`.

Consequence: whether a signalled scope s can be obtained without the browser depends only on s ∈ S, and the
gateway need not know S — it tries the refresh and reads the token-response `scope`. While the active set A
equals S, a missing scope always means s ∉ S and the refresh can be skipped.

### Flow

```
upstream 403 insufficient_scope, scope="s"   (or 401 insufficient_user_authentication)
gateway:
  A may be narrower than S?  refresh with A ∪ {s}
      response scope contains s → replay the request once with the new token (client sees one response)
  otherwise / s missing (s ∉ S):
      answer the client: step-up required, with a step-up URL under the reserved auth paths
      UI opens it in a hidden iframe or popup; authorization request with S ∪ {s}, prompt=none first
      callback: success → session updated, completion signalled to the UI; UI retries the call
                error=invalid_scope / login_required refused → session UNCHANGED, refusal signalled
  still missing after one attempt of each kind → 403 to the client, no loop
```

`insufficient_user_authentication` shares challenge parsing, the browser leg and the loop guard; a refresh
cannot raise the authentication context, so it goes to the browser directly.

### Proposal

1. **Edge integration (AS-level):** evaluate upstream `WWW-Authenticate` on session routes for
   `insufficient_scope` and `insufficient_user_authentication`. `StepUpCoordinator` exists but has no
   production caller today (see `kidicap-gateway-downstream-006`).
2. **Refresh-first scope step-up with replay:** refresh with A ∪ {s}; on success replay once, all methods,
   request body buffered within the route's filter body cap; identical headers except the mediated bearer.
3. **Browser step-up for API calls:** a step-up URL (a reserved path or a parameter of `oidc.login.path`)
   that starts the authorization request with S ∪ {s}, runs silently when the IdP allows it, and reports
   completion or refusal to the opener (e.g. `postMessage` to the same origin). The existing re-drive to
   the request URL stays for top-level navigations.
4. **Callback error handling during a step-up:** an authorization error on the callback (measured:
   `error=invalid_scope`) must not destroy or replace the existing session.
5. **Loop guard:** at most one refresh attempt and one browser step-up per scope per request; then `403`.
6. **PLAN-14 alignment:** keep `endpoint.scopes` for the login request; drop the gateway-side pre-check as
   the trigger for step-up and the navigation-only step-up for API calls.

### Acceptance

* An echo upstream answering `403 insufficient_scope` for a scope inside a narrowed A: the upstream sees
  the request twice, the client one `200`, no authorization request.
* Same for a scope outside the grant: no replay, the client gets the step-up answer; in an iframe with an
  IdP SSO session the step-up completes without interaction and the retried call succeeds.
* A scope not assigned to the client: session unchanged, refusal signalled, retried call `403`, no loop.
