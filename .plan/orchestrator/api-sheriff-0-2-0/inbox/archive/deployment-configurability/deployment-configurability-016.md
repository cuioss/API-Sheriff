envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:27Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): The two defects a PR actually introduced were both outside the diff, and only a review-body comment could carry them

Original lesson `2026-09-23-07-010` (component `api-sheriff`).

## What happened

CodeRabbit posted two findings it could not attach inline, because GitHub refuses inline comments
outside the diff range. Both were real defects the PR introduced: a "the mode set is closed: X, Y, Z"
sentence that a new enum member falsified, at a location the diff never touched, and a sibling
"tightest posture" description that a new, tighter mode made inaccurate. Both landed in the
`review_body` kind, which carries an empty `thread_id` and can never be resolved as a thread at all.

## Candidate rule

Adding a member to a closed set falsifies every prose restatement of that set, including ones the
diff never touches — the diff is a poor proxy for the blast radius of a set change; a content search
for the surviving members is the right one. Outside-diff-range findings are second-class by
transport, not by importance: a workflow that treats inline comments as the work list and review
bodies as noise systematically drops the findings about what the change broke elsewhere, which is
exactly the class a diff-scoped reviewer cannot otherwise reach. A `review_body` row owes a reply but
can never be resolved — the discriminator is the empty `thread_id`, not the row count. When a change
adds or removes a member of a closed set, search the whole tree for restatements of the remaining
members before submitting.

## Source

`deployment-configurability` PLAN-28 (PR #341), PR review_body comment `5b7752` (coderabbitai,
resolution `fixed`). Both findings fixed in the same task as the sibling AwaitsTest/DocumentedSetsContractTest
derivation fix.
