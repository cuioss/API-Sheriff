envelope_version=1
sender_type=plan
sender_id=plan-v02-51-lane-conditional-guards
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-30T00:33:12Z

# Candidate lesson: branch-cleanup's post-merge CI wait blocks on a PR-attached post-merge run the plan does not own

**Component:** plan-marshall:phase-6-finalize (default:branch-cleanup post-merge tail)
**Category (suggested):** bug

## What happened

PR #538 was merged by the platform merge queue at 00:22:56 (7755b948). Branch cleanup then entered a post-merge CI wait. In this repository, `.github/workflows/benchmark.yml` is triggered by `pull_request: closed` gated on `merged == true`, so the post-merge benchmark run stays attached to the PR. The wait picked that run up and blocked past its per-call bound. At 00:29:55 the run logged:

> Branch cleanup: post-merge CI wait skipped after it exceeded the call bound on the PR-attached post-merge run; the PR is already merged and post-merge verification belongs to the orchestrator — continuing with branch cleanup

That cost about 7 minutes of a finalize envelope on a wait that cannot change any plan outcome. The project contract (CLAUDE.md Git Workflow step 8) already says the plan reports the merge and stops, and that post-merge verification belongs to the orchestrator.

## Proposed rule

Once the merge is corroborated, the branch-cleanup post-merge tail should not wait on CI runs. That includes PR-attached runs triggered by the PR's own close or merge event. It should record the merge and continue to the local/remote ref prune, and leave post-merge run verification to the orchestrator. The current behaviour reaches the right outcome only by timing out.

## Evidence

Work log 00e208 (WARNING, 2026-10-30T00:29:55Z) and decision log 69fa5a (queue-landing gate, merged after 1363s) / dc46d9 (post-merge cleanup dispatched 00:30:25) in plan-v02-51-lane-conditional-guards.
