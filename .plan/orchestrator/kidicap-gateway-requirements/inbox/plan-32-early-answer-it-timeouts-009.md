envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:25Z

# Candidate lesson: staged (diagnose-then-fix) outline trips coverage gate for deferred deliverable 3

- Source: Q-Gate finding 4ff4fa (4-plan, qgate-mechanical-checks coverage: deliverable 3 has no tasks, resolved accepted)
- Plan: plan-32-early-answer-it-timeouts

## Observation

The operator chose a staged plan: D2 diagnoses the cause, and the tasks for D3-D5 are created only by a second re-outline once D2 names it. The mechanical coverage check ("every deliverable has at least one task") fired on D3 and had to be accepted by hand as a deliberate gate.

## Candidate rule

The planning gates have no first-class way to mark a deliverable as "tasks deferred until deliverable N reports". Either add one (a declared deferral the coverage check honours) or document that staged plans accept these findings with the deferral reason. See also 26f8a3 and 24e2b6 (same plan, same cause).
