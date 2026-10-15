envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:52:01Z

# Candidate lesson: timing race in a refactored test only showed on a 2-core CI runner

- Source: orchestrator-observed; plan work log ci-verify loop-back 2026-10-15T07:23 (TASK-011, ci-verify findings bba03b / 1c4be8), three local build failures 07:27-07:37, TASK-011 done at bc9b19eb
- Plan: plan-32-early-answer-it-timeouts

## Observation

A refactored test (GatewayEdgeEarlyResponseResetTest) passed locally but failed in CI on a 2-core runner, which caused a ci-verify loop-back. The first diagnosis treated the test's upstream (gateway-side) view and the stub-side view as the same thing, which sent the investigation toward a gateway change that was not needed. Separating the two views showed the problem was the test's own wait condition, and the fix stayed in the test.

## Candidate rule

1. Tests with concurrency or timing assertions get a low-parallelism local run before push (for example `-XX:ActiveProcessorCount=1` or `2`), as the project memory on framing-corpus flakes already suggests.
2. Assertion messages and diagnostics in such tests report the gateway-side view and the stub-side view separately, so a CI failure says which side's expectation broke.
