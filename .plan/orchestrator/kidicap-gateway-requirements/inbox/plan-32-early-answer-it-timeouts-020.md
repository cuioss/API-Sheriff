envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:50Z

# Candidate lesson: documented derived value assumed a framework setting that was never configured (endpoint-routes.adoc)

- Source: Q-Gate finding 1fbd73 (6-finalize, functional-review residual at D5.R1, resolved taken_into_account after loop-back fix TASK-9)
- Plan: plan-32-early-answer-it-timeouts

## Observation

doc/user/endpoint-routes.adoc gave the HTTP/2 connection-window default as a value computed from a per-connection stream count of 100. The gateway never configured that stream count, and the framework default (Quarkus 3.40.1) is different, so the deployed value was not the documented one. The operator decided to configure the cap explicitly (TASK-9, EdgeHardeningOptions), so the documented value became the deployed one. Cost: one finalize loop-back plus a CI re-proof (TASK-10).

## Candidate rule

When a document states a value derived from another setting ("X = per-stream window times N streams"), check that N is configured in the code, not assumed. Verify it against the framework's actual default for the version in use, and add a test that pins N, so the documented number cannot drift away from the deployed one.
