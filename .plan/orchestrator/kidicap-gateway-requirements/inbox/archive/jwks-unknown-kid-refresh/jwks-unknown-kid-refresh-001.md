envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T15:28:30Z

component=plan-marshall:phase-3-outline
category=improvement
created=2026-10-12
bundle=plan-marshall

# Outline assessment-coverage check reports a plan-wide finding when the outline path stores no assessments

Source record: quality-check finding 0f1bfe (phase 3-outline, severity info, resolution accepted) of plan `jwks-unknown-kid-refresh`.

The outline quality check `assessment_coverage` ran `assessment list --certainty CERTAIN_INCLUDE`, got `total_count 0` with `findings_store_state missing`, and reported that none of the 29 declared paths across the 4 deliverables was backed by a CERTAIN_INCLUDE assessment, so the coverage check could not be performed at all.

The finding is plan-wide and names no deliverable. Re-outlining cannot clear it. The only two exits it offers are backfilling assessment records after the fact or accepting the finding.

## Resolution in this run

Accepted as-is. No assessments were recorded for this plan and none were added on the outline re-entry; the outline was not restructured for it (operator instruction). Backfilling 29 assessment records after the outline was written was judged to add no evidence.

## Candidate lesson

When the outline path taken produces no assessments by design, the coverage check has nothing to measure and still emits a finding that costs a triage round. Either the check should recognise "no assessment store for this outline path" as not applicable, or the outline path should record assessments as it goes so the check has an input.

## Impact

One avoidable triage decision per plan on the affected outline path. No effect on the shipped change.
