envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:05:35Z

# Outline changed behaviour that ADR-0018 states as decided, without listing the ADR

Source: quality-check finding 221d8d (outline phase, resolved in the run).

What happened: The request required documentation for every changed component in the same PR. Deliverable 5 changed three things that ADR-0018 records as decided (how the session seam writes, what the sealed payload contains, and one identity rule), yet no deliverable listed the ADR file and the plan's new ADR did not cover those points either. After the plan the ADR would have described behaviour the gateway no longer has. It was closed in three parts: one proposed change was dropped so the ADR statement stands, the ADR joined the documentation deliverable with an amendment section, and the payload format was left unchanged so no version bump was owed.

Candidate rule: When a deliverable changes session, cookie or seam behaviour, search the existing ADRs for statements about that behaviour at outline time. Every ADR the change contradicts is either listed as a file to amend or the change is dropped.
