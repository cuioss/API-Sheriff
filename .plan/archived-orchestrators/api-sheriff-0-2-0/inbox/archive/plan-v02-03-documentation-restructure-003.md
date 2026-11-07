envelope_version=1
sender_type=plan
sender_id=plan-v02-03-documentation-restructure
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T02:00:03Z

component=pm-plugin-development:ext-self-review-plan-marshall
category=bug
title=Self-review domain surfacer returns zero over a non-.py/.md footprint and reads as a pass

# Self-review domain surfacer returns zero over a non-.py/.md footprint and reads as a pass

`pre-submission-self-review` dispatches its domain surfacer
(`pm-plugin-development:ext-self-review-plan-marshall`). On a 34-file documentation restructure
whose footprint was `.adoc` plus one `.js`, the surfacer returned `counts.total: 0`.

## Why

The surfacer's detectors target `.py` and `.md` files. An `.adoc`/`.js` footprint matches no
detector, so the pass produces an empty candidate set.

## Why it is a defect and not a benign zero

The zero is indistinguishable from "checked and found nothing clean". Taken at face value the step
returns *nothing to check* and passes **GREEN**, and the honest verdict string it emits reads as a
pass. `pre-submission-self-review` is `default_on`, so **every consumer project whose documentation
is AsciiDoc gets zero structural self-review coverage** and no signal that coverage was zero.

## What the mechanism should do instead

Distinguish *no detector applied to this footprint* from *detectors applied and found nothing*.
A footprint that matches no detector is a coverage gap and must be reported as such — the same
"which kind of zero is this" discriminator the inbox `inbox_state` field and the `architecture
search --content` coverage fields already carry elsewhere in plan-marshall.

## Evidence

Plan `plan-v02-03-documentation-restructure` (epic `api-sheriff-0-2-0`), PR #197. Footprint: 34
files, all `.adoc` except one `.js`.
