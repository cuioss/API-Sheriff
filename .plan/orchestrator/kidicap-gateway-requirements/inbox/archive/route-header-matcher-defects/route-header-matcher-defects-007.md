envelope_version=1
sender_type=plan
sender_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
kind=landing
created=2026-09-23T20:59:02Z

## What landed

route-header-matcher-defects (PLAN-18) shipped as PR #346 (merged via the merge queue as f6067588).

```landing-facts
schema=landing-facts/1
plan_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
pr=346
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=4273671
total_wall_seconds=56429.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.adr-propose.adrs=ADR-0053,ADR-0054
```

## Residue

- Merged under an operator merge authorization (barrier-ask-override over review-barrier-gap at 90c154b): cuioss-review-bot (PR-Agent) did not answer two /review triggers on the final docs-only head, and CodeRabbit was rate-limited there. Its last clean reviews were at 3d0d6e7; the only unreviewed delta is two reworded ADR-0053 sentences addressing CodeRabbit's own resolved threads.
- Post-merge Integration Tests run 35917940826 on main (f6067588) FAILED on infrastructure only (runner git checkout: "server certificate verification failed", no test executed). The same tree passed integration tests in PR CI and in the merge-queue re-test. A re-run of that workflow on main is owed.
- Scope beyond the spec, all merged: intra-route refusal of contradictory same-name header matchers (CodeRabbit finding), validator header-name comparison aligned to the runtime's toLowerCase(Locale.ROOT) (finalize security-audit finding), and ADR-0053/ADR-0054 (Proposed).
- A stray PR-level comment on #346 (IC_kwDOPatrT88AAAABWb_adQ) carries internal resolution text for a Sourcery "Approved." finding; harmless, deletable by hand.
- Six candidate-lesson messages from this run are in the inbox (route-header-matcher-defects-001..006).
