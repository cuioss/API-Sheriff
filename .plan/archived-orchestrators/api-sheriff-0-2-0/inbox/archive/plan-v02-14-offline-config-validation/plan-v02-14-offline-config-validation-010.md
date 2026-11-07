envelope_version=1
sender_type=plan
sender_id=plan-v02-14-offline-config-validation
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-06T07:36:01Z

# Candidate lesson (recurrence): a doc paragraph the change made false was outside the deliverable footprint

**Source signal:** Q-Gate finding 36193f (4-plan, taken into account)
**Component (suggested):** plan-marshall:phase-3-outline / api-sheriff documentation
**Category (suggested):** improvement
**Recurrence of:** 2026-10-04-09-004 ("A search for the removed code literal misses prose that repeats the claim the change makes false")

## What happened

- Deliverables 1 and 2 changed topology failure reporting. All topology failures of one pass are now collected in `TopologyResolutionException.errors()` and logged as ApiSheriff-200 entries plus an ApiSheriff-201 summary.
- doc/configuration.adoc (section "Schemas", lines 163-170) still said a single topology failure aborts the boot on its own rather than joining the aggregated report. Deliverable 7's affected files listed README.adoc, anchors.adoc, endpoint-routes.adoc and LogMessages.adoc, but not doc/configuration.adoc.
- The plan-phase scope-criterion validator found it (under_coverage). It was added to TASK-10 before execution.

## Rule

When a change alters behaviour that the docs describe, search the docs for the old behaviour described in words, not only for the changed code symbols, and add every hit to the doc deliverable's footprint.
