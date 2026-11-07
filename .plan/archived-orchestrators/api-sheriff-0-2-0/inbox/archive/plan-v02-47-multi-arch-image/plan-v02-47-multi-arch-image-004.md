envelope_version=1
sender_type=plan
sender_id=plan-v02-47-multi-arch-image
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-21T18:28:01Z

## What landed

plan-v02-47-multi-arch-image shipped as #504 (merged through the merge queue as 8e218103).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-47-multi-arch-image
epic=api-sheriff-0-2-0
pr=#504
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=7704153
total_wall_seconds=38117.0
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.1977
executor_version=0.1.1977
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
```

## Residue

- The `release` workflow now builds and tests linux/amd64 and linux/arm64 natively on separate runners, joins the two tested digests into one index and signs the index plus both platform digests. ADR-0070 records the decision and the CPU baseline (observed native targets x86-64-v3 and armv8.1-a; `-march=compatibility` declined).
- No release has been cut from the new lane yet. Tags up to 0.2.4 are linux/amd64 only; the first release cut after this merge publishes the first two-platform index. The docs say so.
- Dry runs on the pushed branch were green with the `release` job skipped (runs 39603044532 on bc3a6c38 and 39627645198 on f59d87c0), recorded as PR comments.
- The functional review and the code review were closed by operator override, and the later simplify, security audit, functional review, code review and pre-push gate re-runs were waived for the documentation-only follow-up commits (TASK-8 and the caveat commit f120bad). The pre-push gate was recorded on the basis of the builds that were green at 62abbca. CodeRabbit, CI and Sonar on the pull request were the check; Sonar showed no new-code issues.
- CodeRabbit's review allowance ran out on the last head; Sourcery refused the review on its quota (optional bot, non-blocking).
- Three candidate lessons were sent to this epic's inbox by this plan.
