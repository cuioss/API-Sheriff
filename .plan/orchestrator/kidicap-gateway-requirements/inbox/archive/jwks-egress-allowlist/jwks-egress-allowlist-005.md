envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:37Z

# Candidate lesson: line-number citations in dev docs drift when the cited test class is edited

Source: Q-Gate finding 37eb6f (4-plan, scope_criterion_validator under_coverage, deliverable 1; taken_into_account).

## What happened

The operationalized success criterion (content search on `secureDefault`) returned 7 files; the plan covered 6.
`doc/development/test-corpus-integrity.adoc` (lines ~2408-2417) cites `TokenValidatorProducerTest.java:247`
(`allowlistSurvivesTheProducerPath`) and discusses `EgressPolicy.secureDefault()` equality semantics — exactly the area
deliverable 1 rewrote and added test methods around. It was not declared anywhere in the outline, so the line citation
and its framing would have gone stale. Resolution: added as a TASK-5 survey step (re-check citation after TASK-2).

## Corrective rule

When a plan edits a test class, search the doc tree for `{TestClass}.java:` line citations and add every citing doc as a
survey step sequenced after the edit. Line-numbered citations are guaranteed to drift under any insertion above them.

## Components

doc/development/test-corpus-integrity.adoc; phase-3-outline / phase-4-plan affected-file coverage.
