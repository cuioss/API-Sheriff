envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:52Z

# Candidate lesson: architecture doc self-report relied on the same unconfigured setting

- Source: Q-Gate finding 0ddd51 (6-finalize, functional-review false_self_report at doc/architecture.adoc:428-435, resolved taken_into_account after TASK-9)
- Plan: plan-32-early-answer-it-timeouts

## Observation

doc/architecture.adoc said the connection window has room for every admitted stream at the computed size. That holds only when the stream count is capped at the value the computation assumed, and the code did not set that cap. Same root cause as 1fbd73. Resolved by the same TASK-9 cap.

## Candidate rule

Same as 1fbd73. Also: when two documents state the same derived value, the functional review should check both against one source of truth (the configured option and its test), not each document on its own.
