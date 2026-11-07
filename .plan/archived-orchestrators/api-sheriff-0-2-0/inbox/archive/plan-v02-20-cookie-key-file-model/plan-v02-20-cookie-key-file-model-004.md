envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:33:50Z

# Candidate lesson: a "count 0 everywhere" exit criterion contradicts the plan's own mandated residuals

- Source: Q-Gate findings 1ab517 (4-plan, error, over_coverage D6) and 3bea70 (4-plan, warning, over_coverage D3), resolution taken_into_account after an operator decision ("narrow the pattern") and a criteria re-freeze
- Suggested component: plan-marshall:phase-4-plan / plan-marshall:phase-3-outline (criterion authoring)

## What happened

D6 / TASK-010 required `architecture search --content` for the retired names to return count 0 over all files, and D3 required no `encryption_key` under api-sheriff/src. The plan itself mandated residual hits: the unknown-key refusal test in ConfigLoaderTest, the "no service sets SESSION_ENCRYPTION_KEY" assertion in BffCookieActivationWiringTest, the migration note in doc/user/bff-cookie.adoc, and ADR-0072 plus the ADR-0058 amendment naming the replaced field. The criterion was unreachable; resolving it cost a re-opened 4-plan, a re-dispatch and a second criteria re-freeze.

## Corrective rule

When writing a removal exit criterion, list the deliberate residuals (refusal tests, migration notes, ADRs recording the old name) in the same breath and split the criterion: retired *statements* count 0 everywhere; retired *identifiers* count 0 only in production sources, overlays, compose and workflows, with an explicit allow-list for the rest.
