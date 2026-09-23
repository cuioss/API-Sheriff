# Landing Analysis: PLAN-11 — Live-Vert.x loopback connection hang (macOS-local)

epic: deployment-configurability
workstream: WS-07
pr: 243 (merged as `5948962`)

> Landing record. Every figure below was corroborated against git, the CI abstraction and the live
> lessons corpus before it was written; the operator's paste supplied the leads.

## Deliverable Fidelity vs Spec

⚠ **This spec was a TRACKING record, not a pre-launch brief.** PLAN-11 was already at phase `4-plan`
when its row was created, so "fidelity vs spec" here means fidelity to a summary written from the
plan's own outline — a weaker check than for a plan staged before launch. The deliverable text below
came from `solution_outline.md` §§ 1–4.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Instrument: capture upgrade status, headers, body, LISTEN owners | shipped-as-specified | `WebSocketRelayStageTest.java` +102, `Awaits.java` +101 in `5948962` |
| 2. Execute the fixed run budget and record the evidence | shipped-as-specified | 31 pre-registered runs, 8/31 stalls, thread dumps |
| 3. Classify the evidence and execute exactly one disposition | shipped-as-specified | disposition (c) — documented, no behaviour change |
| 4. Correct the stale build-gate section and update the lesson | shipped-as-specified | `doc/development/build-gate-discipline.adoc` +271 |

✅ **The declared surface was EXACT — 4 declared, 4 realized, zero drift.** `git show --stat 5948962`
lists precisely `edge/WebSocketRelayStageTest.java`, `testsupport/Awaits.java`,
`testsupport/AwaitsTest.java` and `doc/development/build-gate-discipline.adoc`. This is the corpus's
only measured instance of a perfectly-declared surface, and it is worth naming WHY: the surface was
**derived from the plan's own `references.json` `affected_files`** rather than authored ahead of the
work. A declaration copied from the plan's own footprint cannot under-declare — which is a fact about
the derivation, not evidence that hand-authored declarations are improving.

## Metrics and Anomalies

- Tokens: 1,008,319
- Duration: 58,054 s (≈16h07m)
- Anomalies: none in the harness. The substantive anomaly is scientific, not operational — see below.

## Routing and Merge Behavior

- Review: `automatic-review` 5 findings, all fixed; `pre-submission-self-review` 4 findings fixed;
  Sonar 0 new-code issues; `simplify` 0 edits. CI green at `52f3073` after a re-settle; merged as
  `5948962`.
- CI/merge: clean. No rebase conflicts, no re-verify signals.
- ⚠ **The review layer caught what three earlier gates cleared.** Both of the plan's self-reported
  errors were prose claims that over-reached their evidence, and the Q-Gate, the self-review and the
  simplify pass all passed them. Recorded because it locates where over-claiming is actually caught in
  this pipeline: at review, not at verification.

## What the investigation established

⛔ **The mechanism is NOT established, and the plan says so.** Read the result precisely, because the
strongest finding is a negative and negatives are easy to inflate:

- **Contradicted, within a stated scope:** the long-standing TIME_WAIT/port-reuse hypothesis fails for
  the **held-listener form** — the stall reproduces against ports held for the whole fixture lifetime.
  ✅ That killed disposition (a)'s leading candidate *before* it became a third unmeasured fix, which
  is exactly what this plan existed to prevent. ⛔ The **accepted-socket and client-socket forms were
  never tested**, so port reuse is not refuted as a class.
- **Measured:** 31 pre-registered runs, 8/31 stalls, ten methods across five classes, every elapsed
  within 10 ms of the 30 s ceiling, thread dumps showing the acceptor and all three event loops idle
  in `KQueue.poll`.
- **Inconclusive-with-power, never an all-clear:** zero reproductions of the symptom the budget was
  actually sized against.
- **Still inferred, still uninstrumented:** a loopback readiness event the macOS kqueue selector never
  delivers.

## Reconciliation Actions

- [x] row `status` → `shipped` — `queue --transition PLAN-11 --status shipped`
- [x] row `pr` stamped `243`
- [x] row `landing` stamped `landings/PLAN-11.md`
- [x] row `plan_marshall_plan_id` stamped `macos-loopback-hang-investigation`
- [x] Open Defect on the 30s-hang class **narrowed, not closed** — the mechanism stays open; the
      port-reuse hypothesis is retired only for the held-listener form
- [x] START-HERE and Ordered Queue blocks regenerated
- [x] `resume_anchor` updated

## Collisions and surface findings

⛔ **A collision this landing did NOT cause, but which its analysis surfaced, and which the gate could
not have predicted.** While corroborating this landing, PLAN-02's branch (`040f2ac`, phase
`6-finalize`) was found to have **authored `doc/adr/0038-…carrier_key…adoc`** — taking the ordinal
PLAN-10 was staged to use for the distroless health-probe ADR, on an unrelated subject.

The gate could not see it because **PLAN-02 declared 9 paths and its branch touches 52** — `doc/adr/`
is not among the 9. That is the standard's dominant residual class (under-declaration) at roughly 4×,
and it produced two real collisions:

| Collision | With | Consequence |
|---|---|---|
| `doc/adr/0038` ordinal | PLAN-10 | PLAN-10's "0038 is the next free ordinal" claim is **contradicted** — re-scoped in the same act |
| `doc/user/README.adoc`, `doc/user/environment-variable-overrides.adoc` | PLAN-09 | PLAN-09's surface is being written by PLAN-02 first; sequencing note recorded |

✅ `doc/README.adoc` is **not** touched by PLAN-02, so PLAN-10's index-row patch target is unaffected.

## Follow-Ups

- **The mechanism remains open.** Recorded as a narrowed Open Defect, NOT staged as a follow-up plan:
  the untested port-reuse forms and the uninstrumented kqueue readiness hypothesis are the named next
  steps, and WS-07 exists to carry them if the operator wants a second investigation.
- **PR #242 landed alongside** (`82e44cc`), fixing a pre-commit gate that rewrote `catch (X _)` into
  uncompilable source. ⚠ Its root cause — `UpgradeToJava25` transitively containing `UpgradeToJava21`
  — is claimed to explain why lesson `2026-07-16-09-002`'s `<release>` downgrade kept recurring:
  *"the guard was a no-op from the start."* That lesson is one this epic has carried since July.
  ⛔ Not verified by this orchestrator — recorded as a lead, and `2026-07-16-09-002` is still `active`.
- **Orphan worktree observed**: `.plan/local/worktrees/chore-openrewrite-fixed-point` (branch
  `chore/openrewrite-fixed-point`, 2 commits, **not merged into main**, no row in
  `manage-status list`). Unowned by this epic; flagged so it is not mistaken for epic work.
