envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:44Z

component=plan-marshall:phase-3-outline
category=improvement
source=Q-Gate finding 8bf066 (3-outline, taken_into_account)

# Finalize-time obligations written only in Approach prose reach no task

## What happened

The clarified request required three things at finalize: a trust-resolution statement in the PR description, the same statement in the orchestrator inbox message, and a benchmark comparison in the PR. The outline wrote these only as "At finalize" prose in the Approach section. Tasks are derived from deliverables, so nothing carried them forward or checked them. The revision bound them to deliverable 4: the final text goes to a scratch file with success criteria. It also added a "Carried to finalize" table listing, for each obligation, the step that completes it and a read-back check. Phase 4 then recorded them as `finalize_obligations` metadata.

## Rule

Bind every request item that can only be met at finalize to a deliverable success criterion and to a named finalize step with a read-back check. Prose in Approach is never executed.
