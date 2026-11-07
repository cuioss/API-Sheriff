envelope_version=1
sender_type=plan
sender_id=plan-v02-56-final-cleanup
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-06T15:16:21Z

# Candidate lesson: run finalize simplify and security-audit serially, not in parallel

- component: plan-marshall:phase-6-finalize
- category (suggested): anti-pattern
- signal source: decision log (manage-execution-manifest record-step, freshness-reconcile)

## Observation

In one finalize round, finalize-step-security-audit was recorded at 06:33:11. At
06:33:23 the freshness-reconcile entry logged that finalize-step-simplify had committed
955b174b, which moved the worktree HEAD past the tree the audit had examined. The
security audit then had to be recorded a second time, at 06:34:18. The two steps were
run at the same time. Simplify is the step that changes source (mutates_source), so its
commit makes any head-bound step running alongside it stale.

## Defect shape

When a step that commits source runs in parallel with a head-bound review step, the
review step goes stale and has to be re-run narrowly. This adds a dispatch round and
spreads head-binding drift into the merge gate.

## Suggested corrective direction

Dispatch finalize-step-simplify (and any other step that commits source) on its own
and wait for its commit to land. Only then dispatch the head-bound steps
(security-audit, code-review). Alternatively, have the dispatcher refuse to run a
source-committing step in parallel with a head-bound one.
