envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:57Z

# Candidate lesson: worktree-remove from a worktree-pinned shell fails after integrate_into_main move-back

- Source: orchestrator-observed (not recorded as an [ERROR] line in the plan work or script log; the branch-cleanup worktree-remove at 2026-10-15T11:44:45Z succeeded)
- Plan: plan-32-early-answer-it-timeouts

## Observation

After the integrate_into_main move-back put the plan directory back on the main checkout, a later `git-workflow worktree-remove` issued from a shell still pinned to the (now removed or emptied) worktree returned `plan_resolution_failed`. The plan could no longer be resolved from that cwd.

## Candidate rule

Any cleanup that runs after move-back (worktree-remove, ci calls with --plan-id, status reads) runs from the main checkout, or passes `--project-dir <main checkout>`. Never run it from the worktree cwd. Related: the existing project memory "CI --plan-id After Worktree Removal" describes the same cwd failure for the ci router.
