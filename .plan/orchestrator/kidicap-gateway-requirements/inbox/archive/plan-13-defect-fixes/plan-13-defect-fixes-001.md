envelope_version=1
sender_type=plan
sender_id=plan-13-defect-fixes
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-21T07:45:43Z

id=candidate
component=api-sheriff
category=insight
title=Owed architecture hint (preference-emitter): Sonar new-code findings recur on test-heavy api-sheriff changes

# Owed architecture hint — api-sheriff

- Target module: `api-sheriff`
- Enrich verb: `insight`
- Recurrence: 5 Sonar new-code findings in one plan (disposition taken_into_account)

## Generalized hint

Changes to api-sheriff that add retry/backoff logic and its tests draw Sonar new-code findings on the PR gate — cognitive complexity (java:S3776) in production parsing code and test-hygiene rules (java:S8745, java:S7467, java:S2093) in the accompanying tests. Budget for resolving them in-code within the same PR (method extraction, try-with-resources, test cleanup) rather than discovering them only after the Sonar build in CI.

Owed call: `architecture enrich insight --module api-sheriff` with the hint text above.
