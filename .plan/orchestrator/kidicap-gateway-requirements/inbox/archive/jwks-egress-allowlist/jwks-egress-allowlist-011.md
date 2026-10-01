envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:53:29Z

# Candidate lesson: nothing re-requests cuioss-review-bot after a loop-back fix under merge queue

Source: orchestrator observation 5 (this run); automated-review signal.

## What happened

`cuioss-review-bot` (PR-Agent) has no automatic re-review on push (`.github/workflows/pr-agent.yml` triggers only on
opened/reopened/ready_for_review plus on-demand `/review` comments), and `re_review_on_loopback=false`. After the loop-back
fix commit (TASK-7/TASK-8 for the CodeRabbit findings), the pre-merge barrier blocked on `participated_stale` for that
required bot. With `use_merge_queue=true`, branch-cleanup trigger A (the rebase-driven re-request) never fires because no
rebase happens, so no pipeline step re-requested the bot. An explicit `github_re_review` trigger (the CLAUDE.md `/review`
comment) resolved it manually.

## Corrective rule / suggested fix

For a required bot that does not re-review on push, a loop-back fix commit must itself trigger the explicit re-review
(set `re_review_on_loopback=true` for `cuioss-review-bot` in `.plan/marshal.json`, or make the barrier fire the configured
`github_re_review` trigger when it observes `participated_stale` for a bot with no push-triggered re-review) — independent of
whether merge-queue mode suppresses the rebase path.

## Components

.plan/marshal.json (review-bot config for cuioss-review-bot); plan-marshall:phase-6-finalize (pre-merge barrier, branch-cleanup trigger A); plan-marshall:automatic-review.
