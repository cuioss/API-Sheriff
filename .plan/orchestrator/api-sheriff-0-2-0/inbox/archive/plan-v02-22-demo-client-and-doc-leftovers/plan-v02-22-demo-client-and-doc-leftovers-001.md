envelope_version=1
sender_type=plan
sender_id=plan-v02-22-demo-client-and-doc-leftovers
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-22T19:23:58Z

## What landed

plan-v02-22-demo-client-and-doc-leftovers shipped as #512 (merged, merge commit abf911dc0de9d71ae6f721b3784d40b3187aa5c9); issue #189 closed against it.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-22-demo-client-and-doc-leftovers
epic=api-sheriff-0-2-0
pr=#512
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=8537648
total_wall_seconds=49733.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
```

## Residue

- New CI workflow `.github/workflows/release-guard-docs.yml` (pull requests into main touching release.yml, release-process.adoc or itself) plus `ReleaseGuardPinDocsContractTest` now guard the release-guard pins and quoted reason lines in `doc/development/release-process.adoc`. When the cuioss-organization release workflow or release-guard action pin moves, that section must be re-checked; the workflow fails until it is.
- Code and functional reviews were closed by operator decision after round 8 (workflow-only scope); CodeRabbit re-reviewed every later head and confirmed the fixes.
- The runtime stamp was not refreshed at landing: this tree's executor rejects `orchestrator refresh-runtime-stamp` as an unknown verb (marshal.json is stale; /marshall-steward pending), so the start-time stamp is reported.
- `github_pr fetch_findings` stored only CodeRabbit's review summaries for its re-reviews of 64db644 and f7ffa02, not the four inline comments; those were replied to directly on the PR.
