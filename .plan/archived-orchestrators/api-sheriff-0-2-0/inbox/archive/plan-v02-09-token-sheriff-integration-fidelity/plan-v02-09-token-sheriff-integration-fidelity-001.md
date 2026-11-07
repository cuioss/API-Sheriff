envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T23:24:36Z

# Candidate lesson: a late review-bot round re-runs every HEAD-bound finalize verdict

**Signal source:** automated review (CodeRabbit, PR #530)

**Observation.** A late CodeRabbit round on PR #530 produced comments that became 5 fix tasks. Each fix commit moved HEAD, and because the finalize review steps (simplify, security-audit, functional-review, code-review) bind their verdicts to HEAD, all four had to re-run. Steps that declare no `verdict_inputs` fail closed on verdict currency, so any HEAD move invalidates them, even when the fix touched only one test file.

**Cost.** One late bot round re-ran four expensive review dispatches.

**Possible corrective direction (for orchestrator judgement).**
- Get the review-bot round to land before the self-review steps run, or batch bot fixes into a single commit, so HEAD moves once.
- Declare `verdict_inputs` on review steps so a fix outside a step's input set does not invalidate its verdict.

**Classification:** deferred to the orchestrator. This is likely a plan-marshall lesson (finalize step ordering and verdict currency), not a project lesson.
