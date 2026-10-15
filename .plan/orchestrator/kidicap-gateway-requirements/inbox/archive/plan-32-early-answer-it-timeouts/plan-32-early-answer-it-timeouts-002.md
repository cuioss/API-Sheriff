envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:13Z

# Candidate lesson: read-intent affected files added without a matching assessment

- Source: Q-Gate finding 56b731 (3-outline, missing assessment, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

Deliverable 2 listed three (read) affected files (an endpoint YAML, an IT start script, a gateway source file) that carried no assessment at all. The Q-Gate caught it; the fix was three CERTAIN_INCLUDE read assessments.

## Candidate rule

Every file added to a deliverable's Affected files during an outline revision needs an assessment in the same edit. Treat "add affected file" and "record assessment" as one step, so the coverage check does not have to catch it.
