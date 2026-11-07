envelope_version=1
sender_type=plan
sender_id=plan-v02-44-route-type-merge
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-16T10:31:02Z

# Candidate lesson: a docs edit left a contradicting statement elsewhere in architecture.adoc

**Suggested route:** project (API-Sheriff), keep local.

**Signal:** CodeRabbit finding fcef97 on PR #476 (`doc/architecture.adoc:1769`), resolved `fixed` in-run (fix commit f0219ff2).

## What happened

TASK-9 rewrote the resilience section of `doc/architecture.adoc` to say that circuit breakers are keyed by `RouteAssembler.ResilienceShape`, meaning upstream target plus retry setting (lines 1156-1157 and 1766-1769). An older sentence at line 1169 still said breaker state is per upstream target. That left two contradicting descriptions of whether one route's failures affect another. Neither the code review nor the functional review caught it. CodeRabbit did.

## Suggested corrective action

When a refactor changes the keying or scope of a runtime structure (breaker, cache, guard), search the whole of `doc/architecture.adoc`, and the other living docs, for every statement about that structure's scope, not just the section being edited. Reconcile all of them in the same commit.
