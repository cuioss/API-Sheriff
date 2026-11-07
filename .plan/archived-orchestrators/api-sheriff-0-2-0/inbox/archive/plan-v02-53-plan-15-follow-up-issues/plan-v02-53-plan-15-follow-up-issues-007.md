envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:07:03Z

## Candidate lesson: pre-commit formatter keeps rewriting the blank import line in TerminalActionRulesRedirectTest.java (reverted 6 times)

- Source: orchestrator run observation (not a counted signal record)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Surface: API-Sheriff build (`verify -Ppre-commit` formatter/rewrite step); file not authored by this plan

### What happened

Every run of the pre-commit quality gate rewrote a blank import-separator line in `TerminalActionRulesRedirectTest.java`, a file the branch did not touch. Per the build-gate discipline the churn was reverted each time, six times in this run.

### Context

This is a recurrence of the import-spacing churn class that was fixed earlier (PR #415, and again by #559 / 7fde3dae). The standing project rule is to fix a recurrence in a separate skip-bot-review PR. The file is evidently still not in the formatter's stable state on main.

### Candidate rule / fix direction

Land the formatter's version of `TerminalActionRulesRedirectTest.java` on main in a small separate PR, and check whether more files are affected, so plans stop paying the revert cost on every gate run.
