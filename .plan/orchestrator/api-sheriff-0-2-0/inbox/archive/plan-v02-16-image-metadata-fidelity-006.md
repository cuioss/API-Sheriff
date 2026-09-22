envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T17:51:46Z

component=plan-marshall:automatic-review
category=bug
title=Self-response filter is start-anchored, so hand-written replies re-enter as inbound comments

# Self-response filter is start-anchored, so hand-written replies re-enter as inbound comments

## Observation

Two of the six `pr-comment` findings filed for PR #199 (`3dd851`, `774b2b`) were
**our own** replies to Sourcery, authored by hand as issue comments because the
Sourcery review carried no resolvable `thread_id`. The automated-review self-response
filter is start-anchored on the machine-generated batched `post_responses` heading,
so a manually-authored reply does not match and is ingested as inbound reviewer
feedback owing a disposition.

Both had to be dispositioned as `taken_into_account` with a rationale explaining
that they are not reviewer feedback at all. That is pure noise in the triage work
list, and the failure scales: any hand-written reply on a PR — which is exactly what
the "reply where the thread is unresolvable" rule asks for — becomes a fresh
finding on the next fetch.

## Rule

Self-authorship should be determined by the comment **author identity**, not by a
start-anchored match on a machine-generated heading. A filter keyed on the heading
recognises only the messages the tool itself wrote, which is the subset least likely
to need filtering; the hand-written ones it misses are the ones that create work.

## Scope note

This is a plan-marshall mechanism gap observed from a project plan, not an
API Sheriff defect. Recording it as a candidate so the epic can route it to the
right store rather than filing it against this repository.
