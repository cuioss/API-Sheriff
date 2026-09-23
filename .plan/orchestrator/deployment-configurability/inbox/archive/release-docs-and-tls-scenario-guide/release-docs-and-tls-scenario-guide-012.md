envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:24:15Z

component=plan-marshall:workflow-integration-git
category=bug
source_finding=operational event (orchestrator-reported), plan release-docs-and-tls-scenario-guide

# prune-local-and-remote-ref errors when worktree-remove already deleted the local branch, leaving the tracking ref unpruned

## What happened

In finalize cleanup, `worktree-remove` deleted the plan's local branch along with the worktree. The subsequent `prune-local-and-remote-ref` then tried to delete that local branch, errored because it no longer existed, and stopped before pruning the remote-tracking ref — so the stale `origin/{branch}` tracking ref was left behind.

## Corrective action

`prune-local-and-remote-ref` should be idempotent per ref: an already-absent local branch is a `skipped/already_absent` outcome, not an error, and the verb must still proceed to prune the remote-tracking ref (and report each ref's outcome separately). Alternatively, make the two verbs agree on which one owns local-branch deletion.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305, squash-merged as fb65222)
- Related memory: orphaned-worktree-prune (manual plain-git prune after worktree-remove dead-ends)
