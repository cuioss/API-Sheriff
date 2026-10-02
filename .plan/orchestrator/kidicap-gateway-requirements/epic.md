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

> ↪ Relocated to `settled.md` § "PLAN-15 abandonment retraction" — the claim was retracted and PLAN-15 has since shipped

> ↪ Relocated to `settled.md` § "PLAN-15 follow-ups filed as issues" — all seven are filed as #323-#329

- **ADR-0050** (portal / HTML error pages, from PLAN-16) was committed with status **Proposed**.
  Accepting it is an operator decision on repository source — outside the orchestrator's write boundary.
  — source: `landings/PLAN-16.md`
- Two product gaps PLAN-16 shipped knowingly, both carried in PR #343: an error response that can be
  negotiated to HTML or JSON sends no `Vary: Accept` (the HTML variant is `no-store`, so the risk is a
  shared-cache mismatch on the JSON variant), and no unit test covers the 504 upstream-timeout error page.
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
> ↪ **RESOLVED 2026-09-24 by PLAN-18** (#346 / f6067588): names are normalised once at the route-compile
> seam with `toLowerCase(Locale.ROOT)`, `present: false` requires absence and composes under AND, and the
> boot validator now judges matchers exactly as the runtime evaluates them (ADR-0053, ADR-0054). Record:
> `landings/PLAN-18.md`. Was: names compared case-sensitively against a lower-cased request map, and
> `present: false` constrained nothing while the boot disjointness check treated it as distinguishing
> (source: inbox `kidicap-gateway-downstream-004.md`).

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

> ↪ **RESOLVED 2026-10-02 by PR #367** (`fix(adr): resolve ordinal 0053 collision and add contract
> guards`), independently of this epic: `doc/adr/` now carries ONE 0053, the header-matcher ADR moved to
> 0056, and the PR added contract guards against a repeat. **PLAN-20's deliverable 9 (the renumber) is
> therefore RETIRED** — it would re-do settled work. What is NOT fixed is the blind gate: `manage-adr
> scan` still omits the duplicate population on executor 0.1.1826, so confirm the corpus by listing
> `doc/adr/`, never by that payload. Was:

- **`main` carries TWO ADR-0053 files** — `0053-A_header_matchers_name_is_normalised…` (#346, PLAN-18) and
  `0053-Portal_templates_render_on_a_standalone_Qute_engine…` (#348, which renamed it from 0050 after #341
  had taken 0050; that file is the portal ADR `landings/PLAN-16.md` recorded as ADR-0050). So the ordinal
  has now collided **twice** under concurrent plans, and the second collision is live on `main`. One file
  needs renumbering and the allocator needs to stop handing one ordinal to two plans — repository-source
  work, so a plan's or an operator's, not the orchestrator's. **FOLDED 2026-09-24 into PLAN-20 as
  deliverable 9** (it already authors an ADR and already declares `doc/adr/`, so the fold added no file
  surface); the entry stays open until PLAN-20 lands. PLAN-22's pre-merge duplicate gate was waived over
  this and ALSO returned no verdict, because `manage-adr scan` omits the duplicate population from its
  success payload (plan-marshall lesson `2026-09-24-07-001`) — so confirm the corpus by listing
  `doc/adr/`, never by that payload. — observed 2026-09-24 by the orchestrator, not reported by any plan
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
> ↪ **RESOLVED 2026-09-23 by PLAN-17** (#345 / c1b09c7): the allowance is derived from the `jwks.url`
> host on absent/empty, and blank entries, `host:port` entries and a hostless `jwks.url` now abort
> startup with `CONFIG_INVALID`. Record: `landings/PLAN-17.md`. Was: `allowed_egress_hosts` duplicates the
> JWKS URL host — a blank entry boots and fails later, a `host:port` entry is silently inert (source:
> inbox `kidicap-gateway-downstream-003.md`).

- The merge-lock release reported the lock **"already free"** during PLAN-17's finalize although that plan
  had acquired it. The merge was unaffected, but the release path did not observe its own claim, so mutual
  exclusion is unestablished for that window and the report reads as success. Plugin-side; filed as
  plan-marshall lesson `2026-09-23-05-003`. — source: operator report, PLAN-17 landing

- `orchestrator corpus cross-check` (plan-marshall 0.1.1728) counts `.plan/local/plans/NO_PLAN` — the
  plan-less operations sentinel (`status.json` `metadata.sentinel: true`, no `references.json`) — as a
  live plan with no comparable surface. That sets `candidate_comparison_determinate: false`
  (`live_plan indeterminate: 1`) for EVERY candidate, so the `next` gate fails closed permanently in this
  repository. Not a collision; a plugin defect (the live-plan scan should exclude sentinels). Until fixed,
  each emit needs a recorded operator override naming the sentinel as the sole indeterminate candidate.
  Plugin-side fix, outside this epic. — observed 2026-09-22 after PLAN-14's landing

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

## Watches

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
