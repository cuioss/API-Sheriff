# PLAN-03: Token-Relay Opt-Out and Query-Preserving Login Return URL

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-03-session-token-relay-return-url.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

Two `require: session` behaviours block UI routes in the KIDICAP Gateway (AS-5, AS-6): the gateway always
injects the mediated bearer on a session route, even towards an origin that only serves static files, with
no way to suppress it; and the remembered post-login return URL drops the query string, while the default
return URL is hard-coded to `/`, outside a deployment's context path. This plan adds `auth.token_relay`
(default `true`) and makes the return URL carry the query, with a configurable, boot-validated
`oidc.login.default_return_url`.

## Source

KIDICAP Gateway requirements AS-5 and AS-6 (`archive/api-sheriff-aenderungen.adoc` § AS-5, § AS-6).

**AS-5 — check the session without relaying the bearer (priority high).** Baseline (measured): on a route
with `require: session` the gateway always injects the mediated bearer; `forward.headers_deny:
["Authorization"]` does not prevent it. Need: UIs that let the gateway enforce login without handing the
frontend's origin a token.

```yaml
auth:
  require: session
  token_relay: false        # default true
```

The session is checked (including the redirect to login for page navigations), but no `Authorization`
is set; an inbound `Authorization` header is still not forwarded, as today. Acceptance: an echo origin
sees no `Authorization`; without a session, unchanged `302` (navigation) or `401` (XHR).

**AS-6 — login return address with query, configurable default (priority medium).** Baseline: a page
navigation to a session route without a session returns after login to the path WITHOUT query (measured);
the default `returnUrl` is fixed `/` (code: `LoginFlow.DEFAULT_RETURN_URL`). Proposal: the remembered
return address includes the query (still same-origin only); `oidc.login.default_return_url` configurable,
checked same-origin at boot, sensibly pointing at the portal path (PLAN-07). Acceptance:
`…/liste/1?tab=a` without a session → after login `…/liste/1?tab=a`; login without `returnUrl` → the
configured default.

Consumer migration (context only): session UI routes get `token_relay: false`; `default_return_url` set;
callers stop forcing a `returnUrl`.

## Deliverables

1. `auth.token_relay` (schema `auth` definition + `AuthConfig`, default `true`, inherits along the
   existing route → endpoint → anchor `auth` cascade); when `false`, `SessionAuthenticationStage` checks the
   session but sets no `Authorization`; inbound `Authorization` stripping unchanged.
2. Query-preserving return URL: `SessionAuthenticationStage.returnUrl` includes the original raw query
   (same encoding as received), and `LoginFlow` same-origin validation still applies.
3. `oidc.login.default_return_url` (schema + `OidcConfig.Login`), boot-time same-origin validation
   (boot failure otherwise), used when no valid `returnUrl` is supplied; `LoginFlow.DEFAULT_RETURN_URL`
   constant removed (pre-1.0, no fallback alias) or reduced to the schema default.
4. Tests for both acceptance sets (unit + integration against the echo origin), and user docs in
   `doc/user/bff-session.adoc` and `doc/configuration.adoc`; `doc/LogMessages.adoc` for any new log record.

## Claim Labels

- OBSERVED: no `token_relay` key exists in schema, `AuthConfig` or code; `SessionAuthenticationStage.process` unconditionally calls `request.mediatedBearer(session.accessToken())` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `process`
- OBSERVED: `LoginFlow.DEFAULT_RETURN_URL = "/"` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `DEFAULT_RETURN_URL` (line 63 at fb9e774)
- OBSERVED: `SessionAuthenticationStage.returnUrl` returns only `canonicalPath()`, dropping the query — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `returnUrl`
- OBSERVED: `OidcConfig.Login` carries only `path` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` § `Login`
- OBSERVED: `LoginFlow.initiate` already performs same-origin validation of `returnUrl` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate`
- HYPOTHESIS: the original raw query is reachable from `SessionAuthenticationStage` via `PipelineRequest` without re-encoding — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/` § `PipelineRequest` (verify-at-outline)
- Verify-first clause: confirm the raw query is available to the stage and that re-attaching it round-trips byte-identically (no decode/re-encode path that would reintroduce the AS-13 class of defect). Loop back if only a decoded map is reachable.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/AuthConfig.java` — `tokenRelay`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` — `Login.defaultReturnUrl`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — `process`, `returnUrl`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` — `initiate`, `DEFAULT_RETURN_URL`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json` — `auth` definition
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — `oidc.login`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/` — boot rule for `default_return_url` (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/` — `LoginFlowTest`, `SessionAuthenticationStageTest` (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-session.adoc` — session route documentation (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc` — `auth` and `oidc.login` keys (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none (`default_return_url` benefits from PLAN-07's `portal.path` but does not require it)
- Overlaps with: PLAN-05 and PLAN-09 (`AuthConfig.java`, `SessionAuthenticationStage.java`, `endpoint.schema.json` `auth` definition) — this plan lands first
- Adjacent to: PLAN-02 (query handling in the edge) — coordinate raw-query representation if in flight together

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-03-session-token-relay-return-url.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
