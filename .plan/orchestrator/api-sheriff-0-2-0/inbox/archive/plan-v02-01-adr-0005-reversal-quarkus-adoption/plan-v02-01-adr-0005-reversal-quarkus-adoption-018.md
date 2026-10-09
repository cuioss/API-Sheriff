envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:07:08Z

# Finalize stopped at the automated-review step and was closed by hand, so no file record was captured

Source: finalize phase of the plan, as established by the orchestrating session; the work log shows the stop on 2026-10-08 08:04 with 11 of 19 finalize steps done.

What happened: When PR #409 was split, the plan's finalize stopped at the automated-review step. The remaining steps were closed by the operator afterwards. By then the plan's worktree had a follow-up branch checked out, so the record of which files the plan actually changed could not be captured from it.

Candidate rule: When a plan's PR has to be split mid-finalize, capture the record of changed files from the plan branch before the worktree is reused for another branch. A finalize that stops for an operator decision should say which later steps depend on the worktree still being on the plan branch.
