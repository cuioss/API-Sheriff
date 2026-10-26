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
- **PLAN-29 (staged 2026-10-13, WS-02) — the four inert upstream keys.** HELD by the cross-epic hold (Watches) until the sibling's refactor chain completes; then re-ground and emit. Must read #466's changes to `GatewayEdgeRoute`/`DispatchStage` (request-body idle bound, 408). Its line references into
  `GatewayEdgeRoute` are stale since #431 and #448: run `cleanup` before emitting it. Its D4 (DP-6) must
  be read against #448's abortive close and guard-keyed breaker. Carries two wire-or-remove decisions for
  the operator at outline.
  **Re-grounded 2026-10-26 at `54f82f03`:** the four keys are still inert (#506 added fixed constants, none
  reads them); the guard and client code now live in `edge/upstream/UpstreamGuards.java` and
  `EgressClients.java` (#487); D4 stays a record correction; the cross-epic hold is lifted. See the spec's
  re-grounding table for every moved reference. `PLAN-V02-48` must not run beside it (both write ADRs 0008 and 0064).
- **PLAN-31 (staged 2026-10-14 by the other orchestrator session, WS-01) — the exact JWKS publish rule**
  via token-sheriff 0.9.7's `getKeyIds()`. **Re-grounded 2026-10-26 at `54f82f03`:** premise intact, `main` is
  on 0.9.8 (#491; the jar was not readable locally, so the outline reads it first), the threat-model residual
  moved and was already reworded by #492, and the operator hold's three named overlaps (V02-12, V02-23,
  V02-44) have all shipped. Sequence before sibling `PLAN-V02-48`.
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

- 2026-10-15 — **PLAN-32 shipped** as PR #466 / 73233f1e, closing #455. The inbound HTTP/2 connection window
  is now sized to max concurrent streams × stream window, and streams are capped at 100 per connection.
  A 10 s request-body idle bound on HTTP dispatch resets the upstream request and answers `408`. ADR-0060
  amended. 6/6 deliverables; 22h22m wall, 10.3M tokens. Landing record: `landings/PLAN-32.md`;
  `landing-check` reports it complete. Corroborated against `ci pr view 466`, `ci checks status`
  (success), the merged diff (17 files), and issue #455 (closed). The spec's suspect `SO_LINGER 0` was
  refuted and stays in place. The real cause was flow-control windows interacting with the upstream pool.
  - **Drain (29 messages, 2026-10-15):** `-029` reconciled. `-020`/`-021`, `-026` and `-027` promoted to
    the API-Sheriff corpus as `2026-10-15-12-001`, `-002` and `-003`. The other 24 (`-001`..`-019`, `-022`..`-025`,
    `-028`) are plan-marshall process and tooling lessons. They were grouped into 13 messages and routed to
    plan-marshall's lessons-routing epic: sibling repository `plan-marshall`, inbox
    `.plan/local/worktrees/_orchestrator/.plan/orchestrator/lessons-routing/inbox/` (relative to that
    repository's root, written through its own `orchestrator inbox write --slug lessons-routing`), sender
    `api-sheriff-kidicap-gateway-requirements`, messages `-001`..`-013`. `-023` (the verification-feedback
    producer gap) went as a recurrence of plan-marshall's `lessons-routing-019` / PLAN-LB-24.
    **Standing rule (operator, 2026-10-15): every plan-marshall-related finding from this epic is
    routed to that inbox**, not promoted into this repository's corpus.
  - **Holds (operator, 2026-10-15):** PLAN-20, -21, -26 and -29 stay held until the sibling's whole refactor
    chain lands (Watches). PLAN-31 is held over its multi-file overlap with V02-12, V02-23 and V02-44.
    Nothing is emitted.
- 2026-10-14 — **PLAN-30 shipped** as PR #448 / b7b98699 (gateway-closed HTTP/1.1 upstream connections
  are closed abortively, `SO_LINGER 0` on the default upstream client, ADR-0066 Amendment A1; the
  gateway's own upstream reset no longer logs at ERROR and does not count against the breaker; the
  upload benchmarks' backend now reads the body; 7/7 deliverables; 16h38m wall, 6.5M tokens). Landing
  record: `landings/PLAN-30.md`; `landing-check` reports it complete. Corroborated against
  `ci pr view 448`, the merged diff (13 files), the client option at `GatewayEdgeRoute.java:2382` and
  ADR-0066 A1. Post-merge benchmark (run 38565585855) green, but it no longer covers the early-answer
  path; the fix's load evidence is one local run (51,198 `uploadSmall` iterations, error rate 0).
  - **Merged over this epic's own hold.** #453 recorded "must not merge until fixed" for an intermittent
    `Http2EarlyResponseResetIT` hang; the plan merged an hour later on a green merge-queue run and filed
    #455. The hold lived only in `epic.md`. Lesson `2026-10-14-13-003`.
  - **Drain (6 messages, 2026-10-14):** `-002` promoted as `2026-10-14-13-001` (phase-3-outline: operator
    answers not written back into the outline); `-003` promoted as `2026-10-14-13-002` (api-sheriff: a
    catch-all exception drop justified for one protocol path); `-001` folded into `2026-10-12-16-001`,
    `-004` and `-005` into `2026-10-13-06-004` (recurrences); `-006` reconciled.
  - **Not emitted:** the operator asked for analyze, then rebase and cleanup. PLAN-29 needs `cleanup`
    first.
- 2026-10-13 — **PLAN-28 shipped** as PR #437 / 82f2d7d8 (`runRetry` guarded so the JWKS retry sequence
  survives an unanticipated throwable; WARN 137 `JWKS_REFRESH_FAILED`; the refresh window and grace
  deadline moved to a monotonic clock, ADR-0063 Amendment A1; documentation rows plus a contract test
  guarding them; `BffReservedPathPrecedenceIT`'s flakiness traced to a docker-assigned management port
  and fixed; 8/8 plan deliverables; 7h03m wall, 6.0M tokens). Landing record: `landings/PLAN-28.md`;
  `landing-check` reports it complete. Corroborated against `ci pr view 437`, the merged diff (14 files)
  and `ci checks status` (35 checks, success, **post-merge benchmark green**, run 38407570839).
  - **Two deliverables are diagnoses without a fix.** `GatewayEdgeFramingCorpusTest` did not reproduce
    and stays unexplained. The `upload_small` failure turned out to be two failures: the original
    (#433's red run) was fixed by #431 from outside the plan; a second, introduced by #431, was found on
    the merged branch and filed under the STOP rule.
  - **Drain 6** (2026-10-13, at 48e8d5a6): 8 messages scanned, 8 archived, 0 invalid. Inbox EMPTY
    (live 0, no closed sender, invalid 0).
    - `-001` (finding, early upstream answers exhaust local ports and open the circuit breaker):
      **staged as PLAN-30** (`early-answer-upstream-port-exhaustion`, WS-02). The orchestrator
      corroborated the code and ADR-0066's stated consequence; the measurements are the sender's.
    - `-002` promoted as lesson `2026-10-13-14-001` (`api-sheriff`); `-003` as `2026-10-13-14-002`
      (`integration-tests`); `-005` as `2026-10-13-14-004` (`documentation`).
    - `-004` promoted as `2026-10-13-14-003` and `-006` as `2026-10-13-14-005` (both plan-marshall).
    - `-007` (guessed verbs and flags again): **folded** into lesson `2026-10-13-06-004` as a
      recurrence one plan later; adds no file surface.
    - `-008` (landing): reconciled.
  - **Resolved and removed from Open Defects:** the missing `runRetry` guard (#437) and #433's failed
    benchmark run (its cause fixed by #431; the two runs since are green).
  - **PLAN-28's row carried the wrong plan id** (`jwks-refresh-follow-ups`, stamped by another session);
    corrected to `plan-28-jwks-refresh-follow-ups`, the value in the landing's own facts.
  - **The ledger checkout was 7 commits behind `origin/main`** when this drain started, and another
    session had landed this epic's `running` row and anchor through #436 and #440. This session's own
    unlanded copies of those three files were dropped as superseded and the checkout fast-forwarded
    before any write.
  - **Folds:** PLAN-26's next-free ADR ordinal hint corrected to 0067 (0066 taken by #431); adds no
    file surface.
  - **Not emitted:** the operator asked for analyze and land only. Next candidate is PLAN-30.

- 2026-10-13 — **PLAN-27 shipped** as PR #433 / 128fd07e (M-62 pinned by the new `BffUnboundTokenResponseIT`;
  M-64 completed by one assertion in `BffRefreshScopeWireIT`; ADR-0064 removes the per-route
  `upstream.circuit_breaker` block, ADR-0065 records the test-port topology; five audit findings
  dispositioned; four hygiene items; 9/9 plan deliverables; 4h37m wall, 5.8M tokens). Landing record:
  `landings/PLAN-27.md`; `landing-check` reports it complete. Corroborated against `ci pr view 433`, the
  commit being the tip of `origin/main`, and the merged diff (28 files).
  - ⛔ **The post-merge benchmark run failed** (run 38307102339, `upload_small`, 15.66% errors, HTTP/2
    `GOAWAY` with "Maximum number of RST frames reached"). Cause not established; #433 changes no
    dispatch code and the four runs before it were green. Recorded as an Open Defect and **folded into
    PLAN-28 as deliverable 8**; expected surface updated in the same edit (+2 entries:
    `upload_small.js`; `httpbin.yaml` and `benchmarks/pom.xml` as a hypothesis).
  - **Drain 5** (2026-10-13, at 128fd07e): 9 messages scanned, 9 archived, 0 invalid. Inbox EMPTY
    (live 0, no closed sender, invalid 0).
    - `-001` (finding, four upstream keys parse and act on nothing): corroborated, **staged as PLAN-29**
      (`upstream-inert-keys`, WS-02).
    - `-002` promoted as lesson `2026-10-13-06-001` (a guard narrowed, its prose claim left); `-003`
      **folded** into that lesson as its cost (it is the same defect's procedural consequence); adds no
      file surface.
    - `-004` promoted as `2026-10-13-06-002`; `-005` as `2026-10-13-06-003` (both `documentation`).
    - `-006` promoted as `2026-10-13-06-004` (plan-marshall, guessed verb); `-007` **folded** into it as
      the second occurrence (guessed flag); adds no file surface.
    - `-008` promoted as `2026-10-13-06-005` (plan-marshall, finalize re-runs after a doc-only commit).
    - `-009` (landing): reconciled.
  - **Resolved by #433 and removed from Open Defects:** the inert `circuit_breaker.reset_ms` key
    (removed, ADR-0064) and PLAN-25's unrun `adr-propose` plus five untriaged audit findings.
  - **Folds:** PLAN-26's next-free ADR ordinal hint corrected to 0066; PLAN-28 re-grounded at 128fd07e
    (both verdicts re-stamped; #433 touches none of its main-source or edge-test files).
  - **PLAN-28 emitted** the same day, after this ledger landed. In-flight set empty at the gate.

- 2026-10-12 — **PLAN-24 shipped** as PR #430 / c013781d (bounded single-flight JWKS refresh on an
  unknown key id, three new `jwks` keys, a rate-limited WARN, `JwksKeyRotationIT`, ADR-0063; 4/4 plan
  deliverables; 6h56m wall, 7.8M tokens). Landing record: `landings/PLAN-24.md`; `landing-check` reports
  it complete. Corroborated against `ci pr view 430`, the commit being the tip of `origin/main`, and
  `ci checks status` (35 checks, success, **post-merge benchmark green**, run 38268812353).
  - **The publish rule is an approximation, shipped by operator decision.** A refreshed key set is
    published only when it holds the key id that triggered the refresh, because token-sheriff 0.9.6
    cannot list a key set's key ids. Invented key ids can delay pick-up of a genuine rotation until the
    background refresh; no invalid token is accepted. The upstream accessor request is filed as cuioss/TokenSheriff#801 (2026-10-13).
  - Declared surface 16 entries, realized 22 files (8 undeclared, 4 untouched): 1.4x, against 3x to 5x
    for the earlier landings measured. The first measurement taken after a same-day re-grounding of the spec.
- 2026-10-12 — **Drain 4: 15 messages, 15 archived, 0 invalid.** One landing reconciled. 13 candidate
  lessons PROMOTED (`2026-10-12-16-001` to `-013`: eight for plan-marshall components, filed with
  `--allow-foreign-store`; five for this project — messages 004 and 005 under `documentation`, 006, 009
  and 011 under `api-sheriff`, because the store refuses the `api-sheriff:<area>` components the sender
  wrote). None duplicates a lesson already in the store. Message 012 (`runRetry` lacks the guard
  `runRefresh` got) STAGED as PLAN-28.
- 2026-10-12 — **PLAN-28 staged** (WS-01, 6 deliverables): the `runRetry` guard, a log record on an
  unanticipated `runRefresh` failure, the wall-clock decision, the documentation rows PLAN-24 did not
  check, three simplify leftovers, and the diagnosis of the flaky `GatewayEdgeFramingCorpusTest`. One
  production change, in `auth/`; no other staged spec touches `RetryingJwksLoader`.
- 2026-10-12 — **PLAN-28 widened by operator request: a second possibly flaky test.** Deliverable 7
  asks whether `BffReservedPathPrecedenceIT` is flaky (one local failure in a full run during PLAN-24,
  green on a targeted re-run; never red in CI). Reproduce first, in a full run. Expected surface +1 entry
  (10 → 11). 7 deliverables.
- 2026-10-12 — **PLAN-27 re-grounded at c013781d for its emit; one deliverable is already done.** All
  nine claims stamped. Its M-64 hypothesis is CONTRADICTED: `BffRefreshScopeWireIT` already drives the
  scope-driven refresh through the gateway on the stub. Deliverable 2 shrinks to one added assertion (no
  authorization request is made) plus naming the test as the evidence. The nginx hypothesis is also
  contradicted for the one file named; the remaining tag-only references are located and
  `integration-tests/docker-compose.yml` joins the declared surface (11 → 12 entries).
- 2026-10-12 — Fold, adding no file surface: PLAN-26's next-free ADR ordinal hint corrected to 0064
  (0063 is PLAN-24's ADR).
- 2026-10-12 — **PLAN-27 emitted and marked launched** (`auto_emit`), after the ledger landed so the
  re-grounded spec is the one on `main`. In-flight set empty: no live plan directory, and no sibling
  epic row on `origin/main` is launched or running. Second slot left EMPTY: PLAN-28 overlaps PLAN-27 on
  `doc/adr/` and PLAN-26 on `doc/adr/`, and the WS-04 verdicts are stale.

- 2026-10-12 — **PLAN-25 shipped** as PR #408 / b7f937d3 (the ten downstream-documented properties at
  IT level; 11/11 plan deliverables; 35h01m wall, 11.7M tokens). Landing record: `landings/PLAN-25.md`.
  The first landing since the ledger relocation to arrive over the inbox channel, and `landing-check`
  reports it complete. Corroborated against `ci pr view 408` and the commit's ancestry on `origin/main`.
  - **The post-merge benchmark FAILED and stayed red for nine merges.** The pending-login flood shipped as
    a k6 goal that had never run against a stack; inside the compose network it sent `Host: api-sheriff`,
    and the gateway answers its reserved login path only for the host of `oidc.redirect_uri`. #427 moved
    the flood into the suite as `BffPendingLoginBoundIT`; the benchmark is green again (run 38073671464).
    Not a gateway defect. The plan's own PR had listed "M-41 against a stack" as an unticked box.
  - **The tests-only rule bent once**, by operator decision: `Retry-After: 5` on the open-circuit `503`.
    The spec's STOP-and-file rule was not followed for that row.
  - **D5's blocked rows shipped** by a scoped, named exemption in `ItProfileConfigBindingWiringTest` —
    the "decision, not a test detail" the 2026-10-05 cleanup said it needed, taken by the operator.
  - **The override pairing with `api-sheriff-0-2-0/PLAN-V02-01` collided as predicted, plus once in a way
    no surface can predict** — see the new Watch on the integration job's wall-clock.
- 2026-10-12 — **Drain 3: 18 messages, 18 archived, 0 invalid.** One landing reconciled; 15 candidate
  lessons PROMOTED to the lessons corpus (`2026-10-12-07-001` to `-015`: ten for plan-marshall components,
  filed with `--allow-foreign-store`, five for this project's own components); one DISCARDED (`-006`, the
  gate rewriting 282 untouched files — fixed once on `main` by #415); `kidicap-gateway-downstream-013`
  STAGED as PLAN-27. Promotion over discard, unlike 2026-09-21: the corpus now holds project-component
  lessons (`2026-10-02-12-007`, `-008`), so the earlier "not that shape" ground no longer holds. Message
  `-014` was promoted with a marked correction — its sender flagged one of its own observations as wrong,
  and the orchestrator re-ran the three `pr comments` argument forms to confirm.
- 2026-10-12 — **PLAN-27 staged** (WS-02, 5 deliverables): the downstream's two new IT requests (M-62
  unbound DPoP token response refused; M-64 scope-driven refresh through the gateway) plus PLAN-25's
  leftovers — the two decisions `adr-propose` never saw, the five security-audit findings left pending
  triage, and the test-hygiene items #408 names. Alternative considered: fold M-62/M-64 into PLAN-24 or
  PLAN-21 because they share the IT directory — rejected, neither is about JWKS or the login flood.
- 2026-10-12 — **PLAN-24 emitted and marked launched** (`auto_emit`). The in-flight set is empty
  repo-wide: on `origin/main` PLAN-V02-01 is `shipped` (#409/#410/#412) and no sibling row is launched or
  running. PLAN-24's six claims were re-checked and re-stamped at `fdcfaeb3`, all corroborated. Its
  Expected Surface gained `OneOffGatewayContainers.java` and the topology document (14→16), because every
  test-started container now goes through that harness and its contract-tested port table. Second slot
  left EMPTY: every other staged spec overlaps PLAN-24 (PLAN-26 on 3 entries, PLAN-27 on 4, PLAN-21 on 7,
  PLAN-20 on 10). ⚠ The gate was read as the standard states it — disjointness against plans IN FLIGHT.
  Read literally, `orchestrate.md` refuses any candidate with any overlap row, and every spec here has
  rows against shipped and merely-staged specs, so that reading can never emit anything in this epic.
- 2026-10-12 — Folds, both adding no file surface: PLAN-21 told that the flood test exists and must be
  amended, not duplicated; PLAN-20 given the downstream's two step-up observations (`step_up_url` returns
  to the API URL; under PAR an unknown scope is refused at the push as `400 invalid_request`). ADR
  next-free ordinal corrected again, 0062→0063, in PLAN-24 and PLAN-26.

- 2026-10-06 — **Cleanup at `6788bdae`: 28 claims re-grounded across the five staged specs, none newly
  contradicted, four specs corrected.** Twenty commits had landed since the last pass (`35f2bb37`), three of
  them releases (0.2.2, 0.2.3 and 0.2.4 are tagged; trunk is 0.2.5-SNAPSHOT). What moved:
  - **The ADR ordinal guidance went stale again.** 0060 (dispatch framing, #385) and 0061 (offline config
    validation, #387) are taken, so **0062 is next free**. PLAN-24 and PLAN-26 both said 0060 and now say
    0062, each with a note that the figure has gone stale twice and must be re-listed before allocating.
  - **PLAN-20's replay seam was rewritten underneath it, and is still absent.** #385 and #386 changed
    `DispatchStage` by +629/−90; the body still streams with no replay buffer, and the class javadoc now
    states that a streamed request cannot be safely replayed once a byte has crossed upstream. ADR-0060
    was added to the spec's Expected Surface (29→30 entries, verified by membership in `corpus surfaces`)
    as required reading before deliverable 2.
  - **PLAN-26's threat-model line references moved, its content did not.** #386 added ~59 lines above the
    BFF section: the over-claiming Control line is now at 1930 and the summary row at 3014. The spec
    carries the new positions and says to locate by text.
  - **PLAN-25 and PLAN-21 are unchanged in substance.** The IT tree and the compose file are untouched
    since `35f2bb37` apart from the new `ValidateConfigImageIT`; `bff/`, `auth/`, `BffRuntimeProducer`,
    the schemas and the config model are untouched too, which is why 14 of the 28 verdicts rest on "file
    not in the diff" rather than on a re-read.
  - Duplication cross-check: **zero** shared-origin matches over 24 sibling specs in 2 epics
    (`api-sheriff-0-2-0`, `api-sheriff-0-3-0`) and an empty live plan set; nothing superseded, nothing
    redistributed. ⚠ The FILE overlap with those two epics is broad — every staged spec here shares
    files with several of theirs (`doc/configuration.adoc`, `doc/adr/`, `BffRuntimeProducer`, `auth/`,
    the IT compose file) — so `next` must be read against their queues, not only this one.
  - The 30 verdicts on SHIPPED specs were left at their old shas and read stale by design: a shipped
    spec's premise is settled by its landing, not by re-grounding.
  - Executor regenerated from plan-marshall 0.1.1826 to 0.1.1855 (local, git-ignored) because the `land`
    verb did not exist in the old one; `marshal.json` is still at 0.1.1818 and its reconcile is an
    operator action (`/marshall-steward upgrade`). The shared ledger worktree was on
    `chore/orchestrator-cleanup-1005`, which the new store seam refuses; it was clean and fully contained
    in `origin/main`, so it was switched to a fresh `chore/orchestrator-ledger` cut from `origin/main`.

- 2026-10-05 — **Cleanup at `35f2bb37`: 58 claims re-grounded, three contradicted, three specs narrowed.**
  Five commits had landed since the last pass, two of them on the staged specs' own surfaces (`e8db85bf`
  PAR/DPoP, `35f2bb37` which removed 102 `-Djavax.net.ssl.trustStore` lines from the IT compose file and
  added a guard forbidding `oidc_verify_hostname`). What moved:
  - **PLAN-24's outline fork CLOSED, in the favourable direction.** The earlier hypothesis put the
    non-fetching read and its retry wrapper both in token-sheriff. Half was wrong: `RetryingJwksLoader` is
    the gateway's OWN `auth/` class, so the bounded unknown-`kid` fetch is in-repo work with no library
    fork, and `refresh_interval_seconds` is a pass-through of a config field the library already has.
  - **PLAN-25 lost one deliverable and had one blocked.** D10 is obsolete (the javadoc it would correct is
    already accurate). D5's M-50/M-52 are blocked as filed: the new `ItProfileConfigBindingWiringTest`
    forbids the very keys and JSSE arguments those fixtures need, so relaxing TLS now requires amending a
    deliberate guard — a decision, not a test detail. Eight deliverables stay live.
  - **PLAN-26 shrank because PLAN-23 had already done most of it.** The threat model already carries the
    "Not delivered: token revocation at logout" paragraph and the accepted cookie-mode residual, both from
    PLAN-23's own merge. The over-claim that remains is two lines — the Control line (1871) asserting
    refresh-token-family revocation, and the summary row (2955) reading `COVERED (server)` with no caveat.
    Deliverable 3 dropped. The security point is undiminished and the fix is now two lines.
  - **The retired-path defect was found in this epic's OWN staged specs and fixed.** PLAN-20 and PLAN-21
    still carried `/plan-marshall task="implement .plan/local/orchestrator/…"` hand-off commands. That is
    precisely the pointer form the current tooling classifies `unrecognised_id`, and precisely what made
    PLAN-23 finish with `emit-landing: not orchestrated`. Emitting either spec from the old path would have
    repeated it. Both corrected, each carrying a note saying why; the SHIPPED specs keep their original
    paths because they are frozen records of what was actually emitted.
  - Surface corrections applied and verified by re-reading the parser: PLAN-20 22→29 entries (the
    `DispatchStage` body-streaming seam, `TokenRefreshCoordinator`, `OidcConfig.StepUp`,
    `ApiSheriffLogMessages`), PLAN-21 14→18 (incl. the spec naming the WRONG schema for `rate_limit` — it
    is per-route in `endpoint.schema.json`, not `gateway.schema.json` — and PAR now pushing to the IdP
    BEFORE the pending store, so an admission cap must sit ahead of `authorize`), PLAN-24 12→14, PLAN-25
    4→8, PLAN-26 6→9.
  - Duplication cross-check: **zero** shared-origin matches across 3 epics and the live plan set; the file
    overlaps are all against the ARCHIVED `deployment-configurability` epic (1–6 mostly-doc files each),
    so nothing is superseded.
  - ADR ordinals: 0058 (PAR/DPoP) and 0059 (routing) are now taken, so **0060 is next free** — every spec
    that warned about 0053 was carrying stale guidance and now says so.

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

- 2026-09-15 — **Aggregation (operator request):** the 12-plan cut was too fragmented; the operator
  authorized up to 12 deliverables per plan. Re-decomposed before any launch into 4 plans along shared
  surfaces: PLAN-13 defect fixes (AS-7, AS-10, AS-13; 10 deliverables), PLAN-14 session and scopes (AS-5,
  AS-6, AS-14; 11), PLAN-15 routing and headers (AS-3, AS-4, AS-8, AS-9, AS-11, AS-12; 11), PLAN-16
  application portal (AS-1, AS-2; 9). Order 13 → 14 → 15 → 16. Workstreams reduced to 4: WS-05 security
  headers merged into WS-02; AS-9 moved from WS-01 to WS-02. Superseded specs PLAN-01..12 and the WS-05
  charter relocated verbatim to `superseded/` subdirectories (never deleted). The queue was re-seeded from
  empty (bulk `plans` reset, then one `queue --add-row` per plan) because nothing had launched and no row
  held a result. Mitigation for aggregating externally gated items: each spec states that a gated item
  ships as a documented gap plus inbox finding while the rest of the plan lands. Supersedes the 12-plan
  mapping and the 5-deliverable split-guard entries above.
- 2026-09-15 — AS-13 re-grounded against cui-http 3.0 and `main`: the main fix is API Sheriff handing over
  raw encoded values (it currently decodes twice, and what it checks differs from what it forwards).
  cui-http `main` already has #210/#217. The source's "add `%` to the query set" is rejected.
- 2026-09-15 — **CR/LF decision (operator): option (a).** A cui-http option to reject decoded CR/LF in
  `PARAMETER_VALUE`, enabled by API Sheriff `strict`; requested as cuioss/cui-http#236, which the operator
  implements right away. Rejected: (b) an API Sheriff-only check (duplicates decoding knowledge outside the
  library that owns it); (c) accepting the carve-out (a gateway cannot know whether a backend reflects values
  into headers). PLAN-13 builds against cui-http `3.1-SNAPSHOT` for local integration (operator-approved
  version override) once #236 is merged.

- 2026-09-17 — **PLAN-15 shipped** as PR #320 / 93a4b3e (AS-3, AS-4, AS-8, AS-9, AS-11, AS-12; 11/11
  deliverables; 48h27m, 10.4M tokens). Landing record: `landings/PLAN-15.md`. The row's `pr` was stamped
  **#320**, not the `#310` the landing facts carried — `create-pr` recorded a PR that was later closed,
  and the merge was corroborated against `origin/main`.
- 2026-09-17 — **AS-14 re-scoped by the downstream** (inbox `-008`, `-009`): the backend signals a missing
  scope (`403 insufficient_scope` before any side effect) and the gateway obtains it — refresh first with
  one replay, browser step-up otherwise. PLAN-14 keeps `endpoint.scopes`, the login request and the bearer
  `403`, and gains the active-scope refresh without which it would strip endpoint scopes on the first
  refresh; the gateway-side pre-check and the navigation-only step-up move out to the new PLAN-20. No
  per-route scopes, by downstream decision. Alternative considered: keep the pre-check as a second trigger
  — rejected, because a per-route scope list in the gateway drifts from the backend's own knowledge.
- 2026-09-17 — Drain 2: 5 messages. PLAN-15's landing reconciled; `-008` folded into PLAN-14; `-006`+`-009`
  staged as PLAN-20; `-007` staged as PLAN-21. Queue: 8 staged, 1 shipped.

- 2026-09-21 — **PLAN-13 shipped** as PR #334 / 3abc370 (AS-7, AS-13, AS-10; 10/10 deliverables; 73h35m,
  8.3M tokens; 0 Sonar new-code issues). Landing record: `landings/PLAN-13.md`. **Post-merge verification
  done by the orchestrator** (the plan could not observe it): the main-branch Maven Build for 3abc370 is
  green including `deploy-snapshot`, and the PR-attached benchmark run succeeded.
- 2026-09-21 — Both PLAN-13 candidate lessons **discarded as out of epic scope**, each recorded where it
  belongs instead: the api-sheriff Sonar-budgeting hint is a working practice for the remaining plans
  (Watches) and owes an `architecture enrich insight --module api-sheriff` call the orchestrator does not
  make; the `ci-verify` build-profile mismatch is project tooling configuration (Open Defects). Promote
  was rejected for both: the lessons corpus takes `bug` / `improvement` / `anti-pattern` /
  `arch-constraint` for a plugin component, and neither candidate is that shape.

- 2026-09-22 — **PLAN-14 shipped** as PR #337 / 3e3addc (AS-5, AS-6, AS-14 incl. the active-scope refresh;
  8/8 plan deliverables; 10h58m wall, 8.5M tokens; 0 Sonar new-code issues; ADR-0048, ADR-0049). Landing
  record: `landings/PLAN-14.md`. Corroborated against `ci pr view 337` and `ci checks status` (31 checks
  green, including the post-merge benchmark the plan's wait timed out on). Unplanned but in scope: a
  `sameOrigin` control-character fix closing a `/%09/evil.com` open redirect, and a sealed-cookie format
  reset (9→10 fields) that forces one re-login after upgrade. PLAN-19's and PLAN-20's dependency on
  PLAN-14 is now satisfied. The operator reported the completion before the drain ran; the ledger still
  said `launched` because the orchestrator was not told the plan had started.

## Open Defects

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

- **`GatewayEdgeFramingCorpusTest` flake — tracked in ONE place: `api-sheriff-0-2-0` epic.md, Watch
  "HTTP/1.1 DRAIN 502 FLAKE IN THE FRAMING CORPUS"**, which holds both sightings (#431's main push run and
  PLAN-28's CI-only 502), the owner (`PLAN-V02-06`, or a dedicated fix when it recurs) and the
  reproduction recipe. Report new sightings there, not here. — consolidated 2026-10-13
- **Documentation left open by PLAN-28** (the sender's statement, not re-read): two stale
  `token_validation` mentions in `doc/configuration.adoc` (around line 2213 and the YAML example comment
  near line 380); the `IssuerConfig.Jwks` Javadoc repeats the three refresh ranges unguarded; the
  contract test does not cover the documented restriction to the `http` source. No plan owns these. —
  source: `landings/PLAN-28.md`
- **Four upstream keys parse and act on nothing**: `connect_timeout_ms`, `read_timeout_ms`,
  `retry.max_attempts`, `retry.idempotent_only` (read at 128fd07e: bound in `UpstreamConfig`, no reader;
  the guard uses a fixed 30 s timeout and two retries). The user guide marks them inert;
  `doc/architecture.adoc` and `doc/variants/01-base-gateway.adoc` do not. Staged as PLAN-29. — source:
  inbox `integration-test-evidence-follow-ups-001.md`, `landings/PLAN-27.md`
- **The shared ledger worktree was 23 commits behind `origin/main`** when this drain ran, so the sibling
  epics' queue rows it holds were stale: it showed PLAN-V02-01 as `launched` while `main` has it
  `shipped`. `corpus cross-check` and the `next` gate read the worktree, so they judged the in-flight set
  from stale rows; the orchestrator read the rows from `origin/main` by hand instead. The next `land`
  resyncs the tree. Mechanism (another session lands the sibling ledger from elsewhere and this tree is
  never pulled) is plugin-side. — observed 2026-10-12

- **ADR-0050** (portal / HTML error pages, from PLAN-16) was committed with status **Proposed**.
  Accepting it is an operator decision on repository source — outside the orchestrator's write boundary.
  — source: `landings/PLAN-16.md`
- Two product gaps PLAN-16 shipped knowingly, both carried in PR #343: an error response that can be
  negotiated to HTML or JSON sends no `Vary: Accept` (the HTML variant is `no-store`, so the risk is a
  shared-cache mismatch on the JSON variant), and no unit test covers the 504 upstream-timeout error page
  (**the 504 half is CLOSED 2026-10-12**: PLAN-25's `HtmlErrorPageIT` pins the 503 and 504 pages at IT
  level; the `Vary: Accept` half stays open).
  Small; fold into a later plan touching that surface rather than staging one. — source:
  `landings/PLAN-16.md`
- Project config housekeeping, both operator actions: `marshal.json` is older than the installed
  plan-marshall (remedy `/marshall-steward`), and its `build.queue.max_slots=2` is **ignored** in favour of
  the machine-wide cap of 5 — every build logs a `[BUILD-QUEUE] WARNING` (about 35 times in the PLAN-16
  run), and the remedy is `manage-build-server config migrate`. On a WSL host running native-image builds
  the effective cap is the one that matters. — source: inbox `plan-16-application-portal-012.md`
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
- PLAN-13 merged past a recorded review gap: the required `cuioss-review-bot` last reviewed 8f8d790 and
  was not re-triggered for the final commits; an operator authorization bound to d190d90 and to that one
  gap was recorded and consumed. CodeRabbit's fix commit (d190d90) was never re-reviewed (quota), and
  Sourcery refused on diff size. The finalize loop-back ceiling was also exceeded by one round, and
  `--force` replaced the review step's round-10 record with `done`. Nothing here contradicts the green CI
  and the zero Sonar new-code issues at d190d90 — it is a verification-currency gap, recorded so a later
  defect in this surface is read against it. — source: `landings/PLAN-13.md`
- PLAN-15 merged with seven head-dependent finalize steps recorded `done` at OLDER commits and not re-run
  on the merged head (quality gate, self-review, simplify, security audit, ci-verify, automatic-review,
  sonar-roundtrip). The merge rests on CI fully green at `e0e9fcf` (native ITs included) plus both review
  bots on that commit with no open threads. Not a code defect — a verification-currency gap to weigh if
  something surfaces in that surface later. — source: landing `landings/PLAN-15.md`
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

- **cuioss-review-bot (PR-Agent) cannot be re-triggered anywhere in the organisation.** Its on-demand
  `/review` fails org-side: the GitHub App token cannot reach `cuioss/pr-agent-settings` ("repository does
  not exist or is not accessible to the parent installation"), observed on #350 and on another PR at
  19:22 on 2026-09-23. ⚠ This is the **mechanism behind the "merge head not freshly reviewed" note on five
  of the seven plans in this epic** — it is not per-plan bad luck. The fix is an org-level App-permission
  change, outside this repository and this epic. Until then, every PR's re-review after a fix push depends
  on CodeRabbit's quota alone. — source: `landings/PLAN-22.md`
- Two small cleanups on the WebSocket relay, left deliberately by PLAN-22: the pre-existing
  `source.pongHandler(pong -> resetIdle())` in `WebSocketRelayStage.wire()` is now redundant (since
  `relayFrame` receives pongs and resets the idle timer), and the `queueFullDialer` /
  `frameThreadRecordingDialer` test helpers are near-duplicates one decorating dialer would cover. No
  staged plan touches that surface — fold into whichever plan next enters the relay. — source:
  `landings/PLAN-22.md`

> ↪ Relocated to `settled.md` § "Duplicate ADR-0053, resolved by #367" — resolved 2026-10-02 by #367; the manage-adr scan blind-gate note moved with it
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
- A stray PR-level comment on #346 (`IC_kwDOPatrT88AAAABWb_adQ`) carries internal triage prose that
  `post_responses` transmitted verbatim as a public reply. Harmless; delete by hand on GitHub if wanted.
  Mechanism filed as plan-marshall lesson `2026-09-24-05-001`. — source: inbox
  `route-header-matcher-defects-003.md`
> ↪ Relocated to `settled.md` § "Egress-allowance duplication, resolved by PLAN-17" — resolved 2026-09-23 by PLAN-17 (#345)

- The merge-lock release reported the lock **"already free"** during PLAN-17's finalize although that plan
  had acquired it. The merge was unaffected, but the release path did not observe its own claim, so mutual
  exclusion is unestablished for that window and the report reads as success. Plugin-side; filed as
  plan-marshall lesson `2026-09-23-05-003`. — source: operator report, PLAN-17 landing

> ↪ Relocated to `settled.md` § "NO_PLAN sentinel fails the next gate, resolved in plan-marshall 0.1.1855" — resolved 2026-10-06 by plan-marshall 0.1.1855

- kidicap-gateway still links to the removed `doc/api-sheriff-aenderungen.adoc` (~30 references in
  `README.md`, `CLAUDE.md`, `doc/**`, incl. `#as-N` anchors). Not API Sheriff work, and not owned by any
  plan here; the gateway maintainers repoint them (e.g. to API Sheriff issues/ADRs as plans land). —
  source: removal commit 08620f0 in kidicap-gateway (branch feature/initial-structure, not pushed)

- **The threat model asserts a control the runtime does not implement**: BFF-09 is labelled **COVERED**
  for IdP token revocation on logout, while the revocation hook is bound to a **no-op** in the shipped
  runtime. PLAN-23 corrected the user docs and left the threat model, so the repository now states both
  the truth and the claim. A false COVERED closes a question a gap would have invited. **Staged as
  PLAN-26 deliverable 1.** The plan also recorded the pattern as lesson `2026-10-02-12-007` (kept in this
  repo's store — the component is this project's). — source: `landings/PLAN-23.md`
- Cookie mode cannot observe a logout, so a late refresh or widening response can set a fresh cookie.
  Documented as a residual by PLAN-23; **CodeRabbit rates it Medium and nothing disposes of that rating**.
  Staged as PLAN-26 deliverable 3. — source: `landings/PLAN-23.md`
- Two shipped decisions carry no ADR: the problem+json extension-member rule, and the reserved-path boot
  rule (the ADR check suggests an ADR-0018 amendment for the latter). Staged as PLAN-26 deliverables 4-5.
  — source: `landings/PLAN-23.md`
- Three info-level audit items from PLAN-23, unstaged: a widening does not revoke the superseded refresh
  token; the active scope set falls back to the requested set when the provider states none; no boot rule
  refuses duplicate reserved paths. — source: `landings/PLAN-23.md`
- Pre-existing and unstaged: boot refusal messages echo configured values raw (`default_view`, trusted
  proxies, base-URL alias, websocket origins, anchor prefixes), and roughly forty older comment
  inaccuracies sit in the BFF files. Hygiene, not correctness — fold into whichever plan next enters those
  files. — source: `landings/PLAN-23.md`

- **A breaking BFF change landed from OUTSIDE this epic: #377 (e8db85bf) requires PAR and DPoP and
  defaults to `private_key_jwt`** — 91 files, +14052/−725, ADR-0058. Not an AS item and not staged by this
  epic, but it reshapes the login surface every WS-04 spec targets. Re-grounded against it 2026-10-02:
  PLAN-21's claims all survive (PAR pushes the authorization request before the pending record is stored,
  but the store-then-mint-cookie order its claims 4-5 rest on is unchanged), and PLAN-20's claims survive
  too. ⚠ Treat it as a live input for PLAN-24 and PLAN-26: a DPoP-bound token changes what "relay the
  token" and "revoke at the IdP" mean. — observed 2026-10-02 by the orchestrator

## Watches

- **Cross-epic hold (2026-10-14): `api-sheriff-0-2-0` reworks the edge pipeline and policy-block
  resolution before any further edge or config feature.** Operator ruling; sequence there:
  `PLAN-V02-24` (one resolution mechanism for every policy block, test builders) → `PLAN-V02-25`
  (decompose `GatewayEdgeRoute`) in parallel with `PLAN-V02-26` (split `ConfigValidator`). They start
  after this epic's PLAN-32 has landed. **PLAN-20, -21, -26 and -29 are held until the refactor plan for
  their surface lands** (config blocks → V02-24, edge → V02-25, boot rules → V02-26), then are re-grounded
  against the new seams before emitting. PLAN-31 (`auth/RetryingJwksLoader` only) is not held.
  **Update 2026-10-14 (#461, refactor design settled with the operator):** `PLAN-V02-24` and `PLAN-V02-26`
  were emitted in parallel and run now — they no longer wait for PLAN-32. `PLAN-V02-25` (edge) follows
  once V02-24 **and this epic's PLAN-32** have landed, so PLAN-32 now gates the edge refactor and with it
  PLAN-29. Release order for the held plans: PLAN-26 when V02-26 lands; PLAN-20 and PLAN-21 when V02-24
  lands; PLAN-29 when V02-25 lands. V02-24 first moves every policy block onto one resolution mechanism
  (gateway → anchor → endpoint → route) with the current chains, then applies per-block scope sets the
  operator decides at its outline **as explicit breaking changes** — re-grounding PLAN-20 and PLAN-21 must
  read those decisions, not only the new seams. The config records gain Lombok `@Builder`; tests stop
  constructing them positionally. A later `PLAN-V02-27` simplifies the tests in `config/`, `edge/`,
  `routing/` after the refactor.
  **Update 2026-10-15 (operator ruling, this epic's PLAN-32 landing analysis):** PLAN-32 shipped as #466,
  V02-24 as #465 and V02-26's first slice as #463. The release points above are **replaced** by the
  sibling's chain: validator slices V02-28 → V02-40..43, V02-44 → V02-25, then V02-23, then V02-27.
  **PLAN-20, -21, -26 and -29 stay held until that whole chain has landed**, then each is re-grounded
  (cleanup) before emitting. PLAN-31 is not under this hold, but the operator held it on 2026-10-15 over
  multi-file overlaps with the running V02-12 (8 files: docs, `JwksKeyRotationIT`), the parked V02-23 (8 doc
  files) and the running V02-44 (`RetryingJwksLoaderTest`, `doc/LogMessages.adoc`). Re-run the gate once
  V02-12's second PR and V02-44 have landed.
  **Update 2026-10-26 (cleanup at `54f82f03`): the chain has landed and the hold is lifted.** V02-28, V02-40
  to V02-43 (#485, #489, #502, #505), V02-44 (#476), V02-25 (#487), V02-12 (#450, #492), V02-23 (#515) and
  V02-27 (#520) are all shipped, plus V02-45 (#506), V02-46 (#513), V02-15 (#516), V02-22 (#512) and V02-04
  (#521). PLAN-20, -21, -26 and -29 have been re-grounded and may be emitted subject to the gate: the sibling
  runs V02-05, V02-06 and V02-20 (three of three slots) and the gate finds a 4 to 20 file overlap with each, so
  an emit needs a free slot and the operator's overlap answer. Never two WS-04 plans at once (PLAN-20, -21, -26).
  The reason for the PLAN-31 hold (V02-12, V02-23, V02-44) is gone for the same reason.

- **The JWKS publish rule stays approximate until token-sheriff exposes a key set's key ids — TRIGGER
  FIRED, STAGED AS PLAN-31 (2026-10-14).** token-sheriff 0.9.7 carries `JwksLoader.getKeyIds()`
  (cuioss/TokenSheriff#801 → PR #803); `main` pins 0.9.7 since #449 (`5c2a8143`), which implemented
  `RetryingJwksLoader.getKeyIds()` as a plain delegate and changed no publish behaviour. PLAN-31 switches
  `settleRefresh` to the exact rule and removes the residual from ADR-0063 and threat-model GW-07. This
  entry closes when PLAN-31 lands. — source: `landings/PLAN-24.md`, lesson `2026-10-12-16-013`
- **A same-day re-grounding seems to tighten the declared surface.** PLAN-24 realized 1.4x its
  declaration; the earlier measurements ran roughly 3x to 5x. PLAN-27, also re-grounded on its emit day,
  realized 28 files against 12 entries (several of them directories); 8 of its 10 undeclared files follow
  from one wire-or-remove decision the spec left to the plan. Two data points: re-grounding helps, an
  open decision in the spec does not get declared. PLAN-29 therefore declares both outcomes. — trigger:
  PLAN-28's and PLAN-29's landings; source: `landings/PLAN-24.md`, `landings/PLAN-27.md`
- **Two plans that each add integration suites collide on the native integration job's wall-clock, even
  with disjoint files.** PLAN-25 and PLAN-V02-01 were each green alone; combined they hit the job's
  35-minute limit (raised to 60 in #408, cycle shortened by #424). The disjointness gate cannot see this.
  Before pairing any two of PLAN-24, PLAN-27, PLAN-21 and PLAN-20, check the job's current duration
  against its limit. — trigger: every `next` selection that fills a second slot; source:
  `landings/PLAN-25.md`, lesson `2026-10-12-07-005`
- **Tests the downstream cites as its evidence — do not remove or rename unnoticed.** PLAN-25's nine
  suites (`BffReservedPathPrecedenceIT`, `BffRefreshSingleFlightIT`, `BffRefreshScopeWireIT`,
  `BffRefreshTransientFailureIT`, `BffTlsRelaxationIT`, `HtmlErrorPageIT`, `BffSessionAbsoluteTtlIT`,
  `BffSessionInstanceIsolationIT`, `BffPendingLoginBoundIT`), PLAN-27's `BffUnboundTokenResponseIT`
  (M-62) and `BffRefreshScopeWireIT.refreshGrantsCarryTheSessionScopeOnTheWire` (M-64), the six the PLAN-25 spec lists as already
  pinned, and for M-61 `BffSessionScopeParityIT.navigationNeedingARefusedScopeIsRefusedAtThePush` and
  `.xhrNeedingARefusedScopeIsRefusedAndTheSessionSurvives`. — trigger: any plan refactoring the IT
  directory; source: inbox `kidicap-gateway-downstream-012.md`, `-013.md`
- **The downstream is on 0.2.4** and reports PLAN-19 (`session_fallback`), PLAN-23 (session-route scopes,
  `step_up_url`) and #377 (PAR/DPoP, in client-secret mode) working against Keycloak 26.7.4, with two
  workarounds removed. This settles the "tell the downstream" half of the fired release watch below for
  those items. Two things it noticed and did not request: the default
  `oidc.client_authentication.jwks_path` (`/auth/jwks`) lies outside a build-time context path, like
  `login.default_return_url`'s default; and with a pushed request Keycloak adds a `302` to its own
  `login-actions` address before the form. — trigger: any plan touching reserved-path defaults; source:
  inbox `kidicap-gateway-downstream-013.md`
- Declared surface versus footprint, fifth measurement: PLAN-25 declared 8 entries and touched 40 files
  (14 undeclared, 2 untouched), but 26 of the 40 sat inside its two declared directories. Directory
  declarations absorb growth that file lists do not. — source: `landings/PLAN-25.md`

- **The "0.2.2 release" trigger named by five watches below has FIRED — three times over.** Tags 0.2.2,
  0.2.3 and 0.2.4 exist (observed 2026-10-06). The actions those watches owe are: the breaking-change
  release note for AS-14, telling the downstream that `oidc_verify_hostname` and the six routing AS items
  are consumable, offering the release to the downstream's Helm test, and confirming the unobserved
  main-branch Maven Builds. ⚠ Whether any of them was done is NOT observed — the orchestrator did not read
  the release notes or the downstream. Confirm each, then retire the watch. — trigger: now

> ↪ Relocated to `settled.md` § "PLAN-15 PR chain, retired" — PR #320 merged; the chain is closed

- Declared surfaces under-state footprints in this epic, now measured three times: PLAN-15 declared 23
  entries and touched 82 (37 undeclared, 2 declared-unused); PLAN-13 declared 19 and touched 68 (39
  undeclared, 0 unused); PLAN-14 declared 24 and touched 82 (33 undeclared, 2 unused — undeclared
  clustered in `bff/cookie/`, `edge/RouteRuntimeAssembler`, `routing/RouteRuntime`, `ConfigValidator` and
  config tests); PLAN-16 declared 19 and touched 70 (37 undeclared, 1 unused). Roughly threefold every
  time, four times out of four — a property of this epic's spec granularity, not a per-plan lapse. Treat a `disjoint` verdict from the gate as weak evidence
  here and prefer sequencing within a subsystem. — trigger: every `next` selection; source:
  `landings/PLAN-15.md`, `landings/PLAN-13.md`, `landings/PLAN-14.md`
- The main-branch **Maven Build** for PLAN-14's merge commit 3e3addc (incl. `deploy-snapshot`) is NOT
  verified: the CI abstraction reads PR-attached runs only, and the push run is not attached to #337. The
  PR-attached post-merge benchmark IS verified green. — trigger: confirm that run (Actions → Maven Build →
  3e3addc) before the next release; source: `landings/PLAN-14.md`
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
- **A validator that guards a runtime behaviour must reuse the runtime's own normalisation, never a
  re-implemented equivalent** — PLAN-18's review-fix commit added a boot check comparing header names with
  `equalsIgnoreCase` while the runtime used `toLowerCase(Locale.ROOT)`, so the two disagreed on which
  matchers collide (e.g. `X-İd`). It was caught only by the finalize security audit re-firing, not by the
  review loop or by the tests written for that very fix. **Applies directly to PLAN-19**, which adds boot
  rules over the same auth cascade: any new rule needs a parity test driving one input set through both
  the validator and the runtime. — source: inbox `route-header-matcher-defects-001.md`
- **A behaviour widening makes sibling docs stale, and the outline will not list them for you** —
  PLAN-19 widened `auth.token_relay` to the SESSION branch of a `session_fallback` route but its doc
  deliverable never listed `doc/user/endpoint-routes.adoc`, which states in FOUR places that `token_relay`
  "acts only on `require: session`" and "has no effect on bearer or none routes". Every one of those
  became false. Before declaring the doc set, content-search the key being widened across `doc/**` and
  list what the search returns, rather than listing the documents the plan expects to touch. — source:
  inbox `plan-19-session-fallback-on-bearer-003.md`
- Review bots keep catching doc and Javadoc claims the code does not implement — third plan running
  (PLAN-16, PLAN-18, PLAN-19). Treat prose describing behaviour as an assertion to verify against the
  code before pushing, not as narration. — source: inbox `plan-19-session-fallback-on-bearer-005.md`
- A wording fix from a review comment must be swept across every sibling document in the same act: fixing
  the one line the bot quoted leaves the same claim standing in its neighbours. — source: inbox
  `route-header-matcher-defects-002.md`
- **Doc-precision signals from PLAN-17, for every remaining plan** (both slipped self-review and were
  caught by the review bot, which reinforces the review-bot watch above):
  - When "absent" and "empty" take the same derived code path, the docs must say **both** — `IssuerConfig`
    normalises absent to `List.of()` and the producer branches on `isEmpty()`, so a sentence naming only
    "absent" contradicted the field contract two lines above it. — source: inbox
    `jwks-egress-allowlist-006.md`
  - A threat-model residual claim ("every other host is still subject to X") must be qualified by every
    control that can exempt a host, not only the one just described — an explicit allowlist is
    authoritative and exempts each host it names. — source: inbox `jwks-egress-allowlist-007.md`
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
- A test that writes a file for a bind-mounted container must make it **world-readable explicitly**: under
  the build daemon's umask 077 a generated `gateway.yaml` became mode 600 / uid 1000 and the distroless
  gateway (uid 1001) refused to boot (`ApiSheriff-200`). Committed descriptors are 644, so only generated
  ones break — and the failure depends on the host umask, so it can pass on CI and fail locally. — source:
  inbox `jwks-egress-allowlist-001.md`
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
- AS-5, AS-6 and AS-14 are shipped but unreleased too, and AS-14 is **breaking** for consumers:
  `auth.required_scopes` is removed (use `endpoint.scopes`), bearer routes now enforce `oidc.scopes`, and
  sealed cookies from before the upgrade are rejected (one re-login). The 0.2.2 release note must say so.
  — trigger: API Sheriff 0.2.2 release
- Sonar new-code findings recur on test-heavy api-sheriff changes (cognitive complexity in parsing code,
  test-hygiene rules in the accompanying tests). Budget for fixing them in-code inside the same PR rather
  than discovering them after the Sonar build. An `architecture enrich insight --module api-sheriff` call
  is owed for this hint — an operator action, not an orchestrator write. — trigger: each remaining plan's
  finalize; source: inbox `plan-13-defect-fixes-001.md`
- AS-3, AS-4, AS-8, AS-9, AS-11 and AS-12 are shipped but UNRELEASED (0.2.1 is current). The downstream
  migrates on the next parent bump, so the 0.2.2 release note is the point at which six AS items become
  consumable. — trigger: API Sheriff 0.2.2 release.
- The downstream asked for `egress_tls.oidc_verify_hostname` (proposed AS-15); it is ALREADY on HEAD
  (commit 7ba9734 / #306), together with `oidc_tls_profile`, `ApiSheriff-125` and `ApiSheriff-126`, and
  is unreleased (current 0.2.1, next 0.2.2-SNAPSHOT). Tell the downstream at the 0.2.2 release so it can
  drop its `-Djdk.internal.httpclient.disableHostnameVerification=true` workaround and bind
  `IDP_VERIFY_HOSTNAME` to the real key. — trigger: API Sheriff 0.2.2 release; source: inbox
  `kidicap-gateway-downstream-001.md`
- The downstream now runs a Helm-based in-cluster integration test covering the gateway contract against
  Keycloak, and runs it before adopting a parent-version bump — an external regression signal for API
  Sheriff releases. Its release tags are `<api-sheriff-version>-<n>`. — trigger: offer a release to that
  test before publishing; source: inbox `kidicap-gateway-downstream-002.md`
- Downstream confirmed against 0.2.1: the `egress_tls` knobs behave exactly as documented (including the
  untrusted-chain control), `${VAR}` placeholders coerce to booleans, the JDK-triple trust route is the
  only one that works on the distroless native image, and `ApiSheriff-17/118/120` are usable operator
  signals. — trigger: re-check if any of those surfaces changes.

> ↪ Relocated to `settled.md` § "cui-http #236 and the 3.1 release, resolved" — 3.1 is released and resolved through the parent

- cui-http 3.1 also brought #237 forwarded host/port/context-path hardening, #231 cookie validation,
  #227 content-type pipeline enforcement, #229 config-surface reconciliation, #222 exception-detail
  sanitisation and #240 client hardening — adopted silently with the parent bump, not by any plan here.
  — trigger: watch PLAN-15's and PLAN-13's verification runs for fallout in forwarded-header and cookie
  behaviour; re-check when the next parent bump lands.

- Source-document claims ("measured", code line references) were measured against API Sheriff
  0.2.1 — re-verify each against HEAD at decompose / outline; HEAD may already have moved
  (e.g. AS-10 references API-Sheriff#194). — trigger: every spec's verify-first clause.
