envelope_version=1
sender_type=plan
sender_id=plan-v02-28-validator-limit-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T15:18:39Z

component=plan-marshall:phase-6-finalize
category=anti-pattern

# Moot success-criterion parts must be graded with a residual, not as met

## What happened

During the functional review, one part of a success criterion on deliverable 2 had become moot, because a sibling plan (PR #465) deleted the methods it referred to (effectiveAuth, effectiveProfile, resolveAnchor). The verdict graded that criterion as met without recording a residual. The verifier declined the verdict once for that reason, which cost one extra review round.

## Lesson

A criterion part that no longer applies is not "met". Grade it as met-with-residual (or not applicable) and state the residual explicitly: which part is moot, why (the commit or PR that removed the subject), and that nothing in this plan's scope is left to do for it. The verifier rightly rejects a silent "met" for something that was never checked.

## Impact

Avoids a verifier decline round whenever upstream changes make part of an outline criterion moot.
