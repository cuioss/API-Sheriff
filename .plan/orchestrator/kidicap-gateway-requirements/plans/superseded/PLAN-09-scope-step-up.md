# PLAN-09: Scope Step-Up on a Granted-Scope Shortfall

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-09-scope-step-up.md` and is queued in the epic `status.json` `plans[]` field.
> The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.

## Objective

With additive per-endpoint scopes (PLAN-05), a session that logged in through one application lacks the
scopes of an endpoint it has not used yet. This plan adds the second half of AS-14: on a session route
whose mediated token does not cover `needed(request)`, a page navigation triggers a scope step-up
(authorize with `granted ∪ needed(request)`, silent first, interactive only without IdP SSO session, then
return to the request); an API call and any bearer route answer `403` with
`WWW-Authenticate: Bearer error="insufficient_scope", scope="<missing>"` (RFC 6750).

## Source

KIDICAP Gateway requirements AS-14 (`archive/api-sheriff-aenderungen.adoc` § AS-14), part 2 of 2 (scope
step-up). Priority high. Background, the `needed(request)` definition and additivity are in PLAN-05.

| Situation | Behaviour |
|---|---|
| Login | Request `needed(request)` of the request triggering the login (PLAN-05). |
| Session route, token covers `needed(request)` | Pass through. |
| Session route, scopes missing — scope step-up | Page navigation: authorize with `granted ∪ needed(request)`, first silent (`prompt=none`), interactive without IdP SSO session; then return to the request — the same path as the existing step-up (`StepUpCoordinator`), only with scopes instead of `acr_values`. API call: `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="<missing>"` (RFC 6750); the UI navigates so the step-up applies. |
| Bearer route | Only check `needed(request)` against the incoming token, `403 insufficient_scope`; the gateway cannot request more here. |

- Typical case: login via an application's UI requests the UI endpoint's scopes; the first call to its
  backend triggers, once per session, the step-up to the backend endpoint's scopes. Intended — the session
  grows by exactly what is needed.
- Consequence of additivity: granted scopes remain until the session ends, and the session is shared by
  all applications — after visiting application B the mediated token carries B's scopes also when A is
  called afterwards. This must be documented. Preventing it requires the source's "stage 2" (token
  narrowed per endpoint via scope-restricted refresh, RFC 6749 §6, or token exchange, RFC 8693, cached per
  scope set; Keycloak support with a shared client to be evaluated) — OUT OF SCOPE for this epic unless
  promoted by a decision.
- Acceptance: session without another endpoint's scopes: page navigation → step-up, silent, then `200`;
  API call → `403 insufficient_scope` with the missing scopes; after the step-up `200`. Bearer route
  without needed scope → `403 insufficient_scope`.

## Deliverables

1. Decide the step-up seam (verify-first clause): a scope-shaped sibling of the RFC 9470 step-up path, or
   a generalisation of `StepUpCoordinator` — recorded as an ADR.
2. Session-route step-up on page navigations: silent-then-interactive authorization for
   `granted ∪ needed(request)`, binding/state protection identical to the existing step-up, return to the
   original request; loop protection (no endless step-up when the IdP does not grant the scope → `403`).
3. `403` + `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"` for API calls on session
   routes and for bearer routes (unless PLAN-05 already delivered the bearer header — never both).
4. Tests for the acceptance set against Keycloak in integration tests (optional client scopes), docs
   in `doc/user/bff-session.adoc` including the session-wide scope growth, `doc/LogMessages.adoc`,
   threat-model update.

## Claim Labels

- OBSERVED: `StepUpCoordinator` implements silent-then-re-drive step-up, shaped for RFC 9470 (`acr_values` / `max_age`) and triggered by parsing an upstream `WWW-Authenticate` challenge (`StepUpChallengeParser`) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` § `coordinate`
- OBSERVED: a scope shortfall today throws `SCOPE_MISSING` without any step-up — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` § `enforceScopes`
- HYPOTHESIS: the engine's `StepUpChallenge` cannot be synthesized locally with a `scope` parameter, so the source's "same path, only with scopes" is not a direct reuse — confirm/refute at the external token client engine's `StepUpChallenge` / `StepUpHandler` API used by `StepUpCoordinator` (verify-at-outline)
- HYPOTHESIS: the engine's authorization request accepts an expanded per-request `scope` (shared question with PLAN-05) — confirm/refute at the engine authorization-request API used from `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` § `initiate` (verify-at-outline)
- Verify-first clause: settle both hypotheses (reuse PLAN-05's answer if landed) before scoping; if the engine lacks a scope-driven entry point, loop back — the plan is then gated on an engine release, recorded as such. This is the largest open design question of the epic.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java` — `enforceScopes`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` — `insufficient_scope` challenge
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — step-up wiring (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` — authorization request with expanded scopes (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinatorTest.java`
- HYPOTHESIS: `integration-tests/src/main/docker/` — Keycloak realm optional client scopes (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-session.adoc` — step-up documentation (verify-at-outline)
- HYPOTHESIS: `doc/adr/` — step-up seam ADR (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-05 (hard: `endpoint.scopes` and `needed(request)` must exist)
- Overlaps with: PLAN-03, PLAN-05 (`SessionAuthenticationStage.java`, `AuthenticationStage.java`); PLAN-01 (`BffRuntimeProducer.java`)
- Adjacent to: AS-14 stage 2 (per-endpoint token narrowing) — deliberately not staged

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-09-scope-step-up.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
