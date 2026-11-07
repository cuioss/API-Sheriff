envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:06:52Z

## Candidate lesson: execute_commits_unrecorded at the 5-execute to 6-finalize transition after a finalize simplify commit

- Source signal: script-failure cluster `plan-marshall:manage-status` (error `execute_commits_unrecorded` at the 5 to 6 transition)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Tool surface: plan-marshall (manage-status transition guard, phase-6-finalize item 5f, manage-change-ledger)

### What happened

The 5 to 6 transition refused with `execute_commits_unrecorded`. The unrecorded commit was a5cebaef, the finalize simplify step's commit. It had no `kind=change` ledger row because finalize item 5f commit instrumentation does not append one, yet the transition guard counted it as an execute commit without a ledger record.

### Candidate rule / fix direction

The guard and the commit producers disagree on which commits must carry a change-ledger row. Either finalize item 5f should append a `kind=change` row for every source-mutating finalize commit, or the guard should exclude commits produced by finalize steps. As it stands the guard fires on a path the pipeline itself produces.
