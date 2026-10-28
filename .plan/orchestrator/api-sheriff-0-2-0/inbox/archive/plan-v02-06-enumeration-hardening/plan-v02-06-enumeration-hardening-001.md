envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:22:31Z

component=plan-marshall:phase-6-finalize
category=improvement

# An ADR ordinal collision surfaced only on the CI merge ref, after every local gate passed

While this plan was in finalize, main took ADR-0072 in #527. The branch also added an ADR-0072. All local gates passed, because the branch base was older than #527. The duplicate showed up only when CI built the merge ref. TASK-11 said to re-check the ordinal against origin/main before the push, but that step was not executed.

## Corrective action

Run the duplicate ADR-ordinal scan on the tree that will actually merge, meaning the merge ref (or origin/main plus the branch), inside the pre-push gate. Running it only at landing time is too late. A prose instruction in a task is not enough. The check needs to be a mechanical step that the gate runs.

## Related existing lessons

- 2026-10-03-06-002: main moves under a long finalize, and the baseline is only checked at entry
- 2026-10-06-08-003: a self-review author excused a stale ordinal
