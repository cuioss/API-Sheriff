# PLAN-27: Ship strict refresh-token rotation in the realms and prove end to end that a replayed or IdP-revoked refresh token ends the gateway session

epic: deployment-configurability
workstream: WS-04

> ⛔ **SUPERSEDED 2026-09-15 by `PLAN-26-refresh-failure-dispositions.md`, which carries this plan's scope as its
> deliverables 5-9, 11 and 12.** Retained as the audit record; a superseded spec is never deleted. **Do not emit.**
> **Why:** operator direction the same session — *"do not split into too fine granular plans; remember the 12
> deliverables per plan"*. Split off from PLAN-26 by the orchestrator minutes earlier to keep the integration-test
> lane separate; that split cost a second PR cycle for one sequential stream. Its queue row is `parked` because
> the queue status vocabulary has no `superseded` value.
>
> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-15 by operator decision** (option D for PLAN-26's reuse-detection question — see epic
> `## Decisions` § "Refresh-token reuse detection — operator decision"). PLAN-26 corrects the docs to say
> refresh-token reuse detection is **IdP-side**; this plan is the other half of that decision: make the
> IdP-side defence the **shipped posture** and give the claim a **test** instead of a sentence.
> ⛔ **Security-bearing, integration-test lane.**

## Objective

After PLAN-26 the documented replay defence is IdP-side refresh-token rotation with strict reuse: a
replayed superseded refresh token draws `invalid_grant`, the engine classifies that `CREDENTIAL_REJECTED`,
and the gateway ends the session. Today no shipped Keycloak realm enables strict rotation, so the shipped
stacks demonstrate no reuse detection at all, and no integration test drives a replay.

Enable strict rotation in the sample and integration realms, prove through the running gateway that a
replayed refresh token and an IdP-side revocation each end the session, and prove it by reversion.

## Deliverables

1. **Enable strict rotation in the compose sample realm.** `deployment/compose-sample/docker/keycloak/sample-realm.json`:
   `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`. State in `doc/user/compose-sample.adoc` that the
   sample runs strict rotation, what it defends against, and the cost operators take on with it (a
   duplicate refresh from two gateway instances now ends the session — the trade `doc/variants/03-bff-cookie.adoc`
   already describes).
2. **Enable strict rotation in the integration realm, and keep the existing refresh suites honest.**
   `integration-tests/src/main/docker/keycloak/integration-realm.json`, same two keys. ⚠ `BffTokenRefreshIT`
   and `BffCookieRefreshIT` must stay green **for the right reason**: if either passes today only because
   Keycloak tolerates a second redemption of the same refresh token, that is a finding to report and fix
   in the test — never a reason to relax the realm back.
3. **Cookie mode: prove a replayed superseded sealed cookie ends the session.** In the stateless mode the
   refresh token rides inside the sealed cookie, so replay *through the gateway* is reachable: complete a
   refresh, then present the pre-refresh cookie. Assert the gateway presents the superseded refresh token,
   the IdP rejects it, the session ends with the `session.refresh.on_failure` response, and the
   `CREDENTIAL_REJECTED` disposition's log record (PLAN-26) is emitted.
4. **Server mode: prove IdP-side revocation ends the session at the next refresh.** In server mode the
   gateway store never presents a superseded token, so replay through the gateway is not the reachable
   path — IdP-side revocation is. Revoke the session's grant at the IdP (admin session logout or offline
   revocation), drive a near-expiry request, and assert the session ends. ⚠ Establish first what Keycloak
   actually does on a detected reuse (does it revoke the successor and the user session, or reject only
   the one token?) and record the answer — the threat model's wording depends on it.
5. **Close the IdP-initiated logout gap PLAN-26's report names.** PLAN-26 deliverable 9 reports, read-only,
   which IdP-initiated revocation paths reaching a gateway session are covered by `BffLogoutIT`,
   `BffCookieBackchannelDisabledIT` and `BffTokenRefreshIT`. Cover whichever reachable path that report
   finds uncovered, or record why it is unreachable. ⛔ Read that report from PLAN-26's landing before
   outlining this deliverable; do not re-derive it.
6. **Prove every new assertion by reversion, not by green.** With the two realm keys reverted, deliverable 3's
   replay test must go red; with PLAN-26's `CREDENTIAL_REJECTED` destroy reverted (a local mutation, not
   committed), deliverables 3 and 4 must go red. ⛔ **Before any reversion run, confirm the running
   container's `org.opencontainers.image.revision` label equals the worktree HEAD** — local
   `api-sheriff:*` images are shared across concurrent plans (epic Decisions 2026-09-15; local lesson
   `2026-09-15-16-001`), and a proof against a foreign image proves nothing.
7. **Make the documents cite the mechanism and its test.** `doc/security-threat-model.adoc` reuse-detection
   status names the realm setting as the shipped control and the new IT as its evidence; the
   *"What this suite does NOT prove"* paragraphs of `BffTokenRefreshIT` (`:64`) and `BffCookieRefreshIT`
   point at the new suite for reuse.

## Claim Labels

- OBSERVED: `deployment/compose-sample/docker/keycloak/sample-realm.json`,
  `integration-tests/src/main/docker/keycloak/integration-realm.json` and
  `integration-tests/src/main/docker/keycloak/benchmark-realm.json` contain no `revokeRefreshToken` key at
  `fb65222`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: sample-realm.json and integration-realm.json now both carry revokeRefreshToken:true/refreshTokenMaxReuse:0, landed via successor PLAN-26
- OBSERVED: `BffTokenRefreshIT.java:64-66` states the suite does not exercise refresh-token reuse or family
  revocation, and does not exercise an IdP failure *other than* `invalid_grant`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: BffTokenRefreshIT's does-not-exercise-replay framing still accurate, coverage now lives in new BffRefreshReuseIT.java
- OBSERVED: `BffLogoutIT` exercises the server-mode RP-initiated logout leg with the IdP `TokenRevocation`
  seam bound as a best-effort no-op (`BffLogoutIT.java:28-34`).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: stable code fact about BffLogoutIT's best-effort no-op TokenRevocation seam
- OBSERVED: `integration-tests/src/main/docker/` carries `sheriff-config-refresh` (server mode) and
  `sheriff-config-cookie-refresh` (cookie mode) instances; `BffCookieRefreshIT` targets the latter.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: sheriff-config-refresh and sheriff-config-cookie-refresh instances still exist
- HYPOTHESIS: Keycloak with `revokeRefreshToken: true` and `refreshTokenMaxReuse: 0` answers a replayed
  superseded refresh token with `invalid_grant` — confirm/refute against the running Keycloak's token
  endpoint response in deliverable 3's test (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed via the successful landing of BffRefreshReuseIT.java against the strict-rotation realms
- HYPOTHESIS: no benchmark scenario exercises refresh, so `benchmark-realm.json` is deliberately left
  unchanged — confirm/refute at `benchmarks/src` § any refresh-token use (a grep at `fb65222` found none;
  verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: benchmark-realm.json remains undeclared/unchanged, consistent with no refresh use in benchmarks
- HYPOTHESIS: an existing refresh IT redeems the same refresh token twice and would go red under strict
  rotation — confirm/refute at `BffTokenRefreshIT` and `BffCookieRefreshIT` § their refresh drivers
  (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: BffTokenRefreshIT/BffCookieRefreshIT stayed green through the realm change
- Verify-first clause: PLAN-26 changes the refresh dispositions and their log records first; every
  assertion here is written against PLAN-26's landed behaviour, re-read at HEAD.

## Expected Surface

- OBSERVED: `deployment/compose-sample/docker/keycloak/sample-realm.json`
- OBSERVED: `integration-tests/src/main/docker/keycloak/integration-realm.json`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffRefreshReuseIT.java` — new file; name confirmed at outline
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffTokenRefreshIT.java` — javadoc pointer, and a fix only if deliverable 2 finds a double redemption
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffCookieRefreshIT.java` — same
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffLogoutIT.java` — only if deliverable 5 extends it rather than adding to the new suite (verify-at-outline)
- OBSERVED: `doc/user/compose-sample.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- HYPOTHESIS: `doc/development/integration-test-topology.adoc` — only if a new instance or realm client is added (verify-at-outline)

⛔ **Named files only.** `benchmark-realm.json` is deliberately not declared.

## Dependencies and Sequencing

- ⛔ **Runs after PLAN-26 lands** — asserts PLAN-26's dispositions and log records, reads PLAN-26's
  deliverable-9 report, and overlaps it on `doc/security-threat-model.adoc`.
- ⛔ **Not concurrent with any other integration-test-lane plan** (currently PLAN-18) — shared local
  `api-sheriff:*` images, an undeclared surface.
- ✅ May run concurrently with PLAN-23 (unit lane, `api-sheriff/src/test/` only).
- ⛔ **Both gates run.** Realm JSON is exercised by `-Pintegration-tests`; run that lane, not only the
  quality gate.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-27-idp-refresh-reuse-strict-rotation-e2e.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
