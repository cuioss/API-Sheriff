envelope_version=1
sender_type=plan
sender_id=plan-v02-06-enumeration-hardening
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T14:23:16Z

component=api-sheriff
category=improvement

# A security fix suggested by a review bot during finalize can weaken the property it targets

During finalize, a review-bot finding about a security hardening mechanism was accepted, and the fix was committed inside the PR. The next code-review round found that this fix had partly weakened the property the finding was meant to protect. The operator chose to keep the change and to document its behaviour honestly.

## Corrective action

Before committing a bot-suggested fix to a security mechanism, have the security audit review the fix itself, with the same lens as the original finding. The audit asks whether the fix adds a new observable difference or a new impact, and whether it holds at the edges of the mechanism (small values, boundaries, rounding). "Implements the suggestion" is not evidence that the property got stronger. Record the result of that audit in the finding's resolution.
