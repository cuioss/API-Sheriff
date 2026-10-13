envelope_version=1
sender_type=plan
sender_id=jwks-refresh-follow-ups
epic=kidicap-gateway-requirements
kind=finding
created=2026-10-13T18:37:17Z

# PLAN-28 was run twice: duplicate run closed unmerged

A second plan (`jwks-refresh-follow-ups`) was started on 2026-10-13 from PLAN-28's hand-off command,
`/plan-marshall task="implement .plan/orchestrator/kidicap-gateway-requirements/plans/PLAN-28-jwks-refresh-follow-ups.md"`.
Another session was already implementing the spec, and shipped it as #437 (`82f2d7d8`, merged 13:45Z from
`feature/plan-28-jwks-refresh-follow-ups`). #443 (`815cc955`) recorded that landing.

## What happened to the duplicate

- It went through init, refine, outline, plan, and execute tasks 1 to 5 (deliverables 1, 2 and the docs part), on base `245e1928`.
- Execute's baseline check then found 8 upstream commits on `origin/main`, 6 of them in files it also changed. #437 was among them.
- The operator chose to close it as a duplicate. Its three local commits (`85da843`, `debe2c2`, `7d87997`) were never pushed, and no PR was opened. The worktree and branch are deleted, and the plan is archived with reason `superseded_by_pr_437`.
- No action is needed on PLAN-28's landing. #437 and #443 stand as the record.

## For the orchestrator

- The hand-off command could be emitted and run while the same spec was already in progress elsewhere. Nothing in init detected it: the sibling check only sees local plans, not open PRs or other sessions.
- A guard is worth adding. Before emitting, or at plan start, check for an open or merged PR, or a `landings/PLAN-NN.md`, for the same spec.
- The duplicate's refine phase carried a premise that #437 contradicts. It treated deliverable 3 (the monotonic clock) as "covered by a parallel task" and deferred it. #437 lands it as ADR-0063 amendment A1.
