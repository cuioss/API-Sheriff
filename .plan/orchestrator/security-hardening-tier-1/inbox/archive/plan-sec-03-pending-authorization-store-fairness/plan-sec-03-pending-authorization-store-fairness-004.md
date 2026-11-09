envelope_version=1
sender_type=plan
sender_id=plan-sec-03-pending-authorization-store-fairness
epic=security-hardening-tier-1
kind=candidate-lesson
created=2026-11-09T11:08:10Z

component=plan-marshall:phase-6-finalize
category=bug
title=Dispatched branch-cleanup stalls on its own background queue wait, and a skipped adr-propose is caught by no guard

# Dispatched branch-cleanup stalls on its own background queue wait, and a skipped adr-propose is caught by no guard

## Observation

In plan-sec-03-pending-authorization-store-fairness the `branch-cleanup` step ran as a dispatched agent with `use_merge_queue: true`. The agent enqueued the PR and then started a background wait for the queue landing and the post-merge CI. A dispatched leaf cannot be woken by its own background task, so the agent stalled waiting on itself and had to be stopped.

After it was stopped, the whole post-merge cleanup sequence had to be performed by the orchestrator by hand: `integrate_into_main integrate` -> `worktree-remove` from the main checkout -> `switch-and-pull` -> `prune-local-and-remote-ref` -> recording the merge on the step.

Separately, `adr-propose` (order 62) was not run before `branch-cleanup` (order 70). Nothing caught the ordering gap at the time - not the re-entry check, not the merge gate. It surfaced only at the tail, when the manifest still listed `adr-propose` with no record after the branch was already merged and the worktree removed.

## Why it matters

- A leaf that backgrounds result-bearing work violates the leaf contract (only the orchestrator-tier `await-long-running` seam may background a wait it needs the result of). The stall cost wall-clock time and left the cleanup half-done.
- Steps ordered before the merge gate can be silently skipped; the only enforcement is the end-of-phase `phase_steps_complete` handshake, which fires after the point where the skipped step could still have run in order.

## Suggested correction

- Make the merge-queue landing wait in `branch-cleanup` an orchestrator-owned barrier: the leaf enqueues and returns a wait signal, the orchestrator awaits via `await-long-running`, then re-dispatches the post-merge half.
- Add a pre-merge guard (for example in the pre-merge gate) that refuses to merge while any manifest step ordered below `branch-cleanup` has no terminal record.
