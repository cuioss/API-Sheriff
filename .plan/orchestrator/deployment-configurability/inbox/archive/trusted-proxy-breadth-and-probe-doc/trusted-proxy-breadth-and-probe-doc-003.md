envelope_version=1
sender_type=plan
sender_id=trusted-proxy-breadth-and-probe-doc
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-05T00:10:42Z

## Candidate: the light-lane outline envelope left two handoff obligations unmet, both reconciled by hand

**Signal source**: dispatcher-supplied candidate material (not one of the three counted signals) —
recorded here because the orchestrator is the only party holding the cross-plan view that makes it
judgeable.

**What happened**

Two separate omissions from the same light-lane phase-3-outline envelope, both discovered by the
orchestrator downstream and both fixed by hand:

1. **`metadata.pr_title` was never authored.** The light lane transitioned out of outline without
   writing the field, so the finalize pipeline reached PR creation with nothing to title the PR
   with. The orchestrator supplied it manually
   (`feat(config): raise broad trusted-proxy warning thresholds, fix HEALTHCHECK remedy docs`).
2. **`2-refine` was left at `in_progress` while `3-outline` was transitioned.** The phase array
   therefore carried two non-terminal phases at once — a state the sequential phase model does not
   admit. The orchestrator reconciled it by hand; `status.json` now reads `2-refine,done`.

**Why it is candidate-lesson material**

Both are the same defect class: **the light lane skips work, and skipping the work also skipped the
bookkeeping that the deep lane performs as a side effect of doing it.** The lane exists to be
cheaper, not to be less complete about its handoff contract — a lane that emits a structurally
invalid `status.json` is not a cheaper lane, it is a lane that moved its cost onto the next reader.

The second omission is the more serious of the two, because it is *silent*. A missing `pr_title`
announces itself at PR-creation time. A phase left `in_progress` behind an advanced
`current_phase` does not fail anything immediately — it corrupts the record that every later
resume, retrospective, and progress calculation reads, and it can only be caught by someone
noticing the array is inconsistent. `progress` counts only `done` phases, so the plan under-reported
its own completion for the rest of the run.

The generalisable shape: **when a lane elides a phase's substantive work, the phase's terminal
bookkeeping is not part of what it may elide.** A lane variant should be checkable against the
invariant "at most one non-terminal phase, and every phase before `current_phase` is `done`" at each
transition, so a skipped-but-not-closed phase fails at the transition that created it rather than
being discovered several phases later.

**Cross-plan relevance for the epic**

This plan routed `planning_lane: light`, and light routing is the expected default for the bounded,
well-specified changes that make up most of `deployment-configurability`. If the light-lane envelope
has this gap, every light-routed plan in the epic reproduces it, and each one costs the orchestrator
the same manual reconciliation.

**Evidence**

- `status.metadata.planning_lane: light`, `execution_profile: full`
- `status.metadata.pr_title` present only after manual authorship
- `status.phases`: `2-refine,done` only after manual reconciliation
- plan `trusted-proxy-breadth-and-probe-doc`, landed as PR #267 / `558a38b`
