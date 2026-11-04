envelope_version=1
sender_type=plan
sender_id=plan-v02-55-it-concurrency-and-local-image-hazard
epic=api-sheriff-0-2-0
kind=landing
created=2026-11-04T19:39:20Z

## What landed

plan-v02-55-it-concurrency-and-local-image-hazard shipped as #558 (merged via merge queue as d70d2f16); #423 commented with before/after figures and closed.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-55-it-concurrency-and-local-image-hazard
epic=api-sheriff-0-2-0
pr=#558
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=26775137
total_wall_seconds=257941.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2036
executor_version=0.1.2036
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.code-review.blocking_count=0
step.code-review.advisory_count=5
step.code-review.rules_cited=5
step.branch-cleanup.merge_commit=d70d2f164a6d9cca295b0fe935611e0ce56cf650
step.branch-cleanup.merge_mechanism=merge_queue
```

## Residue

- **Functional review closed by the operator.** It accepted the five D6 findings because D6 can only run after the merge. D6 then ran after the merge: #558 merged, CI on `main` for d70d2f16 green, #423 commented and closed.
- **Review verdicts carried forward.** The code-review, functional-review, simplify and security-audit verdicts recorded at ebdb80e6 were carried forward to b9f814f1 by operator decision. The delta was four commits to one test, `ComposeFixtureImageBuildWiringTest`, all hardening asked for by CodeRabbit. `verdict_currency` reported every step invalidated only because no step declares its verdict inputs. This went to plan-marshall `lessons-routing` as api-sheriff-api-sheriff-0-2-0-079.
- **CodeRabbit took four review rounds** before it stopped raising points on that one wiring test. Each round asked for a stricter check of when Maven runs an execution. One point (ordering against the native build) was declined as outside the test's scope. Local lesson recorded: 2026-11-03-08-001.
- **Formatter churn is a separate change.** The pre-commit formatter rewrite of 380 files went in as its own PR, #559 (`skip-bot-review`, merged as 7fde3dae), not as part of this plan.
- **Follow-up candidates from the code-review advisories** (nothing blocking):
  - the `OneOffGatewayContainers` guards have no tests;
  - stale javadoc at `BffTokenRefreshIT:121-122`;
  - a build option read as a service name in `ComposeFixtureImageBuildWiringTest`;
  - descriptor derivation duplicated between that test and `BffSessionIdleTimeoutIT`;
  - no failing test covers the parallel parameter at `integration-tests/pom.xml:401-403`.
- **Transient wait error.** `wait-for-queue-settle` errored on a GitHub read after the PR had merged, and a retry settled. This is a recurrence and went to plan-marshall `lessons-routing` as api-sheriff-api-sheriff-0-2-0-080.
