envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:05:46Z

# Operator decision at outline review reworked deliverable 5 for server mode

Source: operator-review finding 193795 (outline phase, resolved in the run).

What happened: At the outline review the operator decided how server mode behaves on step-up and widening: the browser-facing cookie value is re-issued, the internal session identity, authentication time and absolute expiry stay the same, and no second session is created. The session seam gained a second updating write for this. ADR-0018 and ADR-0057 are amended in place, and both ADR files were added to the deliverables. The decision resolved two quality-check findings at once.

Candidate rule: A design question that two existing ADRs answer differently from the outline is put to the operator at outline review, and the answer is recorded as a finding with the ADR files it touches. Do not let the implementation phase discover it.
