envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-25T22:03:25Z

## What landed

plan-v02-27-test-simplification shipped as #520 (merged, squash via merge queue, 6b8d88f4).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
pr=#520
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=48083742
total_wall_seconds=28200
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.1986
executor_version=0.1.1986
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=1
step.functional-review.deliverables_met_with_residuals=4
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=11
step.code-review.advisory_count=1
step.code-review.rules_cited=1
```

## Residue

- Scope split (operator decision at outline): this PR covers edge/ plus the five test gaps. Two follow-up plans remain to be staged:
  - FU-1: config/validation/ duplicate removal (17 confirmed duplicates) plus the LimitRulesTest class Javadoc fix (#467 advisory).
  - FU-2: config/ and routing/ duplicates only (C1-C3, R1-R2), with helpers moved to ConfigFixtures.
- Coverage-profile follow-up: `verify -Pcoverage` runs without `clean`, so JaCoCo exec data accumulates across runs and the merged report reads a union of runs. Compared classes were checked against the run's own probe data.
- GW-07 request-body is PARTIAL in the threat model (it had been moved to COVERED and was set back after review). The residual is that no test observes the public `dispatchGrpc` handing its five-minute bound to the overload. The operator chose to hold this as the named residual.
- The `gw-07-upstream-ports` matrix row reads COVERED while naming an unobserved effect. This PR did not author that row and left it unchanged.
- Both finalize reviews were closed by operator override. The only blocking rows were 11 test-integrity rows for 3 class moves and 8 planned duplicate removals. The review verifiers confirmed these are not real weakenings.
- Tests: 6186 before, 6176 after (+4 gap tests, +1 HealthProbe test, +1 gRPC cap-breach test, -16 duplicates). No compared class fell below its 68481922 baseline.
- Post-merge checks are the orchestrator's: the PR-attached Performance Benchmark run 40236744674 was still in progress at merge time, and the main-branch Maven run for 6b8d88f4 also needs checking.
- No security note was recorded by this plan.
