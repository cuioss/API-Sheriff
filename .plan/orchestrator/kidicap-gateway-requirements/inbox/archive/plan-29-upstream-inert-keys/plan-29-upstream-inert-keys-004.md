envelope_version=1
sender_type=plan
sender_id=plan-29-upstream-inert-keys
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-11-09T13:04:52Z

# Candidate lesson: one manage-findings script failure (argparse rejection) during the run

Source record: script-failure cluster from `manage-logging signal-scan` — 1 distinct notation,
`plan-marshall:manage-findings:manage-findings`, marker class `[ERROR] ... script_failure` (failed: 0,
no_progress_reclassification: 0). The 2 verification_handback rows are prescribed hand-backs and are not part
of this candidate.

## What happened

One call to manage-findings exited non-zero. Per the orchestrator's run notes the cause was a mistyped verb.
This capture did not re-derive the exact invoked verb from the script log, so the specific invented-vs-canonical
pair is not stated here.

## Candidate rule

Quote manage-findings verbs verbatim from its `--help` / canonical-invocations block. Its surface is nested
(`qgate {add,list,resolve,resolve-evidenced,resolve-by-rule,clear}` plus top-level verbs), which makes a
paraphrased verb easy to produce.

## Note for classification

Single occurrence, recovered in-run, with no downstream effect recorded. It likely belongs to the existing
"never invent script subcommands" recurrence class rather than being a new lesson; it is forwarded for
recurrence counting only.
