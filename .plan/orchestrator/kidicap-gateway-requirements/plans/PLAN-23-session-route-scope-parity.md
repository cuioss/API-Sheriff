# PLAN-23: A Session Route Obtains the Scopes It Declares Instead of Relaying an Under-Scoped Token

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-23-session-route-scope-parity.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-24 from inbox message `kidicap-gateway-downstream-010.md`, by operator decision,
> AHEAD of PLAN-19 and PLAN-21.

## Objective

One route, one declared scope set (`needed = oidc.scopes ∪ endpoint.scopes`) — but two different
outcomes depending on how the caller arrives. A bearer caller missing a needed scope is refused at the
gateway with `403 insufficient_scope`; a session caller missing the same scope has its token **relayed
unchanged**, leaving the upstream to refuse. The gateway enforces its own declaration for one access path
and silently ignores it for the other.

For the session path the gateway is the party that obtained the token, so it is the only party that can
repair it — an upstream refusal is a dead end for a browser user, with no route back to a wider login.
This plan makes a session route obtain what it declares: compare, refresh when the scope is inside the
grant, step up when it is outside, and refuse rather than relay when the IdP says no.

## Source

Inbox `kidicap-gateway-downstream-010.md` (finding, 2026-09-24), filed by the downstream deployment
`kidicap-gateway` while adopting 0.2.3 and classified by it as a **defect of highest priority**, ahead of
PLAN-19 and PLAN-21. Both halves of the asymmetry were re-verified by the orchestrator at HEAD before
staging — see Claim Labels.

**Why it is a defect rather than a policy choice**, as filed and accepted:

- The same route answers `403` at the gateway for a bearer caller and forwards an under-scoped token for
  a session caller. One declaration, two enforcement regimes.
- The session path's token was obtained BY the gateway; nobody downstream can repair it.
- ADR-0049 deferred the session pre-check "until a live session can be widened". PLAN-14 has since
  shipped exactly that — the active scope set `A` on the session record and the sealed cookie — so the
  stated precondition now holds.

**Why the session case is normal rather than an edge case:** a session reaches a route with an incomplete
token whenever the login did not start on that route — the portal (`require: none`), an application UI
under a `none` route, `/auth/login?returnUrl=` aimed at another endpoint's route, or a session
established through a different endpoint. With `endpoint.scopes` per application that is the ordinary
path: a user signs in on the portal, opens application A, and every session call of A carries a token
without `E(A)`.

**Relation to PLAN-20.** PLAN-20 wires the step-up that an UPSTREAM signals (`insufficient_scope`
relayed back from a backend). This plan needs no upstream signal: the gateway already knows the route's
needed set at boot. It is the narrower half, and the downstream states it must not wait for PLAN-20.
Where the two meet — the re-drive and the machine-readable step-up answer — this plan owns the mechanism
and PLAN-20 consumes it.

**Downstream workaround in place today:** one application, with its scopes folded into `oidc.scopes`, so
every login requests the full set. That does not survive a second application — it re-creates exactly the
over-request ADR-0049 set out to avoid. Downstream reference: `doc/open-issues.adoc#umgehungen`,
`doc/autorisierung/scopes.adoc`.

## Deliverables

1. **Session-route scope comparison before relay.** On `require: session`, compare the session's active /
   granted scope set (carried since PLAN-14 in the session record and the sealed cookie) against the
   route's `neededScopes`. A satisfied route performs **no** additional IdP call — no latency regression.
2. **Missing scopes INSIDE the grant `S` → refresh.** Refresh with `A ∪ missing` and relay the refreshed
   token; the measured Keycloak behaviour in `kidicap-gateway-downstream-009` is that a refresh can
   restore any scope of `S`. One refresh, no browser round trip.
3. **Missing scopes OUTSIDE `S`, top-level navigation → widen the session.** Authorization request for
   `S ∪ needed`, then return to the original URL through the existing re-drive. The session is **widened,
   never replaced**. Try `prompt=none` first where the IdP session allows it.
4. **Missing scopes OUTSIDE `S`, non-navigation (API/XHR) → a machine-readable step-up answer**, carrying
   a step-up URL under the reserved auth paths (shape as proposed in
   `kidicap-gateway-downstream-009`). Never a silent relay of an incomplete token.
5. **IdP refusal is terminal, not a loop.** `error=invalid_scope` or `login_required` leaves the existing
   session unchanged and answers `403`. No retry loop, and no under-scoped relay as a fallback.
6. **ADR amendment.** ADR-0049 recorded "session routes only request them"; this plan supersedes that half
   and must amend ADR-0049 (or supersede it with a new ADR that names it), stating what changed and why
   the deferred precondition is now met. ⚠ `main` currently carries two ADR-0053 files and PLAN-20 owns
   the renumber (its deliverable 9) — do not renumber here, and do not take 0053.
7. **Tests, one per acceptance row** below, plus the no-regression case.
8. **Documentation:** `doc/user/bff-session.adoc` (what a session call now does when a scope is missing),
   `doc/configuration.adoc`, `doc/security-threat-model.adoc` (the widening surface and the refusal path),
   `doc/LogMessages.adoc` for any new record.
9. **Folded 2026-09-25 from PLAN-19 (inbox `-002`) — retire the three caveats this plan falsifies.**
   PLAN-19 shipped `auth.session_fallback` with its SESSION branch running no scope check, and said so in
   three places: the `_auth_session_fallback` SESSION-branch row in `doc/configuration.adoc`, the
   session-fallback behaviour table ("No `Authorization`, live session") in `doc/user/bff-session.adoc`,
   and threat-model **BFF-16** "Nothing new is accepted". Each warns operators off enabling
   `session_fallback` where scopes are the authorization boundary. **When this plan lands those three
   statements become false**, so removing or rewriting them is part of its definition of done — a
   documentation gap left here would leave the repository advising against a configuration that is by
   then safe. Verify by re-reading all three, not by assuming the edit landed.

Split guard: 9 deliverables — within the operator-authorized 12 per plan.

## Acceptance (from the filing, verbatim in substance)

| Case | Required outcome |
|---|---|
| Login on the portal (`oidc.scopes` only), then a session call to a route of an endpoint with `endpoint.scopes: [x]`, `x` assigned to the client | the upstream sees a token containing `x` |
| Same, with `x` inside `S` but outside `A` | no browser round trip; exactly one refresh |
| Same, with `x` NOT assigned to the client | `403`; the session stays valid for routes that do not need `x` |
| A route whose needed set is already satisfied | no additional IdP call (no latency regression) |
| **Folded 2026-09-25 from PLAN-19:** the SESSION branch of a `require: bearer` route carrying `auth.session_fallback: true`, reached with no `Authorization` header and a session established elsewhere | the same outcome as the `require: session` rows above — the branch IS `SessionAuthenticationStage`, so the fix covers it with no extra mechanism. Fixture ready: IT route `bff-session-fallback` in `integration-tests/src/main/docker/sheriff-config/endpoints/bff-scoped.yaml`, already carrying `scopes: ["sheriff_it_endpoint"]` (orchestrator-verified at 1fa648da) |

## Claim Labels

- OBSERVED: the bearer path enforces the route's needed scopes and refuses at the gateway —
  `AuthenticationStage.java:156-162` reads `route.getNeededScopes()`, computes
  `content.determineMissingScopes(...)` and throws `insufficientScope(...)`, and its Javadoc (`:49-51`)
  documents the `WWW-Authenticate: Bearer error="insufficient_scope"` challenge. Re-verified by the
  orchestrator at HEAD 2f4254d3.
  - verdict: corroborated | checked_at: 1fa648da | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: AuthenticationStage still enforces getNeededScopes and throws insufficientScope on the bearer path; PLAN-19 added branch dispatch (AuthBranch) around it, not instead of it
- OBSERVED: the session path deliberately performs no scope check —
  `SessionAuthenticationStage.java:82-85` states "The stage runs **no scope check**: no session-route path
  answers `SCOPE_MISSING`", and `:206-208` requests `route.getNeededScopes()` only at login initiation on
  that route. Re-verified at HEAD 2f4254d3. This pair IS the asymmetry, read from the implementing source
  rather than from the filing.
  - verdict: corroborated | checked_at: 1fa648da | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: SessionAuthenticationStage:82-85 still states the stage runs NO scope check and answers no SCOPE_MISSING at 1fa648da - unchanged by PLAN-19, which routed the fix here
- OBSERVED: the precondition ADR-0049 deferred on is met — PLAN-14 shipped the active scope set `A` on
  the session record and the sealed cookie (`landings/PLAN-14.md`, deliverable 7).
  - verdict: corroborated | checked_at: 1fa648da | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: PLAN-14's active scope set A on the session record and sealed cookie unchanged through PLAN-16/17/18/19/22
- HYPOTHESIS: a refresh can restore any scope inside the grant `S`, so deliverable 2 needs no browser
  round trip. Measured DOWNSTREAM against Keycloak 26.7.4 (`kidicap-gateway-downstream-009`), not in this
  repository — confirm/refute at `integration-tests/` § a refresh IT requesting `A ∪ missing` against the
  test realm (verify-at-outline).
- HYPOTHESIS: the existing re-drive in `StepUpCoordinator` can be reused to widen rather than replace a
  session, so deliverable 3 adds no second re-drive mechanism — confirm/refute at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` § `coordinate`
  (verify-at-outline).
- HYPOTHESIS: `ScopedEngineFlows` (PLAN-14's per-scope-set engine seam, ADR-0048) is the seam both the
  refresh leg and the widening authorization request ride, so no new engine wiring is needed —
  confirm/refute at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java` § its authorize
  and refresh entry points (verify-at-outline).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java`
  — the comparison, the refresh leg, and the branch to the step-up answer
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` — the
  re-drive reused to widen a session
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java`
  — the refresh with `A ∪ missing`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java` — the
  per-scope-set authorize / refresh seam
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionRecord.java` — the
  active scope set `A` after a widening
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/RouteRuntime.java` —
  `getNeededScopes()`, read on the session path for the first time
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` — the step-up URL
  endpoint for the non-navigation answer (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/` — unit coverage per acceptance row
  (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionScopeParityIT.java`
  — the acceptance table end to end against the Keycloak realm. Declared as the one expected IT file
  rather than the whole IT directory, per the over-declaration lesson from PLAN-22 (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/` — a realm client and an endpoint whose
  `endpoint.scopes` are not in `oidc.scopes` (verify-at-outline)
- OBSERVED (added 2026-09-25, re-grounding against PLAN-19's merge 1fa648da):
  `integration-tests/src/main/docker/sheriff-config/endpoints/bff-scoped.yaml` — carries the ready
  `bff-session-fallback` fixture route (`scopes: ["sheriff_it_endpoint"]`) for deliverable 9's acceptance row
- OBSERVED (added 2026-09-25): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthBranch.java`
  and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/AuthenticationStage.java` — PLAN-19's branch
  dispatch, which decides whether a request reaches the session path this plan changes
- OBSERVED: `doc/adr/` — the ADR-0049 amendment (deliverable 6); do NOT take ordinal 0053
- OBSERVED: `doc/user/bff-session.adoc`, `doc/configuration.adoc`, `doc/security-threat-model.adoc`,
  `doc/LogMessages.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-14 — satisfied. The active scope set `A`, `endpoint.scopes`, `neededScopes` and
  `ScopedEngineFlows` all shipped in #337.
- Runs BEFORE PLAN-19 and PLAN-21 by operator decision (2026-09-24), on the downstream's stated priority.
- Overlaps with: PLAN-19 (`SessionAuthenticationStage`, schemas, BFF tests, docs), PLAN-20 (the re-drive,
  `StepUpCoordinator`, `SessionRecord`, docs), PLAN-21 (docs, BFF login tests) — so it is **sequenced
  against all three**, not paired with any of them. WS-04 is a single contended surface; this epic has
  never had two WS-04 plans in flight.
- **PLAN-20 must be re-grounded after this lands**: this plan implements the gateway-side half PLAN-20's
  spec assumed was absent, and it owns the widening mechanism PLAN-20 will consume. Expect PLAN-20's
  deliverables 1–3 to narrow.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-23-session-route-scope-parity.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
