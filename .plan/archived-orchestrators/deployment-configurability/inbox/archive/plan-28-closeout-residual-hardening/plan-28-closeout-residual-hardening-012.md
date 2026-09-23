envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:17:32Z

# Candidate lesson: a guard script emits a finding type the findings store does not accept, so the guard cannot report — three times in one plan

**Source signal**: script-failure cluster — `plan-marshall:phase-5-execute:scope_creep_check`, `exit_code=1`, `failure_kind=script_internal_failure` (three occurrences: 13:06:35, 14:51:29, 17:48:47)
**Component**: `plan-marshall:phase-5-execute` — `scope_creep_check`, against `plan-marshall:manage-findings`

## What happened

`scope_creep_check` attempts to persist its verdict as a finding of type `scope_creep_warning`.
`manage-findings` rejects it:

> Invalid finding type: scope_creep_warning. Must be one of ('bug', 'improvement', 'anti-pattern',
> 'triage', 'tip', 'insight', 'best-practice', 'build-error', 'test-failure', 'lint-issue',
> 'sonar-issue', 'arch-constraint', 'pr-comment', 'pr-comment-overflow')

The guard therefore **measured** scope creep correctly on all three firings — residual counts of 9,
11 and 28 against a threshold of 5 — and could not file any of them. Each time, the measurement
survived only as inline prose in a `[VERIFY]` WARNING that the executing agent chose to write.

## Why this is candidate-lesson shaped

This is a **producer/consumer contract break between two scripts in the same bundle**, and its
failure mode is the worst available one: the guard runs, gets the right answer, and the answer never
reaches the store any downstream reader consults. A reader querying `manage-findings` for scope-creep
evidence on this plan finds nothing — not "no creep", but *nothing*, indistinguishable from a guard
that never ran.

It recurred three times within a single plan because nothing about the failure is progressive: each
firing hits the identical rejection, and the only thing standing between the measurement and total
loss is an agent electing to paraphrase it into a log line.

Note the second-order effect visible in the run: on the third firing the residual count had grown
to 28 because the earlier loop-back tasks' own committed footprints accumulated as "residual" —
so the un-filable number was also becoming harder to interpret with each round.

## Candidate rule

A script that persists through another script's typed enum must draw its type from that enum. Where
a genuinely new finding type is needed, adding it to `manage-findings` is part of the same change as
emitting it. A guard whose persist path can fail must treat persist failure as a **loud** outcome,
not a warning it hopes someone reads.

## Disposition in this plan

Not fixed — this is a plan-marshall tooling defect, outside PLAN-28's scope. Reported inline each
time (`residual_count=9/11/28 vs threshold=5`), with the executing agent confirming in each case that
the residual paths were the plan's own already-committed loop-back footprint rather than genuine
scope creep.
