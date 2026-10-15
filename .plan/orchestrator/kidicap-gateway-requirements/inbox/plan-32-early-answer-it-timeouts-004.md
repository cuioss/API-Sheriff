envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:17Z

# Candidate lesson: same under-coverage class recurred in a second deliverable

- Source: Q-Gate finding 9c6874 (3-outline, scope_criterion_validator, under_coverage for deliverable 3, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

D3's criterion "Http2EarlyResponseResetIT.java is unchanged" named a file in neither its survey nor its mutate list. This is the same class as fb188c (D1), fixed earlier in the same outline. The fix for D1 was not applied across the other deliverables, so the gate fired again.

## Candidate rule

When a Q-Gate finding is resolved for one deliverable, sweep the other deliverables for the same class before re-running the gate. A per-deliverable fix for a per-outline pattern only moves the finding.
