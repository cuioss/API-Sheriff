envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:51:44Z

# Candidate lesson: "mirror this exemplar" task text imports the exemplar's rationale wording

- Source: Q-Gate finding 01aaa9 (4-plan, framing_rule: TASK-5 and TASK-7 told the executor to follow an existing log record's form, resolved taken_into_account)
- Plan: plan-32-early-answer-it-timeouts

## Observation

TASK-5 and TASK-7 told the executor to model a new log record and its documentation entry on an existing record. The existing record's javadoc and doc entry carry a rationale sentence that the plan's wording rules did not allow for the new record. The tasks stated the wording rule in general terms but did not say which parts of the exemplar not to copy, so an executor working by analogy would likely have copied that sentence. Fixed by adding one explicit line to both dispatch prompts: reuse the exemplar for record layout only, and state the rationale as a plain log-volume bound.

## Candidate rule

When a task says "do it like X", it also says which parts of X to copy (layout, structure) and which not to (rationale prose, justifications). Analogy-driven executors copy whole blocks. A general wording rule does not cover text the task tells them to mirror.
