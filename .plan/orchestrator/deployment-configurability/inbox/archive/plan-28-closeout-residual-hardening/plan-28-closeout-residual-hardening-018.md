envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:19:27Z

# Candidate lesson: `--verbose` invented on a read verb that declares only `--plan-id`

**Source signal**: script-failure cluster — `plan-marshall:manage-references:manage-references`, `exit_code=2`, `failure_kind=argparse_rejection` (2026-09-22T11:19:59Z, inside `create-pr`)
**Component**: `plan-marshall:manage-references`

## What happened

> `--verbose` is not declared for `manage-references get-context`: `['plan-id']`

Invoked during PR-body generation, where the agent wanted fuller reference context and reached for
the conventional-looking `--verbose`. The verb declares exactly one flag.

## Why this is candidate-lesson shaped

This is the purest form of the invented-flag class: `--verbose` is not a flag the caller had seen
on a sibling verb or read in prose — it is a flag that *most CLIs have*, imported from general CLI
habit into a surface that does not use it. The failure is therefore not a documentation-reading
failure but a default-assumption failure, which no amount of careful reading of the *adjacent* verb
would have prevented.

It is the fourth flag-shape rejection in this run, across four different scripts (`manage-status`,
`manage-architecture`, `manage-solution-outline`, `manage-references`). Each individually costs one
retry; together they are the run's largest source of wasted calls.

## Candidate rule

Do not extrapolate conventional CLI flags (`--verbose`, `--json`, `--quiet`, `--force`) onto
plan-marshall script surfaces. The declared flag set is the whole flag set; where a verb's output is
insufficient, the answer is a different verb, not a modifier it does not declare.

## Disposition in this plan

Recovered by retry without the flag; PR #341 was created successfully at 11:21:56. No state effect
(argparse rejections never reach the script body).
