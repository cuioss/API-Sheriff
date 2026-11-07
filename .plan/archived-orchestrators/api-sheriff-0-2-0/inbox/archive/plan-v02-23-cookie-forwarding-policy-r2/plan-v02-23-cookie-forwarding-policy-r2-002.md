envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:00Z

component=api-sheriff
category=anti-pattern
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=execution (needed TASK-15)

# New WARN LogRecord reused a retired id (ApiSheriff-123) and broke an integration test

## What happened

A new WARN LogRecord was allocated the id ApiSheriff-123, which had been retired earlier. An
existing integration test (NoCertificatePlainHttpOptInIT) still keys on the old meaning of that id
in the gateway log, so the reuse turned it red. Fixing it needed an extra task (TASK-15).

## Why it matters

Log ids are a public contract (doc/LogMessages.adoc, operator alerting, IT log assertions). A
retired id that is silently reused changes the meaning of an existing signal; tests and downstream
alert rules that still reference it break or, worse, keep passing on the wrong event.

## Proposed rule

- Never reuse a retired LogRecord id; allocate the next free id above the highest id ever issued
  in the range, not the lowest gap.
- Before allocating, search both doc/LogMessages.adoc and the test trees (unit + IT) for the
  candidate id; a hit anywhere means it is not free.
- Consider recording retired ids explicitly in doc/LogMessages.adoc (a "retired" row) so the gap is
  visibly not available.
