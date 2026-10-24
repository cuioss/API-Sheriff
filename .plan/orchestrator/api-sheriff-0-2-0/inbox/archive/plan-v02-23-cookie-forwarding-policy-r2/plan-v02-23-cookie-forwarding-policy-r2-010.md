envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:36Z

component=api-sheriff
category=bug
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=Q-Gate 6-finalize finding cd9b16 (code review, taken_into_account)

# A WARN id was catalogued in doc/LogMessages.adoc for a new refusal path that no code emits

## What happened

The finalize code review found that doc/LogMessages.adoc (row ~75) catalogued WARN ApiSheriff-110
for new refusal paths introduced by the plan, but no code path actually emitted that record for
them. The documentation claimed an operator-visible signal that did not exist.

## Why it matters

doc/LogMessages.adoc is the operator contract for alerting. A catalogued-but-never-emitted id is a
silent observability gap: an operator alert built on it never fires.

## Proposed rule

- Every LogMessages.adoc row added or widened by a plan must be backed by a test that asserts the
  record is emitted on that path (JUL log capture via the CUI test log assertions).
- Code review should cross-check each catalogue row the plan touched against an emitting call site.
