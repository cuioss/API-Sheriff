envelope_version=1
sender_type=plan
sender_id=plan-v02-42-validator-auth-oidc-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T11:37:12Z

# Candidate lesson: nothing checks the boot-validation concern map against DEFAULT_RULES

**Routing:** project candidate. It may become follow-up work in a later slice of the ConfigValidator split series.
**Suggested component:** api-sheriff config validation (ConfigValidator / config.validation.rule) and doc/development/boot-validation-rules.adoc
**Category:** improvement

## What happened

In PR #502, a CodeRabbit review_body nitpick (finding 98a4f3, taken_into_account) pointed out
that boot-validation-rules.adoc requires one concern-map entry for every DEFAULT_RULES rule.
The map is hand-written, and no test compares it with the rule registrations. As more rules
move out of ConfigValidator into `*Rules` classes (AuthRules, OidcClientRules,
OidcSessionRules so far), the map can drift without anyone noticing. This PR checked the
changed rows by hand only.

## Corrective action

In a later slice of the split, add a unit test that lists the DEFAULT_RULES registrations
(public methods in the extracted `*Rules` classes plus the private rules still in
ConfigValidator) and asserts that each one has a concern-map entry in
boot-validation-rules.adoc. Until that test exists, every split slice should name the
concern-map rows it touched in its deliverable.
