# PLAN-26: Classify refresh failures instead of destroying the session on all of them, make IdP-side reuse detection the shipped and tested defence, and correct the guarantee the gateway claims but does not wire

epic: deployment-configurability
workstream: WS-04

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-15 from a cross-repo finding** relayed by the operator: Token-Sheriff epic
> `lessons-handling-26-09-04-01`, finding `e7dd80` (Token-Sheriff PLAN-10, PR cuioss/TokenSheriff#725).
> Every claim of that finding was re-verified by the orchestrator against API-Sheriff `origin/main`
> `fb65222`, `token-sheriff-client-0.9.5.jar` and Token-Sheriff tag `0.9.5` — and the verification found a
> **second, larger gap the finding did not name** (the reuse-detection guarantee, deliverables 5-9).
> ⛔ **Re-cut 2026-09-15 by operator direction**: the reuse-detection question was settled as *option D*
> (IdP-side detection, enabled in the shipped realms, proven end to end), and the end-to-end half — briefly
> staged as `PLAN-27-idp-refresh-reuse-strict-rotation-e2e.md`, now superseded — is folded back in here.
> One plan, up to 12 deliverables, per the operator's standing rule.
> ⛔ **Security-bearing; unit AND integration-test lanes.** This plan changes when a session ends.

## Objective

`TokenRefreshCoordinator.performRefresh` catches every `TokenSheriffException` raised during a refresh
and destroys the session. The engine API Sheriff pins (`token-sheriff-client` 0.9.5) ships a failure
classifier — `RefreshFlow.classify(Throwable)` → `RefreshFailureClassification.Kind` — whose contract is
that the three failure kinds owe **different** dispositions, and API Sheriff never calls it. A transient
IdP outage, DNS failure or `5xx` during near-expiry refresh therefore logs a user out although their
refresh token was never touched.

Worse, the coordinator's javadoc and the threat model both attribute **refresh-token reuse detection**
to "the engine" (`RefreshTokenFamily` + `TokenLifecycleManager`) and mark server mode **COVERED** — but
the gateway calls `RefreshFlow.refresh` directly and references neither class, and no shipped Keycloak
realm enables IdP-side strict rotation either. Today no reuse detection exists on either side.

Dispatch refresh failures by the engine's classification; make IdP-side strict rotation the documented
**and shipped** replay defence; prove through the running gateway that a replayed or IdP-revoked refresh
token ends the session; and make every document state what actually happens.

## Deliverables

### Part A — refresh failure dispositions (unit lane)

1. **Classify the refresh-grant failure — and only that.** Run `RefreshFlow.classify` over the exception
   `refreshExchange.exchange(...)` raises and dispatch with an exhaustive `switch` over
   `RefreshFailureClassification.Kind` with **no default arm**, so a future fourth kind is a compile
   error. ⚠ The current `try` also wraps `rotate(...)` and `sessionBinding.persist(...)`; a failure there is
   not a refresh-grant failure and must not be classified (it would read as `PRE_REDEMPTION`). Narrow the
   classified region to the exchange and decide the post-exchange failure disposition separately — a
   `persist` failure happens *after* the IdP rotated, so the presented token is already burned.
2. **`PRE_REDEMPTION`: never destroy the session.** The presented refresh token is untouched and still
   valid. Decide deliberately what *this request* gets — serve it on the still-valid access token if any
   leeway remains, or fail only this request per `session.refresh.on_failure` without ending the session —
   and what coalesced waiters and the next request see (no refresh storm against a failing IdP: bound the
   retry). Emit a distinct structured `LogRecord` that does not claim "session destroyed". ⛔ Record the
   decision in the class javadoc and an ADR, because it changes a documented contract:
   `doc/configuration.adoc` currently states for `session.refresh.on_failure` that *"the session is
   destroyed either way"*.
3. **`CREDENTIAL_REJECTED`: destroy the session** — the credential is dead, no retry revives it, and this
   is the case a replayed token rejected by the IdP arrives as (deliverable 5). Keep `ApiSheriff-111`'s
   wording true for it.
4. **`REDEEMED`: dispose from the `RefreshRedemption` record, not like `PRE_REDEMPTION`.** The AS consumed
   the grant before a client-side check refused the result; `presentedTokenBurned()` says whether the
   stored token is dead, and `rotated(successor)` / `notRotated()` / `rotationUnknown()` say whether a live
   successor exists that nothing will ever use. Decide per case whether the session ends and whether a
   rotated successor is revoked (RFC 7009) or knowingly abandoned, and record why. ⚠ API Sheriff wires no
   revocation client today — if revocation is chosen, that wiring is in scope. (Token-Sheriff's
   `TokenLifecycleManager` at 0.9.5 quarantines and revokes the successor in this case — a reference
   design, not a dependency.)

### Part B — reuse detection is the IdP's job, shipped and proven (operator decision, option D)

5. **Correct the guarantee, and enable strict rotation in the shipped realms.** ✅ **Operator decision
   2026-09-15** (epic `## Decisions` § "Refresh-token reuse detection — operator decision"): the gateway
   does **not** wire `RefreshTokenFamily` or `TokenLifecycleManager`. Rationale: API Sheriff is a
   confidential client, so a gateway-side family only sees tokens the gateway itself presents — in server
   mode the store holds only the current refresh token under single-flight; in cookie mode a family cannot
   be shared across stateless instances. The defence both modes have is IdP-side rotation with strict
   reuse: a replayed superseded token draws `invalid_grant` → `CREDENTIAL_REJECTED` → session ends.
   - Correct the coordinator javadoc (*"inside its own refresh-token-family primitive"*) and
     `doc/security-threat-model.adoc` (the server-mode **COVERED** status and the *Control* paragraph naming
     `RefreshTokenFamily` + `TokenLifecycleManager`); document the IdP setting as the operator control in
     `doc/configuration.adoc` and the variant docs, including the residual: without it there is **no**
     reuse detection on either side.
   - Enable `revokeRefreshToken: true` / `refreshTokenMaxReuse: 0` in
     `deployment/compose-sample/docker/keycloak/sample-realm.json` and
     `integration-tests/src/main/docker/keycloak/integration-realm.json`, and say so in
     `doc/user/compose-sample.adoc` with the cost operators take on (a duplicate refresh from two gateway
     instances now ends the session — the trade `doc/variants/03-bff-cookie.adoc` already describes).
   - ⛔ Do not let deliverable 2's keep-session rule swallow a reuse signal: the IdP's rejection arrives as
     `CredentialRejectedException` (deliverable 3). A `ClientProtocolException` from any future
     gateway-side family would classify as `PRE_REDEMPTION` — out of scope here, and routed to Token-Sheriff.
6. **Keep the existing refresh suites honest under strict rotation.** `BffTokenRefreshIT` and
   `BffCookieRefreshIT` must stay green **for the right reason**: if either passes today only because the
   IdP tolerates a second redemption of the same refresh token, that is a finding to report and fix in the
   test — never a reason to relax the realm.
7. **Cookie mode, end to end: a replayed superseded sealed cookie ends the session.** The refresh token
   rides inside the sealed cookie, so replay *through the gateway* is reachable: complete a refresh, present
   the pre-refresh cookie, and assert the gateway presents the superseded refresh token, the IdP rejects it,
   the session ends with the `session.refresh.on_failure` response, and deliverable 3's log record is emitted.
8. **Server mode, end to end: IdP-side revocation ends the session at the next refresh.** The server-mode
   store never presents a superseded token, so IdP-side revocation is the reachable path: revoke the
   session's grant at the IdP, drive a near-expiry request, assert the session ends. ⚠ First establish what
   Keycloak actually does on a detected reuse — revoke the successor and the user session, or reject only the
   one token — and record the answer; the threat-model wording of deliverable 5 depends on it.
9. **IdP-initiated logout reaching a gateway session.** Token-Sheriff reports this was never exercised end to
   end. Read `BffLogoutIT` (RP-initiated leg, IdP `TokenRevocation` bound as a best-effort no-op),
   `BffCookieBackchannelDisabledIT` and `BffTokenRefreshIT`; cover the reachable path none of them covers, or
   record why it is unreachable.

### Across both parts

10. **Tests at the unit level — one per `Kind`, plus waiters and the reuse model.** In
    `TokenRefreshCoordinatorTest`: each `Kind` asserts whether the session is destroyed and which
    `RefreshOutcome` is returned; coalesced single-flight waiters observe the leader's outcome for every
    kind; a `persist` failure after a successful exchange takes deliverable 1's disposition. ⛔ **Rewrite
    `shouldFailOnReuseDetection`**: it fakes the exchange throwing `ClientProtocolException("refresh token
    family is revoked")` — under `classify` that is `PRE_REDEMPTION`, so after deliverable 2 it either goes
    red or gets "fixed" by asserting the session survives. It must model the IdP rejecting a replayed token:
    the exchange throwing `CredentialRejectedException`.
11. **Prove every disposition and every end-to-end assertion by reversion, not by green.** Unit: restore
    destroy-on-all and the `PRE_REDEMPTION` test goes red; collapse `REDEEMED` into `PRE_REDEMPTION` and its
    test goes red; make `CREDENTIAL_REJECTED` keep the session and the reuse test goes red. End to end: revert
    the two realm keys and deliverable 7 goes red; locally revert the `CREDENTIAL_REJECTED` destroy and
    deliverables 7 and 8 go red. ⛔ **Before any IT reversion run, confirm the running container's
    `org.opencontainers.image.revision` label equals the worktree HEAD** — local `api-sheriff:*` images are
    shared across concurrent plans (epic Decisions 2026-09-15; local lesson `2026-09-15-16-001`), and a proof
    against a foreign image proves nothing.
12. **Make every document that states the failure or reuse contract true, and cite the evidence.** The class
    javadoc's *On-failure semantics* paragraph (*"destroy the session on any refresh failure"*);
    `doc/configuration.adoc` § `session.refresh.on_failure`; `doc/LogMessages.adoc` `ApiSheriff-111` and any
    new record; `doc/security-threat-model.adoc` reuse status naming the realm setting as the shipped control
    and the new IT as its evidence; `doc/variants/02-bff-session.adoc` (*"on refresh failure the session is
    destroyed"*); `doc/variants/03-bff-cookie.adoc` (*"a refresh rejected as reuse is treated as an ordinary
    refresh failure"*); `doc/features-analysis.adoc` refresh row; and the *"What this suite does NOT prove"*
    paragraphs of `BffTokenRefreshIT` (`:64`) and `BffCookieRefreshIT`, pointed at the new suite.
    ⛔ **Folded in 2026-09-16 at PLAN-25's landing — the TLS scenario guide is understated again.** PLAN-25
    (#306, `7ba9734`) landed two new peer keys, `egress_tls.oidc_verify_hostname` (default `true`) and
    `egress_tls.oidc_tls_profile`, which pin the BFF OIDC back-channel — the very leg this plan's
    dispositions run over. They are documented in `doc/configuration.adoc` (24 hits) and `doc/user/tls-edge.adoc`
    (1) but appear **zero** times in `doc/user/tls-scenarios.adoc`, whose scenario 9 is *"Relax outbound
    hostname verification"*. Add them there, including what a profile REPLACES (`ApiSheriff-119` semantics).
    The guide was written by PLAN-24 before these keys existed — the same understatement PLAN-09 carried.

## Claim Labels

- OBSERVED: `TokenRefreshCoordinator.java:171` catches `TokenSheriffException` around
  `refreshExchange.exchange(...)` (`:163`), `rotate(...)` (`:164`) and `sessionBinding.persist(...)`
  (`:168`), calls `sessionBinding.destroy(latest)` (`:174`) and returns `RefreshOutcome.failed()` (`:177`).
  Unchanged between `fb9e774` and `fb65222`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: TokenRefreshCoordinator.performRefresh now calls RefreshFlow.classify and dispatches per Kind rather than uniformly destroying the session
- OBSERVED: `api-sheriff/src` holds zero references to `RefreshFlow.classify`,
  `RefreshFailureClassification`, `CredentialRejectedException`, `RefreshTokenFamily`,
  `TokenLifecycleManager` or `RevocationClient` — grep at `fb65222`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: api-sheriff/src now references RefreshFlow.classify, CredentialRejectedException and related types extensively
- OBSERVED: `pom.xml:63` pins `version.token-sheriff` `0.9.5`; `token-sheriff-client-0.9.5.jar` contains
  `flow/RefreshFlow` with `public static RefreshFailureClassification classify(Throwable)`,
  `RefreshFailureClassification$Kind`, `CredentialRejectedException` and `RefreshRedemption` (`javap`).
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: pom.xml now pins version.token-sheriff at 0.9.6, not 0.9.5
- OBSERVED: at Token-Sheriff tag `0.9.5`, `RefreshFlow.classify` maps `RedeemedRefreshFailure` →
  `REDEEMED`, `RedeemedResponseException` → `REDEEMED(rotationUnknown)`, `CredentialRejectedException` →
  `CREDENTIAL_REJECTED`, and **everything else** → `PRE_REDEMPTION`; its class javadoc states the caller
  feeds rotations into its `RefreshTokenFamily`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: external library classification mapping is a stable released-artifact fact
- OBSERVED: at Token-Sheriff tag `0.9.5`, `RefreshTokenFamily.rotate(presentedToken, rotatedToken)` throws
  `ClientProtocolException` when the family is revoked or reuse is detected — a type `classify` maps to
  `PRE_REDEMPTION`; `TokenLifecycleManager` handles reuse itself before reaching that classification.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: external library fact, stable
- OBSERVED: `BffRuntimeProducer.java:288` binds the exchange seam as
  `refreshToken -> refreshFlow.refresh(metadata.get(), refreshToken)`, so the engine's exception reaches the
  coordinator unwrapped; `BffRuntimeProducer` is the only other main-code consumer of `RefreshOutcome`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: BffRuntimeProducer still binds the refresh exchange seam to refreshFlow.refresh, line number moved only
- OBSERVED: `doc/security-threat-model.adoc:1115-1128` attributes reuse detection to *"`RefreshTokenFamily`
  + `TokenLifecycleManager`"* and marks server mode *"COVERED"*.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: security-threat-model.adoc:1578 now attributes replay defence to Keycloak revocation/rotation, corrected by this plan's own deliverable 5
- OBSERVED: `TokenRefreshCoordinatorTest.shouldFailOnReuseDetection` (`:252`) models reuse as the exchange
  throwing `ClientProtocolException`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: TokenRefreshCoordinatorTest.java now models reuse via CredentialRejectedException, not a ClientProtocolException-throwing helper
- OBSERVED: none of `deployment/compose-sample/docker/keycloak/sample-realm.json`,
  `integration-tests/src/main/docker/keycloak/integration-realm.json` or
  `integration-tests/src/main/docker/keycloak/benchmark-realm.json` sets `revokeRefreshToken`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: integration-realm.json and sample-realm.json now both declare revokeRefreshToken:true and refreshTokenMaxReuse:0, this plan's own deliverable 5 landed
- OBSERVED: `BffTokenRefreshIT.java:64-66` states the suite does not exercise refresh-token reuse or family
  revocation, nor an IdP failure other than `invalid_grant`; `BffLogoutIT.java:28-34` binds the IdP
  `TokenRevocation` seam as a best-effort no-op; `sheriff-config-refresh` (server) and
  `sheriff-config-cookie-refresh` (cookie) instances exist.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: BffTokenRefreshIT's does-not-exercise-replay section still correct, coverage moved to new BffRefreshReuseIT
- HYPOTHESIS (user impact): a transient IdP outage, DNS failure or `5xx` during near-expiry refresh logs the
  user out with a still-valid refresh token — confirm/refute at `TokenRefreshCoordinatorTest` § a new test
  whose `RefreshExchange` throws a pre-redemption failure and asserts on `sessionBinding.destroy`
  (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed true as the pre-fix state this plan's deliverable 2 was written to close
- HYPOTHESIS: `SessionBinding.persist` can raise a `TokenSheriffException` subtype — confirm/refute at
  `CookieSessionBinding.java` § `persist` and `ServerSessionBinding.java` § `persist` (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: plausible and consistent with the shipped design, persist failures handled as a distinct disposition
- HYPOTHESIS: Keycloak with `revokeRefreshToken: true` and `refreshTokenMaxReuse: 0` answers a replayed
  superseded refresh token with `invalid_grant` — confirm/refute against the running Keycloak token-endpoint
  response in deliverable 7's test (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed by the successful shipping of BffRefreshReuseIT.java against the now-strict realms
- HYPOTHESIS: an existing refresh IT redeems the same refresh token twice and would go red under strict
  rotation — confirm/refute at `BffTokenRefreshIT` and `BffCookieRefreshIT` § their refresh drivers
  (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: BffTokenRefreshIT/BffCookieRefreshIT remained green through the realm change
- HYPOTHESIS: no benchmark scenario exercises refresh, so `benchmark-realm.json` is deliberately left
  unchanged — confirm/refute at `benchmarks/src` § refresh-token use (a grep at `fb65222` found none;
  verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: benchmark-realm.json deliberately left undeclared and unchanged, consistent with no refresh use in benchmarks
- Verify-first clause: re-read every citation at HEAD before editing; PLAN-25 is changing
  `BffRuntimeProducer` and three of the shared docs while this spec is staged.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — the `RefreshOutcome` consumer, and the wiring site if deliverable 4 adds a revocation client
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieSessionBinding.java` — only if deliverable 1 finds `persist` needs a disposition change (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinatorTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducerTest.java` — only if wiring changes (verify-at-outline)
- OBSERVED: `deployment/compose-sample/docker/keycloak/sample-realm.json`
- OBSERVED: `integration-tests/src/main/docker/keycloak/integration-realm.json`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffRefreshReuseIT.java` — new file; name confirmed at outline
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffTokenRefreshIT.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffCookieRefreshIT.java`
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffLogoutIT.java` — only if deliverable 9 extends it (verify-at-outline)
- HYPOTHESIS: `doc/development/integration-test-topology.adoc` — only if a new instance or realm client is added (verify-at-outline)
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED: `doc/variants/02-bff-session.adoc`
- OBSERVED: `doc/variants/03-bff-cookie.adoc`
- OBSERVED: `doc/features-analysis.adoc`
- OBSERVED: `doc/user/compose-sample.adoc`
- OBSERVED: `doc/adr/0046-refresh-failure-dispositions.adoc` — new file; **next free ordinal at outline**. ⚠ PLAN-25 consumed BOTH `0044` (trusted-proxy breadth) and `0045` (BFF OIDC back-channel), so `0046` is next at `c74f5d2`. ⛔ Declare the ORDINAL and confirm the descriptive filename at outline — PLAN-25's declared `0044-trusted-proxies-breadth-threshold.adoc` did not match the file it actually wrote.
- OBSERVED: `doc/user/tls-scenarios.adoc` — deliverable 12's scenario-guide fold

⛔ **Named files only.** `benchmark-realm.json` is deliberately not declared.

## Dependencies and Sequencing

- ⛔ **Runs after PLAN-25 lands.** Overlaps it on `BffRuntimeProducer.java`, `BffRuntimeProducerTest.java`,
  `doc/configuration.adoc`, `doc/LogMessages.adoc` and `doc/security-threat-model.adoc`, and PLAN-25's
  deliverable 1 is changing the `ClientConfiguration` the refresh flow dials with.
- ⛔ **Not concurrent with any other integration-test-lane plan** (currently PLAN-18) — shared local
  `api-sheriff:*` images are an undeclared surface.
- ⛔ **PLAN-23 runs after this plan** — it declares `api-sheriff/src/test/` wholesale and should audit the
  rewritten refresh tests.
- ⛔ **Both gates run, and the integration-test lane with them** — realm JSON is exercised only by
  `-Pintegration-tests`.
- ⚠ **Related shipped work**: PLAN-05 (`BffTokenRefreshIT`, #282) covers the near-expiry refresh's success-side
  outcomes; PLAN-16/PLAN-17 settled the cookie codec. Neither touched the failure dispositions.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-26-refresh-failure-dispositions.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
