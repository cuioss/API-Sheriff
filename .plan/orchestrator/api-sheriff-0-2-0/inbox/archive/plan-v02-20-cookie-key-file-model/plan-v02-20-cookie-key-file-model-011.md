envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:35:22Z

# Candidate lesson (plan-marshall tooling): get-deliverable called with --number instead of --deliverable-number (twice)

- Source: script-failure cluster, notation plan-marshall:manage-solution-outline:manage-solution-outline, argparse_rejection at 14:10:28Z and 14:26:21Z (5-execute)
- Suggested component: plan-marshall:manage-solution-outline (or the phase-5-execute / execute-task prose that names the call). Route to the plan-marshall lessons-routing inbox.

## What happened

`manage-solution-outline get-deliverable --number N` was rejected twice in the same phase; the declared flags are `--deliverable-number` and `--plan-id`. The same paraphrase recurred within 16 minutes, so the first rejection did not correct the caller.

## Corrective rule / improvement

Check whether any workflow prose still shows `--number` for get-deliverable; if not, consider accepting `--number` as an alias or making the rejection text name `--deliverable-number` as the replacement (it already lists it, but the recurrence suggests the call shape comes from a doc).
