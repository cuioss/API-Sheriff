envelope_version=1
sender_type=plan
sender_id=plan-v02-42-validator-auth-oidc-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T11:37:04Z

# Candidate lesson: with q_gate_validation=once, outline warnings stay open into phase 4

**Routing:** process candidate (project configuration and plan-authoring practice).
**Suggested component:** plan-marshall:phase-3-outline (process)
**Category:** improvement

## What happened

Under `q_gate_validation=once`, the PLAN-V02-42 outline Q-Gate ran a single time and left two
warning findings pending into phase 4:

- 2130b9: profile overlap. Deliverable 1 carried a module_testing profile next to deliverable 2's
  test files. It was accepted as intended, because TASK-2 is the behaviour-neutral proof run.
- 5398c9: under-coverage. doc/development/bff-session.adoc also names ConfigValidator as the
  caller of OidcConfig.usesClientSecret(), the same ownership claim deliverable 3 corrected in
  bff-cookie.adoc. It was fixed by adding a step to TASK-4 before execute.

The fix was cheap here, but only because someone read the pending findings before execute.
Nothing forces a decision on outline warnings when the gate runs once.

## Corrective action

When q_gate_validation=once leaves warning findings pending, resolve each one (accept with a
reason, or fold it into a task) at the phase-4 boundary, before execute starts. For move or
ownership refactors, the outline should list every doc that states the moved member's owner or
caller, found with a content sweep for the old class name. It should not stop at the docs named
in the request.
