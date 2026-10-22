envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:22:46Z

# Script failure: architecture search given --plan-id after the verb

- Source: script log [ERROR] 424026 (2026-10-21T15:43:00Z), notation plan-marshall:manage-architecture:architecture search, exit 2
- Component: manage-architecture invocation (router-scoped flag placement)

## Observation

`architecture search --content --pattern ... --plan-id <plan>` was rejected with
`misplaced_router_flag`: for the architecture script `--plan-id` is a top-level router flag and
must come before the verb. The rejection message printed the correct form, and later calls used
it (`architecture --plan-id <plan> ...`).

## Outcome

One rejected call, corrected right away.

## Candidate rule

For manage-architecture, put `--plan-id` before the verb (`architecture --plan-id P search ...`).
This is the router-scoped flag placement signature already listed among the argparse-rejection
recurrences.
