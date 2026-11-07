envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:07:06Z

## Candidate lesson: a `pr checks --watch` wait right after a push exits before the new head's checks exist

- Source: orchestrator run observation (not a counted signal record)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Tool surface: plan-marshall (CI wait after push; tools-integration-ci / await-long-running)

### What happened

A background `pr checks --watch` wait started directly after a push exited immediately: the checks for the new head were not registered yet, so the watch saw the previous head's finished checks (or none) and returned at once, giving a false "done".

### Candidate rule / fix direction

A CI wait after a push must be bound to the pushed head SHA: wait until checks for that SHA are registered before watching for completion, or verify the head SHA of the reported checks. A wait that is not bound to the head can report a result for the wrong commit.
