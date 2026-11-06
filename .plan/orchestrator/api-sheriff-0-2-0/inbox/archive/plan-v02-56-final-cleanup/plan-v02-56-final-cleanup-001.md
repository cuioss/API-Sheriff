envelope_version=1
sender_type=plan
sender_id=plan-v02-56-final-cleanup
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-06T15:16:10Z

# Candidate lesson: operator re-fire waiver does not satisfy the pre-merge gate

- component: plan-marshall:phase-6-finalize
- category (suggested): bug
- signal source: decision log (phase-6-finalize, pre_merge_gate)

## Observation

At final head 2a6d1a1c the operator waived the re-fire of finalize-step-simplify,
finalize-step-security-audit and code-review for a test-only commit (Sonar S5778 fix),
and that waiver was recorded in the decision log. When branch-cleanup then ran
`pre_merge_gate merge`, the gate refused with `merge_candidate_not_gated` because the
three steps were recorded at the previous head d8b84c67. The waiver did not count
toward the gate. The merge mutex had to be released, all three steps were re-run
narrowly over the single commit d8b84c67..2a6d1a1c, and the merge was attempted again
(it then succeeded).

## Defect shape

Two contracts disagree. The loop-back / re-fire waiver lets an operator skip the
re-fire of head-dependent steps, but the merge gate demands that those steps be
recorded at the merge-candidate HEAD. So the waiver only postpones the work and costs
a mutex acquire/release cycle. The decision log records the gate's own reasoning as
"an authorization never covers an unexamined tree".

## Suggested corrective direction

Either make the waiver flow warn up front that the merge gate will still require a
narrow re-run at the final HEAD, or run that narrow re-run as part of the waiver.
Then the operator does not discover the refusal only after taking the merge mutex.
