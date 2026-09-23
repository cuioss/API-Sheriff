envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:14:24Z

# Candidate lesson: an "either a comment or the adjacent doc" option is not an option when the file is strict JSON

**Source signal**: Q-Gate finding `f3e1d0` (phase 2-refine, type `triage`, severity warning, resolution `taken_into_account`)
**Component**: project configuration — `.plan/marshal.json`, `doc/development/`

## What happened

PLAN-28 deliverable 7 offered the executing agent a choice: record the `re_review_on_loopback`
rationale "in a comment or the adjacent doc". `.plan/marshal.json` is strict JSON and admits no
comment syntax, so exactly one of the two offered branches was implementable. An agent that picked
the first branch would have produced a file that no longer parses — i.e. the *request itself*
contained an unimplementable option, and nothing in the request said so.

Refine also surfaced material context the request did not carry: the adjacent
`re_review_on_branch_cleanup` is already `true`, so re-review *does* occur at branch cleanup. A
rationale written without that fact would have been misleading about the actual behaviour.

## Why this is candidate-lesson shaped

Offering a fork in a deliverable is normal and good. The defect class is offering a fork whose arms
were never checked against the **format** of the file they land in. It generalizes past JSON: the
same shape is "add a comment" to a `.properties` file read by a strict parser, to a YAML anchor
block, or to a generated file that a later regeneration overwrites.

## Candidate rule

A deliverable that offers "record this as a comment OR in the adjacent doc" must have each arm
validated against the target file's format and regeneration story before the fork ships. Where one
arm is impossible, drop it in the outline rather than leaving the executing agent to discover it.

## Disposition in this plan

Operator ruled: keep `re_review_on_loopback: false` and record the rationale in an adjacent doc,
noting the contrast with `re_review_on_branch_cleanup` already being `true`. The inline-comment
branch was dropped. Landed as `doc/development/re-review-on-loopback.adoc`.
