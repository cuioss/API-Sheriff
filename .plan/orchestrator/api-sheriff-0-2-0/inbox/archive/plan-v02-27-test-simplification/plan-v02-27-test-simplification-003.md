envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T22:00:13Z

# Candidate lesson: every fix loop re-fires the whole finalize settle band

**Suggested route:** plan-marshall (the ../plan-marshall lessons-routing inbox). The defect is in phase-6-finalize loop-back re-entry.

**Source:** plan-v02-27-test-simplification. Recorded in the decision log and the execution manifest.

## What happened

Every HEAD advance re-fired the whole band: simplify, security-audit, the functional review and the code review. That held even for wording-only and Javadoc-only fixes.

- The simplify/security-audit pair ran four times: at 11:48, 17:07 and 20:12, plus the initial run.
- The functional review ran 3 full rounds plus 1 delta round:
  - 11:02, loop_back, 481k tokens, 970 s
  - 16:13, loop_back, 399k tokens, 699 s
  - 17:20, loop_back
  - 20:40, delta, 394k tokens
- The code review ran 1 full round (482k tokens) and 1 delta round (289k tokens).
- The closing full round was skipped by operator decision 8473d3.

Most of the later rounds re-raised only the same 11 already-refuted `weakened_test` rows (see the companion candidate lesson about the weakened-test check), so their cost bought little new signal.

## Corrective direction

- After a loop-back whose fix commits touch only a bounded path set, default the re-fired reviews to delta rounds over the new commits. Today the operator has to request that.
- Let simplify and security-audit skip when the fix delta holds no production source, or when it falls in a path class they already cleared at the prior HEAD.
- Treat "the same blocking set as last round, all operator-accepted" as a convergence signal that ends the loop without another full round.
