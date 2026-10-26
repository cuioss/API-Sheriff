# PLAN-20: Upstream-Signalled Step-Up — Wire the Coordinator, Refresh First, Browser Step-Up Without a Page Change

epic: kidicap-gateway-requirements
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-20-upstream-signalled-step-up.md` and is queued in the epic `status.json`
> `plans[]` field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-17 from inbox messages `kidicap-gateway-downstream-006.md` and `-009.md`.

## Objective

`StepUpCoordinator` exists but has **no production caller**: the gateway never reads an upstream
`WWW-Authenticate` challenge, so a `401 insufficient_user_authentication` reaches the client unchanged
while `doc/configuration.adoc` describes `step_up.*` as active. This plan wires the challenge path into
the edge and implements the downstream's decided model: the backend signals a missing scope, the gateway
obtains it — refresh first with a single replay when the scope is inside the grant, otherwise a browser
step-up that needs no page change — with a loop guard and without ever destroying the existing session.

## Source

Inbox `kidicap-gateway-downstream-006.md` (finding, amended 2026-09-17) and
`kidicap-gateway-downstream-009.md` (finding, 2026-09-17), filed by the downstream deployment
`kidicap-gateway`; code read at 0.2.1 and `main`, Keycloak behaviour measured against 26.7.4.

**006 — documented but not wired.** `BffRuntimeProducer` builds a `StepUpCoordinator` and stores it in
`BffRuntime`; `BffRuntime.stepUpCoordinator()` has no caller in production code (only unit tests). The
silent-satisfaction seam is bound to `(sessionRecord, challenge, now) -> Optional.empty()`, so even once
wired every challenge would re-drive through the browser. No step-up integration test exists.
`doc/configuration.adoc` row `step_up.*` describes the honouring of an upstream challenge as present.
Wanted: wire it, **or** state in the reference that `step_up.*` has no effect yet and refuse/warn at boot
when it is enabled — with an IT pinning whichever is chosen.

**009 — the decided model.**
- *The backend signals, the gateway obtains.* Which operation needs which scope is known only to the
  backend (in particular to façades calling further backends with the user's token), so the downstream
  declares **no per-route scopes**; `endpoint.scopes` stays coarse (what the login requests).
- *Backend contract:* a missing scope is answered with `403` +
  `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"` (RFC 6750) **before any side effect**;
  a façade passes a downstream challenge through unchanged. On that contract the gateway may replay any
  method.
- *The IdP is the allowlist:* Keycloak rejects scopes not assigned to the client, so the gateway needs no
  list of its own.
- *Measured Keycloak behaviour:* a refresh can never add a scope outside the grant `S` (answers `200`,
  silently omits it); a refresh without `scope` restores the whole grant; a narrowed refresh keeps `S` in
  the refresh token; a token request naming a scope not assigned to the client is `400 invalid_scope`; an
  authorization request with an unknown scope redirects with `error=invalid_scope`.

Flow as filed:

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

Acceptance as filed: an echo upstream answering `403 insufficient_scope` for a scope inside a narrowed
`A` — the upstream sees the request twice, the client one `200`, no authorization request; the same for a
scope outside the grant — no replay, the client gets the step-up answer, and in an iframe with an IdP SSO
session the step-up completes without interaction and the retried call succeeds; a scope not assigned to
the client — session unchanged, refusal signalled, retried call `403`, no loop.

## Deliverables

> ⚠ **RE-SCOPED 2026-10-02 (cleanup, re-grounded at `e8db85bf`). Read this before the list.** PLAN-23
> (#369) shipped the gateway-declared half of scope step-up, and **ADR-0057 explicitly rejects merging the
> two legs** — so several deliverables below are narrowed, one is realized for a different trigger, and
> the ADR-renumber fold is dropped. Per-deliverable state as measured:
>
> | # | State | What changed |
> |---|---|---|
> | 1 | unaffected | `StepUpCoordinator` still has no caller for an upstream challenge. PLAN-23 did add `step_up.path` as a sibling key explicitly decoupled from the RFC 9470 leg, which answers this deliverable's open config-key question |
> | 2 | **narrowed** | PLAN-23 ships a scope-refresh seam requesting `A ∪ missing` — the shape this wanted — but it runs pre-flight against boot-declared `neededScopes`, never against a live upstream `403`. `ResponseStage` and the request-body buffer are untouched, so the novel work (intercept + replay) is still unbuilt |
> | 3 | **realized for a DIFFERENT leg** | PLAN-23 shipped a reserved step-up path (`StepUpEndpoint`), silent-then-one-interactive widening, and a `403 problem+json` carrying `step_up_url` — almost this exact shape, but triggered only by the gateway's own boot-declared comparison, with no `postMessage`-to-opener protocol. ⛔ ADR-0057 rejects extending it in place, so this is a sibling mechanism, not a re-use |
> | 4 | **narrowed** | terminal-refusal-leaves-session-untouched already exists for a widening's interactive attempt; reuse the pattern rather than re-deriving it |
> | 5 | **narrowed** | a one-silent-plus-one-interactive-then-terminal guard now exists as precedent, but not the dual refresh-then-browser guard keyed to one upstream-challenged request |
> | 6 | unaffected | the `Optional.empty()` binding is untouched, and ADR-0057's rejection is a ready citation for the document-why-it-stays option |
> | 7 | unaffected | no test infra for the upstream-challenge scenario; PLAN-23's `BffSessionScopeParityIT` and `OneOffGatewayContainers` may offer reusable Keycloak realm/scope fixtures — check at outline |
> | 8 | unaffected, **but harder** | the misdescribing `step_up.*` rows now sit beside PLAN-23's genuinely-shipped `step_up.path` row in the same table, so the correction must disambiguate two legs rather than one; `bff-session.adoc` and the threat model also grew substantially |
> | 9 | ⛔ **DROPPED** | the ADR-0053 renumber was done independently by #367 (header-matcher ADR → 0056, plus contract guards). Doing it again would re-do settled work |
>
> ⛔ **Do not start this plan without reading ADR-0057 first.** The question it answers is no longer "build
> scope step-up" but "what does the UPSTREAM-SIGNALLED leg still need, given the gateway-declared leg
> shipped and the two are deliberately separate?"
>
> ⚠ **RE-GROUNDED 2026-10-26 (cleanup at `54f82f03`).** The plan is still applicable: no commit since the
> 2026-10-14 check wired an upstream challenge, added a replay buffer or an interception point (the sibling
> landings #476, #487, #492, #502, #506, #515 and #521 are structural or orthogonal), and the hold on this
> plan is lifted because the sibling refactor chain has shipped. What the re-ground changes:
> - **The interception point exists; the replay buffer does not.** The old claim is split. VERIFIED: the live
>   upstream response is in hand before any client byte is written, in
>   `edge/terminal/TerminalDispatch.dispatchAndRelay` (:196–237: `dispatchStage.dispatch` :220–221, ahead of
>   `relayOnEventLoop` :234), and `WWW-Authenticate` is copied unchanged by `ResponseStage.relay`. STILL
>   UNBUILT: buffering. `DispatchStage` states "streamed, never buffered" and that a streamed request cannot
>   be safely replayed; the inbound body is a one-shot `InboundBody`. A backend answering 403 "before any
>   side effect" is typically answering while the request body is still streaming (ADR-0066 and the
>   `TerminalDispatch` late-refusal / early-answer paths :612–672 interact with a replay), a body buffer needs
>   its own bound (strict `max_body_bytes` is 1 MiB, the IT upload anchor 64 MiB), the stage-5 header set
>   including the #515 cookie-policy `Cookie` is computed once and reused with only the mediated
>   `Authorization` changing, and a replay must obey ADR-0060 framing. Replay-for-all-methods needs a bounded
>   pre-dispatch buffer, which conflicts with the ADR-0006/0008 stream-never-materialize stance: deliverable 8's
>   ADR must carve out the exception.
> - **ADR-0057 was over-read above.** Its Alternatives (:323–328) reject reusing `StepUpCoordinator` (the RFC
>   9470 `acr` leg) for scope widening. They say nothing against sharing `SessionWidening`, the pending
>   `Widening` record or `StepUpEndpoint` with an upstream-signalled scope; `SessionWidening.initiate(live,
>   returnUrl, neededScopes, Attempt.SILENT, now)` (:130) is directly reusable with `neededScopes={s}`. Restate
>   the premise accordingly at outline.
> - **`step_up.enabled` and `honor_upstream_challenge` are inert.** The key block is `oidc.step_up`
>   (`OidcConfig.StepUp` :498, schema :506–517, no description), not `session.step_up`. The only consumer of
>   `OidcConfig.StepUp` is `stepUp.path()` (`BffRuntimeProducer` :626); the two keys are read by no production
>   code. `StepUpCoordinator`'s Javadoc (:73) names a gate "the runtime's concern" that does not exist. Under the
>   CLAUDE.md "a key that parses is not a key that acts" rule, deliverables 1 and 8 settle this, and the
>   pre-1.0 rules allow deleting the keys or refusing at boot (a boot rule belongs in a concern class,
>   ADR-0071; an IT must pin the choice). The false claim stands in `doc/configuration.adoc` :2952–2956,
>   `doc/variants/02-bff-session.adoc` :401–407 and :484, and `doc/user/bff-session.adoc` :903–905; only
>   `doc/development/bff-session.adoc` :281–286 is honest (and :306–309 become false once wired).
> - **Refusal under PAR.** An unknown or unassigned scope is refused at the PAR push as a 502 `GatewayException`
>   (ApiSheriff-132, `PushedAuthorizationRequests`), session unchanged (`bff-session.adoc` :834–836;
>   `BffSessionScopeParityIT.navigationNeedingARefusedScopeIsRefusedAtThePush`), not as `error=invalid_scope`
>   on the callback; the Source flow and the acceptance row need the PAR mechanism. The
>   refusal-leaves-session-untouched pattern lives in `CallbackEndpoint` (`completeWidening`, terminal 403,
>   merge via `persistReissuingCookie`; ADR-0057 :156–165), not in `SessionWidening`, which only initiates.
> - **New constraints.** The completion signal to the opener needs a gateway-authored HTML/JS response from the
>   callback, which answers a 302 today; only the portal renders HTML (a standalone Qute engine, ADR-0053), so
>   a CSP/nonce design and a check against the gateway-owned security headers (`frame_deny`) are owed
>   (INFERRED, not verified). The refresh-first leg inherits engine limitation TokenSheriff#763 (an outright
>   `invalid_scope` refresh answer surfaces as a transport failure and arms the 5 s back-off,
>   `TokenRefreshCoordinator` Javadoc, ADR-0057 :245–250); whether token-sheriff 0.9.8 fixes it is unchecked
>   (the jar is not in `~/.m2`). Next free ADR ordinal **0072**; the cited ADRs 0057, 0060, 0048, 0058, 0046,
>   0053 keep their ordinals and file names, and 0056 was merged into 0054 (cosmetic: deliverable 9 is dropped).
> - **Dependencies are satisfied:** PLAN-14 (`endpoint.scopes`, `activeScopes()` / `grantedScopes()`,
>   `ScopedEngineFlows`, `refreshForScopes` at `TokenRefreshCoordinator` :65) and PLAN-19 (`session_fallback`,
>   ADR-0055) have landed. Never two WS-04 plans at once: PLAN-20 and PLAN-21 exclude each other.

1. Edge integration: evaluate the upstream `WWW-Authenticate` on session routes for `insufficient_scope`
   and `insufficient_user_authentication`, and route it into `StepUpCoordinator` — the caller that does
   not exist today. Decide and record whether the existing `session.step_up` (RFC 9470) configuration
   governs both kinds or gains a sibling key.
2. Refresh-first scope step-up with replay: refresh with `A ∪ {s}`; on a token response whose `scope`
   contains `s`, replay the original request exactly once with the new mediated bearer — all methods,
   request body buffered within the route's filter body cap, identical headers.
3. Browser step-up for API calls: a step-up URL (a reserved path or a parameter of `oidc.login.path`)
   that starts the authorization request with `S ∪ {s}`, runs `prompt=none` first, and reports completion
   or refusal to the opener (same-origin `postMessage`). The existing re-drive to the request URL stays
   for top-level navigations.
4. Callback error handling during a step-up: an authorization error on the callback (`invalid_scope`,
   `login_required`) leaves the existing session untouched and is signalled as a refusal.
5. Loop guard: at most one refresh attempt and one browser step-up per scope per request, then `403`.
6. Silent-satisfaction seam: replace the `Optional.empty()` binding (or document why it stays) so a
   challenge that the session can already satisfy does not re-drive through the browser.
7. Tests: unit tests for challenge parsing, the replay and the loop guard; integration tests for the three
   acceptance rows against an echo upstream and Keycloak, including the iframe-silent path; an IT that
   pins the boot behaviour chosen in deliverable 1.
8. Documentation: correct `doc/configuration.adoc` (the `step_up.*` rows describe behaviour that does not
   exist today), `doc/user/bff-session.adoc` (the backend contract and the UI's part), the backend-facing
   contract for `403 insufficient_scope` before any side effect, `doc/security-threat-model.adoc` (replay
   and step-up surface), `doc/LogMessages.adoc`, and an ADR for the step-up model.
9. ⛔ **DROPPED 2026-10-02** — the ADR-0053 renumber this deliverable carried was resolved by PR #367
   (`fix(adr): resolve ordinal 0053 collision and add contract guards`). `doc/adr/` carries one 0053 and a
   contract test against a repeat. Nothing to do; left in place as a numbered record of the fold and its
   retirement rather than silently deleted.

> **Folded 2026-10-12 from `kidicap-gateway-downstream-013.md`** (the downstream on 0.2.4, measured against
> Keycloak 26.7.4). Two observations bear on this plan and on no other:
>
> - **`step_up_url` returns to the refused API URL.** PLAN-23's step-up path takes its scopes from the route
>   behind `returnUrl` and returns to that same `returnUrl`, which for an SPA is not a usable landing. The
>   downstream works around it for gateway-declared scopes with a surface route; it says the gap matters
>   exactly for a backend-signalled scope — this plan. Deliverable 3's completion signal to the opener is
>   therefore the requirement, not an option: do not reuse the return-to-`returnUrl` behaviour.
> - **With a pushed request, an unknown or unassigned scope is refused at the PUSH, as `400
>   invalid_request` ("Invalid scopes"), not as `invalid_scope`.** The flow in Source and deliverable 4 were
>   written before PAR (#377) and expect `error=invalid_scope` on the callback. Under PAR the refusal
>   arrives before any redirect, with a different error code. Deliverable 4 must handle the refusal where
>   it now occurs; `BffSessionScopeParityIT.navigationNeedingARefusedScopeIsRefusedAtThePush` shows the
>   gateway-declared leg doing so.
>
> Neither adds a file surface: both land in files this spec already declares.

## Claim Labels

- OBSERVED: `BffRuntime.stepUpCoordinator()` has no production caller — the only references are `BffRuntimeProducerTest`; grep over `api-sheriff/src/main` at 93a4b3e finds the accessor's declaration and nothing else — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java` § `stepUpCoordinator`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntime.java:220 declares stepUpCoordinator(); no caller under api-sheriff/src/main (GatewayEdgeRoute never reads it); references are BffRuntimeProducerTest, StepUpCoordinatorTest, GatewayEdgeRouteBffWiringTest only
- OBSERVED: `StepUpCoordinator` is RFC 9470 shaped, driven by a parsed upstream challenge — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` § `coordinate`
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: StepUpCoordinator.detectChallenge :138 uses the engine StepUpChallengeParser; coordinate :157 has only silent-satisfaction and RE_DRIVE outcomes, no insufficient_scope branch; request still carries static oidc.scopes (:118-129, :173-176)
- HYPOTHESIS: the silent-satisfaction seam is bound to `(sessionRecord, challenge, now) -> Optional.empty()` at the construction site — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § step-up construction (verify-at-outline)
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.java:641 binds (sessionRecord, challenge, now) -> Optional.empty(); comment :629-631 says no edge code drives the coordinator
- HYPOTHESIS: `doc/configuration.adoc`'s `step_up.*` rows describe the honouring of an upstream challenge as present behaviour — confirm/refute at `doc/configuration.adoc` § `step_up` (verify-at-outline)
  - verdict: corroborated | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: doc/configuration.adoc:2952-2956 still describes honouring an upstream challenge; same false claim in variants/02-bff-session.adoc:401-407 and user/bff-session.adoc:903-905; oidc.step_up.enabled and honor_upstream_challenge are read by no production code (only stepUp.path() at BffRuntimeProducer:626)
- HYPOTHESIS: the upstream response path can reach a step-up decision before the response is relayed, and the request body can be buffered for a replay within the route's filter body cap — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` and the dispatch path (verify-at-outline)
  - verdict: contradicted | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: yes | evidence: Seam half holds: TerminalDispatch.dispatchAndRelay :196-237 has the live upstream response before relayOnEventLoop :234, WWW-Authenticate relayed unchanged. Buffering half false: DispatchStage.java is streamed never buffered, inbound body is a one-shot InboundBody. Spec re-scoped 2026-10-26 into interception point (verified) and bounded replay buffer (unbuilt)
- HYPOTHESIS: the Keycloak behaviours the flow rests on (a refresh never adds a scope outside the grant; a narrowed refresh keeps the grant in the refresh token) hold for the integration realm as measured downstream — confirm/refute with an IT against the integration Keycloak (verify-at-outline)
  - verdict: unverifiable | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: live Keycloak behaviour, not readable from the repo; bff-session.adoc:908-917 records one integration-realm observation and ADR-0057:297-301 states refresh-inside-grant depends on the IdP
- Verify-first clause: settle the replay seam first (deliverable 2) — where in the response path a challenge can be intercepted, and whether a body can be buffered and re-sent without breaking streaming or the body cap. If a replay cannot be done safely for all methods, loop back and re-scope: the flow degrades to the browser step-up with no replay, which changes the acceptance rows.
  - verdict: unverifiable | checked_at: 54f82f03 | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: process rule; the factual answer is available: interception point exists in TerminalDispatch, replay-for-all-methods needs a bounded pre-dispatch buffer that is unbuilt and conflicts with the ADR-0006/0008 streaming stance

## Expected Surface

> ⚠ Re-grounded 2026-10-14 (cleanup at `5490144f`): #450 moved the reserved BFF paths to `/bff/*`; a new
> step-up path belongs there. #448 changed `GatewayEdgeRoute` and `DispatchStage` (abortive upstream close,
> `StreamResetException` drop, guard-keyed breaker) but added no replay buffer and no interception point.

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java`
- OBSERVED (added 2026-10-14): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/PushedAuthorizationRequests.java` — the PAR fold says deliverable 4 handles the refusal at the push
- OBSERVED (added 2026-10-14): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/SessionWidening.java` — deliverable 4 reuses its refusal-leaves-session-untouched pattern
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — a 668-line wiring root since #487; the dispatch and relay seam is not here
- OBSERVED (added 2026-10-26, re-grounding at `54f82f03`): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` — the actual interception and relay seam (`dispatchAndRelay` :196, `relayOnEventLoop` :539, `failRelay` :762)
- OBSERVED (added 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/CallbackEndpoint.java` (widening completion and refusal signalling for deliverables 3 and 4; answers a 302 today), `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/PipelineRequest.java` (`mediatedBearer` :73) and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/forward/ForwardPolicyStage.java` (stage-5 bearer merge; a replay needs the new bearer and the same rebuilt headers), `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/StreamAwareRetryGate.java` (retry gating interplay)
- OBSERVED (added 2026-10-26, re-grounding at `54f82f03`; moved by #487):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` (moved from the edge package root by #487) — the replay seam
  deliverable 2 needs: request-body streaming (`ByteCappedBodyStream`, `InboundBody`) and retry gating.
  `5ddf8081` added ~209 lines here. ⛔ The body streams with **no replay buffer**, which is the concrete
  reason deliverable 2's "replay the original request exactly once" is still unbuilt.
  ⚠ Re-grounded 2026-10-06 at `6788bdae`: #385 and #386 rewrote this file again (+629/−90, bodyless-method
  handling, abandoned-upstream-call release, declared-length framing). Still no replay buffer — the class
  javadoc now states outright that a streamed request cannot be safely replayed once a body byte has crossed
  upstream. **Read ADR-0060 before deliverable 2**: a replay must send a bodyless method no body and frame a
  declared-length body by exactly that length
- OBSERVED (added 2026-10-06):
  `doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc`
  — the dispatch-framing rule any replayed request must satisfy
- OBSERVED (added 2026-10-05):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java` — the
  refresh with `A ∪ {s}` and the loop guard (deliverables 2 and 5)
- OBSERVED (added 2026-10-05):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` — the `StepUp` record
  (`enabled` / `honorUpstreamChallenge` / `path`) is where deliverable 1's sibling-key decision lands,
  with `config/validation/ConfigValidator.java`
- OBSERVED (added 2026-10-05):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationRecord.java` — a
  browser step-up record kind would sit alongside PLAN-23's `Widening` kind
- OBSERVED (added 2026-10-05; corrected 2026-10-26: BFF records live in `BffLogMessages`):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java` — new LogRecords for
  deliverable 8; `ApiSheriffLogMessages.java` is the wrong catalogue for the BFF surface. Ids are allocated
  across catalogues and enforced by `LogMessagesCatalogueTest`; derive the next free WARN id at write time
  (V02-06 and V02-20 claim ids concurrently)
- OBSERVED (added 2026-10-05): `doc/variants/02-bff-session.adoc` and `doc/quality-report/code-correctness.adoc`
  — both describe the RFC 9470 coordinator and go stale with deliverable 8
- OBSERVED (moved by #487, re-grounded 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/ResponseStage.java` (moved from the edge package root by #487)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java`
- HYPOTHESIS (added 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ReservedPathRules.java` (`validateStepUpPath`, registered in the `ConfigValidator` rule list at :242), plus the OidcSessionRules and OidcClientRules classes in the same package for any new `step_up` boot rule (`BootValidationConcernMapContractTest`) (verify-at-outline)
- HYPOTHESIS (added 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java` — `StepUp` is registered at :103; a new nested record or field needs native reflection (verify-at-outline)
- HYPOTHESIS (added 2026-10-26): `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/reserved/ReservedPathHandler.java` — only if a separate step-up path or kind is added (verify-at-outline)
- HYPOTHESIS (added 2026-10-26): tests `ConfigModelContractTest`, `ConfigLoaderTest`, `ReservedPathRulesTest`, `StepUpEndpointTest`, `GatewayEdgeRouteBffWiringTest` (:828 and :1850 construct the coordinator), `StepUpCoordinatorTest`; `doc/development/bff-session.adoc` (:281–286, :306–309), `doc/fapi_status.adoc`, `doc/features-analysis.adoc`, `doc/architecture.adoc` (mention `StepUpCoordinator` or `step_up`; not read); and, if an IT descriptor or endpoint yaml is added under `integration-tests/src/main/docker/`, `doc/development/declared-limit-assertion-coverage.adoc` plus `DescriptorInventoryWiringTest` (machine-checked exact set). The docker directory has no echo upstream emitting a `WWW-Authenticate` challenge. Constraint, not a file: `EdgeSubPackageArchTest` (ADR-0068, acyclic edge sub-packages) governs where replay code may live
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/session/SessionRecord.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/` — the step-up URL endpoint (verify-at-outline)
- OBSERVED (added 2026-10-02, re-grounding at `e8db85bf`):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/StepUpEndpoint.java` — PLAN-23's
  reserved step-up endpoint. The HYPOTHESIS above is now REALIZED, but by the gateway-declared-scope leg,
  not this plan's upstream-challenge leg. ⚠ Naming and placement collision risk if this plan adds a second
  reserved step-up endpoint; settle at outline whether the two legs share one endpoint with two triggers or
  stay separate paths
- OBSERVED (added 2026-10-02):
  `doc/adr/0057-Session_routes_obtain_their_declared_scopes_by_refreshing_inside_the_grant_and_widening_the_live_session_outside_it.adoc`
  — ADR-0057 explicitly rejects reusing `StepUpCoordinator` for the widening leg, so the two legs stay
  separate by design. Bears directly on deliverables 1 and 6 and must be read before either
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` — authorization request with `S ∪ {s}` (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ScopedEngineFlows.java` — PLAN-14's per-scope-set engine seam (authorize / refresh, ADR-0048) that the refresh with `A ∪ {s}` and the browser step-up with `S ∪ {s}` ride (added 2026-09-22 re-grounding at 3e3addc)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/ReturnTargetScopes.java` — PLAN-14's return-target scope resolver, adjacent to the step-up authorization request's scope construction (added 2026-09-22; confirm/refute at `ReturnTargetScopes.java` § its resolve method, verify-at-outline)
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — the `step_up` block EXISTS already (re-grounded 2026-09-21); this plan adds sibling keys for the `insufficient_scope` leg, it does not create the block
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/` (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/` — step-up ITs (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/` — echo upstream emitting a challenge, Keycloak realm (verify-at-outline)
- HYPOTHESIS: `doc/configuration.adoc`, `doc/user/bff-session.adoc`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`, `doc/adr/` (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-14 — hard. `endpoint.scopes`, `needed(request)` and above all the active scope set `A`
  (PLAN-14 deliverable 7) must exist before a refresh-first step-up can widen `A`
- Overlaps with: PLAN-14, PLAN-19 (`SessionAuthenticationStage`, `GatewayEdgeRoute`, `BffRuntimeProducer`), PLAN-13 (`GatewayEdgeRoute`)
- Adjacent to: PLAN-19's `session_fallback` branch resolution shares the edge auth path but not the challenge path

## Hand-Off Command

> Path corrected 2026-10-05: the ledger moved from the retired `.plan/local/orchestrator/` address to the
> git-tracked `.plan/orchestrator/`. The old path is what `phase-1-init` would have persisted as this plan's
> `source_id`, and the current tooling classifies it `unrecognised_id` — which is exactly what made PLAN-23
> finish with `emit-landing: not orchestrated` and file no landing message. Emitting this spec from the old
> path would repeat that.

```text
/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-20-upstream-signalled-step-up.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
