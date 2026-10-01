envelope_version=1
sender_type=plan
sender_id=routing-and-response-headers
epic=kidicap-gateway-requirements
kind=landing
created=2026-09-17T15:38:25Z

## What landed

routing-and-response-headers shipped as PR #320 (merged via the merge queue as 93a4b3e).

```landing-facts
schema=landing-facts/1
plan_id=routing-and-response-headers
epic=kidicap-gateway-requirements
pr=#310
merge_state=merged
cleanup_owed=false
deliverables_total=11
deliverables_done=11
total_tokens=10424874
total_wall_seconds=174443.0
steps=finalize-step-sync-baseline:done,pre-push-quality-gate:done,pre-submission-self-review:done,finalize-step-simplify:done,finalize-step-security-audit:done,architecture-refresh:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:skipped,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
step.branch-cleanup.merge_state=merged
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- `pr=#310` is the create-pr step's recorded fact, which is out of date. PRs #310-#313 and #316-#318 were closed because of a CI-trigger problem in the repository (cuioss/cuioss-organization#279), not because of the code. The branch actually landed as PR #320, merge commit 93a4b3e57df7b25b51b893c36fce271147468880.
- The recorded done outcomes for the head-dependent steps (pre-push-quality-gate, pre-submission-self-review, finalize-step-simplify, finalize-step-security-audit, ci-verify, automatic-review, sonar-roundtrip) date from older commits. At the merged head e0e9fcf they would all have re-fired (verdict_currency: invalidated, verdict_inputs_undeclared). The operator merged anyway, on this evidence at e0e9fcf: CI fully green including native integration tests, CodeRabbit reviewed the commit with no actionable comments, cuioss-review-bot reported no issues, and zero review threads were unresolved. Those local steps were not re-run on the last commits.
- adr-propose and lessons-capture were recorded as skipped because their lane is off in the plan manifest, so they were not dispatched.
- Follow-ups listed in the PR body are not filed as issues: the sameOrigin encoded-spelling blind spot in PendingAuthorizationRecord, file-level declaresLimitShapedKey granularity, the anchor-coverage rule restated in four prose places, hand-mirrored security_headers/cors fixtures, five duplicated slash-stripping helpers, no native IT assertion for redirect no-store, and a forward block on redirect routes that is accepted but never read.
