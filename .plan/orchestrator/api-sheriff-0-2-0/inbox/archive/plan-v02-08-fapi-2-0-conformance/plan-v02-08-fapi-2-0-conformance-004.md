envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:06:15Z

# Candidate lesson: review-bot size limits are met only after the pull request exists

## Pattern

The required review bot refuses a pull request above a fixed file count, and the optional one refuses above a fixed diff size. Both limits are structural: no retry, wait or re-request clears them. The plan's footprint had been above the limit since the outline, but nothing in the pipeline compares the footprint with the reviewers' limits, so the refusal surfaced at the automatic-review step, after create-pr, CI and a full re-verification had already run on the oversized change. The only remedy at that point is to split the change, which is a late and expensive restructuring.

## What the record shows

- Self-review surface at finalize: 114 to 118 files (decision log fe87b4, 3161dc). The outline had declared about 105 paths (29bc89).
- First automatic-review pass: the required bot returned `refused_structural` for 117 files against its 100-file limit; the optional bot returned `refused_structural` for a diff above 150,000 characters; the step ended `loop_back` with the loop-back ceiling already spent (decision log 1d9dad).
- Operator decision: split. The code change kept code, tests, the integration stack and only the documents a contract test pins (91 files); 31 documentation files were reverted to the base on that branch and moved to a follow-up change from a second branch; the full tree was preserved on a separate branch first (9408eb, d86669).
- The split cost another whole-reactor quality gate, a full verify, a push, a CI run and a second automatic-review pass (d86669; work log cdb431, ci-verify completed again at 18:30Z, and d3e02c, automatic-review done at 18:39Z).
- The settle-step records were re-stamped to the split head without re-running (d86669).

## How to recognise it next time

- The declared footprint at outline is near or above 100 files, or the plan carries a broad documentation reconciliation deliverable next to code deliverables.
- A required reviewer is a hosted bot with a per-pull-request file cap.

## Suggested direction (for the orchestrator to classify)

- Compare the declared footprint with the configured reviewers' limits at outline or plan time, and decide the split there: code plus contract-pinned documents in one change, the remaining documentation in a second.
- At the latest, check the realized file count before create-pr and stop for a split decision before the pull request is opened.
- Record the reviewers' limits next to the `required_bots` configuration so the check has something to read.
- When documentation is split off, the documents a contract test reads must stay with the code, otherwise the code change is red on its own.
