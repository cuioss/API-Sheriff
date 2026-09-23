# PLAN-11: Live-Vert.x loopback connection hang — investigation (macOS-local)

epic: deployment-configurability
workstream: WS-07

> ⛔ **TRACKING SPEC, not a hand-off brief.** This plan was NOT staged from this ledger and was never
> emitted by the `next` verb. It was started independently on 2026-09-01 from
> `.plan/temp/macos-loopback-hang-investigation.md`, and this spec was written afterwards, at the
> operator's instruction, so the epic can track it. It is therefore a RECORD of work already in
> flight — every other spec in this corpus is a brief for work not yet begun, and reading this one as
> a brief would invert that.
>
> **The authoritative brief is the plan's own `request.md` and `solution_outline.md`**, under
> `.plan/local/plans/macos-loopback-hang-investigation/`. Nothing here re-states them: a tracking spec
> that paraphrased a live plan's outline would be a second, drifting copy of a document that is
> already the source of truth. Read them directly.

## Objective

Find where the live-Vert.x edge/tls suite hangs on this workstation. PLAN-01's lesson
`2026-08-29-16-002` opened the flake class; PR #241 (`0937922`) rebuilt the wait mechanism against it
and **did not close it** — its N=10 acceptance soak failed 5 RED / 5 GREEN, and the class reproduced
again in isolation at 1 in 9 single-class runs.

⛔ **The locus is a machine, not this repository.** Zero occurrences across 100 CI runs and three
green CI runs on PR #241, against roughly 50% locally. That is why this plan exists at all and why it
is an investigation rather than a fix: there is no repository defect yet identified to fix.

## Deliverables

The live plan owns its own deliverable set — four, at `solution_outline.md` §§ 1–4, summarised here
ONLY so the epic's queue reader knows what is in flight, and superseded by that document wherever the
two differ:

1. Capture the upgrade rejection — status, headers, body, and the LISTEN owners
2. Execute the fixed run budget and record the evidence
3. Classify the evidence and execute exactly one disposition
4. Correct the stale `build-gate-discipline` section and update the originating lesson

⚠ **Deliverable 4 reaches beyond the investigation** and is the reason this plan touches documentation
at all: `doc/development/build-gate-discipline.adoc` is referenced by CLAUDE.md's Pre-Commit Process,
so a correction there changes guidance every plan in this repository reads.

## Claim Labels

- OBSERVED: the plan is **live and past outline** — `manage-status read` reports `current_phase: 4-plan`
  (in_progress) with 1-init, 2-refine and 3-outline all `done`, `planning_lane: deep`,
  `use_worktree: true`, `confidence: 96.5`, created `2026-09-01T14:47:24Z`. Verified at HEAD `0937922`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: .plan/local/plans/ now contains only a NO_PLAN placeholder - the tracked plan is no longer live at current_phase 4-plan
- OBSERVED: it is **not orchestrator-sourced and owes this epic no inbox message** — its origin is the
  standalone brief, and `inbox detect` on the lesson id returns `orchestrated: false` /
  `detection: not_orchestrator_pointer`. ⛔ Its silence on the inbox is CORRECT; do not record it as a
  bypass.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: immutable historical fact about the plan's non-orchestrator origin
- OBSERVED: its declared footprint is **four files**, read from the plan's own `references.json`
  `affected_files` rather than inferred from prose, and it is **disjoint from PLAN-02's declared
  surface** — no path appears in both. Verified at HEAD `0937922`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: all four declared files still exist at HEAD
- OBSERVED: the flake class it investigates is **open** — PR #241 merged as `0937922` with 6/6
  deliverables implemented, but its acceptance soak failed 5 RED / 5 GREEN against a ten-consecutive-green
  criterion. Lesson `2026-08-29-16-002` is ACTIVE in the corpus, restored deliberately rather than
  archived with that plan.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: lesson 2026-08-29-16-002 is now tombstoned, not active - the flake class was root-caused and closed by the loopback-stall-fix plan
- Verify-first clause: this spec is a tracking record and its deliverable list is a SUMMARY. Settle any
  question about scope against the plan's own `solution_outline.md`, never against this file — and if
  the two disagree, the plan's document wins and this one is corrected.

## Expected Surface

⚠ Derived from the plan's own `references.json` `affected_files` — the realized-footprint seam — not
authored here from prose. It is therefore unusually trustworthy for a declared surface, and equally it
will drift if the running plan widens its scope without this file being updated.

- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/Awaits.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/testsupport/AwaitsTest.java`
- OBSERVED: `doc/development/build-gate-discipline.adoc`

## Dependencies and Sequencing

- **Depends on: none.** It is an investigation into an already-shipped mechanism.
- ✅ **Surface-disjoint from PLAN-02**, which is the pairing that matters — the two run concurrently and
  the epic's `parallelization_scope` was raised to 2 to record that. PLAN-02 lives in
  `integration-tests/`, `deployment/compose-sample/` and main-source config; this plan lives in
  `api-sheriff/src/test/.../edge` and `testsupport`. They also occupy different trees: PLAN-02 has its
  own worktree, this one runs from the main checkout.
- ⚠ **Overlaps nothing else in the corpus today, but is the one row whose surface can move without a
  ledger edit** — it is derived from a LIVE plan's references rather than authored ahead of it. Re-read
  it at each `analyze`.
- Adjacent to: PLAN-01's shipped health-probe work (the originating lesson came out of that plan) and
  to every gate-running plan in this epic, via deliverable 4's edit to the build-gate guidance.

## Hand-Off Command

⛔ **No hand-off is owed and none must be emitted.** The plan is already running. Emitting a
`/plan-marshall task=` pointer at this spec would `init` a SECOND plan from a tracking record and
orphan the live one. Resume it, if it ever needs resuming, by plan id:

```text
/plan-marshall plan="macos-loopback-hang-investigation"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message. ⚠ Because it is not
orchestrator-sourced, it will not write one — the orchestrator reconciles this row from the plan's own
artifacts at `analyze`, not from an inbox message. See `orchestration-model.md` § Ledger Write-Boundary.
