envelope_version=1
sender_type=plan
sender_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T09:39:15Z

# Candidate lesson: widening a policy block's scope set leaves user-doc scope labels and fallback chains wrong

**Source**: plan-v02-24-policy-block-resolution, PR #465, CodeRabbit findings 996382 (inline, `doc/user/endpoint-routes.adoc:126`) and dc8117 (review body, outside-diff, `doc/user/endpoint-routes.adoc` "Defaults for omitted blocks", `allowed_methods` row). Both were fixed in-run (commits 5d3ffd36 and ec6cb7b5).

## What happened

The plan widened descriptor scope sets so that one nearest-declared-wins rule resolves every block. Two prose descriptions in `endpoint-routes.adoc` still described the old scope set.

- A YAML example comment said a `forward` block inside `routes[]` was refused "at the endpoint as here". The block shown is route-level, and the refusal applies at either scope.
- The omitted-block defaults table described the `allowed_methods` fallback as anchor, then global, then the standard verb set. It did not say that a declared endpoint list is the nearest scope and wins (`PolicyBlock.ALLOWED_METHODS`: gateway, anchor, endpoint).

Neither statement is covered by a test, so only the review bot caught them. CodeRabbit's suggested wording for dc8117 would have contradicted the row it was editing, so the fix had to be rewritten rather than applied verbatim.

## Corrective rule

When a plan changes a `PolicyBlock` descriptor's scope set, audit every user-doc passage that names a scope ("at the endpoint", "anchor's list", fallback chains) for that block, including YAML example comments and default tables. Review the bot's suggested wording against the row or example it edits before taking it.

## Suggested classification

Project-local (API-Sheriff user docs). Companion to the two "derive, never re-enumerate" candidates from this run. This one covers scope *labels* in prose rather than inventory lists.
