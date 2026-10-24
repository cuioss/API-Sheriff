envelope_version=1
sender_type=plan
sender_id=plan-v02-46-upstream-close-after-early-answer
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-22T18:05:40Z

## What landed

plan-v02-46-upstream-close-after-early-answer shipped as #513 (merged, e70743f).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-46-upstream-close-after-early-answer
epic=api-sheriff-0-2-0
pr=#513
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=5631308
total_wall_seconds=21285.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=3
step.functional-review.deliverables_met_with_residuals=0
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=2
step.code-review.rules_cited=2
```

## Residue

- Deliverable 1 (FIN-first) was settled by analysis per operator decision: SO_LINGER 0 kept, recorded as ADR-0066 Amendment A2 with one Linux reproduction; no FIN-first variant was built. Envoy CVE-2026-48743 not cited (unverified).
- Operator re-fire waiver at 02ad3b7 (docs-only CodeRabbit inline fix to ADR-0066 A2): simplify, security audit, functional review, code review and the local quality gate were not re-run on that commit; their verdicts stand from 4038ec7. CI, ci-verify, CodeRabbit and Sonar re-ran on 02ad3b7.
- First merge-queue attempt was ejected by a merge_group failure of Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway (green on PR CI and on the re-enqueue); operator chose a single re-enqueue, which merged. Filed as a candidate-lesson.
- Two code-review advisories left open (filed as candidate-lessons): EdgeAdmissionTest race case cannot fail on the pre-fix order; ADR-0066 A2 omits the request-body idle timeout as a close trigger.
- Post-merge push CI on main for e70743f was still in progress at landing time; orchestrator to verify.
