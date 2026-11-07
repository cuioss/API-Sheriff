envelope_version=1
sender_type=plan
sender_id=plan-v02-46-upstream-close-after-early-answer
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-22T18:04:21Z

# Candidate lesson: EdgeAdmissionTest race case cannot fail on the pre-fix ordering (api-sheriff edge admission tests)

Plan plan-v02-46-upstream-close-after-early-answer (PR cuioss/API-Sheriff#513, merge e70743f), lessons-capture step. Code-review advisory left open.

## Observation

The admit/drain race case in `EdgeAdmissionTest` does not assert that any round was admitted. On the pre-fix ordering the test can pass with every round rejected, so in practice it cannot fail against the bug it is meant to guard.

## Why it matters

A race regression test that passes on the old ordering does not prove the fix. That matches the project rule that a green suite only settles what a test can actually detect.

## Suggested direction

Assert a non-zero admitted count (and that every admitted request was drained or completed), and check that the test fails when the admit/drain order is reverted.
