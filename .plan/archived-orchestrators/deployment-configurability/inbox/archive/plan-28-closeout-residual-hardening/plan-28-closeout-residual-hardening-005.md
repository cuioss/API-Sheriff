envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:15:02Z

# Candidate lesson: a cross-reference between two files was read as the two files sharing the text

**Source signal**: Q-Gate finding `2d252c` (phase 3-outline, type `triage`, severity warning, resolution `taken_into_account`)
**Component**: `integration-tests/scripts/start-integration-container.sh`, `demo-client/scripts/start-dev-environment.sh`

## What happened

PLAN-28 deliverable 4 instructed: "Rewrite the column-position comment [in
`start-integration-container.sh`]: it currently justifies the LAST-column placement by the
empty-field hazard." **That comment does not exist in that file.** Two content sweeps over the full
inventory (552 files scanned, none unreadable, not truncated, not elided) placed the LAST-column
hazard comment in exactly one file: `demo-client/scripts/start-dev-environment.sh`.

The likely origin is precise and worth recording: `start-dev-environment.sh:159-160`
**cross-references** `start-integration-container.sh` as sharing the *convention*, and the outline
read "shares the convention" as "contains the comment".

## Why this is candidate-lesson shaped

A false premise in a deliverable is more expensive than a missing one. The executing agent either
hunts for absent text, or — worse — **invents** a comment to rewrite so the instruction can be
satisfied, producing a plausible edit that no reviewer can distinguish from a real one.

The specific trigger generalizes: prose of the form "X, like Y, does Z" is evidence about the
*convention*, never about Y's file contents. An outline claim sourced from a cross-reference must
be verified against the referenced file before it becomes an instruction.

## Candidate rule

Every outline instruction of the form "rewrite the existing comment/section at path P" must be
grounded by an actual content search against P, not by a cross-reference found elsewhere. Where the
target text is absent, the deliverable says "author a fresh rationale" rather than "rewrite".

## Disposition in this plan

Taken into account during execution. Commit `9e8aaf5` did not hunt for absent text or invent a
rewrite: it authored a fresh, accurate rationale at the `rows.append` site (column order stays
load-bearing, the root path is emitted RAW, the trim is owned once by `normalize_root_path` in
`lib-docker-compose.sh`) and made the matching point in `start-dev-environment.sh`.
