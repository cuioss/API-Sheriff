envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T21:59:45Z

# Candidate lesson: the weakened-test check misreads class moves and planned dedups

**Suggested route:** plan-marshall (the ../plan-marshall lessons-routing inbox). The defect is in the plan-marshall review tooling, not in API Sheriff.

**Source:** plan-v02-27-test-simplification (PR #520, merged as 6b8d88f4). The Q-Gate findings were filed by the 6-finalize functional-review and code-review steps.

## What happened

The `test-integrity` check raised the same 11 blocking `weakened_test` rows in every functional-review and code-review round. Examples are ff7e0c, 48f4c3, f8dc5d, 663200 and c63431-6073b1, plus 015120 and efa7f0 in the delta round. Each one was raised as "N assertion(s) present at origin/main are gone from the file". None of them was a real weakening:

- 3 were class moves: GatewayEdgeRouteRetryAfterTest to edge/error/ErrorResponseWriterTest, ReservedBodyCeilingTest to edge/reserved/, and EdgeHardeningTest to EdgeHardeningOptionsTest. The diff read the old path as deleted.
- 8 were planned deliverable-3 duplicate removals. Their assertions were carried into the surviving tests first.

The verifier said so itself. Verdict records 10c14e and 376beb state that "F4-F14 as blocking weakened_test do not" hold, and that "moves/dups relocated". Even so, the rows stayed blocking. They could only be closed by operator override (resolution `accepted`), and that happened once per round. The findings reached `reobserved_count` 4, and closing them added several review rounds.

## Corrective direction

- Make the check rename-aware: compare an old path against its git rename or copy target before counting assertions as gone.
- Give a planned dedup a way to be declared: a deliverable or outline marker listing the removed test and its surviving counterpart, which the check honours.
- Once the verifier has refuted a blocking finding with evidence, an identical re-observation in the next round should not reopen it as blocking.
