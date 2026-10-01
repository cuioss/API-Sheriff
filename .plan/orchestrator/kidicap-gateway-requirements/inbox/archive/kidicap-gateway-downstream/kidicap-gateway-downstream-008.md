envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-17T14:46:26Z

## Requirement: the BFF refresh must request the session's active scopes, not the static `oidc.scopes`

Filed by the downstream deployment `kidicap-gateway`. Aimed at PLAN-14 (AS-14, endpoint scopes, `session-and-scopes`),
which is staged: as written, PLAN-14 changes what the login requests but leaves the refresh untouched, and the two
then disagree. Downstream reference: `doc/autorisierung/aufgaben.adoc` AU-1 and AU-6.

### Today (code read, 0.2.1 with token-sheriff 0.9.5; unchanged at `main` with 0.9.6)

`RefreshFlow.refresh` sends `scope = String.join(" ", configuration.getScopes())` on every refresh, and
`BffRuntimeProducer` builds that configuration with `.scopes(oidc.scopes())`. Login and refresh therefore
use the same fixed list. That is harmless while the list is also what the login requests — and it is not
observable downstream for exactly that reason: the result equals a refresh without `scope`.

### Keycloak behaviour (measured 2026-09-17, Keycloak 26.7.4, downstream Helm integration test)

Measured with a password-grant test client that carries the same default/optional client-scope assignment
as the gateway's confidential client. S = the scopes granted by the last authorization request.

| Refresh request | Result |
|---|---|
| no `scope` | access token contains S |
| `scope` ⊂ S | access token contains only the subset; the **new refresh token still carries S** (`scope` claim) |
| no `scope`, after a narrowed refresh | S is back in the access token |
| `scope` containing an assigned scope outside S | `200`, **no error**; that scope is silently absent from the access token |
| `scope` containing an unknown scope | `400 invalid_scope` |
| any | default client scopes are always present and cannot be narrowed away |

Keycloak code for reference: `AbstractRefreshTokenProvider.refreshAccessToken` (intersection with the old
refresh token's scope) and `AccessTokenResponseBuilder.generateRefreshToken(oldRefreshToken, …)`
(`setScope(oldRefreshToken.getScope())`).

### Why PLAN-14 breaks without a change

PLAN-14 reduces `oidc.scopes` to `openid profile email` and requests `oidc.scopes ∪ endpoint.scopes` at
login. The first refresh then sends only `openid profile email`, Keycloak narrows the access token to that
set (measured row 2), and every endpoint scope disappears from the mediated bearer until the session ends —
each refresh re-triggering the scope shortfall that the step-up just resolved.

### Proposal

1. The session record carries the **active scope set A** (what the access token should contain), next to
   the granted set that lives in the refresh token. After login and after an authorization-request step-up
   A = granted.
2. The refresh sends `scope = A`. While A equals the granted set this is equivalent to omitting `scope`;
   omitting it is acceptable as the first step if A is never narrowed.
3. A may be narrower than the granted set. Because a narrowed refresh keeps the full grant in the refresh
   token (measured), a deployment can hold the access token below the grant and widen it again by refresh
   without a browser round trip. This is the basis of the refresh-first step-up in the sibling message
   `kidicap-gateway-downstream-009`, and of two options the downstream has not decided yet: per-scope
   lifetimes, and a login that grants more than it activates.
4. Token-response `scope` is the source of truth for A after a refresh, not the request: Keycloak drops
   unknown-to-the-grant scopes without an error.

### Acceptance

* After a scope step-up, the access token still contains the stepped-up scope after the next refresh.
* A refresh never sends the static `oidc.scopes` once endpoint scopes exist.
* With A narrowed to a subset, the refresh token's grant is unchanged and a later refresh with a wider A
  (within the grant) restores the scopes.
