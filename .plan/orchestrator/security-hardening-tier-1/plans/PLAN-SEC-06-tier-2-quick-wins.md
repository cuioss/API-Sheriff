# PLAN-SEC-06: Tier-2 quick wins — connection cap, compose-sample IdP fronting, JWKS key pickup, WebSocket origin rule, inert `trust_scheme_host`, documentation that overclaims

epic: security-hardening-tier-1
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-06-tier-2-quick-wins.md` and is queued as one row file,
> `queue/PLAN-SEC-06.json`, in the epic ledger. The orchestrator EMITS the command below; it never
> launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer
> and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

The operator's 2026-11-07 code-reading assessment (checked against `main` `7f5c61af`) ranked the
fixes that remain after tier 1 by severity, coverage and cost. This plan takes the cheap ones: each
is small, needs no design decision, and touches no class a running plan holds. They are batched into
one plan to keep the PR count down.

Every finding below was confirmed by reading code and library sources, not reproduced at runtime.
`main` has moved since (PLAN-SEC-01..04, the kidicap logout and inert-keys plans), so **re-check
each claim at outline against current `main`**; a claim that no longer holds is dropped and reported
in the plan's inbox message, not silently skipped.

## Deliverables

1. **Bound the main listener's connection count.** `application.properties` sets no
   `quarkus.http.limits.max-connections`, so the Quarkus connection cap is inactive and the only
   bound on open connections is the process file-descriptor limit. Set an explicit value, derived
   from the container's file-descriptor limit and the admission cap, and document the derivation
   next to the property and in the edge-security documentation. Correct the tracked statements that
   an idle connection is reaped "so a slow-loris / h2 abuse load cannot pin connection slots"
   (`EdgeHardeningOptions` class Javadoc, `doc/user/edge-security.adoc` idle-timeout row,
   `doc/security-threat-model.adoc` matching passage): state the mechanism only — the idle timeout
   is reset by any inbound byte, and the connection cap is the bound on open connections. Tests: a
   test that fails when the property is deleted (a key that parses must act); the cap value is
   asserted where the edge options are built or read back from the running server.
2. **Narrow the `/auth`-fronted compose sample to the login flow.**
   - `deployment/compose-sample/docker/sheriff-config-auth-fronted/endpoints/idp.yaml` routes the
     whole `/auth/realms/sample` realm (account console, account REST API, the token endpoint,
     login-actions) on the BFF's origin, with GET/HEAD/POST. Route only
     `/auth/realms/sample/protocol/openid-connect/auth`, `…/protocol/openid-connect/logout`,
     `/auth/realms/sample/login-actions`, `/auth/resources`, and the realm discovery document only
     if a browser client needs it. Do not route the account console or the token endpoint.
   - Disable or remove the `account` and `account-console` clients in
     `deployment/compose-sample/docker/keycloak/sample-realm.json`.
   - Optionally declare `frame-ancestors 'none'` on the `auth` anchor.
   - `scripts/start-sample.sh --auth-fronted` currently warns and continues when the Compose
     version is unreadable, falling back to the base sample's loopback Keycloak publication with
     admin/admin. For `auth-fronted`, assert from `docker compose config --format json` that
     `services.keycloak.ports` is empty and fail otherwise (check the effect, not the version).
   - Tests: extend `check-bff-login.sh --idp-prefix` and `IdpFrontingCanonicalPathTest` so
     `/auth/realms/sample/account` and `/auth/realms/sample/protocol/openid-connect/token` are
     asserted unrouted, with the login path still routed (matched control).
   - Docs: `doc/user/compose-sample.adoc` states that the sample shares one origin between the BFF
     and the IdP's login pages, and that production fronts the IdP on its own host name.
3. **A rotated key is picked up even when an invented key id claimed the refresh window.** In
   `auth/RetryingJwksLoader`, the first unknown `kid` claims the 30 s refresh window, and
   `settleRefresh` discards a fetched set that lacks *that* kid even when it holds a newly rotated
   key (ADR-0063 residual); a client sending about two invented kids a minute keeps unknown-kid
   pickup off, with the library's 600 s background refresh as the only fallback. In
   `settleRefresh`, also publish when the fresh set holds a key id that neither the current nor the
   retired delegate holds (`getKeyIds()` exists in token-sheriff 0.9.8). An unchanged set is still
   discarded, so the rejected "roll the retired slot" lever stays closed. Test (deterministic,
   `RetryingJwksLoaderTest`): an invented kid followed by a real rotated kid within one window — the
   rotated key validates after the refresh; matched control: an unchanged set is not republished.
   Amend ADR-0063's residual paragraph.
4. **Unauthenticated WebSocket routes that re-admit `Authorization` declare their origins.** On a
   `require: none` WebSocket route the cookie path is closed (`CookieRules.validateWebSocketCookieOrigins`,
   default cookie policy `none`, rebuilt `Cookie` header), but a positive `headers_allow` that
   re-admits `Authorization` carries browser-cached Basic/Negotiate credentials cross-site, and the
   upstream can never run its own origin check: `origin` is in `REQUEST_STRIP` and Netty's
   handshaker sets a synthetic `Origin` equal to the upstream's own origin. Add a boot rule:
   such a route must declare `allowed_origins`, or the configuration is refused with a message that
   names the route and the fix. Document that the upstream never sees the browser `Origin`. Tests:
   the rule refuses the config; a route that declares `allowed_origins`, and one that does not
   re-admit `Authorization`, both still load.
5. **Delete the inert `forwarded.trust_scheme_host` key.** It is declared in `ForwardedConfig` and
   the schema, nothing in main source reads it, and setting it to `true` produces no effect and no
   warning. PLAN-SEC-02 documented it as "accepted but no effect". Pre-1.0 rules: remove it from the
   model, `gateway.schema.json`, fixtures and every doc site (`doc/configuration.adoc`,
   `doc/configuration/routing-and-forwarding.adoc`, `doc/architecture/routing-and-forwarding.adoc`,
   ADR-0003's note on wiring it), so a config that still sets it is refused by the schema like any
   unknown key. Wiring it is a different, larger change and is not this plan. Test: a config
   carrying the key is refused at load.
6. **Correct the tracked documentation that overclaims** (documentation and Javadoc only; state the
   mechanism, no attack narrative):
   - ADR-0043 ("Can an attacker force additional seals? No") and the `SealedSessionCookieCodec`
     Javadoc: re-seals can be driven cross-site (session re-validation, step-up GET without a CSRF
     check), but there is no practical length oracle — the only attacker influence is which
     operator-declared, IdP-granted scope names enter the plaintext, every re-seal carries freshly
     issued tokens, the stable secret cannot be probed, samples are bounded by monotone scope growth,
     and an on-path size observer is needed. Re-decide the ADR text with that reasoning.
   - `doc/user/tls-edge.adoc` (`client_ca` "single global trust anchor", "no gradual cutover"):
     `KeyStoreHelper.loadCA` trusts every certificate in the PEM bundle, so a gradual cutover is
     possible by shipping both roots; an emergency cut-off must drop the old root from the file.
   - Back-channel logout in server mode is per-replica: the session store is per process, the
     receiver destroys sessions in its local store only and answers 200 even when none was destroyed.
     Correct `doc/variants/02-bff-session.adoc` ("Revocation: Immediate") and
     `doc/user/bff-session.adoc` (back-channel section and "Running more than one replica"):
     recommend a single replica or cookie mode where back-channel revocation matters.
   - HTTP/2 resets the transport sends after an early answer are counted by Netty's encoder-side
     reset limit (every sent reset except `NO_ERROR`/`CANCEL`, including `STREAM_CLOSED` for a late
     DATA frame, `REFUSED_STREAM` and the reset after a 431). Correct every statement that
     gateway-sent resets never count (`EdgeHardeningOptions` Javadoc, the threat model's GW-07/GW-08
     passages, `doc/user/edge-security.adoc`, `doc/architecture/tls-and-inbound-security.adoc`, and
     the ADR that states it — find it at outline).
   - The management port falls back to plain HTTP when no key material is supplied
     (`management.tls.enabled: true` enforces nothing; `ManagementPlainHttpAudit` only WARNs).
     Correct "HTTPS by default" in `doc/user/tls-edge.adoc`, `doc/configuration/tls-and-egress.adoc`,
     the `ManagementConfig` Javadoc and the `application.properties` comments.
   - ADR-0008: `not_modified: false` is not hardening against upstreams that "leak inode or version
     information" — the crossing preconditions still let a client recover the stripped values.
   - Admission Javadoc: `EdgeHardeningOptions` (the refused-hold / general-permit sentences that
     contradict each other), `EdgeAdmission` (true only for answers that actually held a permit),
     `SourceGate` (one delayed source can occupy the whole sub-budget when `admission_cap` < 8 —
     copy the qualifier from `EdgeHardeningOptions`).
   - A repeated identical `Content-Length` on HTTP/2 is collapsed by Netty, not rejected: correct
     `doc/configuration/security-and-edge-hardening.adoc` and `doc/user/edge-security.adoc`.

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims; removing a key and refusing a config that
  boots today are allowed.
- No new dependency. Logging through `CuiLogger` with `%s`, exception first; any new
  INFO/WARN/ERROR record is a `LogRecord` documented in `doc/LogMessages.adoc` with the next free
  identifier, derived at write time.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every code fix (deliverables 1, 3, 4, 5)
  starts with a test that FAILS against the unfixed code; a fix whose test cannot be made to fail is
  a refuted premise and loops back to re-scope (record it in the plan's inbox message).
- Do not touch `edge/upstream/**`, `tls/**` or `edge/terminal/**` production code (PLAN-SEC-05 is
  running there). Javadoc in `edge/admission/**` is in scope.
- Documentation wording states the mechanism only — no attack recipe, no rates. The doc fixes of
  deliverable 6 correct overclaims; they do not describe weaknesses that remain open.
- The compose-sample changes are verified by the sample's own checks (`check-bff-login.sh`,
  `IdpFrontingCanonicalPathTest`); a compose bring-up is run if the environment allows it, and its
  absence is reported, not passed over.
- Deliverable count: 6, at the presumptive split threshold. Kept as one plan because each item is
  small and independent, and the operator asked for one plan (minimise the number of PRs).
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.

## Security Findings

Possible weaknesses this plan finds that it does not fix on its branch are NOT written into any
tracked text (PR body, commit message, documentation, inbox message). Append a dated entry to the
git-ignored `.plan/local/security_notes.adoc` instead — plan id, location, what was observed, why
it was not fixed — and say only that a local note was recorded. Fixed findings may be described
once the fix is on the branch.

## Claim Labels

- OBSERVED: `application.properties` sets no `quarkus.http.limits.max-connections` — read at `api-sheriff/src/main/resources/application.properties` (main `047496b2`)
- OBSERVED: the `EdgeHardeningOptions` class Javadoc and `doc/user/edge-security.adoc` state that an idle connection is reaped so a slow-loris load cannot pin connection slots — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java` § class Javadoc (main `047496b2`)
- OBSERVED: the auth-fronted sample routes the whole `/auth/realms/sample` realm — read at `deployment/compose-sample/docker/sheriff-config-auth-fronted/endpoints/idp.yaml` (main `7f5c61af`, assessment)
- OBSERVED: `start-sample.sh --auth-fronted` warns and continues when the Compose version is unreadable — read at `deployment/compose-sample/scripts/start-sample.sh` (main `7f5c61af`, assessment)
- OBSERVED: `RetryingJwksLoader.settleRefresh` discards a fetched set that lacks the kid that claimed the window — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `settleRefresh` (main `7f5c61af`, assessment)
- OBSERVED: no boot rule ties `allowed_origins` to a `require: none` WebSocket route that re-admits `Authorization` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/CookieRules.java` § `validateWebSocketCookieOrigins` (main `7f5c61af`, assessment)
- OBSERVED: `trust_scheme_host` is declared in `ForwardedConfig` and `gateway.schema.json` and read by no main source — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ForwardedConfig.java` (main `047496b2`)
- OBSERVED: ADR-0043 answers "Can an attacker force additional seals? No" — read at `doc/adr/0043-Compress-then-encrypt_in_the_sealed_session_cookie_and_the_CRIME_BREACH_discriminator.adoc` (main `047496b2`)
- OBSERVED: `doc/user/tls-edge.adoc` calls `client_ca` a single global trust anchor with no gradual cutover, and says the management port serves HTTPS by default — read at `doc/user/tls-edge.adoc` (main `047496b2`)
- OBSERVED: `doc/variants/02-bff-session.adoc` states "Revocation: Immediate" including back-channel logout — read at `doc/variants/02-bff-session.adoc` § comparison table (main `047496b2`)
- OBSERVED: ADR-0008 calls `not_modified: false` the hardening setting for upstreams that leak inode or version information — read at `doc/adr/0008-data-plane-transport.adoc` (main `047496b2`)
- HYPOTHESIS: the "gateway-sent resets never count" statements are still present after PLAN-SEC-01..04 — confirm/refute at `EdgeHardeningOptions.java`, `doc/security-threat-model.adoc`, `doc/user/edge-security.adoc`, `doc/architecture/tls-and-inbound-security.adoc` (verify-at-outline)
- HYPOTHESIS: `quarkus.http.limits.max-connections` is honoured by the Quarkus 3.40 recorder for the main listener in this application's server setup — confirm at the Quarkus `VertxHttpRecorder` connection-limit wiring and by the deliverable-1 test (verify-at-outline)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/application.properties` — connection cap, management comments
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeHardeningOptions.java` — Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/EdgeAdmission.java` — Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/admission/SourceGate.java` — Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/CookieRules.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ProtocolRules.java` — if the WebSocket origin rule fits there (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/` — the boot-rule tests (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ForwardedConfig.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`
- HYPOTHESIS: `api-sheriff/src/test/resources/` — config fixtures that set `trust_scheme_host` (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/SealedSessionCookieCodec.java` — Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ManagementConfig.java` — Javadoc
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/pipeline/IdpFrontingCanonicalPathTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/admission/` — the connection-cap test, if it binds there (verify-at-outline)
- OBSERVED: `deployment/compose-sample/docker/sheriff-config-auth-fronted/endpoints/idp.yaml`
- OBSERVED: `deployment/compose-sample/docker/keycloak/sample-realm.json`
- OBSERVED: `deployment/compose-sample/scripts/start-sample.sh`
- OBSERVED: `deployment/compose-sample/scripts/check-bff-login.sh`
- HYPOTHESIS: `deployment/compose-sample/docker-compose.auth-fronted.yml` — only if the anchor's headers are declared there (verify-at-outline)
- OBSERVED: `doc/user/compose-sample.adoc`
- OBSERVED: `doc/user/edge-security.adoc`
- OBSERVED: `doc/user/tls-edge.adoc` — `client_ca` and management sections
- OBSERVED: `doc/user/bff-session.adoc` — back-channel and multi-replica sections
- OBSERVED: `doc/variants/02-bff-session.adoc`
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/configuration/routing-and-forwarding.adoc`
- OBSERVED: `doc/configuration/tls-and-egress.adoc`
- OBSERVED: `doc/configuration/security-and-edge-hardening.adoc`
- OBSERVED: `doc/architecture/routing-and-forwarding.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc` — reset-counting passage only
- OBSERVED: `doc/security-threat-model.adoc` — GW-07/GW-08 passages, idle-reap claim
- OBSERVED: `doc/adr/0003-forwarded-headers.adoc`
- OBSERVED: `doc/adr/0008-data-plane-transport.adoc`
- OBSERVED: `doc/adr/0043-Compress-then-encrypt_in_the_sealed_session_cookie_and_the_CRIME_BREACH_discriminator.adoc`
- HYPOTHESIS: `doc/adr/` — the ADR-0063 residual amendment and the ADR that states resets never count (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` — only if a new record is added (verify-at-outline)

## Dependencies and Sequencing

- Depends on: nothing unshipped. Works on `main` after PLAN-SEC-04 (`47e73a15`) and the kidicap logout plan (#592).
- Overlaps with: PLAN-SEC-05 (running) in documentation only — `doc/security-threat-model.adoc`, `doc/user/tls-edge.adoc`, `doc/architecture/tls-and-inbound-security.adoc`, possibly `doc/LogMessages.adoc` (different sections); no production file shared. `next` asks the operator.
- Adjacent to: `bff/logout/**` (kidicap #592 just changed it; deliverable 6 touches only docs about back-channel logout), `edge/terminal/TerminalDispatch.java` and `edge/upstream/**` (PLAN-SEC-05) — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-06-tier-2-quick-wins.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
