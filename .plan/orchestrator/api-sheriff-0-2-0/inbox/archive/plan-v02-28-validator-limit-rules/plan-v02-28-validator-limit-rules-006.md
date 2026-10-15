envelope_version=1
sender_type=plan
sender_id=plan-v02-28-validator-limit-rules
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-15T15:19:45Z

## What landed

plan-v02-28-validator-limit-rules shipped as #467 (merged via the merge queue as efb3fa7e).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-28-validator-limit-rules
epic=api-sheriff-0-2-0
pr=#467
merge_state=merged
cleanup_owed=false
deliverables_total=2
deliverables_done=2
total_tokens=4573408
total_wall_seconds=34741.0
harness=unknown
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=0
step.functional-review.deliverables_met_with_residuals=2
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=2
step.code-review.rules_cited=2
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
```

## Residue

- PLAN-V02-24's #465 (policy-block resolution) landed while #467 waited and conflicted in all 5 shared files. Operator chose rebase+resolve: LimitRules carries #465's PolicyBlock/ScopeChain version of the minimal refusal verbatim; #465 deleted effectiveAuth/effectiveProfile/RuleSupport.resolveAnchor, so the outline criteria "effectiveAuth exists once, in RuleSupport" and "concern map names effectiveAuth" are moot (graded met_with_residuals). Later slices (V02-40..43) should re-ground against #465's RuleSupport (no effectiveAuth helper exists).
- #465 added `ConfigValidatorTest.EndpointScopeRefusals`, which mixes three endpoint-scope 'minimal' refusal tests (now exercising LimitRules) with forward-block tests. They were left in ConfigValidatorTest in this slice; code review flagged them (advisory) against boot-validation-rules.adoc § Tests. Candidate for PLAN-V02-27 or a later slice, together with the ConfigValidatorTest citation in doc/security-threat-model.adoc.
- LimitRulesTest class Javadoc says every case drives the full validator; two GetBodyOptInDefault cases do not (advisory, both reviews). Candidate for PLAN-V02-27.
- CodeRabbit (required) was quota-limited for ~6h; it reviewed both the original and the rebased head with no actionable comments. Sourcery (optional) refused on its weekly quota. The automatic-review step spent 3 of 5 loop-back rounds on the quota wait.
- Pre-push gate recorded module-tests DEGRADED (no default-scope module-tests canonical in this Maven project); verify -Ppre-commit ran 6134 tests green on the merged tree.
