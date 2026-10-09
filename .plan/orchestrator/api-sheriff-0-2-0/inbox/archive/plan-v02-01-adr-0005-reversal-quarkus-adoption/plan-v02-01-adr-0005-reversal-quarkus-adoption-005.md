envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:05:52Z

# Operator decision at outline review: idle time in a separate cookie avoided a format change

Source: operator-review finding 116816 (outline phase, resolved in the run).

What happened: The first outline carried the cookie-mode last-access time inside the sealed session payload, which would have changed the payload format and forced every user to log in again on deploy. The operator decided to carry it in a small separate cookie instead. The session payload gained no field, the format version stayed at 2, and no forced login was needed. The outline then had to verify three things against the code before it could be accepted: the cookie size against the header limit, that the cookie is set before the response is committed on streamed routes, and the WebSocket upgrade case, which could not be proven by reading and was assigned a test.

Candidate rule: Before adding a field to a persisted or sealed format, ask whether the new value can live next to it instead. State in the outline whether the format version changes and what that costs users on deploy. Claims that cannot be proven by reading the code get a test in the same deliverable.
