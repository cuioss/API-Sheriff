envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T23:30:09Z

## Candidate lesson (plan-marshall tooling): invented manage-findings verb `query`

- Source: script-failure cluster, notation plan-marshall:manage-findings:manage-findings
- Plan: plan-v02-25-edge-route-decomposition (V02-25, edge route decomposition)

### Observation

During the run an agent called `manage-findings query`. The script has no `query` verb, so argparse rejected the call. The correct verb for reading plan findings is `list` (for example `manage-findings list --plan-id P --type pr-comment --resolution fixed`, or `manage-findings qgate list --plan-id P --phase PHASE`).

### Candidate rule (for orchestrator classification)

This is the verb-paraphrase signature of "never invent script subcommands": `query` is plausible because `manage-change-ledger` really has a `query` verb, so the name crosses over between sibling manage-* scripts. Take verbs from the script's canonical-invocation block, not from a sibling script.

### Routing note

This is a plan-marshall tooling finding. The project's memory says such findings belong in the plan-marshall lessons-routing inbox; this plan is orchestrated, so it is sent here as a candidate-lesson for the orchestrator to route.
