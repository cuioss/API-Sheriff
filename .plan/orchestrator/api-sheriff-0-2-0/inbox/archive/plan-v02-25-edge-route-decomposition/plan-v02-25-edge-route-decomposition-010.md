envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T23:30:47Z

## Candidate lesson: post-merge `ci checks wait --adaptive` ran into its deadline only because the post-merge Performance Benchmark was still running

- Source: orchestrator-supplied observation (post-merge CI wait), PR #487
- Plan: plan-v02-25-edge-route-decomposition (V02-25, edge route decomposition)

### Observation

After the merge, `ci checks wait --adaptive` hit its deadline after 702s. Nothing had failed. The only check still running was the post-merge Performance Benchmark job (`.github/workflows/benchmark.yml`, triggered on `pull_request: closed` when merged, so it stays attached to the PR). Its runtime is longer than the adaptive wait budget.

### Candidate rule (for orchestrator classification)

A post-merge wait that includes the PR-attached benchmark run should not read a deadline as a failure. Either allow a separate, longer budget for that known slow job, or treat "deadline with only the benchmark still running and no failing check" as its own outcome, not a timeout. CLAUDE.md already puts post-merge verification with the orchestrator, not the plan, so the plan-side wait on this job may be redundant.

### Routing note

Could be project-side (benchmark workflow runtime or wait budget) or plan-marshall-side (adaptive wait classification). Left for the orchestrator to classify.
