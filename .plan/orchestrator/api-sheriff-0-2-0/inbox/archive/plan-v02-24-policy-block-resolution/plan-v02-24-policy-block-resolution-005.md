envelope_version=1
sender_type=plan
sender_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T09:39:36Z

# Candidate lesson: sibling validator slices that touch ConfigValidator collide at sync-baseline rebase

**Source**: plan-v02-24-policy-block-resolution (PR #465, merged as 9e05a3d6), finalize sync-baseline step.

## What happened

While this plan was in flight, a parallel plan (PR #463, "extract first ConfigValidator concern rule classes") landed on `main` and split `ConfigValidator` into concern rule classes. This plan also edited the policy-block resolution paths in `ConfigValidator`. Rebasing onto `main` at sync-baseline therefore needed manual conflict resolution: this plan's edits had to be moved into the extracted rule classes. The overlap was a known, accepted risk recorded at refine time, so this was not a process failure. The extra work landed in finalize, though, not in planning.

## Corrective rule (for orchestrator sequencing)

When two staged plans in the epic both edit `ConfigValidator`, or any class another slice is splitting, the orchestrator should do one of two things:

- serialize them, landing the structural split first and re-grounding the dependent spec against the post-split HEAD, or
- if they must run in parallel, note in the dependent plan's spec that a rebase onto the split is expected and which rule class each edit moves to.

This makes the conflict resolution a planned step. Otherwise it is a surprise in finalize.

## Suggested classification

Epic-level (api-sheriff-0-2-0 orchestration / validator slices V02-28..39), not a project code lesson. Relevant because the staged validator slices continue the same split.
