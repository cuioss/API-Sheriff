envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:46Z

component=plan-marshall:manage-tasks
category=improvement
source=Q-Gate finding a8398c (4-plan, taken_into_account; component plan-marshall:manage-tasks:qgate-mechanical-checks)

# keyword_drift flags tool names that a task takes word for word from the outline

## What happened

The mechanical Q-Gate check reported `keyword_drift: TASK-005 uses 'CI' not present in deliverable outline`. The token came from the read-back check `ci pr view`, which the outline's Approach section ("Carried to finalize" table) requires planning to carry into the deliverable-4 task. The check compares the task only with the deliverable body, not with the outline sections the deliverable points to, so it reported a sentence copied from the outline as drift. It was resolved as intentional and left unchanged.

## Suggested direction

Compare against the whole outline (Approach and Carried-to-finalize tables included), or ignore known tool and command tokens (`ci`, `git`, `mvn`). That would avoid a false positive the operator has to resolve by hand.
