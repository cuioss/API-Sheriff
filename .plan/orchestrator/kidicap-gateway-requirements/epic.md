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
    Nothing is emitted. **Lifted 2026-10-26**: the chain has landed (see the Watches update).
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

> ↪ Relocated to `settled.md` § "Breaking BFF change #377 from outside the epic" — PLAN-24 shipped and PLAN-20, -21 and -26 were re-grounded against it on 2026-10-26

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
