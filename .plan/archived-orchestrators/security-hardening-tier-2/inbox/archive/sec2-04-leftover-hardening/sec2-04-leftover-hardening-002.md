envelope_version=1
sender_type=plan
sender_id=sec2-04-leftover-hardening
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-19T17:40:23Z

# Candidate lesson: do not attribute an intermittent timing-test failure to a commit from a bisect run under heavy host load

**Category**: anti-pattern · **Component**: api-sheriff tests (DispatchStageTest, GatewayEdgeRouteBffWiringTest, TerminalDispatchLateBodyTimeoutTest)

## Observation
`DispatchStageTest$BodyDeadlineBehindASlowConnection` failed intermittently. A small bisect run at host load 11–56 blamed commit de0e7d00. The follow-up task could not reproduce the failure in 220 runs. The attribution was wrong, and a correction was logged in the decision log. Other timing tests failed for the same reason: a 5 s Vert.x close and 500 ms deadline margins under foreign load. Every re-run passed.

## Lesson
Before attributing an intermittent timing failure to a commit:
- check `uptime` and the host's other load;
- reproduce with many runs, not a handful;
- prefer a controlled reproduction, such as `-XX:ActiveProcessorCount=1`.

The likely remaining cause is a test-side race on reading `upstreamBodyBytes`. It is worth a dedicated look at the test.
