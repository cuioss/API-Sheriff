envelope_version=1
sender_type=plan
sender_id=plan-v02-17-lessons-into-source
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T19:11:30Z

component=plan-marshall:automatic-review
category=bug
title=Sourcery OSS branding footer in ignore_patterns silently drops every Overall Comment

# Sourcery OSS branding footer in ignore_patterns silently drops every Overall Comment

`plan-marshall:automatic-review/standards/sourcery.md` lists Sourcery's OSS-tier branding footer
markers in `ignore_patterns` — the strings "Sourcery is free for open source" and
"Help me be more useful! Please click".

Sourcery appends that footer to **every** review body it posts on an OSS repository, including
bodies that carry Overall Comments. `ignore_patterns` is a **whole-comment substring drop**: a
comment matching any pattern is discarded in its entirety, not trimmed. The two facts compose
into a structural defect — on an OSS repo, Sourcery's Overall Comments can never be filed,
because the footer that guarantees the drop is always present.

## Why this is a defect and not a tuning choice

The same file contradicts itself. Its producer rule at lines 99-100 states: "Do NOT drop the
review body when it contains Overall Comments." The `ignore_patterns` entries make that rule
unreachable on exactly the repository class (OSS) where the footer is emitted. One of the two
statements is always false, and it is the producer rule that loses.

## Observed twice on PR #200, and it cost a real finding

The footer-driven drop fired on both Sourcery review bodies posted to PR #200. One of the
dropped bodies carried an actionable finding — a coupling between the rewrite-report job's
`awk` parser and the log path it reads. That finding was recovered only because an agent read
the raw review body out of band; through the pipeline it did not exist. The findings store for
this plan bears this out directly: it holds nine `pr-comment` findings, every one authored by
`coderabbitai`, and not a single Sourcery-authored row.

## Recurrence

This is not incidental to PR #200. The footer is unconditional on OSS repos and
`ignore_patterns` is a whole-comment predicate, so the loss recurs on every PR in every OSS
project using this bundle, indefinitely, and it is silent — the pipeline reports a clean pass
with the finding simply absent.

## Corrective action

Move the branding-footer markers out of the whole-comment `ignore_patterns` layer and into a
**content-aware** layer that strips the footer text from a comment body while retaining the
comment. Do not filter at whole-comment granularity on a marker that a producer attaches
unconditionally to bodies that also carry payload.

Equivalent guarding rule for the general case: an `ignore_patterns` entry is only sound when the
marker it matches is present **exclusively** on comments that carry no payload. A marker a
producer appends to every comment is a formatting artefact, and formatting artefacts belong to a
trimming layer, never to a drop layer.
