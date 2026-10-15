envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:55Z

# Candidate lesson: verification-feedback has no producer value for the ci-verify caller

- Source: orchestrator-observed; checked against plan-marshall/workflow/verification-feedback.md (Producer modes table and Inputs accept-set) and the plan work log (2026-10-15T07:10:22Z ci-verify dispatched verification-feedback as ci-verify-triage, which led to TASK-011)
- Plan: plan-32-early-answer-it-timeouts

## Observation

ci-verify routes a red CI result through verification-feedback, but that workflow's `producer` accept-set is `build-runner`, `sonar`, `pr-comment`, `plugin-doctor`, `pr-state`, `finalize-feedback`. No value names ci-verify. The doc only mentions ci-verify to say `ci-verify-timeout` is rejected. The ci-verify dispatch therefore has to borrow a producer whose Step 1 branch was written for a different caller.

## Candidate rule

Every caller that dispatches verification-feedback needs its own producer value (for example `ci-verify`) with a documented Step 1 branch and skills[] list. Alternatively, the ci-verify doc names exactly which existing producer it passes and why that producer's Step 1 fits.
