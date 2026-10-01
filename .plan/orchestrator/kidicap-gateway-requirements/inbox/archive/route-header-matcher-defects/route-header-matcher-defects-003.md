envelope_version=1
sender_type=plan
sender_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T20:57:13Z

# Candidate lesson: post_responses posts the resolution detail verbatim as PR reply text

**Source signal**: automated-review (Sourcery "Approved." non-actionable finding, PR #346)

## What happened

Sourcery posted a non-actionable "Approved." review. The orchestrator resolved the finding with an internal resolution text (a triage rationale addressed to the ledger, not to the reviewer). `workflow-integration-github` `post_responses` treated the finding's resolution detail as the reply body and posted it verbatim as a PR comment, so internal triage prose became public PR conversation.

## Candidate rule / defect

Either (a) `post_responses` should skip replies for non-actionable, non-thread findings (review_body / review / issue_comment kinds with no resolvable thread) unless a reply is explicitly requested, or (b) resolution_detail and reply text should be separate fields so a resolver can record an internal rationale without it being transmitted. Until fixed, operators must write every resolution detail as if it were a public reply.

## Suggested component

plan-marshall:workflow-integration-github (post_responses) — plan-marshall bundle defect.
