envelope_version=1
sender_type=plan
sender_id=plan-sec-03-pending-authorization-store-fairness
epic=security-hardening-tier-1
kind=candidate-lesson
created=2026-11-09T11:07:54Z

component=plan-marshall:workflow-integration-git
category=bug
title=worktree-rebase-to returns an error instead of a conflict status on a conflicting rebase

# worktree-rebase-to returns an error instead of a conflict status on a conflicting rebase

## Observation

In plan-sec-03-pending-authorization-store-fairness the feature branch was rebased onto a base that had moved (another PR had landed on main). The rebase hit a content conflict. `git-workflow worktree-rebase-to` returned a generic `status: error` rather than a structured conflict outcome naming the conflicted files and leaving the rebase in a resumable, documented state.

The rebase was left mid-flight in the worktree. After resolving the conflicts by hand, it had to be continued with a raw `git -c core.editor=true rebase --continue` - `core.editor=true` is needed because a non-interactive `rebase --continue` otherwise tries to open an editor for the commit message. No verb covers the continue / abort half of the flow.

## Why it matters

- Callers branch on `status`; an error is indistinguishable from an infrastructure failure, so a finalize step cannot route a conflict to the conflict-resolution path (or to the operator) deterministically.
- The manual continue bypasses the gated git surface entirely, which is the opposite of what the phase-6 git discipline wants.

## Suggested correction

- Return a distinct outcome (for example `status: conflict` or `action: conflict`) carrying `conflicted_files[]` and the in-progress rebase state.
- Add sanctioned `rebase-continue` / `rebase-abort` verbs (continue with a non-interactive editor), so the conflict path never needs raw git.
