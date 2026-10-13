envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T21:09:21Z

# Candidate lesson: undeclared `--number` flag on manage-solution-outline (argparse rejection)

Source: script-failure cluster, notation plan-marshall:manage-solution-outline:manage-solution-outline.

## What happened

An agent passed `--number` to a manage-solution-outline verb whose parser does not declare it; argparse rejected the call with `unrecognized arguments` (exit 2). The deliverable selector on that script uses its own declared flag name, which must be read from the script's Canonical invocations block or `--help`, not inferred from narrative wording ("deliverable number").

## Candidate rule

Invented-flag variant of the "Never invent script subcommands" recurrence: typed-ID flag names are per-script (see persona-plan-marshall-agent standards/argument-naming.md). Candidate for reinforcement of an existing lesson; component plan-marshall:manage-solution-outline.

Classification deferred to the orchestrator.
