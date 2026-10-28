envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:34:42Z

# Candidate lesson (plan-marshall tooling): operator review override does not carry to the pre-merge HEAD binding

- Source: work/decision log, 6-finalize; pre-merge HEAD binding WARNING at 2026-10-26T20:52:38Z
- Suggested component: plan-marshall:phase-6-finalize (pre-merge barrier / merge_candidate_not_gated). Route to the plan-marshall lessons-routing inbox; this is not an API-Sheriff project lesson.

## What happened

At 19:36 the operator closed code-review by override (review not converging) and directed "run only build + quality gate before push" for the fix commits 4bef4d64 and 008f7f7f. The push went through. At branch-cleanup the pre-merge HEAD binding then refused with `merge_candidate_not_gated`: candidate 008f7f7f, ungated steps finalize-step-security-audit (bound at 4bef4d64), finalize-step-simplify (4bef4d64) and functional-review (36229c1c). The merge was blocked and a full functional-review round (round_kind=full, 6 deliverables) plus simplify and security-audit had to be re-fired at 008f7f7f, about an hour after the override.

## Corrective rule / improvement

1. When the operator narrows the re-fire set ("only build + quality gate"), the barrier should either record that narrowing as a HEAD-bound waiver for the named steps, or the override prompt should state up front that the pre-merge barrier will still demand every head-dependent step at the final HEAD. The refusal arriving after the push is the costliest point to learn it.
2. When a re-fire of functional-review follows a small delta, the delta that "already spans all deliverables" forces a full round; consider letting a delta round over a review-only/doc-only change satisfy the binding.
