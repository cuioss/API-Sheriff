# plan-marshall bug: marshalld daemon routing inverts build verdicts (`status: error` → `status: success`)

**Severity:** critical — silent data corruption in the build-result contract.
**Affects:** `plan-marshall:build-maven:maven run` (and, by shared code path, every other `build-*` wrapper).
**Version observed:** plan-marshall `0.1.1182`, marshalld protocol version `1`.
**Precondition:** the project is enrolled in the marshalld registry **and** the daemon answers a verified handshake (`build_server preflight` → `ready`). With no daemon, the bug does not manifest.

## Summary

When a build is routed to the marshalld daemon, a **failing build is reported to the caller as `status: success, exit_code: 0`**. The true result is written only to the daemon's job log. Any caller that trusts the returned TOON — which is the documented contract — concludes the build passed.

The same command run in-process reports the failure correctly. The daemon path inverts the verdict.

## Minimal reproduction

From any marshalld-registered project with the daemon running:

```bash
# In-process — CORRECT
python3 .plan/execute-script.py plan-marshall:build-maven:maven run \
  --command-args "nonexistent:goal" --execution-mode in_process --timeout 300
```
```toon
status: error
exit_code: 1
error: build_failed
errors[5]{file,line,message,category}:
  -,-,"No plugin found for prefix 'nonexistent' ...",deprecation_warning
```

```bash
# Daemon — WRONG, same command
python3 .plan/execute-script.py plan-marshall:build-maven:maven run \
  --command-args "nonexistent:goal" --execution-mode daemon --timeout 300
```
```toon
status: success
exit_code: 0
duration_seconds: 4
log_file: /home/oliver/.plan-marshall/marshalld/job-logs/<id>.log
```

The returned `log_file` contains the truth that was discarded:

```toon
[EXEC] ./mvnw -l ... nonexistent:goal
status: error
exit_code: 1
error: build_failed
```

Note the daemon result carries **no `error` field and no `errors[]` table at all** — it is not a partially-populated failure, it is a fully-formed success envelope.

## Root cause

Two facts combine:

1. **`maven.py run` deliberately exits 0 on a failed build.** It signals failure in its TOON (`status: error`), not through its process exit status. Verified: running the failing goal with `--execution-mode in_process` and capturing `$?` gives shell exit **0** while the TOON says `status: error, exit_code: 1`.

2. **The daemon derives `job_status` from the child process's exit code.** It re-runs `execute-script.py … maven run` as a child (per the D5 routing seam in `script-shared/scripts/build/_build_execute_factory.py`). That child exits 0 by fact (1), so the job is recorded `job_status: success`.

`_daemon_result_to_direct()` in `_build_execute_factory.py` then maps:

```python
job_status = str(waited.get('job_status', ''))
if job_status == 'success':
    return success_result(...)
```

and the child's own TOON — the only place the real verdict exists — is never consulted. The failure is discarded at this line.

The mapping for `timeout` / `failure` / `killed` immediately below it is correct; the defect is that `job_status` can never *be* `failure` for a tool whose executor exits 0 on failure.

## Suggested fix

The daemon must not infer build success from the re-run executor's process exit code, because plan-marshall executors intentionally exit 0 while reporting failure in-band. Options, in order of preference:

1. **Parse the child's TOON** in `_daemon_result_to_direct` (or daemon-side when recording `job_status`) and propagate its `status` / `error` / `errors[]` verbatim. The child already emits a well-formed TOON; forwarding it is lossless and fixes every tool at once.
2. **Make executors exit non-zero on `status: error`** so process exit code becomes a truthful signal. Larger blast radius — callers may rely on exit 0.
3. **At minimum, fail closed:** if `job_status == 'success'` but the child TOON's `status != 'success'`, surface the discrepancy as an error rather than preferring the wrapper's view.

A regression test should assert that a deliberately-failing goal returns `status: error` through **both** execution modes.

## Impact observed in practice

Encountered during a coordinate-relocation plan on `cuioss/API-Sheriff`. Two distinct false greens in one session:

- **A native integration-test run** that was killed by a 662s timeout while still waiting for Keycloak — **zero integration tests executed** — returned `status: success, exit_code: 0`. Its inner log said `status: timeout, exit_code: -1`. It also reported `tests: passed: 709` (the upstream unit tests from `-am`), which reads as corroborating evidence of a passing run.
- **A `verify -Ppre-commit` quality gate** that failed on `maven-javadoc-plugin` returned `status: success, exit_code: 0`. Inner log: `status: error, exit_code: 1, error: build_failed`.

Both were caught only because the orchestrator opened the `log_file` by hand. Nothing in the returned TOON suggested a problem.

This is severe for plan-marshall specifically because the wrapper's TOON is the documented, machine-read contract for:

- every `phase_5.verification_steps` entry,
- the phase-6 `pre-push-quality-gate` step,
- the `execute-task` Handle-Verification loop and its `triage_required` detection.

A dispatched leaf that reads `status: success` has no reason to open the log. The practical effect is that **on any marshalld-enabled machine, plan-marshall's build verification can silently pass over failing builds**, and a plan can reach `create-pr` with its quality gate never actually green. The timeout variant is worse than the plain-failure variant: a build killed before it ran a single test is indistinguishable from a clean pass.

## Workaround until fixed

Treat the wrapper's top-level `status` as untrustworthy whenever the daemon is active. Read the returned `log_file` and use the inner `status` / `exit_code`. Alternatively pass `--execution-mode in_process` to bypass the routing seam, at the cost of the daemon's concurrency control.

---

# Second, related bug: `ci pr merge` reports `merged: true` for a PR it did not merge — and deletes the branch

**Severity:** critical — destructive false-success. Work can be stranded.
**Affects:** `plan-marshall:tools-integration-ci:ci pr merge`
**Version observed:** plan-marshall `0.1.1182`.
**Precondition:** the target repository has a **GitHub merge queue** enabled on the base branch.

## What happened

```bash
python3 .plan/execute-script.py plan-marshall:tools-integration-ci:ci pr merge \
  --pr-number 93 --strategy squash --delete-branch
```
```toon
status: success
operation: pr_merge
pr_number: 93
strategy: squash
merged: true
branch_deleted: fix/javadoc-release-and-oneshot-cleanup
already_gone: false
```

Actual outcome, verified via the GitHub API:

| Signal | Value |
|---|---|
| PR state | `CLOSED` |
| `mergedAt` | `null` |
| `mergeCommit` | `null` |
| remote branch | **deleted** |
| `origin/main` | unchanged — no commit from this PR |

The PR was **closed without merging** and its **branch deleted**. The verb reported `merged: true`.

## Root cause (probable)

`gh pr merge --delete-branch` is rejected outright when a merge queue is enabled:

```
X Cannot use `-d` or `--delete-branch` when merge queue enabled
```

The verb appears not to check `gh`'s exit status before reporting success, and its branch-deletion path ran regardless. Deleting the head branch of an open PR causes GitHub to close that PR — which explains `CLOSED` + `mergedAt: null` + branch gone, with no merge commit anywhere.

Reproduced directly afterwards: on the same repository, `gh pr merge 94 --squash --delete-branch` fails with the message above, and `gh pr merge 94 --squash` responds `! The merge strategy for main is set by the merge queue` (the PR is enqueued rather than merged synchronously).

## Why this is worse than a cosmetic reporting bug

1. **It is destructive.** The branch is deleted. The commit survived here only because a local checkout still held it. An orchestrator that had already returned to `main` and pruned its worktree would have lost the work with no remote copy and no merge commit.
2. **It defeats the phase-6 contract.** `final_merge_without_asking` flows trust this verb's return to decide the plan merged successfully. A plan can archive itself as complete while its PR is closed-unmerged and its branch destroyed.
3. **`merged: true` is affirmative, not merely absent.** Like the daemon bug above, the caller is given a fully-formed success envelope with nothing to prompt a second look.

## Suggested fix

- Check `gh`'s exit code and stderr before reporting `merged`. Never emit `merged: true` without confirming `mergedAt` / `mergeCommit` is non-null via `gh pr view`.
- Detect merge-queue repositories (`ci repo merge-queue probe` already exists) and **omit `--delete-branch`** when a queue is enabled; let the queue's own post-merge branch deletion handle it.
- **Never delete a branch on a failed merge.** Branch deletion must be strictly conditional on a verified merge.
- Recognise the enqueued state: with a merge queue, `gh pr merge` returns before the merge happens. The verb should report an `enqueued` status distinct from `merged`, and callers should poll until `mergedAt` is non-null.

## Common theme with the daemon bug

Both defects share one shape: **a wrapper reports success derived from something other than the actual outcome** — a child process's exit code in one case, an unchecked CLI invocation in the other — and emits a well-formed success envelope that gives the caller no reason to verify. In a system where dispatched leaves are explicitly told to trust structured TOON returns, that shape is especially dangerous.

A useful invariant for both: **a success claim about an external system should be read back from that system before it is reported.**
