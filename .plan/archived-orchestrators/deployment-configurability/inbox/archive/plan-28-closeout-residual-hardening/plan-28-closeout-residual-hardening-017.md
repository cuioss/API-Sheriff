envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:19:09Z

# Candidate lesson: `--number` for a typed-ID flag that is spelled `--deliverable-number`

**Source signal**: script-failure cluster — `plan-marshall:manage-solution-outline:manage-solution-outline`, `exit_code=2`, `failure_kind=argparse_rejection` (2026-09-22T08:25:27Z, first phase-5-execute envelope)
**Component**: `plan-marshall:manage-solution-outline`

## What happened

> `--number` is not declared for `manage-solution-outline get-deliverable`: `['deliverable-number', 'plan-id']`

The verb takes the **typed-ID** spelling `--deliverable-number`; the generic `--number` was invoked
instead. This fired at the very start of execution, on the first deliverable lookup of the run.

## Why this is candidate-lesson shaped

The plan-marshall argument-naming convention is explicitly typed-ID (`--lesson-id`, `--plan-id`,
`--task-number`, `--module`, `--component`), precisely so that a flag name carries what it
identifies. Abbreviating a typed-ID flag to its bare noun is the predictable pressure against that
convention: in the context of `get-deliverable`, `--number` reads as unambiguous, which is exactly
why it gets typed.

Small, cheap, and recurring — and worth recording alongside the sibling `manage-status`,
`manage-architecture` and `manage-references` rejections in this same run, because four
flag-shape rejections across four scripts in one plan is a surface-literacy signal rather than four
independent slips.

## Candidate rule

Quote flag names verbatim from the script's `--help` or its canonical-invocation block. Where a
convention is typed-ID, the typed spelling is the only spelling; do not shorten it to the bare noun
even where context makes the short form unambiguous.

## Disposition in this plan

Recovered by retry with `--deliverable-number`; no state effect (argparse rejections never reach the
script body).
