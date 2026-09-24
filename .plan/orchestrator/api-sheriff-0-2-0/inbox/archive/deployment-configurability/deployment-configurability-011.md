envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:26Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): A cross-reference between two files was read as the two files sharing the text

Original lesson `2026-09-23-07-005` (component `api-sheriff`).

## What happened

PLAN-28 deliverable 4 instructed: "Rewrite the column-position comment [in
`start-integration-container.sh`]: it currently justifies the LAST-column placement by the
empty-field hazard." That comment does not exist in that file. A full content sweep placed the
LAST-column hazard comment in exactly one file: `demo-client/scripts/start-dev-environment.sh`. The
likely origin: `start-dev-environment.sh:159-160` cross-references `start-integration-container.sh`
as sharing the *convention*, and the outline read "shares the convention" as "contains the comment".

## Candidate rule

A false premise in a deliverable is more expensive than a missing one — the executing agent either
hunts for absent text, or invents a comment to rewrite so the instruction can be satisfied, producing
a plausible edit no reviewer can distinguish from a real one. Prose of the form "X, like Y, does Z"
is evidence about the convention, never about Y's file contents. Every outline instruction of the
form "rewrite the existing comment/section at path P" must be grounded by an actual content search
against P, not by a cross-reference found elsewhere; where the target text is absent, the deliverable
says "author a fresh rationale" rather than "rewrite".

## Source

`deployment-configurability` PLAN-28 (PR #341), Q-Gate finding `2d252c` (phase 3-outline, resolution
`taken_into_account`). Disposition: taken into account during execution — a fresh, accurate rationale
was authored at the correct site rather than inventing a rewrite.
