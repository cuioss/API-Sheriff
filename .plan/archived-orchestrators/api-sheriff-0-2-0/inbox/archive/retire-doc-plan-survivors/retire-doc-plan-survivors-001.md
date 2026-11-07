envelope_version=1
sender_type=plan
sender_id=retire-doc-plan-survivors
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-01T15:04:50Z

## What landed

retire-doc-plan-survivors (PLAN-V02-18) shipped as #368 (merged, merge commit 4228d42fc8f95e44a0798ae8d9df0af326d4742a).

```landing-facts
schema=landing-facts/1
plan_id=retire-doc-plan-survivors
epic=api-sheriff-0-2-0
pr=#368
merge_state=merged
cleanup_owed=false
deliverables_total=2
deliverables_done=2
total_tokens=3956398
total_wall_seconds=11208.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:skipped,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.create-pr.pr_number=368
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.branch-cleanup.work_performed=true
step.sonar-roundtrip.count_status=confirmed
step.sonar-roundtrip.new_code_issue_count=0
step.sonar-roundtrip.issues_fetched=0
step.record-metrics.total_tokens=3956398
step.record-metrics.total_wall_seconds=11208.0
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- `archive-plan` is listed as `pending` because it runs after this message is written; it is not a failure.
- The archived index copy `.plan/orchestrator/api-sheriff-0-2-0/archive/doc-plan-README.adoc` predates the last edit of `doc/plan/README.adoc` (#341). The plan left the archive untouched (write boundary); the quality-report NOTE says so truthfully. The final revision of the index survives only in git history. An orchestrator-side refresh of that archive copy is open.
- `.plan/project-architecture/_project.json` and `documentation/enriched.json` still describe "remaining implementation plans under doc/plan/". The finalize architecture refresh reported no structural change and did not rewrite them. A `/marshall-steward` pass is needed.
- `AGENTS.md` was edited in addition to the spec's stated footprint, so both instruction files state the same end condition for the Pre-1.0 Rules.
- The milestone wording names the 1.0 release of the current `de.cuioss.sheriff.gateway` line and states that the abandoned 2026-07-12 `de.cuioss.sheriff.api` 1.0.0 publication is not that cut. The spec's shorter phrasing ("1.0 not yet cut") would have contradicted the recorded accidental publication.
- `pre-push-quality-gate` was skipped on the operator's instruction: the change is documentation-only and the build decision for the footprint was "not necessary". No Maven build ran for this plan.
- CodeRabbit reviewed commit e3db868 in full and raised one inline comment, fixed in 1a33cd6d. For the fix commit it posted a "review limit reached" notice and only marked its thread as addressed; it did not run a full review of 1a33cd6d. The participation check counted it as having participated.
- cuioss-review-bot does not re-review on push; a `/review` comment was posted after the fix push and it then updated its review to 1a33cd6d.
- The PR-attached post-merge Performance Benchmark run (36879327716) was still in progress when the plan's post-merge wait ended (821 s). Its outcome and the main-branch Maven Build run for merge commit 4228d42f were not observed by this plan.
- The PR body's Intent section was truncated by the renderer (1328 of 1574 characters).
