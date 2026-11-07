envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:35:48Z

# Candidate lesson: `ci pr wait-for-queue-settle` timed out while the merge-group run had already passed

**Source signal:** orchestrator observation during finalize (merge queue)
**Component (suggested):** plan-marshall:tools-integration-ci (pr wait-for-queue-settle)
**Category (suggested):** bug
**Related existing lesson:** 2026-10-03-06-004 ("the queue-landing wait is shorter than the queue's own re-test, and its fallback draws on a budget other steps have already spent")

## What happened

- `ci pr wait-for-queue-settle` timed out at 1800s. At that point the queue entry still showed `AWAITING_CHECKS`, but the merge_group run had already finished with success.
- PR #387 merged moments later (merge commit 1a20edad).

## Why it matters

The wait watches only the queue entry state. That state lags the check result, so a timeout here does not mean the queue failed. It can push the finalize run into a fallback path or an operator question it did not need.

## Suggested direction

When the queue entry shows `AWAITING_CHECKS`, also check the merge_group run for the entry's head. If that run succeeded, keep polling for the merge for a short grace period rather than reporting a timeout.
