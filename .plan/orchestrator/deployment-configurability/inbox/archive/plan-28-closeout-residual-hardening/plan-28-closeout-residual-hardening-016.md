envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:18:53Z

# Candidate lesson: `architecture --plan-id` placed after the verb, twice, three hours apart

**Source signal**: script-failure cluster — `plan-marshall:manage-architecture:architecture`, `exit_code=2`, `failure_kind=argparse_rejection` (2026-09-22T07:36:45Z in phase 3-outline; 2026-09-22T16:00:40Z inside `pre-submission-self-review`)
**Component**: `plan-marshall:manage-architecture`

## What happened

Both rejections carried the same diagnosis:

> `architecture.py: error: unrecognized arguments: --plan-id plan-28-closeout-residual-hardening`
> note: `--plan-id` is a top-level flag and belo[ngs before the verb]

`architecture.py` declares `--plan-id` (and `--project-dir`) as **top-level router flags**, consumed
before the subcommand token. Placing them after the verb is an unrecognized-arguments rejection.

It recurred in two different phases, dispatched by two different agents, three hours apart — so this
is not one agent's slip but a shape the surface invites.

## Why this is candidate-lesson shaped

`--plan-id` positioning is **per-script and, on some routers, per-verb**. Across the plan-marshall
surface all three cases exist: scripts that declare it top-level (this one), scripts that declare it
on the subcommand after the verb, and scripts that do not declare it at all (where appending it is
itself a rejection). The `ci` router is the sharpest case, taking it *before* the verb for its read
verbs and *after* the verb for its body-consumer verbs.

Appending `--plan-id` by habit is therefore wrong roughly as often as it is right, and the
rejection is cheap enough (`exit_code=2`, body never runs) that the habit is never punished hard
enough to break.

## Candidate rule

Consult each script's canonical-invocation block for its flag set and the position that parser
requires. Never append `--plan-id` by rote. The error text `architecture.py` emits — naming the flag
*and* stating that it is top-level — is the model every router should follow, because it converts a
retry into a corrected invocation on the first read.

## Disposition in this plan

Both recovered by retry with the flag ahead of the verb; no state effect (argparse rejections never
reach the script body).
