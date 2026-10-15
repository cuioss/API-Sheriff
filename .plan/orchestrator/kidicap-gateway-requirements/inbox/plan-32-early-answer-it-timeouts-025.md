envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:59Z

# Candidate lesson: operator re-scope left a stale "unchanged" sentence in the outline

- Source: orchestrator-observed; context in Q-Gate findings 1fbd73 / 0ddd51 (the operator decision to cap concurrent streams at 100) and TASK-9
- Plan: plan-32-early-answer-it-timeouts

## Observation

An operator decision during finalize changed a deliverable's scope: EdgeHardeningOptions gained the stream cap. The outline still said that part of the configuration stayed "unchanged". Nothing re-read the outline's invariant sentences against the new decision.

## Candidate rule

When an operator decision changes what a deliverable touches, update the outline in place in the same step: search the deliverable's prose and success criteria for "unchanged" and "not touched" claims about the newly touched files or options, and correct them before the loop-back task is created.
