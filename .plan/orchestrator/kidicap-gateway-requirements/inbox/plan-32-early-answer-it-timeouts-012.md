envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:33Z

# Candidate lesson: provisional write path in a deferred deliverable trips declared_set_closure (DispatchStage.java)

- Source: Q-Gate finding 5356f5 (4-plan, declared_set_closure: D3 declares DispatchStage.java as a write, no task targets it, resolved accepted)
- Plan: plan-32-early-answer-it-timeouts

## Observation

D3 ("fix, design pending D2") declared provisional write paths so later gates would know the likely footprint. With D3's tasks deferred, no task step targeted them, and declared_set_closure fired once per path. Accepted as provisional.

## Candidate rule

Provisional write paths in a deferred deliverable are a legitimate outline pattern, but the closure check cannot tell them from forgotten sweeps. Same root cause as 4ff4fa: a declared deferral would let the closure check skip (or soften) paths of a deferred deliverable. Related: 2e3e00, 47924f, 87e7c8, 950b8f.
