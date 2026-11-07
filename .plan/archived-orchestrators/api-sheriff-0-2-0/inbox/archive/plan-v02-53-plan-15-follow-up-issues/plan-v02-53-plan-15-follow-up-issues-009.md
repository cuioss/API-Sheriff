envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:07:10Z

## Candidate lesson: ci_complete_precondition rewait_bound 3 is too small for a 21-45 minute integration-test job

- Source: orchestrator run observation (not a counted signal record)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Surface: plan-marshall finalize CI precondition (`ci_complete_precondition`, `rewait_bound`) together with this repository's CI duration

### What happened

The finalize CI-completion precondition gives up after 3 re-waits. API-Sheriff's integration-test job takes about 21 to 45 minutes, so the bound ran out while CI was still legitimately running, and the wait had to be driven again by hand.

### Candidate rule / fix direction

Either make the bound configurable per project (and set it for API-Sheriff to cover the integration-test job), or derive it from the observed CI duration rather than a fixed count.
