envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:18:13Z

# Candidate lesson: the self-review surfacer refused because the local base branch sat behind origin/main

**Source signal**: script-failure cluster — `pm-plugin-development:ext-self-review-plan-marshall:self_review`, `exit_code=1`, `failure_kind=script_internal_failure` (2026-09-22T11:00:16Z, inside `pre-submission-self-review`)
**Component**: `pm-plugin-development:ext-self-review-plan-marshall`

## What happened

The deterministic candidate surfacer behind `pre-submission-self-review` refused outright:

> local base 'main' sits behind 'origin/main' — refusing to surface a stale scope; advance the local
> base past upstream first

The refusal is correct: a diff computed against a stale local base produces a **larger and wrong**
candidate set, and a self-review that examined the wrong scope while reporting a clean pass is the
false-green this check exists to prevent.

The situation arose from ordinary worktree mechanics. `finalize-step-sync-baseline` had rebased the
*feature branch* onto `origin/main` (`action=rebased, upstream_commits=1`), which advances the
branch but does not fast-forward the main checkout's local `main` ref.

## Why this is candidate-lesson shaped

A precondition on a *sibling ref* is easy to leave unsatisfied: the finalize pipeline is careful
about the branch under review and has no step whose job is keeping the local base ref current. The
consequence is that a correctly-designed refusal fires on a routine state, which trains a reader to
treat the refusal as noise rather than as the safety property it is.

Note the neighbouring degradation in the same step, which sharpens the point: when the scope *could*
be surfaced, the run still logged "full surface, 8 candidates examined, 0 findings" — i.e. the
difference between an analysis that RAN and observed nothing and one that never ran is exactly what
this refusal preserves.

## Candidate rule

A step whose deterministic surfacer diffs against a local base ref must ensure that ref is at or
ahead of upstream before invoking it — `git fetch` plus a fast-forward of the base ref, not a rebase
of the feature branch. The refusal is the right behaviour and should not be worked around by
widening the diff base.

## Disposition in this plan

Recovered in-run: the step was re-dispatched and completed (`outcome=done`), and later finalize
rounds ran the full surface. No workaround was applied to the surfacer. The precondition-ordering
gap is a plan-marshall tooling matter, outside PLAN-28's scope.
