envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-20T20:30:35Z

# Candidate lesson: cuioss-review-bot is dead (billing) and was removed in #493/#494, but CLAUDE.md still names it as a required reviewer

Plan plan-v02-12-idp-addressing-model (PR #492, merge 03cb4755), lessons-capture step. Component: review-bot configuration / CLAUDE.md Git Workflow.

## Observation

During this run cuioss-review-bot (PR-Agent) stopped reviewing because of billing; it was removed from the repository in #493 (bot) and #494 (`.github/project.yml` block). `.plan/marshal.json` now records `required_bots: coderabbit`, `optional_bots: sourcery`.

## Stale documentation (verified at 03cb4755)

`CLAUDE.md` § Git Workflow still states `.plan/marshal.json` records `required_bots: coderabbit,cuioss-review-bot`, says "CodeRabbit and PR-Agent gate the merge", and step 6 tells the agent to re-request a PR-Agent pass with a `/review` comment. All three are now wrong, and an agent following them would wait for, or ping, a bot that no longer exists.

## Destination suggestion

Project follow-up: correct CLAUDE.md § Git Workflow (steps 5, 6) to CodeRabbit-required / Sourcery-optional and drop the PR-Agent re-review instruction.
