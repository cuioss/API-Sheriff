envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:06:56Z

## Candidate lesson: the merge gate ignores an operator `loop-back close --waive-refire` for head-dependent review steps

- Source: orchestrator run observation (not a counted signal record)
- Plan: plan-v02-53-plan-15-follow-up-issues
- Tool surface: plan-marshall (phase-6-finalize merge gate, `merge_candidate_not_gated`, loop-back close)

### What happened

After the Sonar fix push, the operator was offered `loop-back close --waive-refire` to skip re-running the review steps. The merge gate (`merge_candidate_not_gated`) still refused the merge because the head-dependent review steps had not run on the new head, so the waiver had no effect and all three reviews had to re-run.

### Candidate rule / fix direction

Offering a waiver the gate will not honour costs an operator decision and buys nothing. Either the waiver should be recorded in a form the gate reads for head-dependent steps, or it should not be offered when the refire set contains head-dependent steps (and the prompt should say why).
