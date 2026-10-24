envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:10:56Z

component=plan-marshall:phase-5-execute
category=bug
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=script_failure plan-marshall:manage-status:manage-status (failure_kind=script_internal_failure)

# Rebase rewrites commit SHAs and the 5->6 transition then refuses with execute_commits_unrecorded

## What happened

A rebase of the plan branch onto a moved main rewrote 13 of 17 worktree-branch commit SHAs. The
5-execute -> 6-finalize transition then refused (manage-status, exit 1): "13 of 17 worktree-branch
commit(s) since diverging from main carry no kind=change ledger row". The work was fully recorded
before the rebase; only the SHAs had changed. Recovery required re-appending one kind=change ledger
row per new SHA by hand before the transition would pass.

## Why it matters

The change ledger keys settlement on commit SHA, so any history rewrite (rebase, amend, fixup
squash) silently invalidates every prior row. The refusal message points at Step 10a settlement
bookkeeping, which reads as "work was not recorded" rather than "history was rewritten".

## Proposed rule / fix

- Either make the ledger rebase-aware (map old->new SHAs via patch-id or `git range-diff` and
  re-key rows automatically), or provide a dedicated re-key verb on manage-change-ledger.
- At minimum, the transition refusal should detect the rewrite case (unrecorded commits whose
  patch-ids match recorded rows) and name the re-key remedy explicitly.
