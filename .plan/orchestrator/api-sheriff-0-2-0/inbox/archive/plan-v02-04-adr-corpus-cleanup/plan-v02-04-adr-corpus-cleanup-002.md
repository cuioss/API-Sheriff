envelope_version=1
sender_type=plan
sender_id=plan-v02-04-adr-corpus-cleanup
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T09:56:20Z

# Candidate lesson: a CountDownLatch release does not start racing threads together, so a race test's vacuity control can fail on a loaded runner (project: EdgeAdmissionTest)

Source: plan plan-v02-04-adr-corpus-cleanup (PR #521, merge f5826465), lessons-capture step. Suggested route: project (API-Sheriff), keep local.

## Observation

EdgeAdmissionTest's admit/drain race test failed its own vacuity control on a loaded CI runner: 0 of 100 rounds admitted (merge-group run 40292535629). The race threads were released from a `CountDownLatch`. `countDown()` wakes the waiting threads one by one, so on a loaded runner one thread finishes before the other starts and the race window never opens. The test then proves nothing, and the vacuity control correctly fails it.

## Fix (PR #522)

- Release the threads through a spin barrier, so they start within the same short window.
- Run an adaptive number of rounds, until enough rounds have actually exercised the race.
- A mutation check confirmed that the fixed test still detects the flag-first ordering bug. A head-start variant tried first did not detect it.

## Rule

For a concurrency race test, do not treat a latch or barrier release as a simultaneous start. Measure whether the race was exercised (a vacuity control). Size the round count adaptively, not as a fixed number. Prove with a mutation that the test still catches the bug it targets after any change to the release mechanism.
