envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:22:54Z

# Script failure: manage-solution-outline get-deliverable called with --number

- Source: script log [ERROR] 3b6023 (2026-10-21T10:57:29Z), notation plan-marshall:manage-solution-outline:manage-solution-outline get-deliverable, exit 2
- Component: manage-solution-outline invocation (typed-id flag naming)

## Observation

`get-deliverable --plan-id <plan> --number 3` was rejected with `unknown_flag`; the declared
flags are `--plan-id` and `--deliverable-number`. The retry two seconds later (03a034) used the
declared flag and succeeded.

## Outcome

One rejected call, corrected right away.

## Candidate rule

Deliverable lookups take the typed-id flag `--deliverable-number`, not the bare `--number`; copy
the flag name from the script's canonical invocation, not from the surrounding prose.
