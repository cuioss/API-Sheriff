envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:06:59Z

## Candidate lesson: `git-workflow commit` reports FETCH_FAILURE when nothing is staged

- Source: orchestrator run observation (not a counted signal record)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Tool surface: plan-marshall (workflow-integration-git, `git-workflow commit`)

### What happened

`git-workflow commit` refused when there was nothing staged, but the error code was `FETCH_FAILURE`, which points at a network or remote problem. The real cause (empty index) had to be found by hand.

### Candidate rule / fix direction

An empty index should get its own code (for example `NOTHING_TO_COMMIT`) or a no-op success with an explicit discriminator, so callers do not chase a fetch problem that does not exist.
