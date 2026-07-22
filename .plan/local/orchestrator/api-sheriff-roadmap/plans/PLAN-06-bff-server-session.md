# PLAN-06: BFF Foundation + Variant 2 — Server Session (roadmap 07)

epic: api-sheriff-roadmap
workstream: WS-03

> Staged plan spec. Source of truth: `doc/plan/07-bff-server-session.adoc` — read in full at
> launch; this spec is a summary staged from `doc/plan/README.adoc`.

## Objective

Execute `doc/plan/07-bff-server-session.adoc`: the BFF foundation, stateful shape first
(operator decision 2026-07-15) — token-sheriff-client 0.9.2 as the OIDC confidential-client
engine, reserved gateway paths, pending-authorization record, in-memory session store +
opaque `__Host-` cookie, `require: session` runtime with content negotiation, fixed CSRF
defence, transparent refresh, RP-initiated + back-channel logout, RFC 9470 step-up (Variant 2).

## Deliverables

Per the plan doc at launch. Largest/riskiest block of the epic — expect a multi-PR split
inside the plan lifecycle.

### Folded scope: session/user-info endpoint (operator, 2026-07-20)

NOT yet in `doc/plan/07-bff-server-session.adoc` — the plan must fold it in doc-first
(variant doc 02 + `configuration.adoc` + plan doc in the same PR; docs govern):

- One additional reserved gateway path (exact match, OIDC host, before the route table —
  same D2 registration rules as callback/logout) serving session/identity info to the
  browser client.
- Shape (operator decision 2026-07-20, AskUserQuestion): **curated default + parameter,
  config-allowlisted**. Default response: sensible identity subset (`sub`, name, roles)
  plus session metadata (session expiry, `auth_time`, `acr`). A claims/view parameter
  selects specific claims or the full view; EVERY response is filtered through a
  configured claim allowlist with a secure default — the operator caps disclosure,
  never the browser client.
- Source: validated ID-token claims + session metadata only. NEVER raw tokens
  (access/refresh/ID) in any view.
- Semantics: no valid/active session → `401 application/problem+json`, never a redirect
  (XHR probe; composes with D4 content negotiation). `403` reserved for CSRF rejection /
  disallowed-claim requests. `Cache-Control: no-store` on every response.
- Built above the session-resolution seam so PLAN-07 (cookie variant) inherits it with
  minimal delta (claims from the sealed cookie instead of the store).
- Tests: 401-not-redirect for XHR, default-set content, allowlist filtering (requested
  claim outside allowlist absent/rejected), no-token-material-in-response, no-store header.
- Prior art reference for the plan: Duende BFF `/bff/user` (single endpoint, claims +
  expiry, 401 unauthenticated).

### Folded scope: login-initiation endpoint (operator, 2026-07-20)

Gap found while staging PLAN-11 (demo SPA) and verified against the plan doc: **D2's reserved
paths are exactly four** — callback, logout, logout-return, back-channel. There is **no
login-initiation path anywhere in the BFF design**. Login is only ever triggered *implicitly*,
by D4's content negotiation on a navigation request to some `require: session` route.

That is insufficient the moment a SPA is served from an asset anchor (`type: asset`,
`access: public` — PLAN-10): it gets a
`401` from the info endpoint (correctly — never a redirect for XHR) and then has **no defined
URL to navigate to** in order to start login. Operator decision: add an explicit
login-initiation reserved path here, not later.

- A fifth reserved gateway path under the **same D2 rules** (exact match, OIDC host, before
  the route table), e.g. `/{oidc}/login`, accepting a `returnUrl`-style parameter.
- **Reuses existing machinery** — the D2b pending-authorization record already carries the
  post-login return URL with **same-origin validation** and the browser-binding cookie. This
  is a small delta on what this plan already builds, not new subsystem work.
- Security: the return URL is same-origin-validated exactly as D2b requires — **never an open
  redirect**. An already-authenticated caller hitting it must not silently re-drive a full
  auth-code flow; define that behaviour explicitly.
- Prior art: Duende BFF `/bff/login?returnUrl=` — the same endpoint pair as `/bff/user`.
- Tests: login initiation from an unauthenticated browser lands in the auth-code flow and
  returns to the validated return URL; an off-origin `returnUrl` is rejected.
- Doc-first with the rest of this fold: variant doc 02 Login Flow section + `configuration.adoc`
  reserved-paths list (which currently enumerates four) in the same PR.
- PLAN-07 (cookie) and PLAN-11 (demo SPA) both consume this; PLAN-11 does **not** introduce it.

## Expected Surface

- `api-sheriff/` BFF/session packages, reserved-path routing, token-sheriff-client wiring
- `integration-tests/` Keycloak-backed BFF suites; `benchmarks/` hot-path additions

## Dependencies and Sequencing

- Depends on: PLAN-02 (roadmap 03), PLAN-03 (roadmap 04); consumes `oidc` config from delivered plan 02
- Overlaps with: PLAN-07-bff-cookie builds directly on it

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/07-bff-server-session.adoc in full (read it and its required reading first; the design docs govern). Deliver the BFF foundation and Variant 2 (server session) per the plan's work breakdown, with integration tests and benchmarks in the same plan. ADDITIONAL SCOPE (operator decision 2026-07-20, fold doc-first into variant doc 02 + configuration.adoc + the plan doc in the same PR): a session/user-info reserved endpoint for the browser client — one additional reserved gateway path under the D2 rules (exact match, OIDC host, before the route table). Default response: curated identity subset (sub, name, roles) + session metadata (session expiry, auth_time, acr); a claims/view parameter selects specific claims or the full view, with EVERY response filtered through a configured claim allowlist with a secure default (operator caps disclosure, never the client). Source is validated ID-token claims + session metadata only — never raw access/refresh/ID tokens in any view. No valid/active session → 401 application/problem+json (never a redirect; composes with D4 content negotiation); 403 reserved for CSRF rejection / disallowed-claim requests; Cache-Control: no-store on every response. Build it above the session-resolution seam so Plan 08's cookie variant inherits it with minimal delta. Tests: 401-not-redirect for XHR, default-set content, allowlist filtering, no token material in any response, no-store header. SECOND ADDITIONAL SCOPE (operator decision 2026-07-20, same doc-first treatment): a LOGIN-INITIATION reserved path. Verified gap — D2 currently defines exactly four reserved paths (callback, logout, logout-return, back-channel) and login is only ever triggered implicitly by D4 content negotiation on a navigation request to a require:session route. A SPA served from a asset anchor (type: asset, access: public — roadmap plan 10) gets a 401 from the info endpoint and then has no defined URL to navigate to in order to start login. Add a fifth reserved path (e.g. /{oidc}/login) under the SAME D2 rules — exact match, OIDC host, before the route table — accepting a returnUrl-style parameter. Reuse the D2b pending-authorization record, which already carries the post-login return URL with same-origin validation plus the browser-binding cookie: this is a small delta on machinery this plan already builds, not a new subsystem. The return URL MUST be same-origin-validated exactly as D2b requires — never an open redirect; define explicitly what an already-authenticated caller hitting the endpoint does rather than silently re-driving a full auth-code flow. Prior art: Duende BFF /bff/login?returnUrl= (the endpoint pair to /bff/user). Tests: unauthenticated login initiation lands in the auth-code flow and returns to the validated return URL; an off-origin returnUrl is rejected. Update configuration.adoc's reserved-paths list (it currently enumerates four) and the variant doc's Login Flow section in the same PR. Roadmap plan 08 (cookie variant) and the later demo-client plan both consume this endpoint. DOCUMENTATION (standing epic convention, operator 2026-07-20 — applies to every plan; THREE layers, all in the SAME PR): (a) REFERENCE — every new or changed configuration key, default, enum value, and endpoint this plan introduces MUST land in doc/configuration.adoc, and structural changes in doc/architecture.adoc; these are the exhaustive per-key references and they are not optional. (b) OPERATOR GUIDE — doc/user/, task-oriented (how an operator accomplishes the thing), linking to the reference rather than duplicating its key tables. (c) DEVELOPER GUIDE — doc/development/, contributor-facing (build, test, run, extend). The doc/user/ and doc/development/ trees and the convention itself are created by roadmap plan 10 (anchor types + assets) — NOT by plan 04b, which shipped without them; follow what doc/plan/README.adoc records once plan 10 lands. Updating plan docs, variant docs or ADRs does NOT satisfy (a), (b) or (c). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it. SONAR ZERO-FINDINGS (standing epic convention, operator 2026-07-21): the SonarCloud quality gate (project cuioss_API-Sheriff) must be GREEN before merge — a red gate is a HARD STOP, never merge over red or on a stale green while analysis is still pending; deliver ZERO new findings (new bugs = 0, new vulnerabilities = 0, new code smells = 0, security hotspots 100% reviewed, new_reliability / new_security / new_maintainability ratings all A, new coverage >= 80%); the GOAL is to FIX findings, and where a fix is genuinely not sensible an IN-CODE suppression carrying a documented rationale (e.g. // NOSONAR or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo alongside the code — what is NOT acceptable is reaching zero by silent server-side won't-fix / false-positive marking; and this plan is NOT 'done' / 'all green' until the PROJECT-level gate on the merged result is green — the completion report must reflect the actual post-merge gate, not a transient PR-scoped new-code green.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-06.md)
