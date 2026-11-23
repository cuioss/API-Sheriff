# Epic: KIDICAP Gateway requirements on API Sheriff (AS-1..AS-14)

slug: kidicap-gateway-requirements

> Ledger document for one epic under `.plan/orchestrator/kidicap-gateway-requirements/` (relocated there
> 2026-10-01 from the retired `.plan/local/` address; now git-tracked, split layout). The layout and
> authority contract live in the central standard — see
> `persona-plan-orchestrator/standards/orchestration-model.md`. `status.json` is the
> machine authority; any statement here that conflicts with it is stale prose.

## Vision

The KIDICAP Gateway (a downstream deployment built on API Sheriff 0.2.1) catalogued fourteen changes
it needs from API Sheriff beyond 0.2.1 — numbered AS-1 to AS-14 in its requirements document
(`doc/api-sheriff-aenderungen.adoc`, German; archived verbatim in this tree under `archive/`, see
`references.json`). They span two blocking defects (AS-7 structured userinfo claims, AS-13 query
validation after decoding), new routing/portal features (application catalog and portal page, HTML
error pages, redirect action with exact-path routes, optional `base_url`), session and OIDC
behaviour (session check without token relay, login return URL with query, per-endpoint scopes with
scope step-up), header handling (`security_headers` per anchor, CSP, precedence mode), and smaller
fixes (`upstream.path` semantics, JWKS readiness, `Location` rewrite, asset index/fallback). This
is far too large for one plan: the items touch disjoint subsystems (config schema, routing, BFF/OIDC,
security filter, asset serving, health) and several depend on each other (AS-2 needs AS-1's
template; AS-6's default return URL targets AS-1's `portal.path`; AS-1 migration uses AS-3).

API Sheriff is pre-1.0: every change is introduced directly — no aliases, warnings, shims or
transition phases. All epic artifacts are written in English. The epic is **done** when every AS
item has either shipped in API Sheriff (merged PR, documented, native-image covered where the item
asks for it) or been explicitly rejected / deferred with a recorded decision, so the gateway can
migrate on the next parent-version bump. Staged second-stage items (AS-1 per-user visibility and
circuit-breaker state; AS-14 per-endpoint token narrowing) are out of scope unless promoted by a
decision.

## START HERE

### Annotations

<!-- ANNOTATION ZONE — hand-written, and deliberately OUTSIDE the generated markers.
     A regeneration replaces only what sits BETWEEN the markers, so everything written
     here survives it. -->

## Ordered Queue

### Queue annotations

<!-- ANNOTATION ZONE — hand-written, and deliberately OUTSIDE the generated table markers. -->

- PLAN-20 — staged, HELD 2026-11-22 on the operator's call behind PLAN-33: the two share 14 files (`TokenRefreshCoordinator`
  and its test, `CallbackEndpoint`, ADR-0057/0063/0074, the threat model, `LogMessages`, two JWKS ITs). Re-ground it again against
  PLAN-33's result before emitting; it still needs the operator decision at outline (replay against the streaming stance) and must
  call the step-up result verifier.
- PLAN-33 — staged 2026-11-12, **emitted and `launched` 2026-11-22** (operator chose it alone over PLAN-20; the cleanup
  re-ground of the same day is in its `## Expected Surface` banner). WS-05; the operator's next plan to run. Its overlap with PLAN-31 on
  `RetryingJwksLoader` and its test is gone: PLAN-31 merged as #605 (2026-11-15), so the spec's surface now
  sits on the merged `RefreshBasis` rule. Staged PLAN-SEC-06 (sibling epic) deliverable 3 also edits
  `RetryingJwksLoader.settleRefresh`: re-run the cross-check at the gate.
  Operator decisions at outline: expose `maxStaleness` as a gateway key (and its default), drop
  `token-sheriff-client-quarkus` (it interacts with PLAN-34 D1: TokenSheriff's native-image plan would put
  the registration into that extension), readiness for a stale-degraded issuer.
- PLAN-34 — staged 2026-11-12, WS-05; **TokenSheriff 0.9.10 released 2026-11-23 and PINNED on `main`** (bump PR #658 merged
  `4213eb1f`, with a test-only fix for the dormant engine `ParClient`). Still BLOCKED, now on PLAN-33 (#656) alone, which has not
  landed. Not emitted; first step at outline is the verify-first read of the resolved jar. Depends on PLAN-33 and on TokenSheriff epic
  `api-sheriff-seams`; every TokenSheriff API name in it is a proposal to re-verify against the
  released jar. Shares `BffRuntimeProducer` with PLAN-20; the order of PLAN-20 and PLAN-34 is open.

- PLAN-24 — shipped #430. Ran alone; nothing collided.
- PLAN-27 — shipped #433. Ran alone; nothing collided. Its post-merge benchmark run failed (Open
  Defects). The native integration job took about 20 minutes with its one new suite.
- PLAN-28 — shipped #437. Ran alone. Three fixes, three records, two diagnoses that ended without a
  fix (one of them became PLAN-30).
- PLAN-30 — shipped #448. Ran beside the sibling's `PLAN-V02-12` over a 3-path overlap (operator's
  call); nothing collided. Merged while this epic recorded a "must not merge" hold the plan never saw
  (lesson `2026-10-14-13-003`). The upload benchmarks no longer exercise the early-answer path.
- PLAN-32 — shipped #466 (`73233f1e`), closing #455. Ran alone in this epic, beside the sibling's V02-24 and
  V02-26; nothing collided. The cause was not the `SO_LINGER 0` the spec suspected. It was a deadlock between
  the inbound HTTP/2 connection window and the upstream pool. The fix added an operator-requested 10 s
  request-body idle bound that answers `408`. Realized 17 files against 5 declared entries.
- PLAN-29 — shipped #586 (`ab568dab`, 2026-11-09), the four inert upstream keys removed, `retry.enabled` kept, recorded
  as an ADR-0064 amendment. Ran alone in this epic beside the sibling's merged #581 and #582: one shared file with #581
  (`observability-and-errors.adoc`), none with #582, no conflict. Declared 37 entries (both outcomes), realized 16
  files; three realized docs were undeclared. Landing record: `landings/PLAN-29.md`; the three-layer read found
  no blocker.
- PLAN-26 — shipped #592 (`ad00807d`, 2026-11-10), the logout refresh-token revocation bound, BFF-09 and every logout
  record corrected, ADR-0080 and ADR-0081 written, ADR-0018 amended. Ran alone in this epic. It took `ApiSheriff-147` because
  #589 took 146 while it was open (two rebases, one real conflict in `doc/LogMessages.adoc`). Realized 31 files; the five
  session-binding files, `doc/LogMessages.adoc` and ADR-0081 were not in the declared surface. Landing record:
  `landings/PLAN-26.md`; the three-layer read found no blocker (1 layer-1, 5 layer-2 and 4 layer-3 findings, all minor).
- PLAN-31 — shipped #605 (`5aa17338`, 2026-11-15), the exact JWKS publish rule: a refreshed key set is published
  only when it holds a key id that a basis captured at the claim lacks and the basis is still current at settle
  time; the triggering key id plays no part. Ran beside the sibling's #607, which changed the same decision
  while PLAN-31 was in finalize (operator-approved reconciliation, merge commit `7c0ee841` on the branch). The
  gate could not predict that collision: #607's plan belongs to another epic. Realized 6 files, all inside the
  declared surface. Landing record: `landings/PLAN-31.md`; the three-layer read found no blocker (3 layer-1, 3 layer-2
  and 4 layer-3 findings, all minor but the spec-side D1 text). The shipped rule is narrower than the frozen
  D1 criterion (Open Defects).
- **PLAN-21 — SUPERSEDED 2026-11-09 by `security-hardening-tier-1` PLAN-SEC-03 (#581, `3eef748b`).** Its
  acceptance criterion is met by the per-source quota, the `503` at saturation and the rewritten
  `BffPendingLoginBoundIT`. The spec is kept as the audit record. Four gaps stay as Watches.
- **Cleanup 2026-11-09 (HEAD `2200ddb2`, after #581 and #586).** Re-ground of the three emittable-or-held specs
  plus PLAN-31: 23 claims re-stamped at `2200ddb2` (PLAN-20 7, PLAN-21 7, PLAN-26 4, PLAN-31 5), 0 blocking, 0
  stale among the staged specs. Premises intact for PLAN-20, PLAN-26 and PLAN-31; PLAN-21 no longer applicable.
  PLAN-26 is the next emit: the #581 gate is met and it shares nothing with in-flight work. PLAN-20 follows it
  (shared `BffRuntimeProducer`, threat model and `doc/adr`) and still needs an operator decision at outline
  (replay against the streaming stance). PLAN-31 waits for the TokenSheriff 0.9.9 tag (none exists yet).
  Next free ADR ordinal **0080**, next free WARN id **146**. WS-04 is now PLAN-26 then PLAN-20, strictly
  sequential. `security-hardening-tier-1` has PLAN-SEC-02 and -04 staged; the cross-check measures 2 and 3 shared
  files with PLAN-26 (the threat model, ADR files, `authentication-and-oidc.adoc`).
- **Cleanup 2026-11-09 (HEAD `c85817d9`, worktree rebased onto `origin/main`; 54 commits after the last
  pass).** All five staged plans (PLAN-20, -21, -26, -29, -31) were re-grounded by five read-only leaves, one
  per spec: 32 claims, 20 corroborated, 8 contradicted and rescoped in the spec (each spec carries a dated
  banner under `## Expected Surface`), 4 unverifiable, 0 blocking. All 20 specs are `declarative`, 0
  indeterminate, and every added surface path was confirmed by membership in `claimed[]`. Premises are intact for
  all five; what moved is the ground: `doc/configuration.adoc` and `doc/architecture.adoc` were split (#575,
  #533), `doc/quality-report/` was deleted (#575), the next free ADR ordinal is **0080**, `PendingAuthorizationStore.InMemory`
  became `InMemoryPendingAuthorizationStore` (#573), and the per-source substrate PLAN-21 waited for landed
  (#532, #537; ADR-0076 to ADR-0078) but is edge-only. No sibling plan of the archived `api-sheriff-0-2-0` is
  running any more. **The live overlap is `security-hardening-tier-1`:** PLAN-SEC-01 landed as #582 (its ledger
  row still reads `running`; that epic's own `analyze` owes the reconciliation) and PLAN-SEC-03 is open as PR
  #581. **PLAN-21 is a probable duplicate of PLAN-SEC-03** and is held, DO NOT EMIT (Decisions).
  PLAN-20, -21 and -26 share 12 to 15 files with PLAN-SEC-03; PLAN-31 shares 2; PLAN-29 shares 1.
- **Cleanup 2026-10-26 (HEAD `54f82f03`, worktree rebased onto `origin/main`).** All five staged plans
  (PLAN-20, -21, -26, -29, -31) were re-grounded: 32 claims, 21 corroborated, 7 contradicted and rescoped in
  the spec, 4 unverifiable, 0 blocking; every Expected Surface path exists. Still `applicability:
  still_applicable` for all five; nothing else was shipped or fixed elsewhere. The disjointness gate is
  determinate (no unread gate candidate) and reports a multi-file overlap (4 to 20 files) with each of the
  three running siblings V02-05, V02-06 and V02-20 for every staged plan, so each emit raises the operator's
  overlap question, and the sibling's three slots are full. Open operator decisions the re-ground surfaced:
  PLAN-26 D2 (implement or keep unbound logout revocation, now cheap since `oidc.back_channel_origin` landed),
  PLAN-29 D1 (wire or remove each key pair), PLAN-21 option a/b/c (b/c need V02-06's substrate), PLAN-20
  deliverable 1 (wire or remove the inert `oidc.step_up.enabled` / `honor_upstream_challenge`).
- PLAN-25 — shipped #408. The pairing note below ("pairs with anything") held for files and failed for
  the CI job's wall-clock.
- **Flow is sequential in practice.** Every staged pair overlaps on `doc/configuration.adoc`, the schemas
  or `GatewayEdgeRoute.java`, so `parallelization_scope` 2 has never yielded a second slot. Both landings
  also expanded their real surface ~3x beyond the declaration, so a `disjoint` verdict here is weak
  evidence — see the Watch.
- PLAN-16 — PLAN-14 is out of the schemas now (shipped #337); portal CSP composes with PLAN-15's header
  precedence mode.
- PLAN-17 — small and independent; only shares the validation package and docs.
- PLAN-18 — re-grounded at 3abc370 (PLAN-15 rewrote `RouteMatcher` and `ConfigValidator`); see its claim
  verdicts before emitting.
- PLAN-19 and PLAN-20 depended on PLAN-14 — satisfied (the active scope set `A` shipped in #337). Both
  overlap PLAN-14's realized footprint heavily (38 and 41 files) and were grounded at 3abc370, not 3e3addc.
  PLAN-21 independent but shares the BFF login surface.
- **PLAN-23 (staged 2026-09-24, runs FIRST) — the downstream's highest-priority defect.** A session route
  relays a token missing the scopes the route itself declares, while the same route answers `403` for a
  bearer caller: one declaration, two enforcement regimes. It blocks `endpoint.scopes` (AS-14) for more
  than one application, and the downstream's workaround (fold every application's scopes into
  `oidc.scopes`) re-creates the over-request ADR-0049 existed to prevent. Both halves re-verified at
  2f4254d3. It partly reverses ADR-0049 — but that ADR deferred the session pre-check until a live session
  could be widened, and PLAN-14 then shipped exactly that, so this is the deferral's precondition arriving
  rather than a reversal of judgement. Sequenced ahead of PLAN-19/20/21 by operator decision; **PLAN-20
  must be re-grounded once it lands**, since PLAN-23 implements the gateway-side half PLAN-20's spec
  assumes is absent and owns the widening mechanism PLAN-20 will consume.
- PLAN-22 (staged 2026-09-23 from PLAN-17's finding) — the WebSocket relay's early-frame race. **The most
  disjoint spec this epic has had**: no overlap at all with the running PLAN-18's live footprint, and its
  only remaining collisions come from OTHER staged specs declaring the whole
  `integration-tests/src/test/java/` directory, which contains the one IT this spec expects to add. So it
  is the first genuine second-slot candidate under `parallelization_scope` 2. Not an AS item — a defect in
  API Sheriff's own relay, found while verifying an epic landing.
- Re-grounded 2026-09-22 at 3e3addc (41 claims: 37 corroborated, 3 unverifiable, 1 contradicted-and-rescoped
  in PLAN-21; 0 blocking). PLAN-16/17/18: nothing PLAN-14 touched alters their premise. PLAN-19: PLAN-14
  landed exactly its prerequisites (`token_relay`, `neededScopes`, session routes request but do not
  enforce scopes). PLAN-20: PLAN-14 added the gateway's OWN `403 insufficient_scope` on bearer routes
  (`AuthenticationStage`) — outline must disambiguate it from the upstream-relayed challenge this plan
  wires; `StepUpCoordinator` is pre-wired with scope/default-return params and an in-code note naming the
  step-up scope set as PLAN-20's question (ADR-0048). PLAN-21: `LoginFlow.initiate` gained a `scopes`
  parameter; the re-scoped mechanism still holds.
- **PLAN-24 (staged 2026-10-02, WS-01) — the key-rotation window.** Downstream HIGH, measured twice: an
  abrupt IdP key change costs up to 600 s of total outage on every protected route and every login, with
  no knob to shorten it. Wants a bounded single-flight fetch on an unknown `kid` plus a configurable
  refresh interval. **No main-source overlap with any WS-04 spec** — the first real second-slot candidate
  since PLAN-22. Carries an outline fork: if the bounded fetch belongs in token-sheriff, the library half
  leaves this repository.
- **PLAN-25 (staged 2026-10-02, WS-02) — the IT-evidence plan.** Ten properties the downstream states to
  its operators with only unit-level backing. Tests only, with a STOP rule if a property proves false, and
  check-first instructions on the two rows PLAN-23/PLAN-21 may already cover. Surface is
  `integration-tests/**` with no `api-sheriff/src/main` entry, so it pairs with anything.
- **PLAN-26 (staged 2026-10-02, WS-04) — the logout truth-up.** Threat-model BFF-09 claims COVERED for a
  revocation the runtime binds to a no-op; plus the undisposed Medium cookie-mode residual and two missing
  ADRs. Mostly prose, but deliverable 1 removes a false security claim. Sequenced against PLAN-20/21 (same
  threat-model and ADR files); pairs with PLAN-24 or PLAN-25.
- **Pairing, as it now stands:** WS-04 (PLAN-20, PLAN-21, PLAN-26) is one contended surface and stays
  strictly sequential; PLAN-24 (`auth/` + config) and PLAN-25 (`integration-tests/`) are each disjoint from
  it. So the second slot is finally fillable — pair one WS-04 plan with PLAN-24 or PLAN-25, never two WS-04
  plans.
- Superseded first-cut specs PLAN-01..PLAN-12 live in `plans/superseded/` (mapping in its README) and are
  never emitted. Shipped: PLAN-15 (#320), PLAN-13 (#334), PLAN-14 (#337) — their records are in
  `landings/`.

## Decisions

- 2026-11-23 — **Batch C: a fix plan is DUE (operator, proposal 1; threshold rule of the standing fix-routing entry).** The pending-fix
  backlog is now about 17 (the 8 earlier entries plus 9 new ones), over 10, so under the standing rule a fix plan is created and later
  fixes join it first. **Proposed scope, nine deliverables after grouping (cap 12):** (1) `Vary: Accept` on negotiated error
  responses; (2) WebSocket relay cleanups (redundant `pongHandler`, merge the two dialer helpers); (3) per-reason `ApiSheriff-147`
  WARN in the logout revocation (L3-1) and the L3-2 accept-or-plan decision; (4) the PLAN-26 layer-2 pass; (5) canonical-form boot
  check for the callback, logout, logout-return and back-channel-logout paths plus a duplicate-reserved-path rule; (6) the
  pending-store fairness ADR; (7) a WARN or metric on a source evicting its own oldest login record; (8) raw-value echo in boot
  refusals plus the old comment inaccuracies; (9) the PLAN-28 doc leftovers (Javadoc range guard, `source: file` coverage) and the
  retry-wording fix. **The five held `RetryingJwksLoader` fixes (item 2)** join this plan later if PLAN-34's re-ground leaves the
  code alive: that would make ten deliverables, still under the cap. **Follow-up the operator words:** staging the plan (this verb
  never stages one); the orchestrator can draft the spec on request. **Not fixes, stay open as decisions:** the access log, the
  PLAN-23 scope-fallback and refresh-token items, the login rate limiter, `authorize` before the store, the TokenSheriff
  separation audit, the cookie-size re-measure, the 0.2.4 residual, the Sonar note. These were presented in the batch and the
  answer did not decide them.
- 2026-11-23 — **Batch B (operator: B1:1, B2:1, B3:1).** **B1:** eight release and downstream watches retired on the operator's
  confirmation that the 0.2.2 release-note and downstream actions are done (an operator-confirmed account, not re-read by the
  orchestrator; the 0.2.4 downstream report backs the notification half). One residual of the 0.2.4 report was kept as its own
  open watch (reserved-path defaults outside a build-time context path; Keycloak's extra `302`). **B2:** four entries on unobserved
  main-branch runs stay open, marked awaiting the operator's Actions check; the orchestrator cannot read push runs, and the
  operator has not yet reported any result. **B3:** seven entries accepted as outside this epic, each marked with where it is
  tracked; none retired or relocated. Open-item count after this batch: the product-gap defects and watches (batch C) remain.
- 2026-11-23 — **Practice-note batch A (operator, proposal 1).** Fourteen standing practice notes accepted as standing practice
  and marked in place (surface-vs-footprint measurements x3, same-day re-grounding, native-IT wall-clock collision, downstream-cited
  ITs, validator/runtime parity, sibling-doc staleness, review-bot doc catches, sweep-the-wording, doc-precision, semantics-change
  sweep, bind-mount file mode, PLAN-16 test practice, spec verify-first clause). Accepted means they stay in `epic.md` with their
  triggers and are not presented again; none was retired or relocated. **Kept open:** the Sonar new-code-findings note, because it
  owes the operator an `architecture enrich insight --module api-sheriff` call. Not re-read this run, so these were operator
  decisions, not orchestrator decisions.
- 2026-11-23 — **Threshold lowered to 60; second `handle-open` re-read pass (operator: "revisit all finding, decide if you are 60%
  confident").** The `orchestrator.decision_confidence_threshold` knob was found at 90 (the earlier change to 75 was not on disk)
  and is now 60, uncommitted in `.plan/marshal.json`. Decided this pass: item 5 narrowed (2 fixes to the backlog, applied at the
  threshold on the operator's wording), the WARN-id/ADR-ordinal watch retired on PLAN-20's spec, and the retry-jitter question
  answered (SmallRye's default jitter is 200 ms; 1 wording fix to the backlog). **Backlog now: 8** (5 from item 2, 2 from item 5,
  1 retry wording), under 10, so no fix plan yet; target plan still held for PLAN-34's re-ground. Items 3 and 4 were answered by
  the operator earlier in the run (measure the gate churn after PLAN-33; route the breaker-identity ADR to the next plan on
  `edge/`). **Re-read this run and still open (nothing decided on them, evidence recorded so the next run does not repeat it):**
  `Vary: Accept` absent; no `quarkus.http.access-log.*` setting; `WebSocketRelayStage.java:531` still sets the redundant
  `pongHandler` and the two near-duplicate dialer helpers still sit in `WebSocketRelayStageTest`; `token-sheriff-client-quarkus`
  still declared at `api-sheriff/pom.xml` :123-125 with no import in main; PLAN-26 L3-1 (single `revocationFailureReported`
  latch, `RpInitiatedLogout.java:121`) and the layer-2 residue (`LogoutEndpointTest.java:682` keeps its misleading name,
  `BffLogMessages.java:32` keeps a hand-written inventory, ADR-0080 does not link ADR-0071, `RpInitiatedLogout.java:58` still says
  no other path revokes); the canonical-form boot check covers `user_info`, `login`, `step_up` and the client JWKS path
  (`ReservedPathRules.java` :211, :239, :267) but not callback, logout, logout-return or back-channel-logout; no ADR records the
  pending-store fairness mechanism (`doc/adr` has none); `StepUpResultVerifier` has no caller (PLAN-20). The `getLoaderStatus`
  masking is carried by PLAN-33's spec (`plans/PLAN-33-*.md` :42-46), which is running. Not re-read, so capped at the threshold and
  not decidable: every CI-run, release-note and downstream watch, the plugin-side lessons, and the practice notes.
- 2026-11-23 — **Standing fix-routing rule (operator, `handle-open`).** "For the fixes: add to an existing plan if they count less
  than 10, create a fix plan otherwise; later fixes may be added to the newly created plans first." Recorded reading, open to
  correction: the count is the pending fix backlog across every `handle-open` item, not per item. Under 10 pending fixes, each goes
  as a deliverable onto an existing staged plan that owns the surface; at 10 or more, a new fix plan is created, and later fixes join
  that fix plan before any other. Staging a plan and editing a spec stay acts the operator words, so `handle-open` records the
  routing and names the follow-up. **Backlog now: 5** (all from item 2: PLAN-31 L3-3, L3-4 and three layer-2 test items).
  **Target plan (operator chose proposal 1, 2026-11-23): HOLD.** PLAN-34 is the only staged plan on `RetryingJwksLoader` but its D6
  reduces the class, so the fixes would be moot if D6 ships; PLAN-33 is running and may not be edited. The five stay in the backlog
  until PLAN-34's re-ground shows what D6 keeps: they then go onto PLAN-34 if the code survives, or onto a plan the operator names if
  D6 removes it. The backlog is under 10, so no fix plan is created yet.
- 2026-11-23 — **Cleanup then `handle-open` (threshold 90, knob).** Cleanup: corpus reconciles both ways (22 rows, 22 specs), no
  Done-when finding, no duplication; the A1 re-ground of PLAN-20 and PLAN-34 is DEFERRED until PLAN-33 lands (they share 14 and 23
  files with it and it rewrites the claimed surface; PLAN-33 is `running` and excluded). Four settled items relocated verbatim to
  `settled.md` on the operator's request (the tier-2 inbox drain, the four inert upstream keys, the stale ledger worktree note,
  PLAN-31 `surface_delta`); compaction ok, 26 pointers. Archive drain refused (no epic-wide quiescence signal). Restart `not_ready`:
  PLAN-33 running and `registry_parity` (registry 0.1.2139 / 0.1.2184 against executor 0.1.2184). `handle-open`: item 8 (the
  portal ADR, formerly named ADR-0050) decided by the orchestrator at confidence 93 — retired, the file introduced by #343 is
  `Accepted` as ADR-0053 (`doc/adr/0053-...adoc:15-17`). The threshold was then lowered to 75 at the operator's request (knob
  `orchestrator.decision_confidence_threshold`, was 90). Item 1 (PLAN-31's narrower publish rule) was put to the operator, who chose
  proposal 1: **accept the narrowing in writing**, no re-opening plan; ADR-0063 stays the place the cost is stated. Every other item
  stays open and is put to the operator one per turn.
- 2026-11-23 — **0.9.10 bump PR #658 MERGED and `main` pins 0.9.10 (operator session report, checked against GitHub).** Merged
  2026-11-23T11:40:10Z by the release bot as `4213eb1f`; two commits, `bfbd199d` (the bump) and `d65bcc97` (test fix and BOM-comment
  re-measure, `BffRuntimeProducerTest.java` and `pom.xml` only). The red CI was a test artefact, not a behaviour change: 0.9.10's
  `AuthorizationCodeFlow` owns a private, unused `ParClient`, which the test's reflection walk counted. The report also names an
  infrastructure flake (`generate-mtls-certificates`, `Stream closed`, green on re-run), one timing-test failure under concurrent
  load that passed in isolation and in CI, and a partial local native run (461 passed, 0 failed, hit the 50-minute limit); the CI
  integration job is the authoritative run and passed. **This supersedes the same day's entry below for the pin: the "bump cannot merge
  alone" finding is resolved by the test-only fix, and the PLAN-34 block is now PLAN-33 alone.** Open consequences, none recorded as
  a defect: (1) PLAN-33's PR #656 was built against the 0.9.9 pin and now sits behind a `main` that pins 0.9.10; its merge state read
  `CLEAN` when checked, but its CI ran against 0.9.9, so the landing message should say which pin it verified against; (2) PLAN-34 D2
  must delete the `gatewayParClients` exclusion and its control test when it adopts `authorizePushed`; (3) PLAN-20 re-ground after
  PLAN-33 is unchanged. Not applied: no PLAN-34 verdicts re-stamped, nothing emitted.
- 2026-11-23 — **TokenSheriff 0.9.10 released (operator report, verified); inbox drain found nothing.** The epic inbox is empty
  and PLAN-33's landing is still owed (`awaiting_plans: PLAN-33`, PR #656 open and clean, 44 files, no `pom.xml`). The release is
  real: tag `0.9.10` published 2026-11-23T07:17:44Z, `token-sheriff-bom` 0.9.10 is on Maven Central. `main` (`1f6dbfec`) still
  pins 0.9.9; the release workflow's bump PR #658 is open and RED on `BffRuntimeProducerTest` (the assembled runtime now reaches two
  pushed-authorization-request clients, not one), so it cannot merge by itself. **Applied:** dated release banner in PLAN-34's
  `## Expected Surface`, the PLAN-34 queue annotation, the resume anchor. **Not applied:** PLAN-34's 22 verdicts are not re-stamped
  (the 7 unverifiable TokenSheriff-side claims need the resolved jar, which the verify-first gate reads at outline), PLAN-34 is not
  emitted, and PLAN-20 stays held. PLAN-34's order against PLAN-33 is unchanged: PLAN-33 lands first, since both touch
  `BffRuntimeProducer` and the dependency decision of PLAN-33 D4.
> ↪ Relocated to `settled.md` § "Inbox drain of 2026-11-22 from security-hardening-tier-2 (all discarded)" — the sender epic is closing, every claim was read against origin/main and discarded; no spec, defect or watch resulted
- 2026-11-22 — **Cleanup at `226d9f3a` (worktree rebased onto `origin/main`, 48 commits after the last pass), then `next`.**
  Re-ground of the three staged specs by three read-only leaves, one per spec: 58 claims (PLAN-20 7, PLAN-33 29, PLAN-34 22),
  42 corroborated, 11 unverifiable (TokenSheriff-side or live-IdP), 5 contradicted and rescoped in the spec (PLAN-20 the replay
  buffer, PLAN-33 two, PLAN-34 two), 0 blocking. **Applied:** a dated re-grounding banner under `## Expected Surface` of each of
  the three specs (next free ADR **0083**, next free WARN id **153**; every line anchor stale, locate by symbol) and 7, 5 and 16
  surface entries added, verified by membership in `claimed[]`; PLAN-34's two unresolved entries (bare file names) were fixed
  by re-writing them as full paths (`indeterminate_count` 0 before and after, all 22 specs `declarative`). **Declined:**
  nothing superseded or merged (no source-origin match, no live plan, gate population empty); `declaration: none named` on the three
  staged specs is flagged, not applied: it has no gate effect (`emit_allowed: true`) and the lane's outline resolves skills.
  Settled-narrative relocation done on the operator's confirmation: five Decisions entries (2026-11-09 and 2026-11-10: the cleanup,
  PLAN-26 and PLAN-29 landings, the PLAN-21 hold and its supersede) moved verbatim to `settled.md`. Compaction ok (22 rows, 22
  specs, 22 pointers); archive drain refused; restart `indeterminate` only on `registry_parity` (the executor is 0.1.2106, the
  registry pins 0.1.2184: regenerate the executor).
  **Emit.** `parallelization_scope` 2, nothing in flight, so two slots. PLAN-20 and PLAN-33 both pass the prep-ready, declared and
  disjointness tests and the cross-check is determinate, but they share 14 files, so the overlap prompt was asked: the operator
  chose **PLAN-33 only**, PLAN-20 held until PLAN-33 lands (recommended option; alternatives offered: both, or PLAN-20 only).
  PLAN-33 is recorded `launched` (`auto_emit` is true); it is not recorded `running`. PLAN-34 stays staged and BLOCKED: no
  TokenSheriff 0.9.10 tag exists (tags end at 0.9.9) and it depends on PLAN-33.
> ↪ Relocated to `settled.md` § "Cleanups, landings and the PLAN-21 hold of 2026-11-09 and 2026-11-10 (PLAN-21, PLAN-26, PLAN-29)" — PLAN-26 and PLAN-29 shipped, PLAN-21 is superseded by #581, and the two cleanup passes are superseded by the 2026-11-22 pass; five dated Decisions entries, moved verbatim

- 2026-10-26 — **Operator decisions on the four open questions the cleanup surfaced** (each recorded in the
  spec as a dated DECIDED block; the spec is the durable record):
  - **PLAN-26 D2: implement logout refresh-token revocation INSIDE PLAN-26.** Overrides the spec's former
    stop-and-split rule. PLAN-26 is no longer documentation-only: full quality gate, verify and the native IT
    lane apply. Alternatives offered: a separate follow-up plan (the recommendation, which keeps PLAN-26
    doc-only and fast) and keeping the seam deliberately unbound.
  - **PLAN-29 D1: remove all four inert upstream keys** (`connect_timeout_ms`, `read_timeout_ms`,
    `retry.max_attempts`, `retry.idempotent_only`); `retry.enabled` stays; recorded as an ADR-0064 amendment.
    Alternatives offered: wire the timeouts only, wire both pairs.
  - **PLAN-20 D1: delete `oidc.step_up.enabled` and `honor_upstream_challenge` now; the upstream-signalled leg
    is still built later behind ONE opt-in key, off by default, resolved per policy block.** Reasons to disable
    are per route (client handles step-up itself, untrusted upstream, routes that cannot buffer for replay),
    which is why a global key was the wrong shape. Alternatives offered: one global key refused at boot, or
    dropping the leg.
  - **PLAN-21: HOLD until `PLAN-V02-06` ships, then do all of it as one plan.** Alternatives offered: split
    observability now and cap later (the recommendation), or option (a) with its own cookie.
    *V02-06 shipped as #528 — the hold condition is met (see the cross-epic hold entry, update 2026-11-07).*
  Consequence for sequencing: PLAN-26 now touches `BffRuntimeProducer` and `BffLogMessages`, shared with the
  running V02-06 and V02-20, so it is no longer a free filler; PLAN-29 must run before sibling `PLAN-V02-48`.
> ↪ Relocated to `settled.md` § "PLAN-32 landing and the 2026-10-15 holds" — PLAN-32 shipped (#466) and the holds were lifted 2026-10-26. ⚠ The standing rule inside it (route every plan-marshall-related finding to plan-marshall's lessons-routing inbox, sender `api-sheriff-kidicap-gateway-requirements`) is also kept in the resume anchor
> ↪ Relocated to `settled.md` § "Landings and drains of PLAN-27, PLAN-28 and PLAN-30 (2026-10-13 to 2026-10-14)" — PLAN-27, PLAN-28 and PLAN-30 shipped; their drains, folds and resolved defects are closed

> ↪ Relocated to `settled.md` § "Staging, emission, drains and landings of 2026-10-12 (PLAN-24, PLAN-25, PLAN-27, PLAN-28)" — PLAN-24, PLAN-25, PLAN-27 and PLAN-28 shipped; drains 3 and 4 are archived

> ↪ Relocated to `settled.md` § "Cleanup passes of 2026-10-05 and 2026-10-06" — two earlier cleanup passes, superseded by the 2026-10-26 pass

- 2026-10-02 — **PLAN-20 re-scoped rather than retired.** PLAN-23 (#369) shipped the gateway-declared half
  of scope step-up — a reserved `StepUpEndpoint`, silent-then-one-interactive widening, and a
  `403 problem+json` carrying `step_up_url` — which is nearly the shape of PLAN-20's deliverable 3, and
  **ADR-0057 explicitly rejects merging the two legs**. So PLAN-20 keeps its subject (the leg an UPSTREAM
  `insufficient_scope` signals, which still has no caller at all) with deliverables 2, 4 and 5 narrowed to
  what PLAN-23 did not build: the intercept-and-replay against a live upstream `403`, for which
  `ResponseStage` and the request-body buffer are still untouched. Deliverable 9 (the ADR-0053 renumber)
  is DROPPED — #367 did it. Alternative considered: retire PLAN-20 entirely as superseded — rejected,
  because the upstream-signalled trigger is the downstream's original request (`-006` / `-009`) and no
  shipped mechanism answers it. The re-scope banner sits at the top of its deliverable list so the plan
  cannot be started on the pre-PLAN-23 premise.

- 2026-09-15 — Epic created from the KIDICAP Gateway requirements document (German source,
  14 items AS-1..AS-14). All ledger artifacts are authored in English; the source document is
  archived verbatim under `archive/` and removed from the kidicap-gateway repository once its
  content is integrated here. Rationale: one durable, English requirements record inside the repo
  that implements the changes.
- 2026-09-15 — `parallelization_scope` = 2 (operator accepted the project default).
- 2026-09-15 — Decompose: ground-truth sweep at HEAD fb9e774 corroborated all 14 baseline claims; none
  is already implemented. AS-8 is worse than the source states (per-route resolved headers are never
  read at runtime). Every "Migration" section in the source concerns the KIDICAP Gateway repository and
  is carried only as consumer context, never as an API Sheriff deliverable.
> ↪ Relocated to `settled.md` § "Superseded 12-plan decomposition" — superseded by the aggregation decision below

> ↪ Relocated to `settled.md` § "Aggregation into four plans, AS-13 and the CR/LF decision (2026-09-15)" — PLAN-13 to PLAN-16 shipped; the cui-http change was released

> ↪ Relocated to `settled.md` § "PLAN-15 shipped (2026-09-17)" — PLAN-15 shipped and reconciled
- 2026-09-17 — **AS-14 re-scoped by the downstream** (inbox `-008`, `-009`): the backend signals a missing
  scope (`403 insufficient_scope` before any side effect) and the gateway obtains it — refresh first with
  one replay, browser step-up otherwise. PLAN-14 keeps `endpoint.scopes`, the login request and the bearer
  `403`, and gains the active-scope refresh without which it would strip endpoint scopes on the first
  refresh; the gateway-side pre-check and the navigation-only step-up move out to the new PLAN-20. No
  per-route scopes, by downstream decision. Alternative considered: keep the pre-check as a second trigger
  — rejected, because a per-route scope list in the gateway drifts from the backend's own knowledge.
> ↪ Relocated to `settled.md` § "Drain 2 and the landings of PLAN-13 and PLAN-14 (2026-09-17 to 2026-09-22)" — PLAN-13 and PLAN-14 shipped and reconciled

- 2026-11-12 — **TokenSheriff separation staged as WS-05 (PLAN-33, PLAN-34) plus a TokenSheriff epic.**
  Operator asked for full separation of concerns with TokenSheriff after the 0.9.9 bump removed one
  duplicate. Decisions: (1) the work that needs only the pinned 0.9.9 is one plan, PLAN-33 (five
  deliverables: truthful JWKS status through `RetryingJwksLoader` with a failing test first, the
  library's `ErrorTextSanitizer` in `SignatureOnlyTokenVerifier`, documentation 0.9.9 made false, the
  unused `token-sheriff-client-quarkus` dependency, and a verify-first check of the refresh-versus-
  widening race); the operator named it the next plan to run. (2) Everything that needs a new
  TokenSheriff seam is NOT planned here: it is the TokenSheriff epic `api-sheriff-seams` (seven plans,
  target 0.9.10, in `/home/oliver/git/TokenSheriff`'s own ledger, not landed), and PLAN-34 deletes the
  gateway copies against the released 0.9.10, one droppable deliverable per TokenSheriff plan. The
  version bump is the release workflow's PR, never PLAN-34's. (3) Split-or-proceed: PLAN-34 carries
  seven deliverables, under the 12 cap, because each is independently droppable and a deliverable
  whose TokenSheriff plan did not ship is removed at outline rather than waited on. (4) The audit's
  "StepUpCoordinator is dead code" is rejected: PLAN-20 wires it, and PLAN-20 must also call the
  step-up result verifier. (5) Corrections the drafting made to the audit: the `pom.xml` comment on
  `GatewayExceptionMapper` was already fixed by `0c0680b3`; the race in PLAN-33 D5 involves the
  widening/step-up landing, not login (login mints a new session id). Alternative considered: one
  large plan covering both; rejected because PLAN-34 cannot start before a TokenSheriff release.

## Open Defects

> ↪ Decided 2026-11-23 by the operator in `handle-open` (item 1; the recommendation sat at confidence 70, below the 75 threshold, so it was
> put to the operator): **the narrowing is ACCEPTED in writing.** PLAN-31's shipped publish rule discards a refresh whose basis moved
> during the fetch and counts a retired delegate's key ids as held, which is narrower than the frozen D1 "publish when the key ids differ
> at settle time"; the cost is stated in ADR-0063 and the functional review carried it as the non-blocking residual D1.R1. No plan
> re-opens the criterion. Original close condition: the operator accepts the narrowing in writing, or a plan re-opens it.
> — source: `landings/PLAN-31.md` L1-1, L2-1; see `## Decisions` 2026-11-23 handle-open item 1
> ↪ DECIDED 2026-11-23 by the operator in `handle-open` (item 2): the findings are FIXES, routed by the standing fix-routing rule in
> `## Decisions` 2026-11-23. Re-read at HEAD that day: L3-1 (:553-557) still true and the code comment calls it deliberate, so it is
> ACCEPTED, no fix; L3-3 (:668-681), L3-4 (:556) and the three layer-2 test items are FIXES, **5 fixes** into the backlog. Follow-up
> named, not yet staged: a deliverable on the plan that owns `RetryingJwksLoader`; the target is OPEN because PLAN-34 D6 reduces
> that very class. The entry below stays open until the fixes land.
- **PLAN-31 layer-3 and layer-2 findings, all minor (2026-11-15), reported to the operator, nothing fixed inline.**
  (L3-1) the claim's basis snapshot is read before the in-flight flag is taken, so a refresh that publishes in between
  leaves the new claim a stale basis and it wastes its window (`RetryingJwksLoader.java` :553-557); (L3-3) an exception in
  `settleRefresh` is swallowed by the `whenComplete` future, leaves the fresh delegate unclosed and logs no
  `JWKS_REFRESH_FAILED` (:596-606, :668); (L3-4, not shown by reading) `RefreshBasis.of` runs on the request thread
  outside the try that shields scheduling (:226-229); (L2) `refreshOutrunByADropIsDiscarded` (test :1133) may not fail if
  `isCurrent` were deleted, two near-duplicate unit tests (:1010, :1035) and a redundant IT leg 5. One small pass
  would close all of it. The operator decides. — source: `landings/PLAN-31.md`
> **Decided 2026-11-23 (operator, `handle-open`, proposal 1): MEASURE AFTER PLAN-33 LANDS.** Run `verify -Ppre-commit` once on a clean `main` checkout, then `git status --porcelain`. Clean tree: retire this entry to `settled.md`. Dirty tree: it becomes a fix in the pending backlog (then 6). The entry stays open until measured; I did not confirm the 457-file claim.
- **`verify -Ppre-commit` is not stable on `main` after #618 (2026-11-15).** The landing reports that the gate removes one
  blank line from 457 existing files on every run (and earlier one from `MainListenerConnectionCapTest.java`, from
  #607), cleared by hand after each run. Not re-measured by this analysis: treat as a lead. A formatter PR with the
  `skip-bot-review` label, like #608, would close it; no plan owns it. — source: `landings/PLAN-31.md` residue
> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in `landings/PLAN-31.md`, where the false claim is already recorded. Stays here as a pointer; not retired, not relocated.
- **`review_body_findings_total` of PLAN-31 disagrees with the provider (2026-11-15).** The landing claimed 5; `review-gate`
  on the merged head reads 4 (and 0 dispositions, which only the run's progress file can settle). Recorded as a false
  claim in the landing record; the thread count (0) matches. — source: `landings/PLAN-31.md`
> **Decided 2026-11-23 (operator, `handle-open`, proposal 1): ROUTED TO THE NEXT PLAN THAT TOUCHES `edge/`.** Re-read this run: no ADR covers the breaker identity (ADR-0064 does not mention keying or `guardFor`), the draft still exists, `SO_LINGER 0` is still at `EgressClients.java:221`; `PLAN-V02-46`'s row was not found in `api-sheriff-0-3-0`'s queue, so its status is unknown. The ADR is one deliverable for that plan, written from the draft after it reads V02-46's result. It is not in the pending-fix backlog (still 5). The entry stays open until the ADR exists.
- **The circuit breaker's identity changed in #448 without an ADR.** `GatewayEdgeRoute.guardFor` no
  longer registers it under a SmallRye name (the JVM-global registry rejected a reused upstream port
  once the abortive close freed TIME_WAIT); the guard, keyed `scheme://host:port:h2=…:retry=…`, is the
  identity now. The operator deferred the ADR; the draft is git-ignored at
  `.plan/temp/adr-propose-plan-30-breaker-identity-draft.adoc` in the main checkout. No plan owns it.
  Stale breaker-name comments and other advisory leftovers are listed in `landings/PLAN-30.md` § Residue.
  ⚠ Since #472 the sibling's staged `PLAN-V02-46` (after V02-25) evaluates replacing PLAN-30's
  `SO_LINGER 0` with a FIN-first close and falls back to the abortive close only if port exhaustion is not
  bounded. If it adopts FIN-first, the TIME_WAIT behaviour that forced this identity change returns:
  whoever writes the breaker-identity ADR reads V02-46's result first, and V02-46 must keep the
  guard-keyed identity working. — source: `landings/PLAN-30.md`

> ↪ Relocated to `settled.md` § "PLAN-15 abandonment retraction" — the claim was retracted and PLAN-15 has since shipped

> ↪ Relocated to `settled.md` § "PLAN-15 follow-ups filed as issues" — all seven are filed as #323-#329

> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in the `api-sheriff-0-3-0` epic watch "EDGE / HTTP/2 / IT FLAKE FAMILY" (already consolidated there). Stays here as a pointer; not retired, not relocated.
- **`GatewayEdgeFramingCorpusTest` flake — tracked in ONE place: `api-sheriff-0-3-0` epic.md, Watch
  "EDGE / HTTP/2 / IT FLAKE FAMILY — A RECURRENCE IS A SUSPECTED PRODUCTION DEFECT"**, which holds both
  sightings (#431's main push run and PLAN-28's CI-only 502), the owner (the next plan touching `edge/`; a
  recurrence gets a dedicated fix plan) and the reproduction recipe (`-XX:ActiveProcessorCount=1`, repeated
  runs). Report new sightings there, not here. — consolidated 2026-10-13; repointed 2026-11-07 when
  `api-sheriff-0-2-0` closed (its Watch "HTTP/1.1 DRAIN 502 FLAKE IN THE FRAMING CORPUS" is archived under
  `.plan/archived-orchestrators/api-sheriff-0-2-0/`)
> **Decided 2026-11-23 (operator, `handle-open`: "decide if you are 60% confident"; orchestrator confidence 60, applied as at-threshold on that instruction): NARROWED, TWO FIXES TO THE BACKLOG.** Re-read this run: the `token_validation` line numbers cited below no longer exist (`doc/configuration.adoc` is 804 lines, split by #575) and the three remaining mentions (:141, :448, :640) name a live key, so the first claim is NOT reproduced; it is not retired, because no commit is a positive account of its closing. Still standing: the `IssuerConfig.Jwks` Javadoc repeats the three admitted ranges in prose (`IssuerConfig.java` :77, :84, :91) with no guard test found, and the `source: file` restriction coverage in `ConfigModelContractTest` was not traced. Both go to the pending-fix backlog (now 8 with the retry-wording fix); target plan open under the hold decision. See `## Decisions` 2026-11-23.
- **Documentation left open by PLAN-28** (the sender's statement, not re-read): two stale
  `token_validation` mentions in `doc/configuration.adoc` (around line 2213 and the YAML example comment
  near line 380); the `IssuerConfig.Jwks` Javadoc repeats the three refresh ranges unguarded; the
  contract test does not cover the documented restriction to the `http` source. No plan owns these. —
  source: `landings/PLAN-28.md`
> ↪ Relocated to `settled.md` § "Four inert upstream keys, resolved by PLAN-29" — PLAN-29 shipped #586 (ab568dab) and removed the four keys; no reader or binding of any of them remains in api-sheriff/src/main (re-checked 2026-11-23)
> ↪ Relocated to `settled.md` § "Stale shared ledger worktree, resolved by a land resync" — re-measured 2026-11-23: the worktree is 0 behind and 0 ahead of origin/main with 0 pending paths; the plugin-side mechanism stays upstream

> ↪ Decided 2026-11-23 by `handle-open` (item 8, confidence 93 over threshold 90): retired on positive account. The portal / HTML
> error-pages ADR that PLAN-16 added in #343 (`69b322b5`) now lives as `doc/adr/0053-Portal_templates_render_on_a_standalone_Qute_engine_and_escape-bypass_constructs_are_refused_at_boot.adoc`
> and its `== Status` section reads `Accepted` (:15-17). The number 0050 now names a different ADR. Original text: ADR-0050 was
> committed `Proposed` and acceptance was an operator decision. — source: `landings/PLAN-16.md`; see `## Decisions` 2026-11-23 handle-open
> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 1, `Vary: Accept`.
- Two product gaps PLAN-16 shipped knowingly, both carried in PR #343: an error response that can be
  negotiated to HTML or JSON sends no `Vary: Accept` (the HTML variant is `no-store`, so the risk is a
  shared-cache mismatch on the JSON variant), and no unit test covers the 504 upstream-timeout error page
  (**the 504 half is CLOSED 2026-10-12**: PLAN-25's `HtmlErrorPageIT` pins the 503 and 504 pages at IT
  level; the `Vary: Accept` half stays open).
  Small; fold into a later plan touching that surface rather than staging one. — source:
  `landings/PLAN-16.md`
> ↪ Relocated to `settled.md` § "Project config housekeeping (marshal.json provisioning, build queue cap)" — the provisioning half was fixed 2026-11-09; ⚠ the `build.queue.max_slots` half stays an operator action (`manage-build-server config migrate`)
> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in project configuration of plan-marshall (`marshall-steward` architecture match rule) and plan-marshall lesson `2026-09-22-18-004`; not API Sheriff product work. Stays here as a pointer; not retired, not relocated.
- `ci-verify` files this repository's Maven build jobs as `ci_policy_failure` rather than
  `ci_build_failure`: the job names (`build / build (25|26)`, `build / sonar-build`, `build / conclusion`,
  `integration-tests / test`) contain none of the build-profile tokens it matches (`verify`,
  `quality-gate`, `module-tests`, `coverage`), so every red build or test job is routed to the policy
  triage producer. Remedy is project configuration (the architecture match rule / `marshall-steward`),
  not API Sheriff product work — outside this epic, recorded here so the next plan's triage is read with
  that in mind. **Recurred in PLAN-16** (2026-09-22): a plain unit-test failure in `build (25)` was
  presented as a CI policy failure, and the later green `ci_verify` did not overwrite the loop-back record
  (corrected by hand in that run). Also promoted as plan-marshall lesson `2026-09-22-18-004`. — source:
  inbox `plan-13-defect-fixes-002.md`, `plan-16-application-portal-004.md`
> ↪ Relocated to `settled.md` § "Verification-currency gaps on the PLAN-13 and PLAN-15 merges" — both plans shipped; the gap is a historical reading aid, not an open defect
> ↪ Relocated to `settled.md` § "Header-matcher defects, resolved by PLAN-18" — resolved 2026-09-24 by PLAN-18 (#346)

- **No per-request access log exists.** The gateway ships events and metrics and relies on the Quarkus
  HTTP access log, but no `quarkus.http.access-log.*` setting exists anywhere in the repository —
  application properties, IT config or compose sample — so per-request logging is off by default
  (orchestrator-verified 2026-09-24). PLAN-19's deliverable 5 therefore ships only the branch counter
  `sheriff_auth_branch_total{route,branch}`; the "chosen branch in the access log" half is NOT delivered.
  Recorded rather than planned by operator decision (2026-09-24): not an AS item, no downstream request,
  so it does not displace catalogued work. Enabling it would need a decision on default on/off, format,
  and redaction of sensitive headers and query strings. — source: inbox
  `plan-19-session-fallback-on-bearer-001.md`

> ↪ Relocated to `settled.md` § "cuioss-review-bot cannot be re-triggered, resolved by its removal" — resolved 2026-10-20: the bot was removed (#493, #494) and CodeRabbit is the only required reviewer
> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 2, relay cleanups.
- Two small cleanups on the WebSocket relay, left deliberately by PLAN-22: the pre-existing
  `source.pongHandler(pong -> resetIdle())` in `WebSocketRelayStage.wire()` is now redundant (since
  `relayFrame` receives pongs and resets the idle timer), and the `queueFullDialer` /
  `frameThreadRecordingDialer` test helpers are near-duplicates one decorating dialer would cover. No
  staged plan touches that surface — fold into whichever plan next enters the relay. — source:
  `landings/PLAN-22.md`

> ↪ Relocated to `settled.md` § "Duplicate ADR-0053, resolved by #367" — resolved 2026-10-02 by #367; the manage-adr scan blind-gate note moved with it
> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in plan-marshall (filed upstream as a lesson). The standing mitigation still applies to this epic: do not relocate the ledger while a plan is in flight. Stays here as a pointer; not retired, not relocated.
- **`source_id` is a stored PATH, resolved at finalize time, so a ledger relocation orphans every
  in-flight plan's outbox.** PLAN-23 shipped #369 with `emit-landing` reporting *not orchestrated, no
  landing emitted*: its `source_id` was persisted on 2026-09-25 as
  `.plan/local/orchestrator/…/PLAN-23-….md`, and the executor regeneration to 0.1.1826 (2026-10-01) made
  that the RETIRED address — `detection: unrecognised_id`, `orchestrated: false`. Reproduced both ways at
  2026-10-02. **Caused by this session**: the regeneration alone was sufficient, and the subsequent ledger
  move made the new address correct without rewriting the pointer the running plan already held. The
  landing was recovered from the operator's paste, so nothing was lost — but the automatic channel was
  broken mid-flight, which is the consequence the four earlier detector-disagreement recurrences had not
  yet produced. **Mitigation until the pointer is resolved dynamically: do not relocate the ledger while a
  plan is in flight.** Filed upstream as a plan-marshall lesson. — source: `landings/PLAN-23.md`
> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in plan-marshall lesson `2026-09-24-05-001`; delete the comment by hand on GitHub if wanted. Stays here as a pointer; not retired, not relocated.
- A stray PR-level comment on #346 (`IC_kwDOPatrT88AAAABWb_adQ`) carries internal triage prose that
  `post_responses` transmitted verbatim as a public reply. Harmless; delete by hand on GitHub if wanted.
  Mechanism filed as plan-marshall lesson `2026-09-24-05-001`. — source: inbox
  `route-header-matcher-defects-003.md`
> ↪ Relocated to `settled.md` § "Egress-allowance duplication, resolved by PLAN-17" — resolved 2026-09-23 by PLAN-17 (#345)

> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in plan-marshall lesson `2026-09-23-05-003`. Stays here as a pointer; not retired, not relocated.
- The merge-lock release reported the lock **"already free"** during PLAN-17's finalize although that plan
  had acquired it. The merge was unaffected, but the release path did not observe its own claim, so mutual
  exclusion is unestablished for that window and the report reads as success. Plugin-side; filed as
  plan-marshall lesson `2026-09-23-05-003`. — source: operator report, PLAN-17 landing

> ↪ Relocated to `settled.md` § "NO_PLAN sentinel fails the next gate, resolved in plan-marshall 0.1.1855" — resolved 2026-10-06 by plan-marshall 0.1.1855

> ✔ Accepted 2026-11-23 as outside this epic (`handle-open` batch B3, operator answer): tracked in the kidicap-gateway maintainers (downstream repository). Stays here as a pointer; not retired, not relocated.
- kidicap-gateway still links to the removed `doc/api-sheriff-aenderungen.adoc` (~30 references in
  `README.md`, `CLAUDE.md`, `doc/**`, incl. `#as-N` anchors). Not API Sheriff work, and not owned by any
  plan here; the gateway maintainers repoint them (e.g. to API Sheriff issues/ADRs as plans land). —
  source: removal commit 08620f0 in kidicap-gateway (branch feature/initial-structure, not pushed)

> ↪ Resolved by PLAN-26 (#592, `ad00807d`, 2026-11-10): the BFF-09 over-claim (revocation now bound and the record rewritten),
> the cookie-mode residual (already an accepted residual; deliverable 3 dropped) and the two missing ADRs (ADR-0081 for the problem+json
> rule, an ADR-0018 amendment for the reserved-path boot rule; ADR-0080 records the logout decision). Record: `landings/PLAN-26.md`

> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 5 for the duplicate-reserved-path rule; the other two stay open as decisions.
- Three info-level audit items from PLAN-23, unstaged: a widening does not revoke the superseded refresh
  token; the active scope set falls back to the requested set when the provider states none; no boot rule
  refuses duplicate reserved paths. — source: `landings/PLAN-23.md`
> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 8, the hygiene pass.
- Pre-existing and unstaged: boot refusal messages echo configured values raw (`default_view`, trusted
  proxies, base-URL alias, websocket origins, anchor prefixes), and roughly forty older comment
  inaccuracies sit in the BFF files. Hygiene, not correctness — fold into whichever plan next enters those
  files. — source: `landings/PLAN-23.md`

> ↪ Relocated to `settled.md` § "Breaking BFF change #377 from outside the epic" — PLAN-24 shipped and PLAN-20, -21 and -26 were re-grounded against it on 2026-10-26

## Watches

> ⏳ Awaiting the operator's Actions check (`handle-open` batch B2, proposal 1, 2026-11-23): the operator reports what is green and the orchestrator retires only that; anything unreported stays open.
- The push runs of **Maven Build and Integration Tests on PLAN-31's merge commit `5aa17338`** are NOT verified: the CI
  abstraction reads PR-attached runs only. Verified green and PR-attached: merge-group run `43710119529`, every PR check
  including SonarCloud, and the post-merge benchmark (`43711768997`). `main` has moved on since (#618 to #622), so a
  later green run on a descendant does not stand in for it. — trigger: confirm Actions → Maven Build → `5aa17338`
  before the next release; source: `landings/PLAN-31.md`
> ↪ Relocated to `settled.md` § "PLAN-31 surface_delta unmeasured, read by hand" — PLAN-31 shipped #605; the six-file diff was read by hand and all six were declared; the watch had no trigger

> ↪ Relocated to `settled.md` § "Cross-epic hold on api-sheriff-0-2-0, resolved by its closure" — every sibling plan it names has shipped and the sibling epic is closed and archived

> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 3 (L3-1) and fix 9 (L3-2 accept or plan); L3-3 and L3-4 unchanged.
- **Four minor defects in the merged logout revocation (2026-11-10).** (L3-1) one process-wide latch covers a discovery failure,
  a transport failure and a non-success answer, so a transient first failure consumes the only `ApiSheriff-147` WARN and a later
  persistent failure stays at DEBUG (`RpInitiatedLogout.java` :251; the spec offered one-per-reason, as `ApiSheriff-132` does);
  (L3-2) cookie mode is stateless, so a replayed still-valid cookie triggers one synchronous revocation POST per logout
  (about 15 s bound per call) and never trips the latch, and ADR-0080 does not discuss it; (L3-3) two concurrent logouts of one
  server-mode session send two revocation POSTs (harmless, RFC 7009 section 2.2, unrecorded); (L3-4, not shown by reading)
  `metadata.get()` is called twice per logout and the memoizing supplier may serialise concurrent logouts during a discovery
  outage. L3-1 plus L3-2 are the ones worth a plan. The operator decides. Closes when each is planned or accepted in writing.
  — source: `landings/PLAN-26.md` L3-1..L3-4
> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 4, the layer-2 pass.
- **Layer-2 residue of PLAN-26 (2026-11-10).** A test named `shouldRevokeNothingWhenTheSessionWasGoneAtDestruction` asserts the
  opposite and the threat model cites it by that name (:3079); `RpInitiatedLogoutTest` :145 pins the WARN only through its
  self-resolved identifier, so a renumber of 147 stays green; the `BffLogMessages.WARN` Javadoc keeps a hand-written
  inventory its class Javadoc disclaims; `RpInitiatedLogout.java` :58 says no other session-ending path revokes but
  `TokenRefreshCoordinator` does; the BFF-09 summary row's not-revoked list omits a failed revocation and a session without a
  refresh token, `variants/03-bff-cookie.adoc` :298 drops two conditions the prose carries; ADR-0080 and the ADR-0018
  amendment do not link ADR-0071; no cookie-mode runtime or IT case observes the revocation request at the IdP. One small
  documentation-and-test pass would close all of it. — source: `landings/PLAN-26.md` L2-1..L2-5
> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fix 5 for the canonical-form boot check; the `step_up_url` echo stays with PLAN-20's re-ground.
- **Two gaps the PLAN-26 reviews found outside its scope (2026-11-10).** The boot does not check the callback, logout,
  logout-return and back-channel-logout reserved paths for canonical form (ADR-0018 now says so), and `step_up_url` echoes the
  request's own query back to the client (ADR-0081 says so). The second bears on PLAN-20 and is carried into its
  re-grounding. — source: `landings/PLAN-26.md`; ADR-0018, ADR-0081
> ↪ Decided 2026-11-23 by `handle-open` (confidence 85 over threshold 60): retired on positive account. The watch closed when the
> staged specs that allocate declared the surfaces; PLAN-20's spec now lists `doc/LogMessages.adoc` and an ADR for the step-up model
> in its deliverable-8 documentation surface (`plans/PLAN-20-*.md` :184-186) and instructs "derive the next free id at write
> time, never from a number in this spec" for WARN ids and "re-list `doc/adr` at write time" for the ordinal (:267-270).
> Original text: PLAN-26 and the in-flight #589 both took `ApiSheriff-146`; the plan renumbered in its second rebase; the watch
> closed when the staged specs that allocate (PLAN-20) declared `doc/LogMessages.adoc` / `doc/adr/`. — source: `landings/PLAN-26.md`;
> see `## Decisions` 2026-11-23 handle-open
> ⏳ Awaiting the operator's Actions check (`handle-open` batch B2, proposal 1, 2026-11-23): the operator reports what is green and the orchestrator retires only that; anything unreported stays open.
- **Two PLAN-26 post-merge reads are unverified (2026-11-10).** The main-branch Maven Build run for `ad00807d` (where a
  snapshot-deploy failure appears) is not readable through the CI abstraction; the lane's `total_wall_seconds` of about 28
  hours does not reconcile with this session's clock. Closes when the run is read or the figure is explained. —
  source: `landings/PLAN-26.md`

> ➜ Decided 2026-11-23 (`handle-open` batch C, operator proposal 1): in the scope of the DUE FIX PLAN (backlog over 10); entry stays open until the fix lands; staging is the operator's act. See `## Decisions` 2026-11-23 — fixes 6 (ADR) and 7 (eviction WARN or metric); gaps 2 and 3 stay open as decisions.
- **What #581 left of PLAN-21's scope (2026-11-09).** Four gaps, none a defect of the shipped fix: (1) no ADR records
  the pending-store fairness mechanism (`doc/adr` has none; PLAN-21 deliverable 1 asked for one, and the sibling
  epic's plan did not write it); (2) no login-initiation rate limiter (`rate_limit` stays reserved, ADR-0077);
  (3) `LoginFlow.initiate` calls `authorize` (the PAR push to the IdP) before the store, so a refused login has
  already cost the IdP a round trip, which #581 accepts as a residual; (4) more than 256 concurrent logins behind
  one shared source key (a NAT) evict that source's own oldest record, with no WARN or metric. The operator decides
  whether any of them earns a plan; (1) is the cheapest and the one a reviewer would ask for. Closes when each is
  planned or accepted in writing. — source: PLAN-21's spec banner; #581 body

> **Decided 2026-11-23 by `handle-open` (confidence 80 over threshold 60): factual question answered, one wording fix to the backlog.** Read this run from the SmallRye Fault Tolerance 6.11.1 bytecode (`GuardImpl$BuilderImpl$RetryBuilderImpl`): the retry builder's default jitter is 200 ms (`jitter = 200`, `jitterUnit = MILLIS`), so a retry is NOT a fixed 100 ms apart; the guard (`UpstreamGuards.java` :172-174) sets `.delay(100, MILLIS)` and no `.jitter(...)`, so the default applies. The "about 90 s" bound moves by at most a fraction of a second. Fix (backlog +1): soften "100 ms apart" at ADR-0064 :86, `doc/configuration/routing-and-forwarding.adoc` :566 and `UpstreamGuards.java` :141 and :85, or set `.jitter(0, MILLIS)` explicitly if a fixed gap is intended (an operator choice). Not read: the exact version Quarkus resolves at runtime (6.11.1, 6.11.2 and 6.11.4 are all in the local repository). The entry stays open until the fix lands.
- **Retry gap "100 ms apart" is unverified against SmallRye's default jitter (2026-11-09).** ADR-0064:86,
  `doc/configuration/routing-and-forwarding.adoc` and `UpstreamGuards.java:85` state retries are 100 ms apart;
  the guard sets `.delay(100, MILLIS)` and no `.jitter(...)`. Whether the library jitters by default is not
  readable in this repository. Settle it with one measuring test or soften the wording; it moves the "about 90 s"
  bound by a fraction of a second at most. Closes on whichever. The operator decides whether it earns a plan. —
  source: `landings/PLAN-29.md` L3-1
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 3, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **A same-day re-grounding seems to tighten the declared surface.** PLAN-24 realized 1.4x its
  declaration; the earlier measurements ran roughly 3x to 5x. PLAN-27, also re-grounded on its emit day,
  realized 28 files against 12 entries (several of them directories); 8 of its 10 undeclared files follow
  from one wire-or-remove decision the spec left to the plan. Two data points: re-grounding helps, an
  open decision in the spec does not get declared. PLAN-29 therefore declares both outcomes. — trigger:
  PLAN-28's and PLAN-29's landings; source: `landings/PLAN-24.md`, `landings/PLAN-27.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 4, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **Two plans that each add integration suites collide on the native integration job's wall-clock, even
  with disjoint files.** PLAN-25 and PLAN-V02-01 were each green alone; combined they hit the job's
  35-minute limit (raised to 60 in #408, cycle shortened by #424). The disjointness gate cannot see this.
  Before pairing any two of PLAN-24, PLAN-27, PLAN-21 and PLAN-20, check the job's current duration
  against its limit. — trigger: every `next` selection that fills a second slot; source:
  `landings/PLAN-25.md`, lesson `2026-10-12-07-005`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 5, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **Tests the downstream cites as its evidence — do not remove or rename unnoticed.** PLAN-25's nine
  suites (`BffReservedPathPrecedenceIT`, `BffRefreshSingleFlightIT`, `BffRefreshScopeWireIT`,
  `BffRefreshTransientFailureIT`, `BffTlsRelaxationIT`, `HtmlErrorPageIT`, `BffSessionAbsoluteTtlIT`,
  `BffSessionInstanceIsolationIT`, `BffPendingLoginBoundIT`), PLAN-27's `BffUnboundTokenResponseIT`
  (M-62) and `BffRefreshScopeWireIT.refreshGrantsCarryTheSessionScopeOnTheWire` (M-64), the six the PLAN-25 spec lists as already
  pinned, and for M-61 `BffSessionScopeParityIT.navigationNeedingARefusedScopeIsRefusedAtThePush` and
  `.xhrNeedingARefusedScopeIsRefusedAndTheSessionSurvives`. — trigger: any plan refactoring the IT
  directory; source: inbox `kidicap-gateway-downstream-012.md`, `-013.md`
> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): the "tell the downstream" half is settled by the downstream's own 0.2.4 report (PLAN-19 `session_fallback`, PLAN-23 session-route scopes and `step_up_url`, #377 PAR/DPoP in client-secret mode, working against Keycloak 26.7.4). Original text, residual kept below: the downstream noticed, and did not request, two things.

- **Residual of the downstream 0.2.4 report (kept open).** Two things the downstream noticed and did not request: the default `oidc.client_authentication.jwks_path` (`/auth/jwks`) lies outside a build-time context path, like `login.default_return_url`'s default; and with a pushed request Keycloak adds a `302` to its own `login-actions` address before the form. — trigger: any plan touching reserved-path defaults; source: inbox `kidicap-gateway-downstream-013.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 1, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- Declared surface versus footprint, fifth measurement: PLAN-25 declared 8 entries and touched 40 files
  (14 undeclared, 2 untouched), but 26 of the 40 sat inside its two declared directories. Directory
  declarations absorb growth that file lists do not. — source: `landings/PLAN-25.md`

> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): the 0.2.2 release trigger fired (tags 0.2.2, 0.2.3, 0.2.4 exist, observed 2026-10-06) and the operator confirmed its owed actions (AS-14 breaking-change note, telling the downstream about `oidc_verify_hostname` and the six routing AS items, offering the release to the downstream's Helm test, the main-branch Maven Builds) are done. Original text: the five watches below named that trigger.

> ↪ Relocated to `settled.md` § "PLAN-15 PR chain, retired" — PR #320 merged; the chain is closed

> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 2, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- Declared surfaces under-state footprints in this epic, now measured three times: PLAN-15 declared 23
  entries and touched 82 (37 undeclared, 2 declared-unused); PLAN-13 declared 19 and touched 68 (39
  undeclared, 0 unused); PLAN-14 declared 24 and touched 82 (33 undeclared, 2 unused — undeclared
  clustered in `bff/cookie/`, `edge/RouteRuntimeAssembler`, `routing/RouteRuntime`, `ConfigValidator` and
  config tests); PLAN-16 declared 19 and touched 70 (37 undeclared, 1 unused). Roughly threefold every
  time, four times out of four — a property of this epic's spec granularity, not a per-plan lapse. Treat a `disjoint` verdict from the gate as weak evidence
  here and prefer sequencing within a subsystem. — trigger: every `next` selection; source:
  `landings/PLAN-15.md`, `landings/PLAN-13.md`, `landings/PLAN-14.md`
> ⏳ Awaiting the operator's Actions check (`handle-open` batch B2, proposal 1, 2026-11-23): the operator reports what is green and the orchestrator retires only that; anything unreported stays open.
- The main-branch **Maven Build** for PLAN-14's merge commit 3e3addc (incl. `deploy-snapshot`) is NOT
  verified: the CI abstraction reads PR-attached runs only, and the push run is not attached to #337. The
  PR-attached post-merge benchmark IS verified green. — trigger: confirm that run (Actions → Maven Build →
  3e3addc) before the next release; source: `landings/PLAN-14.md`
> ⏳ Awaiting the operator's Actions check (`handle-open` batch B2, proposal 1, 2026-11-23): the operator reports what is green and the orchestrator retires only that; anything unreported stays open.
- The main-branch runs for **three** merge commits are NOT verified, for one structural reason: the CI
  abstraction reads PR-attached runs only (`checks status --head main` returns "no pull requests found"),
  and a push to `main` has no PR. The PR-attached post-merge benchmarks ARE verified green for all three
  (#337 run 35661424860, #343 run 35758705252, #345 run 35802988238).
  - 3e3addc (PLAN-14) — Maven Build incl. `deploy-snapshot`: unobserved.
  - 69b322b (PLAN-16) — same: unobserved.
  - c1b09c7 (PLAN-17) — **Integration Tests run 35802987168 SETTLED 2026-09-23**: attempt 1 failed in
    `WebSocketRelayStageTest.relaysBidirectionalTextFrames` (30 s echo timeout), attempts 2 and 3 were
    green (3612/0 unit, 222/0 IT). Reported by the filing plan, which watched the re-run; the
    orchestrator cannot read a push run itself. So it is INTERMITTENT, not reproducible — and **not
    dismissed as a runner flake**: the attempt-1 diagnostics (all Vert.x threads idle in `EPoll.wait`,
    both relay legs ESTABLISHED, `Recv-Q`/`Send-Q` zero everywhere) say the frame reached user space and
    was discarded. Escalated to **PLAN-22**. Maven Build for this commit remains unobserved.
  - f6067588 (PLAN-18) — **Integration Tests run 35917940826 FAILED on INFRASTRUCTURE ONLY**: the runner
    could not check out the repository ("server certificate verification failed", three attempts), so **no
    test executed**. Not a code signal — the same tree passed the ITs in PR CI and in the merge-queue
    re-test. ⚠ A re-run is OWED and is an operator action: the orchestrator's CI access is read-side only,
    so it can neither re-run the workflow nor read the result of one. Maven Build for this commit also
    unobserved.
  — trigger: confirm all four before the next release, and re-run 35917940826 now; source:
  `landings/PLAN-14.md`, `landings/PLAN-16.md`, `landings/PLAN-17.md`, `landings/PLAN-18.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 6, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **A validator that guards a runtime behaviour must reuse the runtime's own normalisation, never a
  re-implemented equivalent** — PLAN-18's review-fix commit added a boot check comparing header names with
  `equalsIgnoreCase` while the runtime used `toLowerCase(Locale.ROOT)`, so the two disagreed on which
  matchers collide (e.g. `X-İd`). It was caught only by the finalize security audit re-firing, not by the
  review loop or by the tests written for that very fix. **Applies directly to PLAN-19**, which adds boot
  rules over the same auth cascade: any new rule needs a parity test driving one input set through both
  the validator and the runtime. — source: inbox `route-header-matcher-defects-001.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 7, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **A behaviour widening makes sibling docs stale, and the outline will not list them for you** —
  PLAN-19 widened `auth.token_relay` to the SESSION branch of a `session_fallback` route but its doc
  deliverable never listed `doc/user/endpoint-routes.adoc`, which states in FOUR places that `token_relay`
  "acts only on `require: session`" and "has no effect on bearer or none routes". Every one of those
  became false. Before declaring the doc set, content-search the key being widened across `doc/**` and
  list what the search returns, rather than listing the documents the plan expects to touch. — source:
  inbox `plan-19-session-fallback-on-bearer-003.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 8, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- Review bots keep catching doc and Javadoc claims the code does not implement — third plan running
  (PLAN-16, PLAN-18, PLAN-19). Treat prose describing behaviour as an assertion to verify against the
  code before pushing, not as narration. — source: inbox `plan-19-session-fallback-on-bearer-005.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 9, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- A wording fix from a review comment must be swept across every sibling document in the same act: fixing
  the one line the bot quoted leaves the same claim standing in its neighbours. — source: inbox
  `route-header-matcher-defects-002.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 10, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **Doc-precision signals from PLAN-17, for every remaining plan** (both slipped self-review and were
  caught by the review bot, which reinforces the review-bot watch above):
  - When "absent" and "empty" take the same derived code path, the docs must say **both** — `IssuerConfig`
    normalises absent to `List.of()` and the producer branches on `isEmpty()`, so a sentence naming only
    "absent" contradicted the field contract two lines above it. — source: inbox
    `jwks-egress-allowlist-006.md`
  - A threat-model residual claim ("every other host is still subject to X") must be qualified by every
    control that can exempt a host, not only the one just described — an explicit allowlist is
    authoritative and exempts each host it names. — source: inbox `jwks-egress-allowlist-007.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 11, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **Semantics-change sweep signals from PLAN-17** — when a plan changes the MEANING of a config key, the
  sweep must cover four places a name-only search misses, all four measured in that run:
  - every verbatim-copied compose overlay (8 of them under `integration-tests/src/main/docker/`), several
    of which claim to be "a faithful copy of the primary gateway.yaml" — a claim that silently goes false;
  - test comments and Javadoc outside the named test class (`ConfigLoaderTest:478/486` stated the old
    semantics while its assertion stayed correct, so no test would ever go red);
  - line-number citations in `doc/development/**` that point into the class being edited;
  - and conversely: a sweep HIT must be verified before the outline declares the file — PLAN-17's outline
    listed `doc/technical_aspects.adoc` as expected-to-mutate on a false positive, while the file that did
    need changing needed it for a different reason.
  — source: inbox `jwks-egress-allowlist-002.md`, `-003.md`, `-004.md`, `-005.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 12, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- A test that writes a file for a bind-mounted container must make it **world-readable explicitly**: under
  the build daemon's umask 077 a generated `gateway.yaml` became mode 600 / uid 1000 and the distroless
  gateway (uid 1001) refused to boot (`ApiSheriff-200`). Committed descriptors are 644, so only generated
  ones break — and the failure depends on the host umask, so it can pass on CI and fail locally. — source:
  inbox `jwks-egress-allowlist-001.md`
> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 13, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- **Test-practice signals from PLAN-16, for every remaining plan in this epic** (recorded here rather than
  in the global corpus: they are API Sheriff practices, not plan-marshall component defects):
  - A metric, counter or log written in a Vert.x end handler must be AWAITED, never read right after the
    response — the `GatewayEdgePipelineTest.metersDisallowedVerbUnderItsRoute` race from #320 surfaced on
    CI only and cost PLAN-16 a finalize loop-back. — source: inbox `plan-16-application-portal-005.md`
  - A new block added to `integration-tests/.../sheriff-config/gateway.yaml` must be mirrored into the
    passthrough-empty benchmark overlay, or the pre-commit gate fails late. — source: inbox
    `plan-16-application-portal-006.md`
  - An IT needing a specific origin status or header must route to the `HTTPBIN_ROOT` upstream: routes
    under `/proxy/*` land on go-httpbin's `/anything` echo base and answer 200 whatever was asked
    (`HtmlErrorPageIT.relayedOriginErrorPassesThrough` expected 503, got 200). — source: inbox
    `plan-16-application-portal-007.md`
  - Review bots reliably catch two classes here: documentation that over-claims what the code does, and
    substring (rather than directive-aware) assertions on `Cache-Control`. Budget for both in the same PR.
    — source: inbox `plan-16-application-portal-010.md`
- The cookie-size figure in `doc/user/bff-cookie.adoc` / `doc/development/bff-cookie.adoc` was measured on
  the nine-field sealed payload; PLAN-14 made it ten fields. Re-measure — natural fit for PLAN-20, which
  touches `SessionRecord` again. — trigger: PLAN-20 outline; source: inbox
  `plan-14-session-and-scopes-006.md`
> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): the 0.2.2 release note for AS-14 (`auth.required_scopes` removed, bearer routes enforce `oidc.scopes`, old sealed cookies rejected) is confirmed done. Original text: AS-5, AS-6, AS-14 shipped unreleased; the 0.2.2 note must say AS-14 is breaking.
- Sonar new-code findings recur on test-heavy api-sheriff changes (cognitive complexity in parsing code,
  test-hygiene rules in the accompanying tests). Budget for fixing them in-code inside the same PR rather
  than discovering them after the Sonar build. An `architecture enrich insight --module api-sheriff` call
  is owed for this hint — an operator action, not an orchestrator write. — trigger: each remaining plan's
  finalize; source: inbox `plan-13-defect-fixes-001.md`
> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): released with 0.2.2 (tags exist); the release-note trigger is confirmed done. Original text: six AS items become consumable at the 0.2.2 release note.
> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): confirmed told at release; the downstream's report on 0.2.4 says two workarounds were removed (inbox `kidicap-gateway-downstream-013.md`). Original text: `oidc_verify_hostname` was already on HEAD (#306, `7ba9734`); tell the downstream at 0.2.2 so it can drop its hostname-verification workaround.
> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): the offer of the release to the downstream's Helm test is confirmed done. Original text: the downstream runs a Helm in-cluster test against Keycloak before adopting a parent bump; release tags are `<api-sheriff-version>-<n>`.
> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): historical confirmation that the `egress_tls` knobs behave as documented; the owed release actions are confirmed done. Original text: `${VAR}` placeholders coerce to booleans, the JDK-triple trust route is the only one that works on distroless native, `ApiSheriff-17/118/120` are usable operator signals; re-check if any of those surfaces changes.

> ↪ Relocated to `settled.md` § "cui-http #236 and the 3.1 release, resolved" — 3.1 is released and resolved through the parent

> ↪ Retired 2026-11-23 on the operator's confirmation (`handle-open` batch B1, proposal 1; operator-confirmed, NOT an orchestrator decision, not re-read by the orchestrator): PLAN-13 and PLAN-15 have shipped without a reported forwarded-header or cookie regression, so the fallout watch has run its course. Original text: cui-http 3.1 adopted #237, #231, #227, #229, #222 and #240 silently with the parent bump; watch PLAN-15's and PLAN-13's verification runs; re-check on the next parent bump.

> ✔ Accepted 2026-11-23 as standing practice (`handle-open` batch A, note 15, operator answer): stays here with its trigger; not retired, not relocated. See `## Decisions` 2026-11-23.
- Source-document claims ("measured", code line references) were measured against API Sheriff
  0.2.1 — re-verify each against HEAD at decompose / outline; HEAD may already have moved
  (e.g. AS-10 references API-Sheriff#194). — trigger: every spec's verify-first clause.

- **TokenSheriff separation audit (2026-11-12, main at `26e34cd4`, TS 0.9.9).** Operator goal: API Sheriff
  duplicates nothing TokenSheriff provides; generic code moves to TokenSheriff. Four read-only audits
  (validation, OIDC client flow, session/runtime/config, generic utilities) each read both sides. The
  `BoundTokenEndpointClient` class of duplicate is gone; what remains is mostly a missing TS seam, so most
  work is **TS first, then delete here**. Not a plan yet; findings (AS = API Sheriff):
  - *Do now, 0.9.9 only (AS side):* `RetryingJwksLoader.getLoaderStatus` (:644-647) returns `OK` whenever
    its own state is `LOADED`, masking the delegate's `UNDEFINED`; the gateway never sets `maxStaleness`
    (#844), so the staleness bound is unreachable. The class Javadoc :56-65 still claims the library status
    is untruthful (fixed in TS #830). Read on one side only and from code, not run.
  - *Security gap, TS seam private:* `TokenRefreshCoordinator.rotate` (:817-823) stores the refreshed ID
    token unverified and keeps the old `sub`; TS `TokenLifecycleManager` checks OIDC 12.2 iss/sub (private
    static, :1272-1294). Also AS never calls `StepUpResultVerifier`, so `acr`/`max_age` is not enforced
    after step-up. The latter belongs in PLAN-20 (which wires `StepUpCoordinator`, today with no caller;
    do NOT delete it as dead code).
  - *Move to TS (then delete here):* native-image reflection for the client DSL-JSON converters
    (`TokenClientDslJsonReflection`, ~110); authorization-request parameters/`response_mode`/PAR/per-call
    scope (`QueryResponseModeAuthorizationRequestBuilder`, `PushedAuthorizationRequests`,
    `ScopedEngineFlows`, ~560); client signing-key PEM load and public-JWK export (`ClientSigningKey`,
    ~200-400); `SignatureOnlyTokenVerifier` (uses TS-internal classes, ~200); back-channel
    `LogoutTokenValidator` (~330, needs a TS scope decision: TS docs call receivers the BFF's concern,
    CLIENT-19); `IdTokenClaimProjection` (native JSON claims, ~180); unknown-kid bounded refresh in
    `RetryingJwksLoader` (~330, medium-high risk, flood bound); OIDC discovery cache (~190); readiness over
    JWKS state (`IssuerKeySetStatus`, ~130, needs `TokenValidator` to expose issuer configs).
  - *Small or optional:* RFC 6750 challenge build and Bearer extraction (TS private, JAX-RS-bound);
    `renderForMessage`/`sanitizeForLog` vs TS `ErrorTextSanitizer` (`SignatureOnlyTokenVerifier`'s strip
    misses U+2028/2029 and bidi controls); IdP egress-host validators (TS private); bounded body handler.
  - *Not TS:* `TokenRefreshCoordinator` single-flight stays (ADR-0046; TS manager is store-keyed, no
    cookie mode); two intra-AS duplicates (CIDR parse in `TcpPeerGate` and `EdgeRules`; the Cookie-header
    parse loop copied 5 times; the trust-all guard in `EgressTrustProfileResolver`/`JwksTrustProfileResolver`).
  - *Unused dependency:* `token-sheriff-client-quarkus` (`api-sheriff/pom.xml` :123-125); no import of
    `de.cuioss.sheriff.token.client.quarkus` in main (re-checked). Dropping needs operator approval.
  — trigger: operator decides scope (see anchor); verify each row against HEAD before a spec cites it.
