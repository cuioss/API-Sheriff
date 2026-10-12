envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:27:01Z

component=plan-marshall:phase-6-finalize
category=improvement
created=2026-10-08

# Finalize loop-back ceiling of 5 was too small for a plan with self-review, CI, review-bot and rebase rounds

## What happened

Plan plan-25-integration-test-evidence (PR #408). The project default `phase-6-finalize.max_iterations` is 5. All five iterations were spent by the pre-submission self-review before the PR was created. Everything after that needed operator intervention:

- CI failure at 84d296ad ("Offline configuration check"): ceiling spent, so fixed inline by operator decision instead of through a triaged fix task (decision log 936484).
- Operator raised the ceiling to 8 for this plan only; `.plan/marshal.json` not edited (decision log b1f1d3).
- Iterations 6 and 7: two CodeRabbit review rounds. Iteration 8: rebase and integration after `main` gained #412.
- Round 9, individually authorised past the plan-only ceiling: CI job cancelled by its own time limit (work log cdc2c8).
- One further round, individually authorised: one CodeRabbit comment on the integration commit (joined publish-option forms, fixed in 7692b10e; review-body finding 820c6d).

## Observations for the pickup

- One counter covers rounds of very different kinds: pre-PR self-review, CI repair, review-bot comments, rebase onto a moved main. A step that loops before the PR exists can consume the budget the post-PR steps need.
- With the ceiling spent, fixes were made inline and settle-band steps were deliberately not re-fired. That is a weaker path than the triaged one and it was taken out of necessity.
- A plan-only ceiling had to be kept by hand in the decision log; there was no per-plan setting used for it.

## Evidence

- Decision log entries 6e7fbe, 936484, b1f1d3, 27b7fd, 81c830, e68417; work log cdc2c8.
