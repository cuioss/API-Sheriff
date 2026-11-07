envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:36:06Z

# Candidate lesson: review-bot findings fixed in PR #387 (three CodeRabbit inline comments)

**Source signal:** automated review (3 pr-comment findings with resolution fixed: a17382, 9095e1, 701a21)
**Component (suggested):** api-sheriff / integration-tests / .github workflows
**Category (suggested):** improvement

## Findings and what each one teaches

1. **a17382 — .github/workflows/config-validation.yml:147.** The list of config sets the workflow validates was kept by hand, so a new `integration-tests` `sheriff-config-*` directory would go unvalidated without anyone noticing. Fix (TASK-15): the workflow records each validated set, and a final step fails when a config directory has no validation result. The only way out is an explicit, commented exclusion list.
   Rule: a hand-kept list of things to cover needs a guard that fails when the actual set of things grows.
2. **9095e1 — ConfigValidationCommand.java:305.** A validation report entry could spread over several lines when a message contained line breaks. Fix (operator chose fix, commit 30c9a511): each report record is one line, with LF rendered as `\n`.
   Rule: line-oriented report output must escape embedded newlines so one record is always one line.
3. **701a21 — doc/configuration.adoc:176.** The doc described failure-report granularity incorrectly. Checked against TopologyResolver and EnvSecretResolver, the stage reports one ApiSheriff-200 entry per failed alias, with all of that alias's missing variables in that single entry. Only a passthrough_sni target missing from topology.properties is left to the validator. Fix (TASK-14).
   Rule: check doc statements about how errors are grouped against the resolver code, not against what the outline intended.

## Note

All three were caught by the review bot after the PR was opened, not by the in-run self-review.
