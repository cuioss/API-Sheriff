# PLAN-V02-11: where does resource-exhaustion detection belong — the IT suite or the benchmark lane?

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

**Answer one design question, and implement the answer.** Is a soak-shaped integration test — sustained
traffic, then a health probe — the right home for resource-exhaustion detection, or does that belong to
the benchmark lane?

It is a legitimate design question, not a foregone conclusion, and it is a plan rather than a note
because **the answer changes what the project's test lanes are for**.

## Why it is a plan and not a note

**The IT suite cannot detect resource-lifecycle or layer-boundary defect classes** (epic Open
Defect 6). Three shipped defects prove it:

- `218b5c`, the unauthenticated WebSocket admission-permit leak: any upgrade leaked a permit until the
  gateway answered 503 to all traffic until restart (fixed in #126).
- The body-cap derivation defect (fixed in #131 and #134).
- GitHub issue [#201](https://github.com/cuioss/API-Sheriff/issues/201): `MtlsHandshakeIT` fails
  2 of 3 under `-Pjfr` only — fail-open on handshake rejection — while the identical tree passes
  3 of 3 under `-Pintegration-tests`.

The first two were not caught by a functional IT, because a functional IT makes one request and
asserts one response. **A leak is only visible over time or over volume.** The third is a different
shape of the same gap: it is **lane-conditional**, invisible in one lane and visible in another, and
it stayed undetected for as long as the `-Pjfr` lane could not run — its green was the absence of
execution.

So the question is not academic: today **no lane owns this defect class**, and every instance was
found by inspection rather than by a gate.

## Deliverables

**Two deliverables.** Deliberately small — this is a decision plus its consequence.

1. **NAMED LINE ITEM — the decision, recorded as an ADR.**
   Evaluate both homes against the three shipped instances and record the verdict with its reasoning:
   - **Soak-shaped IT**: runs in the existing containerised suite, gates every PR, but lengthens the IT
     lane and needs a sustained-traffic harness the suite does not have.
   - **Benchmark lane**: already generates sustained traffic and already has the harness, and it is
     green — both formerly quarantined k6 goals run by default. **But it does not gate.**
     `.github/workflows/benchmark.yml` triggers on a closed pull request with `merged == true` (plus
     manual dispatch), so it runs after the merge and cannot block it. A detection mechanism in a
     lane that does not block a merge detects nothing, so choosing this home carries *making that
     lane gate* as a first-class cost. Turning a post-merge workflow into a merge gate is a
     structural change to the release lane, not a flag flip; weigh it as such.

   Derive the ADR ordinal from `doc/adr/` on the branch at write time, checking `main` and open
   branches; a duplicate ordinal fails the build.

   **Say which of these adjacent open defects the plan adopts and which it declines** — the epic
   ledger routes them here and none is automatically in scope:
   - **(6)** the detection gap itself, as stated above. Re-verify it against the regression tests the
     two fixes added before treating it as fully open; the gap may be narrower than the entry says.
   - **(7)** the k6 scripts are an unguarded consumer of gateway posture: the benchmark and the IT
     suite share one `gateway.yaml` through a compose overlay, and nothing requires a behavioural
     change to update the k6 side.
   - **(9)** `K6BenchmarkLogMessages` sits outside `LogMessagesCatalogueTest`, which anchors on
     `ApiSheriffLogMessages.class.getProtectionDomain()` and therefore walks `api-sheriff/target/classes`
     only. A guard anchored on one module's protection domain silently excludes every other module.

2. **NAMED LINE ITEM — implement the decision, or record why it is not implementable yet.**
   Whichever home wins, deliver the mechanism that detects one of the known instances, and prove it
   detects it — **a mechanism that would not have caught `218b5c` is not an answer to this question.**
   Reproducing the leak against a fixed gateway is acceptable evidence (revert-and-detect in a test
   fixture); shipping a detector with no demonstrated catch is not.

   **Issue #201 is admissible as the demonstrated catch**, and a mechanism that would not have
   surfaced it is a weak answer. Fixing the fail-open handshake behaviour itself is **not** this
   deliverable: it is a security fix that belongs to whichever work owns mTLS handshake behaviour.
   Say explicitly which of the two the plan did.

   If the honest outcome is "not implementable without X", say so and name X. **"Decided but not
   implemented" is an acceptable outcome; "implemented but never demonstrated to catch anything" is
   not** — that would reproduce the detection gap in a new lane.

   If D1 selects the benchmark lane, the workflow edit touches `.github/workflows/**`, a
   gate-requiring path, and runs the full pre-commit process.

## Claim Labels

- **OBSERVED** — epic Open Defect (6): the IT suite cannot detect resource-lifecycle or layer-boundary
  defect classes. Two shipped instances (`218b5c` WebSocket permit leak, fixed in `#126`; the
  body-cap derivation defect, fixed in `#131 #134`).
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: epic Open Defect 6 matches; #126 releases the WebSocket permit, #131 derives the body limit and 413; #134 is a benchmark.yml follow-up to #131
- **OBSERVED** — the benchmark lane runs **13** k6 goals (#408 added `run-k6-pending-login-flood-benchmark`, a resource-bound proof in the post-merge lane, which has failed on every run since — epic Open Defect 17), and both formerly quarantined
  goals are enabled (`skip.benchmark.upload.large` and `skip.benchmark.websocket.echo` both default
  `false` in `benchmarks/pom.xml`). The lane is red at `386f3f74` — re-verify at outline rather than inheriting it.
  - verdict: contradicted | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: benchmarks/pom.xml declares 13 run-k6 executions (#408 added pending-login-flood); both quarantine props still false; the lane is red since #408; claim re-scoped
- **HYPOTHESIS — the benchmark lane is not currently *gating*.** It runs post-merge on the PR and does
  not block the merge itself. Confirm against `.github/workflows/benchmark.yml` (verify-at-outline).
  **If it is not gating, deliverable 1's "benchmark lane" branch carries making it gate as a stated
  cost, not as an afterthought.**
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: benchmark.yml runs on pull_request closed with merged==true plus tags and dispatch; it cannot block a merge
- **Verify-first clause**: re-read both fixed defects' regression tests before deciding. If those fixes
  already added detection that generalises, this plan narrows to documenting that and the
  design question is largely settled.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural clause applicable: compose service api-sheriff-ws-admission exists as the permit-exhaustion rig; LargeBodyIT covers #131

## Expected Surface

- OBSERVED absence → NEW: one ADR under `doc/adr/` — D1
- HYPOTHESIS: `integration-tests/**` **or** `benchmarks/**` — D2, whichever the decision selects.
  **The plan cannot name this surface before D1 answers**; that is inherent to a decision plan, not a
  scoping failure.
- OBSERVED: `.github/workflows/benchmark.yml` — read for the gating question; edited only if D1
  selects the benchmark lane
- OBSERVED: `doc/development/` — the developer layer explaining which lane owns which defect class
- HYPOTHESIS: `benchmarks/pom.xml`, `benchmarks/scripts/benchmark-manifest.py` — only if D1 selects the benchmark lane; the manifest script derives the expected-goal set
- HYPOTHESIS: `benchmarks/src/main/java/**`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/LogMessagesCatalogueTest.java` — the catalogue guard of Open Defect 9, if this plan adopts it
- OBSERVED (absence, asserted): **NO `api-sheriff/src/main/java/**` change.** This plan builds a
  detection mechanism; it fixes no gateway defect. A production edit is a finding to report.

## Dependencies and Sequencing

- Depends on: none. The two fixes whose regression tests are D1's evidence base have landed.
- Overlaps with: every plan declaring `integration-tests/**`, `benchmarks/**` or `doc/adr/`; the
  disjointness gate decides at emit time. Not concurrent with `PLAN-V02-04`, which audits the ADR
  corpus this plan adds a record to.
- Adjacent to: test-corpus integrity work, which asks whether existing assertions prove anything.
  This plan asks whether a whole defect class has any assertion at all. Related questions, different
  lanes.

## Standing Epic Clauses

- **SONAR ZERO-FINDINGS** — red is a HARD STOP.
- **NAMED LINE ITEMS** — both deliverables are named; collapsing D2 into D1 turns this into a memo.
- **ACTIVATE IT IN A TEST** — D2's demonstrated catch **is** the activation. A detector nothing has
  ever seen fire is not a detector.

## Finalize Boundary — the plan STOPS at the merge

Everything up to and including the merge is the plan's. **The aftermath is the ORCHESTRATOR's and
MUST NOT be attempted by the plan** — the PR-attached post-merge run and the main-branch run for the
merge commit, including `deploy-snapshot`. Report the merge and stop.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-11-resource-exhaustion-test-home.md" plan_id=plan-v02-11-resource-exhaustion-test-home
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
