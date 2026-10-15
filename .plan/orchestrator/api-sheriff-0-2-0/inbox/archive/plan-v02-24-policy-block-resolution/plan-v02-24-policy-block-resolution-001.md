envelope_version=1
sender_type=plan
sender_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T09:38:49Z

# Candidate lesson: a boot reader that hand-lists policy-block scopes misses a widened scope

**Source**: plan-v02-24-policy-block-resolution, PR #465, CodeRabbit inline finding 53de56 (fixed in-run by TASK-13, commit 0ad1f16d).

## What happened

The plan widened the scope sets of the `PolicyBlock` descriptors (one rule resolves every policy block, nearest declared scope wins). `ConfigBootPipeline.maxDeclaredBodyBytes` scanned `max_body_bytes` declarations by hand-listing the anchor, endpoint and route scopes instead of reading them from `PolicyBlock.SECURITY_FILTER`. If the descriptor gains a scope, the boot check silently skips declarations at that scope. Nothing in the local gate caught it. CodeRabbit flagged it under the "hardcoded list that mirrors a set defined elsewhere" path instruction.

## Corrective rule

A boot-time or validation reader that walks policy-block scopes must take its scope set from the `PolicyBlock` descriptor itself, through an exhaustive `switch` over the scope enum, so the compiler rejects a new scope that has no handling. It must not re-enumerate scopes by hand. When a plan widens or narrows a descriptor's scope set, search for every reader of that block's key, not only the resolver.

## Suggested classification

Project-local (API-Sheriff config/boot). Candidate component: `api-sheriff` config boot pipeline. Related to candidate "doc enumerations of PolicyBlock.ALL" from the same run (same defect class, applied to prose).
