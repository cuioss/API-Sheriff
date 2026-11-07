envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:39:00Z

# Closing a recorded-open boundary must sweep every doc that records it

**Surface:** API-Sheriff docs (ADRs, `doc/security-threat-model.adoc`); secondary: `plan-marshall:phase-3-outline` doc-impact sweep
**Signal:** Q-Gate records 66f9aa (warning) and f5b64d (info) — 3-outline, taken_into_account
**Plan:** plan-v02-05-response-hygiene

## Observation

- 66f9aa: deliverable 3 closed the forward-all `not_modified` validator asymmetry, but `doc/adr/0008-data-plane-transport.adoc` (Decision bullet and Amendment A1) still recorded that boundary as open ("recorded rather than resolved"). It was in no deliverable, so after the change it would contradict the code and ADR-0037.
- f5b64d: the plan added a default identity-header strip on all response paths and pinned version-string absence in `ErrorContractIT`, but no deliverable updated the threat model's response-path description (GW-03) or the information-disclosure threat (GW-12).

## Candidate rule

When a plan closes a gap or adds a response-path control, search the ADR corpus and the threat model for every place that records the gap or describes that path, and add each to a documentation deliverable. Amendments in older ADRs are the easiest to miss.
