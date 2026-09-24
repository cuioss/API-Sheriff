# Epic: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

slug: api-sheriff-0-2-0

> Ledger document for one epic under `.plan/local/orchestrator/api-sheriff-0-2-0/`. The layout and
> authority contract live in the central standard — see
> `persona-marshall-orchestrator/standards/orchestration-model.md`. `status.json` is the
> machine authority; any statement here that conflicts with it is stale prose. This document
> describes the epic's **current state** only — no history is retained.

## Vision

Take what 0.1.0 shipped and make it **honest, current and defensible** — without adding capability.
"Done" means: the framework-agnostic architecture question is settled rather than worked around; the
code reads as current Java; the documentation and ADR corpus say what is true, once; the
reconnaissance surface is closed; the BFF meets the FAPI 2.0 Security Profile; and the
`token-sheriff` integration runs through one trust mechanism rather than a process-global override.

**Scope**: consolidation and hardening only. Every net-new capability — the deception layer, the
inventory endpoint, the Helm chart — belongs to `api-sheriff-0-3-0`. The one deliberate exception is
stated in WS-03: the per-client detection substrate is *built* here because its consumer in 0.3.0
would otherwise force a retrofit.

## START HERE

## Ordered Queue

### Queue annotations

- **PLAN-V02-19** (staged 2026-09-24, inbox drain): **land before `PLAN-V02-04`.** V02-04 audits
  and renumbers `doc/adr/**`, so it must start from a corpus with unique ordinals, and V02-19's
  ordinal-uniqueness test then guards V02-04's own merges. Sequence it against `PLAN-V02-18` as well:
  V02-18 edits `CLAUDE.md`, and V02-19's module-list test reads it. Otherwise small and build-light,
  so it is a good fit for a second or third slot.

## Workstreams

| Workstream | Title | Plans |
|---|---|---|
| WS-01 | Architecture and Code Quality | PLAN-V02-01, PLAN-V02-02, PLAN-V02-14, PLAN-V02-16 |
| WS-02 | Documentation and Records | PLAN-V02-03, PLAN-V02-04, PLAN-V02-15, PLAN-V02-17, PLAN-V02-18 |
| WS-03 | Threat Hardening | PLAN-V02-05, PLAN-V02-06, PLAN-V02-07, PLAN-V02-13 |
| WS-04 | OIDC Client Conformance | PLAN-V02-08, PLAN-V02-12 |
| WS-05 | Integration and Test Fidelity | PLAN-V02-09, PLAN-V02-10, PLAN-V02-11 |

**PLAN-V02-12 through PLAN-V02-17 are NOT in the renumbering map below.** They were authored fresh
(V02-12 on 2026-08-04 from an operator-directed analysis; V02-13/14/15 on 2026-08-07 from the
open-issue triage; V02-16/17 on 2026-08-08 from the `api-sheriff-roadmap` close-out) and carry no
`api-sheriff-next` predecessor — so an in-body `PLAN-NN` reference inside them means what it says and
needs no translation.

## Renumbering Map — `api-sheriff-next` → this epic

The `api-sheriff-next` backlog was split by target version on **2026-08-04** and retired. Plan specs
were renumbered; **their in-body `PLAN-NN` references were deliberately NOT rewritten**, because many
point at `api-sheriff-roadmap` plans that keep their own numbers. Resolve any in-body reference
through this map first; if the number is not listed here, it belongs to `api-sheriff-roadmap` or to
`api-sheriff-0-3-0` and is unchanged.

| Was | Now | Plan |
|---|---|---|
| PLAN-38 | **PLAN-V02-01** | ADR-0005 reversal / Quarkus adoption |
| PLAN-39 | **PLAN-V02-02** | Java idiom sweep |
| PLAN-40 | **PLAN-V02-03** | Documentation restructure |
| PLAN-47 | **PLAN-V02-04** | ADR corpus cleanup |
| PLAN-17 | **PLAN-V02-05** | Response hygiene |
| PLAN-18 | **PLAN-V02-06** | Enumeration hardening |
| PLAN-19 | **PLAN-V02-07** | Threat classification |
| PLAN-49 | **PLAN-V02-08** | FAPI 2.0 conformance |
| PLAN-46 | **PLAN-V02-09** | Token-Sheriff integration fidelity |
| PLAN-48 | **PLAN-V02-10** | Per-client TLS trust |
| PLAN-44 | **PLAN-V02-11** | Resource-exhaustion test home |

Moved to `api-sheriff-0-3-0`: PLAN-20 → PLAN-V03-01, PLAN-21 → PLAN-V03-02, PLAN-22 → PLAN-V03-03,
PLAN-41 → PLAN-V03-04.

## Decisions

- **2026-08-04 — the backlog is split by target version.** `api-sheriff-next` was a single
  undifferentiated backlog; it is replaced by this epic and `api-sheriff-0-3-0`, and removed.
- **2026-08-04 — enumeration hardening lands here, not in 0.3.0.** Operator decision. The plan
  closes an existing status-code oracle, which is hardening. The recorded and accepted cost: the
  per-client detection substrate it builds ships a release *ahead* of its principal consumer
  (honeypot, `PLAN-V03-01`). Design the substrate general-purpose here — retrofitting it later is
  the more expensive order.
- **2026-08-04 — FAPI 2.0 conformance lands here.** Operator decision. It closes a gap against a
  security profile in an already-shipping flow, and it carries a breaking `oidc` configuration
  change that is better taken earlier than deferred.
- **2026-08-04 — workstreams re-cut from WS-01.** The old WS-05..WS-09 split across both new epics,
  so carrying the numbers would have left gaps in each and put WS-05 in both.
- **2026-08-08 — `parallelization_scope` = 3, `auto_emit` = true.** Operator decision at `decompose`,
  discharging the opening anchor's obligation (3). Both were previously unset at epic level while
  `.plan/marshal.json` carried `2` / `true`, so the epic was inheriting by accident — which is what
  the anchor warned against. **Carrier note**: `marshal.json` still reads `parallelization_scope: 2`
  and was deliberately **not** changed — `.plan/` config is outside the orchestrator's write boundary
  per Watch (31), and it is the *epic metadata* that `orchestrate.md` Step 4 reads. The `2` is a
  dormant project default nothing in this epic consumes. A `marshall-steward` run owns reconciling it.
- **2026-08-08 — the `PLAN-V02-07` → `PLAN-V02-06` merge is RETIRED**, superseding the 2026-07-27
  ruling and discharging obligation (2). Two facts absent when that ruling was taken changed the
  balance: `PLAN-V02-13` now re-categorises the `EventType`/`EventCategory` taxonomy V02-07 weights
  (so V02-07 must follow it, a dependency the substrate work does not need), and a merged spec would
  carry ~11 deliverables — far past the split guard. **The original intent is preserved by contract
  rather than by merger**: V02-06 gains a new deliverable 7, a written general-purpose substrate
  contract naming V02-07 as its first consumer. The substrate is still designed once. A substrate
  V02-07 must generalise on arrival is V02-06 D7 failing, and is a finding against V02-06.
- **2026-08-08 — the epic is re-grounded at `963e422`**, discharging obligation (1). All 17 specs
  carry a `## Re-Grounded` section that outranks their stale bodies. Per-plan carries now live in the
  specs, per the decompose contract, rather than being duplicated in the resume anchor.
- **2026-08-08 — the lessons corpus was audited and `PLAN-V02-17` re-clustered.** > ↪ Relocated to `settled.md` § "Decision 2026-08-08 — lessons corpus audit and PLAN-V02-17 re-clustering" — V02-17 shipped (PR #200); `logs/decision.log` remains authoritative

## Sequencing Constraints

Derived at the 2026-08-08 re-grounding. These bound every `next` emit; disjointness alone is not
sufficient.

| Chain | Order | Why |
|---|---|---|
| oidc / `BffRuntimeProducer` | **V02-08 → V02-12 → V02-09** | All three write the `oidc` block and the BFF assembly point. V02-08 first: it is the larger reshaping and settles the RFC 9207 `iss` constraint V02-12 inherits. |
| rejection taxonomy | **V02-13 → V02-06 → V02-07** | V02-13 re-cuts `EventCategory` and makes `renderProblem` content-negotiating at all five call sites. V02-06's uniform-404 branch edits those same sites; V02-07 weights the resulting taxonomy. |
| sample vs. model | **V02-12 → V02-15** | V02-15 D5 defers the browser-vs-container issuer address to V02-12 rather than letting a sample become the de-facto specification. |
| doc structure | **V02-03 before or after V02-17, chosen deliberately** | Both write `doc/development/**`. Landing prose into documents V02-03 is about to split wastes both. |
| ADR-authoring exclusion | **V02-04 runs against none of** V02-01, V02-06, V02-07, V02-11, V02-12, V02-13 | V02-04's spec forbids concurrency with any ADR-authoring plan. Also excluded: V02-09, whose ADR-0027 re-opening V02-04 is told not to pre-empt. |
| broad Java sweeps | **V02-01 and V02-02 never concurrent** | Both sweep `api-sheriff/src/main/java/**`. |
| runs alone | **V02-01** | Retiring an arch gate mid-flight changes the gate set every concurrent plan is verified against. |
| ~~trust wiring~~ | **EDGE REMOVED 2026-08-09** | V02-10's D1 moved into V02-09 as its D6, so the conditional now lives inside the plan that takes the decision. **V02-10 is reduced to the compose cleanup and is UNBLOCKED.** |

**Freest candidates, revised 2026-08-09 after four landings** (V02-16 and V02-17 have shipped):
**V02-10** (now a single-deliverable compose cleanup, unblocked by the redistribution below),
**V02-14**, **V02-05**, **V02-04**. **V02-13** is the head of the WS-03 chain and unblocks two plans
behind it, so it has the highest downstream value of any staged plan.

**Updated 2026-08-09 after the first two landings.** Three constraints in the table above are now
settled by events rather than pending:

- **`V02-01` is unblocked.** Its only live blocker was concurrency with `V02-02`, which has shipped.
  It is the epic's load-bearing plan and it **runs alone**, so emitting it consumes the whole board.
- **The doc-structure row is discharged.** `V02-03` landed first, so `V02-17` now writes into the
  settled `doc/development/**` layout rather than racing it — the deliberate choice the row demanded
  was made by the landing order.
- **`V02-13`'s `doc/plan/04-request-pipeline.adoc` reference is discharged** — that file is among the
  nine `V02-03` deleted. The directory survives (three deliberate keepers) and **must not be
  resurrected**.

**Second-order cost of a filled slot**: concurrent Docker IT suites contend for local CPU in a repo
with documented contention-driven IT startup flakes, and `main` is merge-queue-gated. Prefer a
doc-only or build-light plan for the second and third slots.

## Open Defects

14. **HIGH, SECURITY — OWNER: `PLAN-V02-11` (adopted 2026-08-09) — `MtlsHandshakeIT` fails 2/3 under `-Pjfr` only: FAIL-OPEN ON HANDSHAKE
    REJECTION.** GitHub issue **[#201](https://github.com/cuioss/API-Sheriff/issues/201)**, verified
    OPEN. Opened 2026-08-09 by `PLAN-V02-16` and **correctly not fixed there** — it sits outside that
    plan's declared boundary, and reporting rather than absorbing is the standing rule. The same tree
    passes 3/3 under `-Pintegration-tests`, so the defect is lane-conditional and was **invisible for
    as long as the `-Pjfr` lane was unrunnable**. **This one must not sit unowned**: it is fail-open
    behaviour on a security control. Candidate homes: `PLAN-V02-11` (which owns the *what are the
    test lanes for* question and now has a concrete instance to answer it with) or `PLAN-V02-10`
    (per-client TLS trust). **HOME DECIDED 2026-08-09: `PLAN-V02-11`.** Its question is *which lane owns which defect class*,
    and #201 is a defect **invisible in one lane and visible in another** — the first
    lane-conditional instance beside its two time-or-volume-conditional ones, and therefore new
    information for that decision rather than extra scope. **It is admissible as D2's demonstrated
    catch. Fixing the fail-open behaviour itself is a security fix belonging to whichever plan owns
    mTLS handshake behaviour — say explicitly which you did.**

10. **CARRIED PAST THIS EPIC — `doc/plan/09-release-readiness.adoc` becomes actionable AT THE 1.0
    CUT.** Opened 2026-08-09 by `PLAN-V02-03` D3. The file is **not superseded**: its defining
    deliverable is the 1.0.0 cut and the flip of the pre-1.0 rules, which has not happened — the
    project is 0.1.1 alpha and `CLAUDE.md` still carries the Pre-1.0 Rules. The outline had listed it
    for deletion; **verification refused, because an expectation is a prior, not a licence.**
    `01-base-implementation.adoc` likewise survives (no counterpart across all 41 landing records,
    enumerated rather than sampled) and needs no action. **This entry must survive into the successor
    epic** — 0.2.0 cannot close it, and closing it here would silently convert "not yet due" into
    "done".
11. **LOW, traceability — the deleted `doc/archive/others/excluded.adoc` traces to nothing live.**
    Opened 2026-08-09 by `PLAN-V02-03` D1. Its content (Apiman, WSO2 and other
    considered-but-not-evaluated gateways) has no live design document behind it —
    `doc/features-analysis.adoc` distils only the six *evaluated* gateways. Zero inbound refs and git
    history preserves it, so the deletion stood, but **`doc/README.adoc`'s "fully adapted" claim
    over-reaches for that one file.** Either narrow the claim or restore the content somewhere live.
12. **MEDIUM, dead allocation — OWNER: `PLAN-V02-01` (conditional) — `RouteRuntimeAssembler` allocates a per-tuple `HttpClient` and a
    resilience `Guard` for `WEBSOCKET` routes that no longer read them.** Opened 2026-08-09 by
    `PLAN-V02-02`, **reported rather than swept** exactly as the standing rule requires. Collapsing
    it is a behavioural change to boot-time allocation plus a nullability-contract change reaching
    `RouteRuntime` and `DispatchStage` — a design task, not a sweep edit, so it needs a home rather
    than a follow-up commit.
13. **LOW — CLOSED AS AN ORPHAN 2026-08-09: FOLDED INTO `PLAN-V02-09`.** `TokenValidatorProducer.applyJwks` qualifies for a switch conversion but sat outside
    `PLAN-V02-02` D5's declared surface and was correctly left alone. **Natural home: `PLAN-V02-09`**,
    which already owns that file. Fold it there rather than carrying it as standalone work.

1. **MEDIUM — upstream stack-identity leak.** `edge/ResponseStage` `isForwardableResponseHeader`
   (:63) filters only hop-by-hop and conditional headers, so an upstream emitting `Server` or
   `X-Powered-By` leaks its stack identity through the gateway. The orchestrator recommended fixing
   it inside 0.1.0; the operator ruled it out of the release track 2026-07-27. **0.1.0 and 0.1.1 both
   ship with this leak.** OWNER: `PLAN-V02-05` D1. *Re-grounded 2026-08-08: still present, and the
   fix must cover **both** relay paths — `relay`:84 and `relayWithTrailers`:118.*
2. **MEDIUM, developer trap.** `demo-client/playwright.config.js` routes three variables through
   `required()` at module scope while `demo-client/pom.xml` supplies them only for the Maven
   execution, so the copy-pasteable `cd demo-client && npm run test` aborts at config load. The
   README was fixed to export them first, but `start-dev-environment.sh`:289 still prints that exact
   command. **Keep the generalizable check**: for any doc offering run-it-directly beside
   run-it-through-the-build, enumerate what the build supplies that the direct path does not —
   environment, working directory, classpath.
3. **MEDIUM — divergent readiness contract.** `demo-client/scripts/start-dev-environment.sh` gates on
   `/q/health/live` with an unmeasured 30-attempt budget while `start-integration-container.sh` gates
   on readiness with a measured one (PLAN-42 D2 proved the live→ready delta is 0.00s across six
   instances under contention). Deliberate scoping at the time and disclosed in-tree, but it is the
   one place the recorded readiness contract is not honoured. Route with (2) — same file.
4. **LOW, not release-gating** — the test-corpus integrity backlog PLAN-43 left as a countable
   residual: 43 files / 140 marker occurrences, enumerated in
   `doc/development/test-corpus-integrity.adoc`. Reported as a count rather than as prose.
5. > ↪ Relocated to `settled.md` § "Open Defect 5 — BFF secrets missing from the env-var page (closed 2026-08-09)" — closed by `PLAN-V02-03` D2 (PR #197, `89a3cfe`)
6. **STRUCTURAL** — the IT suite cannot detect resource-lifecycle or layer-boundary defect classes.
   Its stated owner was roadmap PLAN-34, **which shipped**, so it arrived here orphaned rather than
   fixed. Not strikeable on sight: it carries independently CI-observed defects and a deferral
   transmitted to a public PR thread. **Re-verify against the landed PLAN-42 work before striking** —
   PLAN-34's D4/D5 left in the 2026-08-02 three-way split and PLAN-42 shipped both, so the gap may be
   narrower than this entry says. Read the landed code, not the split decision. Home: `PLAN-V02-11`.
7. **STRUCTURAL, an open operator decision never taken** — the k6 benchmark scripts are an unguarded
   consumer of gateway posture. `docker-compose.benchmark.yml` overlays the base compose and mounts
   the same `./src/main/docker/sheriff-config`, so the benchmark and the IT suite share one
   `gateway.yaml`. A plan changing gateway behaviour updates the IT suite in lockstep, but nothing
   requires it to update the k6 scripts. **The general shape, and it will recur**: a behavioural
   default flip is the class where "we updated the tests" hides "we did not update the other
   consumers". Enumerate the consumers of a default, not just its tests. Home: `PLAN-V02-11`.
8. **Unverified Sonar claim** — PLAN-06's uncorroborated Sonar claim is still unverified with no
   recovered evidence, and needs an **operator live-gate check** rather than plan work. Carried
   rather than closed because "we could not recover the evidence" is not "the claim was false", and
   closing it would silently convert one into the other.
9. **MEDIUM** — `K6BenchmarkLogMessages` sits outside the catalogue guard that shipped beside it.
   PLAN-08A (#154) added `LogMessagesCatalogueTest`, but it anchors on
   `ApiSheriffLogMessages.class.getProtectionDomain()` and therefore walks `api-sheriff/target/classes`
   **only** — so the benchmarks module's catalogue, including PLAN-46's `K6Benchmark-211`, is not
   machine-protected against a duplicate id. **The generalisable shape: a guard anchored on one
   module's protection domain silently excludes every other module, and its green is
   indistinguishable from coverage.** When adopting it, check whether the fix generalises to every
   module carrying a catalogue rather than special-casing benchmarks. Home: `PLAN-V02-11`.

## Watches

- **INHERITED TOOLING AND PROJECT-CONFIG CONSTRAINTS — not fixable by any plan here, and every plan
  in this epic runs under them.** Carried as one watch rather than five duplicated entries so the
  copies cannot drift; full text and evidence live in the `api-sheriff-roadmap` ledger under the
  numbers given.
  - **(31) HIGH, the dangerous one**: `q_gate_validation: once` routes Q-Gate findings to a review
    gate that `plan_without_asking: true` **disables**, so BLOCKING findings reach task planning
    ungated and nothing reports it. The pairing occurs twice — `phase-3-outline` and `phase-4-plan`.
    On PLAN-27 it let through a hardening block that would not have started and a bucket
    misassignment that would have stripped the verify lane, both caught only by manual operator
    intervention. The two settings are individually reasonable and **jointly unsound**. OWNER: a
    `marshall-steward` run — `.plan/` config is outside every plan's write boundary **and the
    orchestrator's**.
  - **(22) HIGH**: no plan runs the Docker IT suite pre-push, so IT feedback comes only from CI after
    push. `verification_steps` holds quality-gate, module-tests and coverage only, and the root
    resolve fails. **Do not work around it via `per_deliverable_build`**: the config layer accepts
    the entry but the standard forbids the placement and a non-zero exit is a hard STOP, so a
    deliverable touching only `api-sheriff` would halt the plan. Attempted and reverted 2026-07-30.
  - **(25)**: `documentation.skills_by_profile.module_testing` is empty, so a task legitimately
    domained documentation under module_testing resolves the persona floor alone — no doc testing
    standards, no verification recipes — and nothing reports it.
  - **(30)**: the deep-lane component-assessment sink is empty (a two-plan recurrence), so section
    2.2 assessment-coverage is unevaluable on any deep-lane plan; an empty sink is indistinguishable
    from "the pass ran and matched nothing". **Third recurrence, 2026-09-24** (inbox
    `deployment-configurability-007.md`, from sibling PLAN-28 / PR #341): `assessment list` again
    returned `total_count: 0` on a deep-lane run, so the Q-Gate evaluated 35 declared paths over an
    empty population. The same run hit two more instances of the same shape:
    `derive_gate_bundles` left 19/37/52/56 footprint paths unbundled, and no module-tests canonical
    resolved. Folded here as a recurrence, not as a new entry, and still owned upstream.
  - **(16)+(20) — PARTLY DISCHARGED 2026-08-09, and the correction matters.** The *mechanism* half is
    confirmed by exhausting the surface: `ci checks status` accepts only `--pr-number` / `--head`,
    `--head` resolves as a **branch name** rather than a SHA, `checks wait` takes only
    `--pr-number`, and `checks rerun` / `checks logs` take a `--run-id` that **no verb in the
    abstraction can discover from a commit**. `ci repo` offers only merge-queue and label. The
    by-merge-commit lookup is genuinely unreachable through the abstraction.
    **What was WRONG is the conclusion drawn from it.** "No sanctioned execution path" was read as
    "unverifiable", and the axis then went unchecked across four merge commits. It is verifiable in
    one read-only `gh` call, and on operator instruction it was done: **all four are green** — see
    § Post-Merge Verification. The standing rule is corrected: a landing report records the
    post-merge check as OWED **until it is checked**, not indefinitely, and the check is an
    operator-authorised `gh` read rather than a tooling change.

  All five are delivered upstream as bundle material. **Re-check by executing the mechanism, never by
  reading a version number.**

- **STANDING CLAUSES, still binding**: three-layer docs in the same PR; Sonar zero-findings with red
  a HARD STOP; **named line items survive outlining** (this project has twice had named deliverables
  abstracted away); activate-it-in-an-IT (lesson `2026-07-25-15-001`, which already recurred verbatim
  once); **the plan stops at the merge** and the post-merge check is the orchestrator's; every emit
  carries an explicit `plan_id`; mark a claim OBSERVED only with a read behind it; **a green suite is
  not evidence for a behavioural claim** until its assertions are read.
- **A capability present in a dependency is not a capability of the product.** Earned on the FAPI
  claim: `doc/features-analysis.adoc` asserted a PAR-driven sender-constrained flow because the
  engine shipped `ParClient` and `SenderConstraint` while the gateway wired neither. The
  native-image reflection registration is what made it look evidenced — it lists the dependency's
  whole DTO surface, so grepping for a class name finds a feature that is never invoked. **Settle
  every completion claim at the call site, never at the import.** *Re-confirmed 2026-08-08: the only
  `ParResponse` mentions in the tree are still reflection registrations.*
- **In-body `PLAN-NN` references in these specs were deliberately not rewritten** at the 2026-08-04
  renumbering, because many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve
  through the renumbering map above; a number absent from that map belongs to another epic and is
  unchanged. Each spec's `## Re-Grounded` section now translates the ones that matter to it.
- **A spec's asserted surface absence can be overturned by its own outline**, so a disjointness check
  against a RUNNING plan reads that plan's **current outline**, never its staged spec.
- **A `/marshall-steward` RUN IS NOW OWED BY TWO INDEPENDENT ROUTES.** Added 2026-08-09. Beside
  Watch (31)'s config pairing, `PLAN-V02-03` D3 left the architecture inventory's project description
  reading *"…follow the plans under `doc/plan/`"*, which is stale now that nine of those twelve files
  are deleted. It is **steward-regenerated, not hand-editable**, so the plan flagged it rather than
  patching it — the correct call. Two unrelated findings now point at the same un-run mechanism.
- **A SPEC-ASSERTED COUNT IS A CLAIM, NOT A MEASUREMENT — and the orchestrator is the author who owes
  the re-derivation.** Earned 2026-08-09 on `PLAN-V02-02` D3: a headline occurrence count propagated
  spec → clarified request → outline unchallenged, arriving at implementation as three
  mutually-corroborating statements of one unverified number. **The contested instance is itself the
  second data point** — the plan measured 20, this epic's own spec table sums to 19, and a
  re-measurement at `e343404` returns 19. Two parties each derived a confident total from the same
  document and disagreed. Re-derive every count in a staged spec from its own breakdown before the
  spec is emitted; the check needs no codebase access.
- **EVERY SPEC SENTENCE ASSERTING CURRENT REPOSITORY STATE IS A MEASUREMENT WITH A TIMESTAMP — the
  consuming phase re-establishes it rather than inheriting it.** Earned 2026-08-09 on `PLAN-V02-17`,
  and it completes a three-instance pattern in four landings, all in specs the orchestrator authored:

  | Plan | The defect | Found by |
  |---|---|---|
  | `V02-02` | an occurrence count **asserted, not derived** | measuring during the run |
  | `V02-16` | an Expected Surface **accurate but insufficient** — named the consumer, not the channel | attempting to implement it |
  | `V02-17` | a premise about current repo state **stale and false** | running the gate |

  The V02-17 case is the sharpest because the orchestrator supplied the reasoning: resolving the
  formatter contradiction it asserted that *"bringing `main` to the fixed point is the durable fix
  nobody has done"*. **A full-reactor gate run produced zero rewrites — the repository is already at
  the fixed point.** The verdict (*revert unrelated churn*) survived; the reason did not, and the
  shipped page carries a better one: the fixed point is a property of the tree at a point in time,
  not a guarantee. **The common shape is structural, not carelessness** — a spec asserts facts at
  authoring time and executes later, and nothing in between re-measures unless someone does.
- **AN EXPECTED SURFACE MUST BE CHECKED FOR SUFFICIENCY, NOT ONLY ACCURACY — and this one is the
  orchestrator's own.** Earned 2026-08-09 on `PLAN-V02-16`. Its spec named `Dockerfile.native` +
  `.github/workflows/release.yml` for the revision-label deliverable. Every claim in that surface was
  **accurate**, and the surface was **not implementable**: `release.yml` performs no `docker build`
  and forbids adding one, so the only channel into the image is `integration-tests/docker-compose.yml`'s
  `build.args` — a file the spec never named. **Implementing the literal surface would have shipped an
  empty `revision` label on every published image**, a new falsehood inside the plan whose objective
  was to stop the image lying. At the 2026-08-08 re-grounding the orchestrator read this spec and
  recorded *"every claim holds; no correction is owed"* — verifying the claims made, never asking
  whether the surface reached the deliverable. **The distinction is consumer vs. channel**: naming the
  consumer reads like naming the surface and does not build. Trace the value from producer to
  artifact and name every hop. Sixth instance of the enumerate-every-carrier rule, and the first
  inside a spec the orchestrator wrote itself.
- **A TEST LANE NOBODY CAN RUN IS NOT A PASSING LANE, IT IS AN UNMEASURED ONE.** Earned 2026-08-09:
  the `-Pjfr` lane could never start (root-owned bind mount vs. a uid-1001 container), so its green
  was the **absence of execution**. Repairing it surfaced Open Defect (14) — a fail-open handshake
  rejection — within the same run. Where a lane is broken, prefer fixing it to scoping around it; the
  cost is bounded and the yield is whatever the lane has silently not been checking.
- **ASSERTED ABSENCES NEED A CONTROL QUERY — three known false-negative mechanisms, not one.**
  `architecture search --content` returns a clean `count: 0` over compose/infra YAML and
  `src/main/resources`; `git grep <pattern> origin/main -- 'path/**'` returns a clean zero for every
  query in this tree while the same search without the pathspec returns hits; and — **added
  2026-08-08, found during this epic's own re-grounding** — an **unquoted `--include=*` in `grep -r`
  is shell-expanded into a filename list**, silently dropping real hits. That third one is a
  different mechanism from the other two (shell expansion, not tool behaviour), so knowing the first
  two does not predict it. **Run a control query before trusting any asserted absence.**

## Inbox Drain — 2026-09-24 (sender `deployment-configurability`, closing hand-off)

20 messages, all from the sibling epic `deployment-configurability` as it closed: 2 findings and 18
candidate lessons. Each one was verified against `origin/main` at `05f6ee3` before being
dispositioned, and each has a matching `decision.log` line.

**The headline finding was already stale, and in a worse way than it said.** Message `-001` reported
a duplicate ADR ordinal `0050`. PR #348 (the sibling's PLAN-29) did clear it, by renaming the portal
ADR to `0053`. But PR #346 claimed `0053` for the header-matcher ADR concurrently and merged
**afterwards**, so `main`, and released `0.2.3`, carry a new duplicate, `0053`. The ordinal space has
now collided twice in one week, across two epics, with nothing to catch it. That is why
`PLAN-V02-19` adds a uniqueness contract test as well as the renumber.

| Message | Kind | Disposition | Where it went |
|---|---|---|---|
| `-001` | finding | staged | `PLAN-V02-19` D1+D2. The 0050 claim is refuted as stale; the live 0053 duplicate is fixed and guarded. |
| `-002` | candidate-lesson | promoted | `2026-09-24-15-001`. The stale-image half is fixed (#341); the open half is the local `revision=dev` label, which cannot discriminate images. |
| `-003` | candidate-lesson | folded | `PLAN-V02-19` D3, the module-list contract test. This is open, unowned work rather than a lesson. |
| `-004` | candidate-lesson | discarded | Fixed in PR #314 and mechanically guarded by `TokenClientDslJsonReflectionTest.shouldRegisterEveryEngineDslJsonConverter` (verified on `main`). |
| `-005` | candidate-lesson | promoted | `2026-09-24-15-002` |
| `-006` | candidate-lesson | promoted | `2026-09-24-15-003` |
| `-007` | candidate-lesson | folded | Watch (30), recorded as its third recurrence (tooling, owned upstream). |
| `-008` | candidate-lesson | promoted | `2026-09-24-15-004`, the closed-set-restated-at-N-sites lesson. It also carries `-013` and `-016`. |
| `-009` | candidate-lesson | promoted | `2026-09-24-15-005` |
| `-010` | candidate-lesson | promoted | `2026-09-24-15-006` |
| `-011` | candidate-lesson | promoted | `2026-09-24-15-007` |
| `-012` | candidate-lesson | promoted | `2026-09-24-15-008` |
| `-013` | candidate-lesson | folded | Into `2026-09-24-15-004`, as a recurrence of the same shape in the same plan. |
| `-014` | candidate-lesson | promoted | `2026-09-24-15-009` |
| `-015` | candidate-lesson | promoted | `2026-09-24-15-010` |
| `-016` | candidate-lesson | folded | Into `2026-09-24-15-004`. Its `review_body` half is already binding via `CLAUDE.md` Git Workflow steps 6 and 8. |
| `-017` | candidate-lesson | promoted | `2026-09-24-15-011` |
| `-018` | candidate-lesson | promoted | `2026-09-24-15-012` |
| `-019` | candidate-lesson | promoted | `2026-09-24-15-013`, quantified evidence for any revisit of `re_review_on_loopback: false`. |
| `-020` | finding | folded | `PLAN-V02-19` D4 (Sonar `java:S3398`). The claim is verified: the method is called only from nested `HttpUpstreamFetcher`. |

All 13 promotions were filed with `--allow-foreign-store`. The store's bundle-ownership guard does
not recognise project-local component names (`api-sheriff`, `project:…`, `integration-tests`) as
belonging to this repo, but these lessons are about this repository and lived in this same store
before the sibling epic closed. Bodies are carried verbatim from the payloads, each with a
provenance section.

## Post-Merge Verification — `deploy-snapshot`

> ↪ Relocated to `settled.md` § "Post-Merge Verification — `deploy-snapshot` (four V02 landings, 2026-08-09)" — all four merge commits verified green; the standing gh-run method for the next landing is carried there verbatim

## Release Pre-Flight Knowledge for the 0.2.0 Cut

Harvested from the predecessor epic, which cut **both** 0.1.0 and 0.1.1. **This is the only place
this knowledge exists.** These are instance-specific cautions; the process itself lives in
`.claude/skills/release/`.

- **Re-run the Trivy gate immediately before the cut.** A green is worth **days, not weeks** — it was
  RED on 2026-08-04 and green on the 5th only because a Dependabot base bump landed between. The DB
  snapshot carries a `NextUpdate` ~24h out: cut that day, or re-run.
- **Rebuild the scan target from the tree under test.** A stale image's green looks **identical** to
  a real one — the 0.1.0 rehearsal's first target predated its code by hours, and at 0.1.1 a
  tag-keyed reuse would have scanned an image built *before* the commit under test. Scan by
  content-derived image ID, as the workflow does.
- **The Trivy gate runs AFTER Maven Central publication.** `publish-image` is `needs: [release]`, so
  jars are irrevocably on Central before the scan or the signature happens. **That ordering is why
  the pre-cut dry run exists, and it is not optional.**
- **Assert GHCR `visibility == 'public'` via `gh api`, never by pulling as an org member.** The 0.1.0
  cut landed on INTERNAL first, which keeps anonymous pulls broken while every member check passes.
  At 0.1.1 the release skill checked **anonymously** throughout (no `ghcr.io` credential) — that is
  the standing method, and authenticating the verification would reintroduce the blind spot.
- **Never pass `--delete-branch` on a queue-gated merge.** It closed PR #167 **unmerged** while
  returning `merged: true`. Verify via the GitHub API and `mergeQueue.entries`, never the tool's
  return.
- **The merge of the version bump IS the release.** `.github/project.yml` `current-version` is the
  trigger; editing that file is by construction a release trigger.
- **The guard is proven in both directions but NOT isolated.** It fired correctly at 0.1.1 (run
  31157727796) and refused correctly at PLAN-51 (run 31256991225) — **but the refusal observation
  cannot distinguish a working guard from one stuck on `unchanged` OR stuck on `changed`**, because
  `current-version` was tagged and both refusal predicates were simultaneously true. **Still owed**:
  read the pinned reusable workflow's checkout depth. That, not another control run, settles the
  mechanism.

## Standing Rules Inherited from the `api-sheriff-roadmap` Close-Out

Five corpus lessons were dispositioned into this ledger because they govern **how this epic is run**
rather than how the code is built. Verbatim copies live at
`.plan/local/archived-orchestrators/api-sheriff-roadmap/archive/lessons-consumed/{id}.md`.

1. **`2026-08-02-15-002` — BUILD SUCCESS IS NOT EVIDENCE THAT WORK HAPPENED.** Assert positive
   evidence, never the exit code. This is the single most-recurring shape of the predecessor epic:
   a clean result nobody computed, nine separate instances in one bundle round.
2. **`2026-08-05-05-001` — for a path-triggered check-run, absence of a failure is not a pass.** Only
   presence-and-success **on the commit that changed the file** is.
3. **`2026-08-06-08-002` — a fix to a duplicated value is not done until every carrier is enumerated
   mechanically.** Learned when a Cosign identity change hit two of three carriers; recurred within
   hours of being promoted (a four-carrier config value), and again at PLAN-50 where a spec's own
   carrier list was stale and a published SVG was missed. **Enumerate, never recall.** *This epic's
   own re-grounding found four more instances — see the decompose decision log.*
4. **`2026-08-08-11-001` — a review-bot fix can be reverted by the project's own quality gate.**
   OpenRewrite `SimplifyTestThrows` re-widens narrowed test `throws` clauses **and exits 0 while
   rewriting the file**, so an accepted bot suggestion re-dirties the tree on every future gate run.
   **A bot suggestion is not verified by implementing it, only by surviving the gate.**
   *Re-verified 2026-08-08: `JUnit5BestPractices` is in the `pre-commit` recipe list (`pom.xml`:436)
   and `BuildGateCoverageContractTest`:142/:171 still read `throws Exception` — the gate won and the
   narrowing did not survive.* **This rule is one of three faces of a single mechanism** — the other
   two are `2026-08-02-17-001` (non-idempotent import churn, which also poisons the change-ledger
   freshness stamp) and `2026-07-27-09-001` (the formatter is non-idempotent and `main` is not its
   fixed point). `PLAN-V02-17` D2 lands all three as one class, and a post-gate
   `git status --porcelain` assertion is the check that covers them.
5. **`2026-08-04-05-001` — out-of-scope Sonar findings on a bounded-write-boundary plan are reported,
   not absorbed.**

## Deferred Item — the release dry run is NOT a queue item

**Operator ruling 2026-08-05**: the release dry-run standardisation runs as a **direct Claude Code
session**, not a plan-marshall plan. The brief is `release-dry-run-standardisation-claude-brief.md`
in this tree. A WS-06 workstream and a `PLAN-V02-13` spec were staged and then **removed** when the
ruling landed — do not be surprised by the gap, and **do not re-add them** (the `PLAN-V02-13` number
was subsequently reused for terminal-rejection-contract).

**Its hard gate has opened**, and the thing it gated on moved further than the gate anticipated.
Roadmap PLAN-48 shipped (#171 / `b5ce4b6`) and the release trigger is re-armed — but its D4 did not
ship with it: the release-skill work went to PLAN-50, and PR #179 (`9a8dc3c`) then **rewrote**
`.claude/skills/release/SKILL.md` substantially (a two-path release, Step 1 restructured so the
safety work precedes the merge, Step 3(i) replaced, the frontmatter description rewritten), after
PR #169 had already renumbered its steps. **So the brief's own instruction — re-read `release.yml`
and the skill on `main` and RE-DERIVE rather than patch — is now load-bearing rather than cautious.**
PLAN-50 has since landed, so the Cosign-identity collision that deferred this behind the 0.1.1 cut is
cleared.

**The form decision is taken and argued in the brief**: a dry-run **mode** on the existing `/release`
skill, not a sibling skill, because a dry run is worth exactly its fidelity to the real steps and a
second document drifts — three first-party drift defects in this same lane on one day are the
evidence. Overturnable only on evidence; record the rationale either way.
