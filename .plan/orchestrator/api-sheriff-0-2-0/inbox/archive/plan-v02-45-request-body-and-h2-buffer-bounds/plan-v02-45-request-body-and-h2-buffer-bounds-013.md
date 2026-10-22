envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:22:20Z

# Late timeout callback wrote request-context data from another event loop; long bot comment body cut off at ingest

- Source: PR #506 review-bot finding 8e28f3 (CodeRabbit inline comment at TerminalDispatch.java:686, resolution fixed, fix task 15, fix commit 24ca7e1a); automatic-review signal
- Component: api-sheriff TerminalDispatch / ConnectionRetirement; review-comment ingestion

## Observation

CodeRabbit pointed out that the late request-body timeout callback runs on the upstream event
loop but wrote its retirement marker into the RoutingContext data map, which belongs to the
client event loop. None of the in-run reviews (simplify, security-audit, functional-review,
code-review) had caught it before the bot did.

Second observation, about the evidence rather than the code: the stored comment body for this
finding ends mid-way through a quoted documentation excerpt. That is consistent with the ingest
length limit on review-bot comment bodies (reported by the orchestrator as 8000 characters).
CodeRabbit puts its analysis transcript ahead of the conclusion, so in a long comment the
substantive text is what gets cut off. Triage here had to rely on the visible head and the code.

## Outcome

Fixed by TASK-15: the late-timeout retirement is recorded only in the synchronized retirement set,
and the connection is retired from failRelay on the late REQUEST_BODY_TIMEOUT. The thread was
replied to and resolved; the PR merged as 99e9dfd3.

## Candidate rules

- A callback that can run on a different event loop from the request (timers armed on the
  upstream side, late completions) never writes RoutingContext data; it writes only to
  thread-safe structures shared by both sides. Review checklists for edge code should ask which
  loop each callback runs on.
- When an ingested review-bot body is at the ingest length limit, treat it as truncated and read
  the full comment on the PR before deciding the disposition.
