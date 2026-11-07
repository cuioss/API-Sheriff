envelope_version=1
sender_type=plan
sender_id=plan-v02-46-upstream-close-after-early-answer
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-22T18:04:22Z

# Candidate lesson: ADR-0066 A2 lists three upstream-close triggers but omits the request-body idle timeout (doc/adr)

Plan plan-v02-46-upstream-close-after-early-answer (PR cuioss/API-Sheriff#513, merge e70743f), lessons-capture step. Code-review advisory left open.

## Observation

ADR-0066 section A2 lists three triggers for the kept abortive upstream close, but does not mention the request-body idle timeout, which also leads to that close.

## Why it matters

The ADR is the reference for when the gateway closes the upstream connection abortively. A missing trigger means readers and later changes will not consider the idle-timeout path.

## Suggested direction

Add the request-body idle timeout as a fourth trigger in ADR-0066 A2 (or explain why it is not one), and check that a test covers it.
