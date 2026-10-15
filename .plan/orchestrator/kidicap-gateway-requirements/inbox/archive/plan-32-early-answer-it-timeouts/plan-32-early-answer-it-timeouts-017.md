envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:41Z

# Candidate lesson: deliverable's risk-mitigation gate not carried into the task text

- Source: Q-Gate finding e0abfe (4-plan, scope_criterion_validator: TASK-2 omits the arm J first-run dump gate from D2, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

D2's risks_and_mitigations committed to a gate: after the first run of the diagnostic arm, check that the thread dump was actually written, and fix the dump setup before the remaining runs if not (the hardened container can block the attach). TASK-2's description left the gate out. An executor following only the task text could have spent all 100 runs without a usable dump. The gate was carried in the orchestrator's dispatch prompt instead of the task.

## Candidate rule

Every gate or check stated in a deliverable's risks_and_mitigations must appear in the description of the task that runs the risky step. Executors read tasks, not outlines. For expensive diagnostic rounds, "check the first run produced the evidence" belongs in the task, before the rest of the round is released.
