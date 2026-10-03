envelope_version=1
sender_type=plan
sender_id=plan-13-defect-fixes
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-21T07:45:51Z

id=candidate
component=plan-marshall:phase-6-finalize
category=insight
title=Owed hint (preference-emitter): ci-verify classifies this repo's Maven build jobs as policy failures

# Owed hint — ci-verify build-profile matching

- Target: `plan-marshall:phase-6-finalize` (ci-verify classifier), observed on API Sheriff
- Enrich verb: `insight`
- Recurrence: 4 triage findings in one plan (disposition taken_into_account) — `build / conclusion` x2, `build / sonar-build` x2, plus the `integration-tests / *` jobs

## Generalized hint

In this repository the CI jobs are named `build / build (25|26)`, `build / sonar-build`, `build / conclusion` (workflow `Maven Build`) and `integration-tests / test` (workflow `Integration Tests`). None of those names contains the build-profile tokens ci-verify matches (`verify`, `quality-gate`, `module-tests`, `coverage`), so every red build or test job is filed as `[ci_policy_failure]` instead of `[ci_build_failure]` and routed to the policy triage producer. Either override the match rule through the architecture configuration for this project, or extend the token set with the workflow names above.

No `architecture enrich` call can express the classifier override directly; this needs an orchestrator-side decision on where the match rule should live.
