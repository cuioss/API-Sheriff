envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:05:54Z

# Candidate lesson: main moves under a long finalize, and the baseline is only checked at entry

## Pattern

Finalize checks the branch against the base once, at entry (sync-baseline). When the steps between that check and the push take hours, the base keeps moving and nobody looks again. The conflict is then discovered at the latest and most expensive point: after the pull request exists, with the loop-back budget already spent.

A second, related pattern: a plan that authors a numbered decision record allocates the ordinal when the record is written. Every upstream merge that lands a record in the meantime invalidates it. This plan renumbered its record twice.

## What the record shows

- Sync-baseline at finalize entry, 08:55Z: `classification=no_overlap`, one upstream commit, auto-proceed (decision log 4100f1, ca9c78).
- Push and pull-request creation at 13:35Z to 13:39Z, four and a half hours later, with no baseline check in between.
- At 13:41Z the new pull request was `mergeable=conflicting`: the base had advanced by 7 commits during finalize; a trial merge showed 24 conflicted files (10 production, 6 test, 8 documentation), and the base now carried its own decision record under the ordinal this plan had used (work log e51b52).
- Recovery needed an operator-authorized loop-back beyond the spent ceiling: merge of the base, re-integration of the plan's change with the upstream change in the same production files, a second renumbering of the decision record, document corrections, and a full re-verification (decision log 795744, af8034). That took about two and a half hours (13:47Z to 16:13Z).
- The first renumbering had already happened in the execute phase, for the same reason, after an earlier upstream merge (decision log 6b7b5b, f54118; work log fd3cfe).
- The settle steps (simplify, security audit, self-review) were not re-run over the merge delta; their records were re-stamped to the new head (af8034).

## How to recognise it next time

- Finalize has been running for more than about an hour and a source-mutating or long-waiting step (a self-review loop, an operator question) has just ended.
- The plan creates a file whose name carries a corpus-wide ordinal.
- Sibling plans are active on the same module.

## Suggested direction (for the orchestrator to classify)

- Re-fetch and re-classify against the base immediately before the push, not only at finalize entry; a conflict found there costs a merge, not a pull-request round trip.
- Re-derive any corpus ordinal at the same point, and treat the ordinal chosen at authoring time as provisional.
- For the orchestrator: two plans touching the same production package should not be in flight together, or the later one should expect a re-integration task and plan for it.
