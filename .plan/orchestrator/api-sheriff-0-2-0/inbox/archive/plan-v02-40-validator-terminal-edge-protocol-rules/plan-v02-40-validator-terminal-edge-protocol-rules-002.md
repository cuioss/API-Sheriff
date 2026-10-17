envelope_version=1
sender_type=plan
sender_id=plan-v02-40-validator-terminal-edge-protocol-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T13:32:09Z

# Inline tech_debt outline path filed no CERTAIN_INCLUDE assessments

**Source**: 3-outline Q-Gate, finding c501c1 (assessment_coverage, taken_into_account).
**Probable owner**: plan-marshall:phase-3-outline (inline change-type workflow) / plan-marshall:manage-findings assessment contract.

## What happened

The outline ran the inline `tech_debt` change-type workflow. It logged a consumer sweep in decision.log but filed no assessment for any of the 19 affected files in deliverables 1-5. The `assessment list --certainty CERTAIN_INCLUDE` call returned 0 rows, and its `findings_store_state` was `missing`. The assessment_coverage Q-Gate flagged this, and the orchestrator had to file 22 assessments by hand in the revision round.

## Corrective rule (candidate)

The inline change-type outline path should file one CERTAIN_INCLUDE assessment per declared affected file as part of the sweep it already runs, or the gate should accept a recorded reason why this path files none. Right now the inline workflow and the assessment_coverage gate disagree about what a finished outline contains, so every inline tech_debt outline fails this gate once.
