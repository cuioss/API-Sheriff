# Settled narrative — kidicap-gateway-requirements

Relocated from `epic.md` by the `cleanup` verb on 2026-09-21, operator-confirmed. Every item's subject is
CLOSED. Content is verbatim; `epic.md` carries a pointer at each origin. Nothing here is re-derived — a
retraction and a resolved defect are the anti-rework record.

## PLAN-15 abandonment retraction

- ~~PLAN-15 has no plan directory and may be abandoned~~ — **RETRACTED 2026-09-17, do not re-derive.**
  The orchestrator looked only at the main checkout's `.plan/local/plans/`; PLAN-15 is running normally
  and its plan state lives in its own worktree at
  `.plan/local/worktrees/routing-and-response-headers/.plan/local/plans/routing-and-response-headers`
  (operator-confirmed). A plan's absence from the main checkout's plan directory is NOT evidence of an
  abandoned plan while its worktree exists.
- RESOLVED 2026-09-18 — PLAN-15's seven PR-body follow-ups are filed as cuioss/API-Sheriff issues:
  #323 `sameOrigin` encoded-spelling blind spot (bug), #324 file-level `declaresLimitShapedKey`
  granularity (bug), #325 anchor-coverage rule restated four times (docs), #326 hand-mirrored IT
  `security_headers`/`cors` fixtures, #327 five slash-stripping helpers, #328 no native IT assertion for
  redirect `no-store`, #329 `forward` block on a redirect route accepted but never read (bug). None is
  owned by a plan in this epic; they are repository backlog. — source: landing `landings/PLAN-15.md`

## PLAN-15 follow-ups filed as issues

- RESOLVED 2026-09-18 — PLAN-15's seven PR-body follow-ups are filed as cuioss/API-Sheriff issues:
  #323 `sameOrigin` encoded-spelling blind spot (bug), #324 file-level `declaresLimitShapedKey`
  granularity (bug), #325 anchor-coverage rule restated four times (docs), #326 hand-mirrored IT
  `security_headers`/`cors` fixtures, #327 five slash-stripping helpers, #328 no native IT assertion for
  redirect `no-store`, #329 `forward` block on a redirect route accepted but never read (bug). None is
  owned by a plan in this epic; they are repository backlog. — source: landing `landings/PLAN-15.md`

## PLAN-15 PR chain, retired

- RETIRED 2026-09-17 — PLAN-15's PR chain (#310–#313, #316–#318 closed unmerged over the CI-trigger
  problem cuioss/cuioss-organization#279) ended with PR #320 merging as 93a4b3e; corroborated on
  `origin/main`. The reviewed work reached `main`. Residue moved to Open Defects.

## cui-http #236 and the 3.1 release, resolved

- RESOLVED 2026-09-17: cuioss/cui-http#236 closed by PR #239; cui-http `3.1` released 2026-09-16 and
  already resolved by API Sheriff through `cui-quarkus-parent` 1.7.5 (commit 799976a). PLAN-13's
  cui-http gate is gone; it now waits only on PLAN-15 landing (shared surface).

## Superseded 12-plan decomposition

Superseded by the 2026-09-15 aggregation decision (4 plans, then 9 after the downstream drains), which
stays in `epic.md`. These three bullets record the first cut and why it was made.

- 2026-09-15 — Workstream cut: 5 workstreams (defect fixes, routing primitives, application portal,
  session and scopes, security headers) instead of the 7 the drafting pass proposed. Alternative (one
  workstream per theme incl. single-plan query-validation and scope workstreams) rejected as
  fragmentation without charter value.
- 2026-09-15 — Plan mapping: 12 plans. The drafted 4-item routing bundle (AS-3/4/11/12) was split into
  PLAN-04 (AS-3 + AS-4, high/medium) and PLAN-12 (AS-11 + AS-12, both low) so low-priority items do not
  ride with high-priority work; they share surface and are sequenced. AS-14 split into PLAN-05
  (additive scopes) and PLAN-09 (scope step-up). AS-5 + AS-6 bundled (PLAN-03, same session-stage
  surface). AS-14 "stage 2" (per-endpoint token narrowing) and AS-1 "stage 2" (visible_when, breaker
  state) deliberately not staged.
- 2026-09-15 — Split guard: PLAN-04 and PLAN-07 carry five deliverables each; both proceed unsplit —
  their parts (schema, resolution, dispatch, validation, tests/docs) cannot ship independently to any
  observable benefit.
- 2026-09-15 — Drafted PLAN-02 wrongly included the consumer's `app`-anchor filter revert and a consumer
  integration check as deliverables; removed. External dependencies (`cui-http` for AS-13, the token
  client engine for AS-14, the token-validation library for AS-7/AS-10) are verify-first clauses,
  never in-repo implementation; any dependency bump needs explicit user approval.

## Header-matcher defects, resolved by PLAN-18

> ↪ **RESOLVED 2026-09-24 by PLAN-18** (#346 / f6067588): names are normalised once at the route-compile
> seam with `toLowerCase(Locale.ROOT)`, `present: false` requires absence and composes under AND, and the
> boot validator now judges matchers exactly as the runtime evaluates them (ADR-0053, ADR-0054). Record:
> `landings/PLAN-18.md`. Was: names compared case-sensitively against a lower-cased request map, and
> `present: false` constrained nothing while the boot disjointness check treated it as distinguishing
> (source: inbox `kidicap-gateway-downstream-004.md`).

## Duplicate ADR-0053, resolved by #367

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

## Egress-allowance duplication, resolved by PLAN-17

> ↪ **RESOLVED 2026-09-23 by PLAN-17** (#345 / c1b09c7): the allowance is derived from the `jwks.url`
> host on absent/empty, and blank entries, `host:port` entries and a hostless `jwks.url` now abort
> startup with `CONFIG_INVALID`. Record: `landings/PLAN-17.md`. Was: `allowed_egress_hosts` duplicates the
> JWKS URL host — a blank entry boots and fails later, a `host:port` entry is silently inert (source:
> inbox `kidicap-gateway-downstream-003.md`).

## NO_PLAN sentinel fails the next gate, resolved in plan-marshall 0.1.1855

> ↪ **RESOLVED 2026-10-06 by plan-marshall 0.1.1855**: `corpus cross-check` now excludes the sentinel
> before counting (`excluded_sentinel_plan_count: 1`) and reports `candidate_comparison_determinate: true`
> for this epic, so the `next` gate no longer fails closed on it and no operator override is needed. Was:

- `orchestrator corpus cross-check` (plan-marshall 0.1.1728) counts `.plan/local/plans/NO_PLAN` — the
  plan-less operations sentinel (`status.json` `metadata.sentinel: true`, no `references.json`) — as a
  live plan with no comparable surface. That sets `candidate_comparison_determinate: false`
  (`live_plan indeterminate: 1`) for EVERY candidate, so the `next` gate fails closed permanently in this
  repository. Not a collision; a plugin defect (the live-plan scan should exclude sentinels). Until fixed,
  each emit needs a recorded operator override naming the sentinel as the sole indeterminate candidate.
  Plugin-side fix, outside this epic. — observed 2026-09-22 after PLAN-14's landing

## Landings and drains of PLAN-27, PLAN-28 and PLAN-30 (2026-10-13 to 2026-10-14)

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Decisions: PLAN-27, PLAN-28 and PLAN-30 shipped; their drains, folds and resolved defects are closed. The text below is the original, moved verbatim.

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

## Staging, emission, drains and landings of 2026-10-12 (PLAN-24, PLAN-25, PLAN-27, PLAN-28)

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Decisions: PLAN-24, PLAN-25, PLAN-27 and PLAN-28 shipped; drains 3 and 4 are archived. The text below is the original, moved verbatim.

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

## Cleanup passes of 2026-10-05 and 2026-10-06

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Decisions: two earlier cleanup passes, superseded by the 2026-10-26 pass. The text below is the original, moved verbatim.

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

## Aggregation into four plans, AS-13 and the CR/LF decision (2026-09-15)

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Decisions: PLAN-13 to PLAN-16 shipped; the cui-http change was released. The text below is the original, moved verbatim.

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

## PLAN-15 shipped (2026-09-17)

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Decisions: PLAN-15 shipped and reconciled. The text below is the original, moved verbatim.

- 2026-09-17 — **PLAN-15 shipped** as PR #320 / 93a4b3e (AS-3, AS-4, AS-8, AS-9, AS-11, AS-12; 11/11
  deliverables; 48h27m, 10.4M tokens). Landing record: `landings/PLAN-15.md`. The row's `pr` was stamped
  **#320**, not the `#310` the landing facts carried — `create-pr` recorded a PR that was later closed,
  and the merge was corroborated against `origin/main`.

## Drain 2 and the landings of PLAN-13 and PLAN-14 (2026-09-17 to 2026-09-22)

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Decisions: PLAN-13 and PLAN-14 shipped and reconciled. The text below is the original, moved verbatim.

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

## Verification-currency gaps on the PLAN-13 and PLAN-15 merges

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Open Defects: both plans shipped; the gap is a historical reading aid, not an open defect. The text below is the original, moved verbatim.

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

## cuioss-review-bot cannot be re-triggered, resolved by its removal

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Open Defects: resolved 2026-10-20: the bot was removed (#493, #494) and CodeRabbit is the only required reviewer. The text below is the original, moved verbatim.

- **cuioss-review-bot (PR-Agent) cannot be re-triggered anywhere in the organisation.** Its on-demand
  `/review` fails org-side: the GitHub App token cannot reach `cuioss/pr-agent-settings` ("repository does
  not exist or is not accessible to the parent installation"), observed on #350 and on another PR at
  19:22 on 2026-09-23. ⚠ This is the **mechanism behind the "merge head not freshly reviewed" note on five
  of the seven plans in this epic** — it is not per-plan bad luck. The fix is an org-level App-permission
  change, outside this repository and this epic. Until then, every PR's re-review after a fix push depends
  on CodeRabbit's quota alone. — source: `landings/PLAN-22.md`

## Breaking BFF change #377 from outside the epic

> ↪ **Relocated 2026-10-26** (cleanup at `54f82f03`) from `epic.md` § Open Defects: PLAN-24 shipped and PLAN-20, -21 and -26 were re-grounded against it on 2026-10-26. The text below is the original, moved verbatim.

- **A breaking BFF change landed from OUTSIDE this epic: #377 (e8db85bf) requires PAR and DPoP and
  defaults to `private_key_jwt`** — 91 files, +14052/−725, ADR-0058. Not an AS item and not staged by this
  epic, but it reshapes the login surface every WS-04 spec targets. Re-grounded against it 2026-10-02:
  PLAN-21's claims all survive (PAR pushes the authorization request before the pending record is stored,
  but the store-then-mint-cookie order its claims 4-5 rest on is unchanged), and PLAN-20's claims survive
  too. ⚠ Treat it as a live input for PLAN-24 and PLAN-26: a DPoP-bound token changes what "relay the
  token" and "revoke at the IdP" mean. — observed 2026-10-02 by the orchestrator
