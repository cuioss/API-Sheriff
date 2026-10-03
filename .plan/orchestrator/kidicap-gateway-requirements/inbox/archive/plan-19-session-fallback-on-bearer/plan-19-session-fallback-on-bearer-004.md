envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-24T21:10:34Z

# Candidate lesson: deep-lane outline recorded no CERTAIN_INCLUDE assessments

**Source**: Q-Gate finding `af67c0` (phase 3-outline, severity warning, resolved in-run as taken_into_account).
**Plan**: plan-19-session-fallback-on-bearer (PR #356, merged as 1fa648d)

## What happened

The plan ran the deep planning lane. The outline declared 34 affected paths across 7
deliverables. The findings store held no assessments at all: `assessment list --certainty
CERTAIN_INCLUDE` returned total_count 0 and the store state was `missing`. The
assessment-coverage check had nothing to compare, so it could not pass for any deliverable. The
Q-Gate checked the outline content independently and found it sound. The gap was procedural:
the outline author never wrote the assessment records.

Resolution: 41 CERTAIN_INCLUDE assessments were recorded in the re-outline pass, one per declared
path (read-intent, write-new and the newly added endpoint-routes.adoc included).

## Candidate rule

In the deep lane, the outline author should record one CERTAIN_INCLUDE assessment per declared affected
path before handing off to the Q-Gate. Alternatively, the outline workflow should self-check the
assessment count against the affected-path count before it declares the outline done. At present
the gap shows up only as a Q-Gate finding, which costs a re-outline round trip.

## Classification hint

This looks like a plan-marshall workflow gap (phase-3-outline deep lane). It is not a project
defect, so global or upstream routing may fit better than the epic.
