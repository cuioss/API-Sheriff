envelope_version=1
sender_type=plan
sender_id=plan-v02-46-upstream-close-after-early-answer
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-22T18:04:19Z

# Candidate lesson: Http2EarlyResponseResetIT failed once in the merge_group run, possible timing flake in the early-response reset path (integration-tests)

Plan plan-v02-46-upstream-close-after-early-answer (PR cuioss/API-Sheriff#513, merge e70743f), lessons-capture step.

## Observation

`Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway` failed once in the merge_group run: 10 of 400 streams were client-reset with CANCEL, the connection was dropped, and 2 connections were used. The same tree was green on PR CI and on the re-enqueue. This is the first observed failure of this test.

## Why it matters

The test guards the #431 early-response reset path. A failure that shows up only under merge-queue timing can be a real race (like the HTTP/2 reset-before-write race fixed in PR #403), not just a flaky test. Re-enqueuing hides it.

## Suggested direction

Try to reproduce under constrained CPU (`-XX:ActiveProcessorCount=1`, repeated runs) before calling it flaky. If it reproduces, check whether an early answer plus abortive upstream close can still send RST_STREAM CANCEL or GOAWAY to unrelated streams. Track recurrences against this test name.
