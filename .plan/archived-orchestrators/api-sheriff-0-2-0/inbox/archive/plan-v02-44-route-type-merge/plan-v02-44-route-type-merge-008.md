envelope_version=1
sender_type=plan
sender_id=plan-v02-44-route-type-merge
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-16T10:31:03Z

# Candidate lesson: invented `--number` flag on manage-solution-outline get-deliverable

**Suggested route:** plan-marshall (argparse-rejection recurrence, verb-flag paraphrase). Forward to the plan-marshall lessons-routing inbox on drain. Check whether this is a recurrence of an existing lessons-archive item.

**Signal:** script failure, work-log row 6a94f5 (2026-10-15T16:58:50Z).

## What happened

```
script_failure notation=plan-marshall:manage-solution-outline:manage-solution-outline exit_code=2
failure_kind=argparse_rejection detail=`--number` is not declared for
`plan-marshall:manage-solution-outline:manage-solution-outline get-deliverable`: ['deliverable-number', 'plan-id']
```

A phase-5-execute leaf, in its per-deliverable commit step, called `get-deliverable --number N` instead of `--deliverable-number N`. It recovered, but this is the "plausible-flag paraphrase" class the persona's "Never invent script subcommands" rule targets.

## Suggested corrective action

Check that every phase-5-execute or execute-task workflow site calling `get-deliverable` quotes `--deliverable-number` verbatim from the canonical-invocation block. If a doc site shows `--number`, fix it.
