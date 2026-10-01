envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:12:48Z

# Candidate lesson: metrics recorded in a Vert.x end handler must be awaited, not read right after the response

- Signal source: ci-verify loop-back iteration 2 (TASK-21), decision.log 15:22:55 and 15:28:21
- Component: api-sheriff (project-local; GatewayEdgePipelineTest / edge metering tests)
- Suggested category: bug (test flake)

## What happened

GatewayEdgePipelineTest.metersDisallowedVerbUnderItsRoute failed on CI only (build (25), expected 1.0 got 0.0). The
test reads REQUESTS_TOTAL immediately after the response, but metering runs asynchronously in
`ctx.addEndHandler -> recordRequestMetrics`. The test and the end-handler metering both came from main (#320); the plan
only changed the constructor call. Same test was green on CI at 655c328 and locally at a4cbffd, so it is a pre-existing
timing race that surfaced on the slower CI runner.

Fix (TASK-21, commit 695f72f): bounded `Awaits.until` for the counter, then re-assert. A CI re-run was rejected as the
remedy.

## Suggested corrective rule

Any test asserting on a meter, counter, or log produced by an end handler / after-response hook must wait for it with a
bounded Awaits.until (and re-assert), never read it synchronously after the response returns. Worth a sweep of the other
edge metering tests for the same shape.
