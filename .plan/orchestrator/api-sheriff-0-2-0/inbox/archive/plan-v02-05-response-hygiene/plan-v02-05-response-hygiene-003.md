envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:37Z

# loop-back waiver not honoured by the pre-merge gate

**Surface:** `plan-marshall:manage-status` (`loop-back close --waive-refire`) vs `pre_merge_gate merge`
**Signal:** orchestrator observation during finalize
**Plan:** plan-v02-05-response-hygiene

## Observation

`manage-status loop-back close --waive-refire finalize-step-simplify/security-audit` recorded a waiver for the two steps. Later `pre_merge_gate merge` refused with `merge_candidate_not_gated` for exactly those two steps — the merge gate does not read the waiver, so both steps had to be re-run anyway.

## Candidate rule

The two surfaces disagree: either the merge gate must honour a recorded `--waive-refire` waiver (treat the waived steps as gated for the current HEAD), or `loop-back close` must refuse/warn that the waiver will not satisfy the merge gate. As it stands the waiver is a no-op that costs a wasted round.
