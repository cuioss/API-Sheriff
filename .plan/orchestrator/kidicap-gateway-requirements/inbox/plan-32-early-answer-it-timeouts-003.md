envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:15Z

# Candidate lesson: stale assessments survive a refuted design and contradict the new mutation list

- Source: Q-Gate finding 5b2431 (3-outline, assessment contradicts deliverable 3, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

The outline was re-written after an earlier design hypothesis was refuted. DispatchStageTest.java moved onto D3's "Files expected to mutate" list, but its stored assessment (80c69f) was still CERTAIN_EXCLUDE ("run, not edited"), written for the refuted design. Two survey files were also unassessed.

## Candidate rule

When a re-outline drops a design, re-check every assessment written for that design: supersede each one whose verdict no longer matches the new deliverable lists, in the same revision. Assessments do not follow a design change on their own.
