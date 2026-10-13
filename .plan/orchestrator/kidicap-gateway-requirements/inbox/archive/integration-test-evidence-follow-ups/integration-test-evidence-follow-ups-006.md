envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T21:09:14Z

# Candidate lesson: invented `query` verb on manage-findings qgate (argparse rejection)

Source: script-failure cluster, notation plan-marshall:manage-findings:manage-findings.

## What happened

An agent called `manage-findings qgate query ...`. The `qgate` noun declares only `add`, `list`, `resolve`, `resolve-evidenced`, `resolve-by-rule`, `clear`; `query` was rejected by argparse (exit 2). The correct read is `qgate list --plan-id P --phase PHASE [--resolution R]` (note `--phase` is required).

## Candidate rule

Verb-paraphrase recurrence signature (persona-plan-marshall-agent "Never invent script subcommands"). Candidate for reinforcement of an existing plan-marshall lesson on invented verbs rather than a new one; component plan-marshall:manage-findings.

Classification deferred to the orchestrator.
