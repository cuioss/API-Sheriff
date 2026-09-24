# PLAN-V02-11: where does resource-exhaustion detection belong — the IT suite or the benchmark lane?

epic: api-sheriff-0-2-0
workstream: WS-05
track: **POST-0.1.0** — gated behind the release cut (PLAN-08B in `api-sheriff-roadmap`).

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Deferred out of `api-sheriff-roadmap` PLAN-34 on 2026-08-02 by operator decision. That spec always
> named it a deferral candidate: *"a design decision with no forcing deadline."*
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-44-resource-exhaustion-test-home.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**HYPOTHESIS CONFIRMED — the benchmark lane is NOT gating, and now it is a fact rather than a
suspicion.** `.github/workflows/benchmark.yml` triggers on `pull_request: branches:[main],
types:[closed]` with the job guarded by `github.event.pull_request.merged == true`, plus a
`workflow_dispatch`. It runs **after** the merge and cannot block it. **Consequence for D1, stated
as the spec asked**: the "benchmark lane" branch carries *making that lane gate* as a first-class
cost, not an afterthought — and making a post-merge-triggered workflow into a merge gate is a
structural change to the release lane, not a flag flip. Weigh it as such.

**CONFIRMED — the lane is green and the old objection is dead.** `benchmarks/pom.xml` still defaults
both formerly quarantined goals to run: `skip.benchmark.upload.large=false` (:39) and
`skip.benchmark.websocket.echo=false` (:49). The k6 goal count is still **12**
(`run-k6-*-benchmark` executions). The spec's instruction to re-verify rather than inherit the
objection is discharged in this plan's favour.

**ADR numbering** — the corpus is contiguous `0001`–`0037`, so **`0038` is next free and genuinely
free** (roadmap PLAN-50 landed `0035`). D1's caution about two concurrent plans both taking `0025`
still applies: **claim at write time, checked against `main` and every open branch.** Note that
V02-01, V02-06, V02-07, V02-12 and V02-13 also author ADRs in this epic.

**OPEN DEFECTS THIS PLAN IS THE NATURAL HOME FOR** — the epic ledger routes three here, none of
which this spec currently mentions. Read them at outline and say explicitly which you adopt and which
you decline:
- **(6)** the IT suite cannot detect resource-lifecycle or layer-boundary defect classes — this is
  Open Defect (4) in this spec's own wording, re-homed. **Re-verify it against the landed PLAN-42
  work before treating it as open**; the gap may be narrower than the entry says.
- **(7)** the k6 scripts are an unguarded consumer of gateway posture — the benchmark and IT suite
  share one `gateway.yaml` via a compose overlay, and nothing requires a behavioural change to update
  the k6 side. Directly adjacent to D1's "what are the test lanes for" question.
- **(9)** `K6BenchmarkLogMessages` sits outside `LogMessagesCatalogueTest`, which anchors on
  `ApiSheriffLogMessages.class.getProtectionDomain()` and therefore walks `api-sheriff/target/classes`
  only. A guard anchored on one module's protection domain silently excludes every other module.

**RENUMBERING.** "Prefer after PLAN-39" means **PLAN-V02-02** (java-idiom-sweep) — still binding,
it touches the test corpus broadly. "PLAN-32", "PLAN-33", "PLAN-42", "PLAN-43", "PLAN-34" are landed
`api-sheriff-roadmap` work.

**GATE COST.** `.github/workflows/**` is now gate-requiring (`build.map`, roadmap PLAN-51 / #196), so
if D1 selects the benchmark lane, D2's workflow edit pays a full quality gate.


## Re-Grounded (2) 2026-08-09 at `95dd566` — after four landings

`PLAN-V02-02`, `-03`, `-16` and `-17` have shipped. **This section outranks the 2026-08-08
re-grounding above it wherever they conflict.**

**EPIC-WIDE, AND NO SPEC BELOW KNOWS IT: THE BUILD NOW FAILS ON ANY COMPILER WARNING.**
`PLAN-V02-02` turned on `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>` reactor-wide (`pom.xml`:163, :178), so javac runs with
`-Werror` across all six modules. A deprecated API or an unchecked cast is now a **build failure**,
not a log line. Two consequences bind every plan:

1. **Answer such a failure by migrating off the warned construct.** `CLAUDE.md` states it directly:
   a `@SuppressWarnings` added to get back to green *"hollows the gate out while leaving it reporting
   success"*, and it collides with the Pre-1.0 rule forbidding deprecated code at all.
2. **The failure reaches the executor as a `warnings[]` row plus a `-Werror` `errors[]` row.** Read
   both arrays — the line number lives on the warning row.

**ANCHORS HELD** at `95dd566`: `benchmark.yml` is still `pull_request: types:[closed]` gated on
`merged == true` — **post-merge, not gating** — and both formerly quarantined k6 goals still default
to running.

**OPEN DEFECT (14) IS ADOPTED INTO THIS PLAN — decided 2026-08-09, and it sharpens D1 rather than
widening it.** GitHub issue **[#201](https://github.com/cuioss/API-Sheriff/issues/201)**:
`MtlsHandshakeIT` fails **2/3 under `-Pjfr` only — fail-open on handshake rejection** — while the
identical tree passes 3/3 under `-Pintegration-tests`.

**Why it belongs here and not in a TLS plan.** This plan's question is *which lane owns which defect
class*. #201 is a defect that is **invisible in one lane and visible in another**, and it existed
undetected for as long as the `-Pjfr` lane was unrunnable — its green was the **absence of
execution**. That is a third shipped instance of the defect class D1 is weighing homes for, and it is
the first one that is **lane-conditional rather than time-or-volume-conditional**, which is new
information for the decision.

**It does not become a fix deliverable.** D1 weighs homes; D2 implements the answer and must
demonstrate a catch. **#201 is admissible as D2's demonstrated catch** — a mechanism that would not
have surfaced it is a weak answer — but fixing the fail-open behaviour itself is a security fix that
belongs to whichever plan owns mTLS handshake behaviour. **Say explicitly which you did.**

**Third evidence item for D1, now available:** the two shipped instances the spec names (`218b5c`
WebSocket permit leak, the body-cap derivation defect) plus #201.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

All claims hold. Both benchmark skip flags are still `false`, and `benchmark.yml` is still post-merge only. No ADR or soak test exists yet, so D1 and D2 are open.

**ADR numbering, corrected across the corpus:** `doc/adr/` now holds 55 records. `0053` is DUPLICATED (#348 renamed the portal ADR `0050`→`0053` while #346 claimed `0053` concurrently), and the next free ordinal is `0055`. Every earlier "next free is 0038/0050" line in this spec is stale. Re-derive the ordinal at write time, and prefer landing after `PLAN-V02-19`, which fixes the duplicate and adds an ordinal-uniqueness test.

## Objective

**Answer one design question, and implement the answer.** Is a soak-shaped integration test — sustained
traffic, then a health probe — the right home for resource-exhaustion detection, or does that belong to
the benchmark lane?

The question was raised explicitly as *a legitimate design question, not a foregone conclusion*, and it
is deferred here rather than answered in passing because **the answer changes what the release track's
test strategy is for**, and because answering it well needs evidence that only existed after the
PLAN-32 and PLAN-33 fixes landed.

## Why it is a plan and not a note

Epic Open Defect (4) records the underlying gap: **the IT suite cannot detect resource-lifecycle or
layer-boundary defect classes.** Two shipped defects prove it — `218b5c`, the unauthenticated WebSocket
admission-permit leak (any upgrade leaked a permit until the gateway 503'd all traffic until restart),
and the body-cap derivation defect. Neither was caught by a functional IT, because a functional IT
makes one request and asserts one response. **A leak is only visible over time or over volume.**

So the question is not academic: today **no lane owns this defect class**, and both instances were
found by inspection rather than by a gate.

## Deliverables

**Two deliverables.** Deliberately small — this is a decision plus its consequence.

1. **NAMED LINE ITEM — the decision, recorded as an ADR.**
   Evaluate both homes against the two shipped instances and record the verdict with its reasoning:
   - **Soak-shaped IT**: runs in the existing containerised suite, gates every PR, but lengthens the IT
     lane and needs a sustained-traffic harness the suite does not have.
   - **Benchmark lane**: already generates sustained traffic and already has the harness — but
     **the consequence must be stated plainly: if the answer is "the benchmark lane", then the
     benchmark lane must be green AND gating.** A detection mechanism in a lane that does not block a
     merge detects nothing.

   **Take the next free ADR number, and claim it at write time.** Verify against `main` **and every
   open branch** — two ADRs numbered 0025 shipped from concurrent plans in the release epic on
   2026-08-01 precisely because a number was chosen from `main` alone and nothing re-checked it.

2. **NAMED LINE ITEM — implement the decision, or record why it is not implementable yet.**
   Whichever home wins, deliver the mechanism that detects one of the two known instances, and prove it
   detects it — **a mechanism that would not have caught `218b5c` is not an answer to this question.**
   Reproducing the leak against a fixed gateway is acceptable evidence (revert-and-detect in a test
   fixture); shipping a detector with no demonstrated catch is not.

   If the honest outcome is "not implementable without X", say so and name X. **"Decided but not
   implemented" is an acceptable outcome; "implemented but never demonstrated to catch anything" is
   not** — that would reproduce Open Defect (4) in a new lane.

## Claim Labels

- **OBSERVED** — Epic Open Defect (4): the IT suite cannot detect resource-lifecycle or layer-boundary
  defect classes. Two shipped instances (`218b5c` WebSocket permit leak, fixed by PLAN-32 `#126`; the
  body-cap derivation defect, fixed by PLAN-33 `#131 #134`).
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: #126/#131 fix commits untouched in af63895..05f6ee3
- **OBSERVED** — the benchmark lane runs **12** k6 goals, and as of `818d964` both formerly quarantined
  goals are re-enabled (`skip.benchmark.upload.large` and `skip.benchmark.websocket.echo` both default
  `false` in `benchmarks/pom.xml`). **The "the lane is not green today" objection that stood when this
  question was first raised no longer holds** — re-verify at outline rather than inheriting it.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: benchmarks/pom.xml:39,49 both skip flags false
- **HYPOTHESIS — the benchmark lane is not currently *gating*.** It runs post-merge on the PR and does
  not block the merge itself. Confirm against `.github/workflows/benchmark.yml` (verify-at-outline).
  **If it is not gating, deliverable 1's "benchmark lane" branch carries making it gate as a stated
  cost, not as an afterthought.**
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: benchmark.yml still pull_request closed + merged==true, post-merge only
- **Verify-first clause**: re-read both fixed defects' regression tests before deciding. If PLAN-32 and
  PLAN-33 already added detection that generalises, this plan narrows to documenting that and the
  design question is largely settled.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural verify-first instruction, still accurate

## Expected Surface

- OBSERVED absence → NEW: one ADR under `doc/adr/` — D1
- HYPOTHESIS: `integration-tests/**` **or** `benchmarks/**` — D2, whichever the decision selects.
  **The plan cannot name this surface before D1 answers**; that is inherent to a decision plan, not a
  scoping failure.
- OBSERVED: `.github/workflows/benchmark.yml` — read for the gating question; edited only if D1
  selects the benchmark lane
- OBSERVED: `doc/development/` — the developer layer explaining which lane owns which defect class
- OBSERVED (absence, asserted): **NO `api-sheriff/src/main/java/**` change.** This plan builds a
  detection mechanism; it fixes no gateway defect. A production edit is a finding to report.

## Dependencies and Sequencing

- **Gated behind the 0.1.0 cut**, like every plan in this epic.
- **Depends on PLAN-32 and PLAN-33 having landed** — both have (`#126`, `#131 #134`). Their regression
  tests are the evidence base for D1.
- **Adjacent to `api-sheriff-roadmap` PLAN-43** (test-corpus integrity): 43 asks whether existing
  assertions prove anything; this plan asks whether a whole defect class has any assertion at all.
  Related questions, different lanes — no surface overlap.
- **Prefer after PLAN-39** (java-idiom sweep) if both are queued — 39 touches the test corpus broadly.

## Standing Epic Clauses

- **SONAR ZERO-FINDINGS** — red is a HARD STOP.
- **NAMED LINE ITEMS** — both deliverables are named; collapsing D2 into D1 turns this into a memo.
- **ACTIVATE IT IN A TEST** (lesson `2026-07-25-15-001`) — D2's demonstrated catch **is** the
  activation. A detector nothing has ever seen fire is the exact shape that lesson names.
- **ADR NUMBER CLAIMED AT WRITE TIME**, checked against `main` and every open branch — see D1.

## Finalize Boundary — the plan STOPS at the merge

**Operator ruling, 2026-07-30.** This overrides `CLAUDE.md` § Git Workflow step 8 for plan-executed
work. Everything up to and including the merge is the plan's.

**The aftermath is the ORCHESTRATOR's and MUST NOT be attempted by the plan** — the PR-attached
post-merge run and the main-branch run for the merge commit, including `deploy-snapshot`. Report the
merge and stop.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-11-resource-exhaustion-test-home.md" plan_id=plan-v02-11-resource-exhaustion-test-home
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan touches only its own repository source and tests. It creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message.
