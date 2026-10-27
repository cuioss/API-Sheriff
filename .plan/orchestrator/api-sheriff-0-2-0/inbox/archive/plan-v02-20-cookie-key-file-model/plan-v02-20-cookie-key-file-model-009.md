envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:35:00Z

# Candidate lesson (plan-marshall tooling): every HEAD advance re-fires simplify and security-audit because their verdict inputs are undeclared

- Source: work/decision log, 6-finalize (17:10:55, 17:12:10, 18:31:02, 21:46:53)
- Suggested component: plan-marshall:phase-6-finalize (verdict currency / re-fire policy for finalize-step-simplify and finalize-step-security-audit). Route to the plan-marshall lessons-routing inbox.

## What happened

finalize-step-simplify and finalize-step-security-audit re-fired on each HEAD advance with `verdict_currency=invalidated/verdict_inputs_undeclared` (6e1f0ed0 -> 1996696e and 91eea903 -> 1996696e), including advances made only by doc corrections from the functional review. The CodeRabbit inline-fix commit 36229c1c (doc wording plus Java) looped finalize back and re-fired all head-dependent steps, and the later operator-directed fix commits forced a third full set at 008f7f7f (see the merge_candidate_not_gated candidate). Functional review closed at three different HEADs (1996696e, 36229c1c, 008f7f7f) and code review ran at two before the operator override.

## Corrective rule / improvement

Let these steps declare their verdict inputs (for example the Java/production path set), so a HEAD advance that touches only docs or tests leaves their verdict current instead of invalidated. Without declared inputs, every review-driven doc fix pays the full re-fire cost.
