envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:07:00Z

# Candidate lesson: the scope-creep guard fails exactly when it has something to report, and it counts upstream merges as plan scope

## Pattern

Two defects in the same guard (`plan-marshall:phase-5-execute:scope_creep_check`), which compound.

1. **It cannot persist its own finding.** When the measured residual is over the threshold, the guard tries to file a finding of type `scope_creep_warning`. The findings store does not know that type and rejects it, so the script exits 1 with `finding_persist_failed`. Below the threshold there is nothing to persist and the script succeeds. The guard therefore works only while it has nothing to say.
2. **It measures the committed diff against the base, including merged-in upstream commits.** After the base branch was merged into the feature branch, the files that merge brought in were counted as paths outside the plan's declared scope. They put the residual over the threshold on their own, although the plan authored none of them.

Together: one upstream merge turned the guard into a failing script call after every task for the rest of the execute phase, and the genuine out-of-scope paths that appeared later were buried in that noise.

## What the record shows

- First call, before any upstream merge: no failure (work log da28ed, TASK-1).
- After the merge of the base: 14 `script_failure` lines for this notation between 2026-10-01 13:49Z and 2026-10-02 08:25Z, all with the same detail: `Invalid finding type: scope_creep_warning. Must be one of ('bug', 'improvement', ... 'pr-comment-overflow')` (work log 5b10d1, repeated; b99b97).
- Residual 7 over threshold 5, "all 7 paths arrived with the merge of origin/main ... none is authored by this plan" (work log 727418, 6bafc8, 464d83).
- The residual later grew to 9, 10 and 12 as real additions joined: an authorized wording fix in two files, a new test-support class, a test file and a redrawn diagram that no deliverable declared (43359f, e9e96b; decision log cef291). Those are the cases the guard exists for; they were recorded only in log prose because the finding could not be stored.
- The defect was named a known tool defect in the first affected envelope and carried as an open item to the end of the phase (decision log cbbcb6, 2e5c29).

## How to recognise it next time

- `finding_persist_failed` on `scope_creep_check` with `Invalid finding type`.
- The residual count equals the number of files of the last upstream merge.

## Suggested direction (for the orchestrator to classify)

- Tooling fix, two parts: register the finding type the guard emits (or have the guard emit a type the store accepts), and exclude paths that reach the branch only through a merge of the base from the residual.
- Until fixed: on the first `finding_persist_failed`, subtract the merge paths by hand once, record the true residual, and report only changes to it; do not let each later envelope rediscover the same explanation.
- This is the one script-failure cluster of this run that is a tool defect rather than a caller error.
