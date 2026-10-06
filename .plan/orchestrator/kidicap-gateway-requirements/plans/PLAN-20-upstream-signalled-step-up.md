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

## Claim Labels

- OBSERVED: `BffRuntime.stepUpCoordinator()` has no production caller — the only references are `BffRuntimeProducerTest`; grep over `api-sheriff/src/main` at 93a4b3e finds the accessor's declaration and nothing else — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java` § `stepUpCoordinator`
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: unchanged since 35f2bb37 (file not in the 35f2bb37..6788bdae diff): BffRuntime:167 declares stepUpCoordinator(); zero main call sites - only BffRuntimeProducerTest
- OBSERVED: `StepUpCoordinator` is RFC 9470 shaped, driven by a parsed upstream challenge — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java` § `coordinate`
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: unchanged since 35f2bb37 (file not in the 35f2bb37..6788bdae diff): StepUpCoordinator.coordinate parses via StepUpChallengeParser (RFC 9470 acr only); no insufficient_scope branch
- HYPOTHESIS: the silent-satisfaction seam is bound to `(sessionRecord, challenge, now) -> Optional.empty()` at the construction site — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` § step-up construction (verify-at-outline)
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: unchanged since 35f2bb37 (file not in the 35f2bb37..6788bdae diff): BffRuntimeProducer:557-558 binds SilentSatisfaction to Optional.empty() verbatim; comment at 546 says no edge code drives it
- HYPOTHESIS: `doc/configuration.adoc`'s `step_up.*` rows describe the honouring of an upstream challenge as present behaviour — confirm/refute at `doc/configuration.adoc` § `step_up` (verify-at-outline)
  - verdict: corroborated | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: doc/configuration.adoc:2659-2663 still describes honouring an upstream 401 insufficient_user_authentication challenge as present; BffRuntime.stepUpCoordinator() still has no main caller
- HYPOTHESIS: the upstream response path can reach a step-up decision before the response is relayed, and the request body can be buffered for a replay within the route's filter body cap — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java` and the dispatch path (verify-at-outline)
  - verdict: unverifiable | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: DispatchStage rewritten by #385/#386 (+629/-90) and still streams with no replay buffer - its javadoc says a streamed request cannot be safely replayed once a body byte crossed; the interception point stays unproven
- HYPOTHESIS: the Keycloak behaviours the flow rests on (a refresh never adds a scope outside the grant; a narrowed refresh keeps the grant in the refresh token) hold for the integration realm as measured downstream — confirm/refute with an IT against the integration Keycloak (verify-at-outline)
  - verdict: unverifiable | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: live Keycloak behaviour, not readable from source; BffSessionScopeParityIT (unchanged) asserts only in-grant restore and never an out-of-grant omission
- Verify-first clause: settle the replay seam first (deliverable 2) — where in the response path a challenge can be intercepted, and whether a body can be buffered and re-sent without breaking streaming or the body cap. If a replay cannot be done safely for all methods, loop back and re-scope: the flow degrades to the browser step-up with no replay, which changes the acceptance rows.
  - verdict: unverifiable | checked_at: 6788bdae | by: kidicap-gateway-requirements/cleanup | rescoped: n/a | evidence: replay seam still unbuilt at 6788bdae (DispatchStage streams the body); ADR-0060 now fixes the framing a replay must honour; ADR-0057 rejects reusing StepUpCoordinator

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/StepUpCoordinator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/BffRuntime.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java`
- OBSERVED (added 2026-10-05, re-grounding at `35f2bb37`):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java` — the replay seam
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
- OBSERVED (added 2026-10-05):
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java` — new LogRecords for
  deliverable 8; the surface previously declared only `doc/LogMessages.adoc`, which is the document, not
  the code that must carry the record
- OBSERVED (added 2026-10-05): `doc/variants/02-bff-session.adoc` and `doc/quality-report/code-correctness.adoc`
  — both describe the RFC 9470 coordinator and go stale with deliverable 8
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/ResponseStage.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java`
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
