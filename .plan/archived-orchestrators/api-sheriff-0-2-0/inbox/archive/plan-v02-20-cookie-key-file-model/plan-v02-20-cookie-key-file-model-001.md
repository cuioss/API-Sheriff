envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:33:15Z

# Candidate lesson: outline sweep classified carriers as survey-only that its own exit regex could not catch

- Source: Q-Gate finding 770acd (3-outline, scope_criterion_validator, under_coverage, D6), resolution taken_into_account
- Suggested component: plan-marshall:phase-3-outline (project-side: doc sweep of a removed config surface)

## What happened

Deliverable 6 classed doc/user/bff-session.adoc and doc/fapi_status.adoc as survey-only, although the outline-time sweep already showed both still carrying the retired shape ("may name the same file", "passed sealing key"). The D6 exit regex (`encryption_key|encryptionKey|SESSION_ENCRYPTION_KEY|passed key`) did not match those phrasings, so the exit condition could pass with stale sentences in place.

## Corrective rule

When a deliverable retires a concept, derive the exit search from the retired *statements* (prose paraphrases), not only the retired identifiers, and move every sweep hit into the mutate set instead of survey-only. Fix applied: both files moved to mutate; regex widened to `passed (sealing |cookie |session )?key|may name the same|two (separate )?keys are recommended`.
