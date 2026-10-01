envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:12:29Z

# Candidate lesson: Q-Gate assessment-coverage rule (2.2) fires on a deep-lane feature outline that owes no assessments

- Signal source: Q-Gate finding 682354 (phase 3-outline, resolution accepted)
- Component: plan-marshall:plan-marshall (workflow/q-gate-validation.md, assessment_coverage check) / plan-marshall:phase-3-outline
- Suggested category: bug

## What happened

The 3-outline q-gate-validation pass flagged all 9 deliverables with "no CERTAIN_INCLUDE assessments (2.2)"
(decision.log 07:52:15-07:52:25, summary "0 passed, 9 flagged") and filed finding 682354. Every other validator passed
and the gate itself verified the declared footprint (file existence, constructor call-site sweeps, EventType names).

The orchestrator resolved it `accepted` as a validator mismatch: the every-path-carries-an-assessment rule applies to
sweep plans (phase-3-outline "Sweep declaration form") and plugin component analysis (component-analysis-contract.md),
not to a Java feature plan on the deep Complex track whose deliverables enumerate their affected files explicitly.

## Why it matters

A validator that fires on a population it does not govern produces a 100% false-positive flag, costs an operator
resolution round, and trains the operator to accept Q-Gate findings wholesale.

## Suggested corrective rule

Gate the assessment_coverage check on the outline's declaration form (sweep / component-analysis) instead of running it
on every deep-lane outline; when not applicable, report the check as not-applicable rather than failed.
