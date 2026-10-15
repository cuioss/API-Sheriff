envelope_version=1
sender_type=plan
sender_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-15T09:40:42Z

## What landed

plan-v02-24-policy-block-resolution shipped as #465 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
pr=#465
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=10395666
total_wall_seconds=67488.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
```

## Residue

- Merge commit on main: 9e05a3d6514c90c498f7b6c730a0d84b8d7d7ada. The post-merge "Run Integration Benchmarks" job (benchmark.yml) was still running when finalize ended; it was not verified by this plan.
- Final re-fire at HEAD ec6cb7b5 ran functional-review and code-review as delta rounds over the 2 changed files (operator decision, logged at WARNING); the last full closing rounds were at 0ad1f16d.
- Open advisories not addressed in this PR: doc/development/README.adoc:354-356 says "every run fails" although the offline --validate-config run (null framework limit) never reaches the body-limit reader; the ConfigBootPipeline.java:192 javadoc overstates what a newly gained scope reaches without an edit.
- The sync-baseline rebase onto #463 (PLAN-V02-26) moved the allowed_methods resolution into RouteMatchingRules via PolicyBlock.ALLOWED_METHODS and deleted RuleSupport.resolveAnchor; later validator slices (V02-28..39) build on that state.
- One operator-deferred finding from this plan is recorded in the plan's findings store for a later plan; it is not restated here.
