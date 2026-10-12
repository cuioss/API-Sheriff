envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:27:04Z

component=api-sheriff
category=bug
created=2026-10-08

# The quality gate exits 0 but rewrites untouched files on every run

## What happened

Plan plan-25-integration-test-evidence (PR #408). Reported by the orchestrator for this run: `verify -Ppre-commit` exits 0 and, on every run, strips the blank line between import groups in 284 `api-sheriff` files the branch never touched. The churn had to be reverted after each gate run, and the gate ran many times in this plan.

The plan logs corroborate the mechanism, not the count: the execute-exit gate run "rewrote 4 branch-authored test files", which were committed as 5891a455 (decision log 892a22); a later gate run is recorded as "success, 988 s, tree clean" only after such handling (decision log 203a2d).

## Rule (already stated in CLAUDE.md, restated because it cost time in every round)

After every gate run: `git status --porcelain`, attribute the dirty tree, revert unrelated churn, keep the rewrite only for files the branch authored.

## Open point for the pickup

A rewrite that reappears identically on every run in 284 untouched files is a standing defect of the gate configuration or of the files, not a per-plan chore. Either the recipe should not touch them or the files should be normalised once on `main`, so that a clean tree after the gate becomes the normal case again.

## Evidence

- Decision log entries 892a22, 203a2d.
- The figure 284 and "every run" come from the orchestrator's account; they are not in the plan logs read for this message.
