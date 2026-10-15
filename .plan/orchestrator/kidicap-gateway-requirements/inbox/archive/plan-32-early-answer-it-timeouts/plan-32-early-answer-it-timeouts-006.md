envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:20Z

# Candidate lesson: CERTAIN_INCLUDE assessments from a refuted design left orphaned

- Source: Q-Gate finding da009b (3-outline, two CERTAIN_INCLUDE assessments match no deliverable, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

GatewayEdgeRouteTest.java (9963b0, written for the refuted design) and ResponseStage.java (06520e, a conditional survey file whose condition did not hold) stayed CERTAIN_INCLUDE but appeared in no deliverable after the second re-outline. Fixed by superseding CERTAIN_EXCLUDE assessments.

## Candidate rule

A conditional assessment ("moves to the mutate list only if X") must be resolved when X is decided: either the file joins a deliverable or the assessment is superseded with CERTAIN_EXCLUDE. Second instance in this plan of assessments outliving the design they were written for (see 5b2431).
