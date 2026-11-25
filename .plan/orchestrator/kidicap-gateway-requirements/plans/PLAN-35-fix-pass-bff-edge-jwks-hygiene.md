# PLAN-35: Fix Pass — Logout and Pending-Store Hygiene, Edge Cleanups, JWKS and Doc Follow-Ups

epic: kidicap-gateway-requirements
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-35-fix-pass-bff-edge-jwks-hygiene.md` and is queued as one row file,
> `queue/PLAN-35.json`, in the epic ledger. The orchestrator EMITS the command below; it never launches
> the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief, so every per-plan carry is authored here and nowhere else.
> Staged 2026-11-25 by `cleanup` at the operator's instruction ("distribute the open aspects / leftover
> sensibly over the remaining plans"). This is the **DUE FIX PLAN** of the standing fix-routing rule
> (`epic.md` `## Decisions` 2026-11-23: backlog over 10 means a fix plan is created and later fixes join it
> first). Eleven deliverables, under the operator's 12-deliverable authorisation, which overrides the ~6
> split guard (recorded in `epic.md` `## Decisions` 2026-11-25).
> **Sequencing: PLAN-34 merged 2026-11-25 (#663, `5b95b60b`), so this plan is emittable.** Deliverables 7 to 9
> are written against files PLAN-34 touched or was expected to touch (`RetryingJwksLoader`, `IssuerConfig`);
> a deliverable whose code PLAN-34 deleted is dropped at outline with the reason recorded, never re-implemented.
> **RE-GROUNDED 2026-11-25 against `5b95b60b` (analyze, verdicts persisted in `## Claim Labels`):** deliverable 10
> is **DROPPED** (`SignatureOnlyTokenVerifier` and its test were deleted by #663; the logout seam is the engine's
> `TokenValidator#verifySignature`, `BffRuntimeProducer` :818-827). Deliverables 7, 8 and 9 survive: `RetryingJwksLoader`,
> its test, `TokenValidatorProducerTest` and `IssuerConfig` were not reduced by #663 (PLAN-34 D6 was dropped), so the
> D8/D9 line anchors still hold. D7: `IssuerConfig.Jwks` Javadoc now documents FOUR admitted ranges (refresh, grace,
> unknown-kid, max-staleness), not three: re-count at outline. D9 L3-1 is feasible as written (the engine pin is 0.9.10).
> Residual for outline, not a deliverable: `BackchannelLogoutReceiver` :115-125 maps every `TokenSheriffException` to
> `LogoutRejection.SIGNATURE_REJECTED`, so the JWKS-outage mislabel D10 named can still exist in the receiver.
> **Next free ids (derived, re-derive at write time):** ADR 0085 (0083 and 0084 are taken), WARN 154 (153 is taken).
> Ten live deliverables remain (1-9 and 11).

## Objective

Close the pending-fix backlog the epic has accumulated across PLAN-16, PLAN-22, PLAN-23, PLAN-26, PLAN-28,
PLAN-29, PLAN-31 and PLAN-33 in one reviewable pass: the logout revocation WARN and its layer-2 residue, the
pending-store fairness record, the WebSocket relay and retry-wording cleanups, `Vary: Accept` on negotiated
error responses, the `RetryingJwksLoader` fixes held for PLAN-34, and the documentation lag PLAN-33 left. Every
deliverable is a small, independent correction; none changes a configuration key's meaning, so any of them
can be dropped at outline without touching the rest.

## Deliverables

All deliverables are independent unless stated. Each carries its source entry in `epic.md` so the entry can be
closed when the deliverable lands (an entry "stays open until the fix lands").

1. **`Vary: Accept` on negotiated error responses.** An error response that can be negotiated to HTML or JSON
   sends no `Vary: Accept`; the HTML variant is `no-store`, so the risk is a shared-cache mismatch on the JSON
   variant. Add the header on every negotiated variant and pin it with a test that asserts the directive by
   header name (not a substring of another header). Source: `epic.md` Open Defects "Two product gaps PLAN-16
   shipped knowingly"; `landings/PLAN-16.md`.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality
   - Persona: implementer
2. **WebSocket relay cleanups.** `WebSocketRelayStage.wire()` still sets `source.pongHandler(pong -> resetIdle())`
   (:531, re-read 2026-11-25), redundant since `relayFrame` receives pongs and resets the idle timer; the
   `queueFullDialer` and `frameThreadRecordingDialer` helpers in `WebSocketRelayStageTest` are near-duplicates one
   decorating dialer covers. Remove the handler (prove by test that the idle timer still resets on a pong) and merge
   the helpers. Source: `landings/PLAN-22.md`.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality
   - Persona: implementer
3. **Per-reason logout revocation WARN, and the cookie-mode replay decision.** One process-wide latch
   (`RpInitiatedLogout.revocationFailureReported`, :121 and :258, re-read 2026-11-25) covers a discovery failure, a
   transport failure and a non-success answer, so a transient first failure consumes the only `ApiSheriff-147` WARN
   and a later persistent failure stays at DEBUG. Latch per reason, as `ApiSheriff-132` does. Decide and record
   whether the cookie-mode replayed-cookie revocation POST (one synchronous POST per logout, about 15 s bound, never
   trips the latch) is accepted in ADR-0080 or planned. Source: `landings/PLAN-26.md` L3-1, L3-2.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality
   - Persona: implementer
4. **PLAN-26 layer-2 pass, plus the cookie-mode destroy test.** `LogoutEndpointTest` keeps
   `shouldRevokeNothingWhenTheSessionWasGoneAtDestruction`, which asserts the opposite and is cited by that name in
   `doc/security-threat-model.adoc`; `RpInitiatedLogoutTest` pins the WARN only through its self-resolved identifier
   (a renumber of 147 stays green); the `BffLogMessages.WARN` Javadoc keeps a hand-written inventory its class
   Javadoc disclaims; `RpInitiatedLogout` :58 says no other session-ending path revokes but `TokenRefreshCoordinator`
   does; the BFF-09 summary row omits a failed revocation and a session without a refresh token;
   `doc/variants/03-bff-cookie.adoc` drops two conditions the prose carries; ADR-0080 and the ADR-0018 amendment do
   not link ADR-0071; no cookie-mode case observes the revocation request at the IdP. Add the PLAN-33 layer-2 item
   `CookieSessionBinding.destroyIfCurrent` (no direct test). Source: `landings/PLAN-26.md` L2-1..L2-5;
   `landings/PLAN-33.md` L2-2.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality, pm-dev-java:junit-core
   - Persona: module-tester
5. **Pending-store fairness: the ADR and the eviction signal.** No ADR records the pending-store fairness mechanism
   (`doc/adr` has none; PLAN-21 deliverable 1 asked for one), and more than 256 concurrent logins behind one shared
   source key evict that source's own oldest record with no WARN or metric. Write the ADR (next free ordinal, derived
   at write time) and add a WARN or a metric for a source evicting its own oldest record, with the WARN id derived
   at write time. Two of the four #581 residual gaps (a login-initiation rate limiter, `authorize` before the store)
   stay open as decisions and are NOT in this deliverable. Source: `epic.md` Watches "What #581 left of PLAN-21's
   scope" gaps 1 and 4.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality, pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: implementer
6. **Retry-gap wording against SmallRye's default jitter.** SmallRye Fault Tolerance 6.11.1 applies a default retry
   jitter of 200 ms (read from bytecode 2026-11-23), and the guard sets `.delay(100, MILLIS)` with no `.jitter(...)`,
   so "100 ms apart" is wrong at `UpstreamGuards.java` :141 and :85, ADR-0064 :86 and
   `doc/configuration/routing-and-forwarding.adoc` :566. Soften the wording, or set `.jitter(0, MILLIS)` explicitly
   if a fixed gap is intended: **an operator choice, decided at outline**. Source: `landings/PLAN-29.md` L3-1.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality, pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter
7. **PLAN-28 configuration leftovers.** The `IssuerConfig.Jwks` Javadoc repeats the three admitted refresh ranges in
   prose with no guard test, and the `source: file` restriction coverage in `ConfigModelContractTest` was not traced.
   Add the range guard and the coverage test. The `token_validation` stale mentions are NOT reproduced (the cited
   lines no longer exist): do not re-derive them. **Held for PLAN-34:** `IssuerConfig` and `ConfigModelContractTest`
   are on its surface, so re-read both at the merged file first. Source: `landings/PLAN-28.md`.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality, pm-dev-java:junit-core
   - Persona: module-tester
8. **The five held `RetryingJwksLoader` fixes (PLAN-31).** (L3-3) an exception in `settleRefresh` is swallowed by the
   `whenComplete` future, leaves the fresh delegate unclosed and logs no `JWKS_REFRESH_FAILED`; (L3-4)
   `RefreshBasis.of` runs on the request thread outside the try that shields scheduling; and three layer-2 test items
   (`refreshOutrunByADropIsDiscarded` may not fail if `isCurrent` were deleted, two near-duplicate unit tests, a
   redundant IT leg 5). L3-1 (basis snapshot read before the in-flight flag) is ACCEPTED, no fix. **Gate:** PLAN-33
   changed `getLoaderStatus` and the `settleRefresh` neighbourhood and PLAN-34 D6 reduces the class, so every item is
   re-read against the merged file and a fix whose code no longer exists is dropped with the reason. Source:
   `landings/PLAN-31.md`; `epic.md` Open Defects "PLAN-31 layer-3 and layer-2 findings".
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality
   - Persona: implementer
9. **PLAN-33 JWKS findings.** (L3-1, major but conditional) `degradedLoaderRejectsAValidTokenFailClosed` degrades the
   loader BEFORE the validator is built, so whether the library issuer cache re-reads the status on every validation
   (an issuer admitted and degraded afterwards) is tested nowhere, and the `max_staleness_seconds` fail-closed claim
   rests on it: add the test that degrades afterwards, decompiling the 0.9.10 `IssuerConfigCache` first (PLAN-33 did
   not). (L3-2) the `RetryingJwksLoader` Javadoc still says an unknown key id "can still claim a refresh that
   re-admits the issuer", which ADR-0063 contradicts. (L3-3, lead) after a first load the library recovered itself,
   `runRetry` replaces the recovered `OK` delegate with a fresh `LOADING` one: settle by a failing test, fix or record
   as accepted. Gate as deliverable 8. Source: `landings/PLAN-33.md` L3-1..L3-3.
   - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality, pm-dev-java:junit-core
   - Persona: module-tester
10. ⛔ **DROPPED 2026-11-25 — logout verifier WARN reason.** The premise is refuted at HEAD: PR #663 deleted
    `SignatureOnlyTokenVerifier.java` (and its test) and the logout seam is now the engine's
    `TokenValidator#verifySignature` (`BffRuntimeProducer` :818-827). Nothing is implemented here. The one residue
    (`BackchannelLogoutReceiver` :115-125 maps every `TokenSheriffException` to `LogoutRejection.SIGNATURE_REJECTED`)
    is an outline question for deliverable 1 (the logout-WARN neighbourhood), not a deliverable. Source:
    `landings/PLAN-33.md` L2-1, `landings/PLAN-34.md`.
    - Skills: none
    - Persona: implementer
11. **Hygiene sweep: `@since`, doc wording lag, raw-value echo.** (a) `SessionBinding.PersistResult` factories lack
    `@since` (:360, :367, :375 at the PLAN-33 landing); (b) wording lag in `doc/user/bff-session.adoc` (:1003, :870),
    `doc/variants/02-bff-session.adoc` (:208) and the four ADR or doc tables PLAN-33 left open on purpose; (c) boot
    refusal messages echo configured values raw (`default_view`, trusted proxies, base-URL alias, websocket origins,
    anchor prefixes), so quote or sanitise each through the one shared renderer; the roughly forty older comment
    inaccuracies in the BFF files are fixed only where the sweep already opens the file, not as a campaign. Run this
    deliverable LAST: it touches the files the other deliverables touch. Source: `landings/PLAN-33.md` L3-6, L3-7;
    `landings/PLAN-23.md`.
    - Skills: pm-dev-java:java-core, plan-marshall:ref-code-quality, pm-documents:ref-asciidoc, pm-documents:ref-documentation
    - Persona: documenter

## Standing Constraints

- **Not in scope, stay open as decisions** (the operator's answer did not decide them): the per-request access log,
  the PLAN-23 scope-fallback and refresh-token items, the login-initiation rate limiter, `authorize` before the
  store, the TokenSheriff separation audit's remainder, the Sonar note, the PLAN-18 red run. The reserved-path
  canonical-form boot check and its duplicate rule, the cookie-size re-measure, the 0.2.4 default-path residual and
  the refresh-versus-widening leads (PLAN-33 L3-4 and L3-5) are carried by **PLAN-20**, not here.
- **Pre-1.0 rules:** no deprecation, no alias, no compatibility shim; delete what a deliverable makes dead.
- **A fix is proved by a test that goes red without it** (CLAUDE.md "a key that parses is not a key that acts");
  a documentation-only deliverable is proved by re-reading the claim against the code it describes.
- **Allocate ordinals at write time:** the next free ADR ordinal and WARN id are derived from `doc/adr` and
  `doc/LogMessages.adoc` when written, never from a number in this spec (PLAN-34 consumes some).
- **Run order:** deliverable 11 last. Deliverables 8 to 10 only after the PLAN-34 re-read.
- Where the project analyses its pull requests with Sonar: an open Sonar finding is fixed in a pushed commit, or
  DISMISSED ONLY ON THE OPERATOR'S SELECTION, never by the lane's own record; the merge gate reads the open-finding
  count at the head (`0` confirmed, or every remaining key selected by the operator), and an `undecidable` read
  blocks.

## Claim Labels

- OBSERVED: `WebSocketRelayStage` still sets the redundant `pongHandler` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStage.java` :531, 2026-11-25 at `b236bf06`
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: WebSocketRelayStage.java:531 still sets source.pongHandler(pong -> resetIdle()); resetIdle at :584; test helpers queueFullDialer/frameThreadRecordingDialer still exist
- OBSERVED: `RpInitiatedLogout` holds a single `revocationFailureReported` latch — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/RpInitiatedLogout.java` :121 and :258, 2026-11-25 at `b236bf06`
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RpInitiatedLogout.java:121 single AtomicBoolean revocationFailureReported, :258 compareAndSet then LOGGER.warn
- OBSERVED: the "100 ms apart" wording stands in `UpstreamGuards` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` :141, 2026-11-25 at `b236bf06`
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: UpstreamGuards.java:141 says 100 ms apart; retry builder :172-177 has delay(RETRY_DELAY_MILLIS) and no jitter; same wording ADR-0064:86 and routing-and-forwarding.adoc:566
- HYPOTHESIS: an error response negotiable to HTML or JSON sends no `Vary: Accept` — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/ErrorResponseWriter.java` § negotiation (verify-at-outline; last read 2026-11-23)
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: ErrorResponseWriter.answeredWithErrorPage:378-410 selects on Accept; no addVary Accept anywhere in api-sheriff main
- HYPOTHESIS: no ADR records the pending-store fairness mechanism and an own-oldest eviction raises no WARN or metric — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java` § eviction and `doc/adr/` (verify-at-outline; last read 2026-11-23)
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: no pending-store fairness ADR in doc/adr 0001-0084; eviction lives in InMemoryPendingAuthorizationStore.java:53,:82-83, whose only logging is the saturation WARN :221-224; the spec cites the interface PendingAuthorizationStore.java, not the implementation
- HYPOTHESIS: the layer-2 residue of PLAN-26 stands as listed in deliverable 4 — confirm/refute at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpointTest.java` § `shouldRevokeNothingWhenTheSessionWasGoneAtDestruction` (verify-at-outline; last read 2026-11-23)
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: LogoutEndpointTest:683 still asserts the resolved token IS revoked (:678-679,:700); RpInitiatedLogout.java:59-60 still says no other path revokes; CookieSessionBinding.destroyIfCurrent:405 has no CookieSessionBindingTest hit; four listed sub-items not individually re-read
- HYPOTHESIS: SmallRye's default retry jitter is 200 ms and the guard sets none — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` § retry builder (verify-at-outline; read from SmallRye 6.11.1 bytecode 2026-11-23, the version Quarkus resolves at runtime was not read)
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: guard half only: UpstreamGuards.java:172-177 sets no jitter; the 200 ms SmallRye default is bytecode knowledge and not re-readable from the repo
- HYPOTHESIS: the five `RetryingJwksLoader` fixes of deliverable 8 and the findings of deliverable 9 survive PLAN-34 D6 — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` § `settleRefresh`, `runRetry` (verify-at-outline, AFTER PLAN-34 merges)
  - verdict: corroborated | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: RetryingJwksLoader.java untouched by #663 (last change #656, 911 lines); settleRefresh:680-694, RefreshBasis.of:568, Javadoc :756, runRetry:494-508 and TokenValidatorProducerTest:1277-1285 all still as D8/D9 cite; PLAN-34 D6 did not reduce the class
- HYPOTHESIS: the logout verifier's non-`OK` issuer refusal still exists after PLAN-34 D5 — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/SignatureOnlyTokenVerifier.java` (verify-at-outline, AFTER PLAN-34 merges; the class may be deleted)
  - verdict: contradicted | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: yes | evidence: auth/SignatureOnlyTokenVerifier.java and its test were deleted by #663; logout seam is now the engine TokenValidator#verifySignature (BffRuntimeProducer:818-827); D10 dropped 2026-11-25; residual: BackchannelLogoutReceiver:115-125 maps every TokenSheriffException to SIGNATURE_REJECTED
- Verify-first clause: re-read every cited line against the merged `main` before scoping a deliverable. A premise refuted at HEAD drops its deliverable with the reason recorded in the PR and the inbox landing; the plan never re-implements what PLAN-34 deleted.
  - verdict: unverifiable | checked_at: 5b95b60bda60a6f64fccd394d3e849fce84ebeb6 | by: kidicap-gateway-requirements/analyze | rescoped: n/a | evidence: process rule; applied at HEAD 5b95b60b: D10 drops, D7 D8 D9 survive, D1-D6 and D11 premises hold

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStage.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStageTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/ErrorResponseWriter.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/error/ErrorResponseWriterTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuardsTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/logout/RpInitiatedLogout.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/logout/RpInitiatedLogoutTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/reserved/LogoutEndpointTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieSessionBinding.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/cookie/CookieSessionBindingTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStore.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStoreTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionBinding.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoader.java` (rewritten by PLAN-34 D6; re-sweep after merge)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/RetryingJwksLoaderTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducerTest.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/InMemoryPendingAuthorizationStore.java` (the own-oldest eviction lives here; `PendingAuthorizationStore.java` above is only the interface)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/pending/InMemoryPendingAuthorizationStoreTest.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/RuleSupport.java` (the shared renderer `renderForMessage` / `renderForLog` for the raw-value echo, D11 c)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ReservedPathRules.java` (`default_view` is judged here, D11 c)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/IssuerConfig.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/model/ConfigModelContractTest.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/CookieRules.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/EdgeRules.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRules.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/TopologyRules.java`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/adr/0018-BFF_session_mode_is_one_SessionBinding_seam_behind_a_fixed_reserved-path_and_CSRF_model.adoc`
- OBSERVED: `doc/adr/0064-The_upstream_guard_is_fixed_in_the_gateway_and_a_route_declares_no_circuit_breaker_settings.adoc`
- OBSERVED: `doc/adr/0080-RP-initiated_logout_revokes_the_sessions_refresh_token_at_the_identity_provider_before_the_end-session_redirect.adoc`
- OBSERVED: `doc/configuration/routing-and-forwarding.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED: `doc/user/bff-session.adoc`
- OBSERVED: `doc/variants/02-bff-session.adoc`
- OBSERVED: `doc/variants/03-bff-cookie.adoc`
- OBSERVED: `doc/adr/` (one new ADR for the pending-store fairness mechanism, ordinal derived at write time)

## Dependencies and Sequencing

- Depends on: **PLAN-34 — SATISFIED (merged 2026-11-25, #663).** Re-read of the merged files is done (see the banner): deliverable 10 is dropped, 7 to 9 survive, 1 to 6 and 11 are independent of it. #663 touched `BffLogMessages`, `doc/LogMessages.adoc`, ADR-0018 and `doc/security-threat-model.adoc`, which D3, D4 and D5 edit: re-read those four before editing.
- Overlaps with: **PLAN-20** (`BffLogMessages`, `SessionBinding`, `LogoutEndpoint` neighbourhood, `doc/user/bff-session.adoc`,
  `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`, `doc/adr/`). The `next` gate decides; the cheaper reading is to
  sequence this plan after PLAN-20, since PLAN-20 owns the larger and riskier change and is the one with an open design decision.
  Native-IT wall-clock watch applies if both run together.
- Adjacent to: `GatewayEdgeRoute`, `TerminalDispatch` and `DispatchStage` stay untouched (PLAN-20's replay design owns them).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-35-fix-pass-bff-edge-jwks-hygiene.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
