envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T23:30:21Z

## Candidate lesson (plan-marshall tooling): undeclared `--number` flag passed to manage-solution-outline

- Source: script-failure cluster, notation plan-marshall:manage-solution-outline:manage-solution-outline
- Plan: plan-v02-25-edge-route-decomposition (V02-25, edge route decomposition)

### Observation

An agent passed `--number` to a `manage-solution-outline` verb. The flag is not declared, so argparse rejected the call as an unrecognized argument.

### Candidate rule (for orchestrator classification)

This is the typed-ID flag case of "never invent script arguments": a generic `--number` was guessed instead of the typed flag the verb declares. Check the verb's canonical-invocation block or `--help` for the deliverable-addressing flag before calling, and do not carry flag names over from `manage-tasks` (`--task-number`) or other siblings.

### Routing note

This is a plan-marshall tooling finding. The project's memory routes such findings to the plan-marshall lessons-routing inbox; this plan is orchestrated, so it is sent here as a candidate-lesson for the orchestrator to route.
