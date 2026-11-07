envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:31Z

component=plan-marshall:phase-4-plan
category=improvement
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=Q-Gate 4-plan findings 66bb22, 55f694 (taken_into_account by operator ruling)

# Value-change sweep fixed one copy but missed identical sibling copies (recurrence after the outline gate)

## What happened

Even after the outline Q-Gate had added a value-change sweep, the 4-plan Q-Gate found the same
stale statement in sibling files the sweep did not reach:

- doc/variants/01-base-gateway.adoc still stated the old never-forward rule, while its siblings
  02-bff-session.adoc and 03-bff-cookie.adoc were in the mutate list;
- BffSessionFallbackIT and BffSessionMediationIT carried the same stale class-Javadoc rationale that
  DeclaredLimitBoundaryIT had been fixed for.

Both needed an operator ruling to extend TASK-12 / TASK-14.

## Why it matters

The outline-time sweep was scoped to the files the finding named, not to the statement. Fixing
one instance of a stale statement is evidence the same wording exists in its siblings.

## Proposed rule

When a Q-Gate finding names one stale copy of a statement, the fix must content-search the whole
repository for the statement (and its paraphrases), not only for the named file, and record the
complete hit list — including sibling documents of the same family (variants/*.adoc, *IT.java of
the same feature) — before the finding is marked taken_into_account.

Related: the 3-outline value-change-sweep candidate-lesson from the same plan.
