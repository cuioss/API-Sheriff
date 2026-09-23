# Landing Analysis: PLAN-18 — Nine hollow integration tests, the named loopback sites, and the throw-then-assert pair

epic: deployment-configurability
workstream: WS-06
pr: [#308](https://github.com/cuioss/API-Sheriff/pull/308) — merged through the merge queue as squash `c74f5d2`, 2026-09-16

> ⛔ **REPORTED LATE — reconciled first from `main`, corrected afterwards.** The operator's PLAN-18 report
> arrived AFTER this record was written, and it settled two things the merged artifact could not: the
> reversion proofs were performed, and the metrics. Those corrections are marked ✅ **CORRECTED 2026-09-16**
> below. The original finding stands: at reconciliation time neither channel had reported this plan.
>
> ⛔ **THIS LANDING WAS NOT REPORTED AT RECONCILIATION TIME.** The operator's 2026-09-16 paste covered PLAN-25 (#306) only; this
> plan reached the ledger because `analyze` fetched `main` while corroborating that landing and found a
> second merge two commits later. The inbox was empty — like PLAN-25, this plan ran with `emit-landing` and
> `lessons-capture` on lane `off`, so **both channels were silent on a shipped plan**. This is the fourth
> instance of the emit-landing channel failing this epic (see truthful-signals `…-010`), and the first where
> the cause is a deliberate lane setting rather than a skip.

## Deliverable Fidelity vs Spec

Verified at `c74f5d2`; the commit body itself states the scope and matches.

| Deliverable (spec) | Verdict | Evidence checked at `c74f5d2` |
|---|---|---|
| 1. Fix the nine integration sites | ✅ shipped | `benchmarkGatewayHealthTargetServesOverHttps` **deleted** from `ApiSheriffIntegrationIT`; `ConfigLoadedIntegrationIT:48-63` now carries the positive control on `/proxy/get` the class javadoc named — the one leg that fixes both `404` rows; `WsAdmissionActivationWiringTest:74`/`:95` derives the cap from `WebSocketProxyIT.LOW_CAP_SEQUENTIAL_UPGRADES` instead of a copied constant; `BodyLimitActivationWiringTest` pairs with `LargeBodyIT`'s own constant |
| 2. `DescriptorInventoryWiringTest` javadoc drift | ✅ shipped — **and better than specified.** The spec allowed correcting the count or replacing it with a pointer; the landed javadoc (`:50-51`) says *"Read the note for the current membership — this Javadoc deliberately restates none of it"*, removing the class of drift rather than the instance |
| 3. `TlsEdgeProducerTest` allocation socket (fix target A) | ✅ shipped | `LoopbackHost` test-support type added (+78); the wildcard allocation is gone |
| 4. `SniFrontListenerTest` (fix target B) | ✅ shipped — **production change taken, as the spec's HYPOTHESIS anticipated** | `SniFrontListener` gains a host-taking constructor (commit body), so the unit test binds loopback; the spec declared that file HYPOTHESIS and it was needed |
| 5. Narrow the arch-test carve-out | ✅ shipped | Carve-out shrinks from the whole `TlsEdgeProducerTest` class to **four named wildcard sites** (commit body); `LoopbackEphemeralBindArchTest:132` now documents *why the carve-out cannot be removed* |
| 6. Remove the throw-then-assert pair | ✅ shipped, **guard not landed** | `TlsEdgeProducerTest:346` keeps `Awaits.until(...)` and the redundant `assertFalse` is gone. The mechanical guard was conditional in the spec (*"if cheap"*) — `AwaitsReassertionArchTest` is the one declared path with no realized file, so the guard was judged not cheap. ⚠ The spec's own warning stands: *removing the last instance does not stop the next one* |
| 7. Prove by reversion | ✅ **CORRECTED 2026-09-16 — performed.** The operator's report states every change was *"mutated, observed red, restored, re-ran green"*. Not independently re-run here; the merged artifact carries no reversion record, so this rests on the operator's own narrative, which the analysis contract treats as trusted input |

### Surface fidelity

Declared 15, realized 18: **4 added, 1 missing**.

- Added: `api-sheriff/src/test/.../testsupport/LoopbackHost.java` (new shared helper), `LargeBodyIT.java` and
  `WebSocketProxyIT.java` (the two constants the fixes now derive from — reads that became edits),
  `doc/development/build-gate-discipline.adoc`.
- Missing: `AwaitsReassertionArchTest.java` — the conditional guard above.
- ✅ **No overlap with PLAN-25**, which landed two commits earlier from the same round: the two plans'
  realized footprints are disjoint, as their declarations predicted.

## Metrics and Anomalies

✅ **CORRECTED 2026-09-16 from the operator's report** (the original entry recorded "no metrics available"):
**23h34m wall, 4h54m worked, 6.3M tokens** across 6 phases, all with closed boundary markers. 7 deliverables,
11 tasks, 18 files — the file count matches the realized footprint below exactly.

- ⚠ **The stale-image incident belongs to this plan's run.** Local lesson `2026-09-15-16-001` records that a
  foreign `api-sheriff:jfr` (built 13:32 UTC, revision `f4a035c`) made its first reversion proofs run against
  a stale gateway binary, so production-side mutations could not turn anything red. The orchestrator had to
  retag the image and rerun. That image plausibly came from PLAN-24's concurrent jfr lane (unproven —
  `f4a035c` no longer resolves). This is the epic's shared-Docker-image hazard, observed, not hypothetical.

## Routing and Merge Behavior

- CI/merge: `ci checks status --pr-number 308` → **`overall_status: success`**, 31 checks (re-read
  2026-09-16 after the operator's report). ✅ The post-merge **`Run Integration Benchmarks`** job
  (run `35077796596`) finished **SUCCESS** in 1252 s — it was `IN_PROGRESS` at first reconciliation and at
  the plan's own CI-wait deadline. #306's equivalent (run `35074374324`) is green too.
- Gates per the operator's report: whole-tree quality gate and full `-Pintegration-tests` green (2111 unit
  tests, 1 skipped; 146 ITs), CI run `35072203077` green, Sonar 0 new-code issues at the scanned HEAD, both
  required review bots participated with **0 new findings**, pre-merge review barrier clean.
- ⚠ Still open: the `main` Maven Build for `c74f5d2` carrying `deploy-snapshot` — not attached to the PR, and
  the CI abstraction has no commit-addressed read (truthful-signals `-013`).
- Cleanup: branch and remote-tracking ref pruned, worktree removed, working tree clean — unlike PLAN-25,
  whose `prune-local-and-remote-ref` errored.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` 308; `landing` `landings/PLAN-18.md`; `plan_marshall_plan_id` stamped
- [x] Watch added: PR #308 benchmark run + `main` build for `c74f5d2`
- [x] Open Defect added: both round-1 plans ran with `emit-landing` on lane `off`, so a shipped plan was
      invisible to the epic — the reporting channel this epic has already routed upstream four times
- [x] Shared-image hazard promoted from hypothesis to **observed** on the lesson and in the epic Decisions
- [x] START-HERE and Ordered Queue regenerated

## Follow-Ups

- The `Awaits.until`-then-re-assert guard was not landed. PLAN-23's audit covers `api-sheriff/src/test/`
  wholesale and is the natural owner if the pattern recurs; recorded as an Open Defect rather than folded,
  since the spec explicitly made it conditional.
- ✅ Reversion evidence: the operator reports every change was mutated, observed red, restored and re-run
  green. PLAN-23 still audits these tests like any other — that is its job — but the earlier "assume nothing
  was proven" caveat is withdrawn.
- The `SniFrontListener` 5-arg constructor delegates with `NetServerOptions.DEFAULT_HOST`, so production
  binding is unchanged — the spec's HYPOTHESIS about needing a production change resolved in the safe
  direction.
- The carve-out's four per-site exemptions are pinned by an **exact-set control**, so a silently widened
  carve-out fails the test. Worth copying wherever this epic narrows a guard.
