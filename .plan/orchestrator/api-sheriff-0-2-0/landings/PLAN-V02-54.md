# Landing Analysis: PLAN-V02-54 — What TokenSheriff 0.9.8's Lazy Key Generators Bought, Measured

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #553 (https://github.com/cuioss/API-Sheriff/pull/553), merged as `267a285d`

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-11-01 from the inbox message
> `plan-v02-54-token-sheriff-lazy-generators-measured-001`. Checked against `origin/main`, the merge commit, the merged diff, the PR body,
> the CI runs on the merge commit, the PR review state, and the plan's archived run records under
> `.plan/local/archived-plans/2026-11-01-plan-v02-54-token-sheriff-lazy-generators-measured/work/`.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — controlled before/after | shipped | Six runs, A B A B A B, two detached worktrees at `fd77bb05` and `199a0cda`, each through the executor with `--project-dir`. The run records `run01-A.json` to `run06-B.json` name the worktree, commit, maven log and duration of each. Spread reported as min / median / max per measure. |
| D2 — per-JVM first use, from existing reports only | shipped, limit stated | 18 classes use the generators (3 direct through `InMemoryKeyMaterialHandler`, 15 through `TestTokenGenerators` / `TestTokenHolder`). Read from the surefire per-class `time`; no measurement class was written. The doc states what cannot be isolated (JVM start, discovery-time generation at A, classes that reach the generators only through other code). |
| D3 — counts and coverage unchanged | shipped | 4747 run (2 skipped) / 117 / 942 in every run. One `clean verify -Pcoverage` per commit: 470 classes, 10076 of 10410 lines in both; only `AnchorRules` differs (96 vs 95 of 98 branches), a class that does not import TokenSheriff. |
| D4 — record it | shipped | New section "TokenSheriff 0.9.8 lazy key generation: before and after" in `doc/quality-report/test-quality.adoc`. Verdict stated plainly: inside the noise. |
| D5 — close #422 after the merge | done by the main session | #422 was commented on and closed at 12:25:36Z by the operator's account, after the merge and with CI green on the merge commit. The PR names #422 without a closing keyword (`Refs #422`). I added a second comment with my own verification; the issue was already closed. |

**Declared surface vs realised.** The merged diff is one file, `doc/quality-report/test-quality.adoc` (+85 lines), exactly the declared
surface. No production code, test code or dependency changed.

## Orchestrator Verification (the spec's `Verification by the orchestrator`)

- **(a) The pin.** Root `pom.xml` `version.token-sheriff` is `0.9.8` on `main`; unchanged by the PR. Held.
- **(b) Test counts.** The spec asked for the CI log's surefire summary lines at the merge head. **They do not exist:** the merge commit's footprint is
  a single `*.adoc`, so CI skipped `build / build` (`build / conclusion` is `success`, `build / sonar-build` skipped), and the push runs for
  `199a0cda` are not retrievable. I re-derived the counts instead from the **raw Maven log of each of the six runs** (the file the executor wrote,
  not the plan's summary): all six show `Tests run: 117`, `4747 (Skipped: 2)` and `942`, no failures. They equal the figures in the document.
  This is a weaker source than the one the spec named, and the weakness is the CI skip, not the plan.
- **(c) Document and PR body agree.** The per-run table, the spread table, the 18-class and 15-JVM statements, the counts and the coverage figures are
  identical in `test-quality.adoc` and the PR body. Build durations 278 / 291 / 447 / 556 / 462 / 359 s also equal the `duration_seconds` in the six run records.
- **(d) Two worktrees at the two named commits, three or more runs each.** Held: runs 1, 3, 5 in `plan-v02-54-ab-A-fd77bb05`, runs 2, 4, 6 in
  `plan-v02-54-ab-B-199a0cda`.
- **Verdict: `measured`.** The spread is reported, and the claim is "inside the noise", not a gain. The result is a measurement on a **loaded host**
  (1-minute load 7.38 to 40.36; from run 3 other projects' Maven builds ran alongside), so the document says it cannot rule out a gain of a few
  seconds, only that none is detectable on that host. Only one pair (runs 1 and 2) ran with nothing alongside it: 278 s at A, 291 s at B.
- **Pre-registered CI baselines** (`Maven Build` median about 932 s, range 793 to 1,479 s) were not used as evidence, as the spec said.

## Metrics and Anomalies

- Tokens: 6,253,464. Wall time: 52,687 s (about 14.6 h, most of it waiting: the 90-minute CodeRabbit quota wait and the six builds). Model
  claude-opus-5-5, effort `low`, plugin and executor 0.1.2036.
- 12 finalize steps `done`, `cleanup_owed=false`; `emit-landing` and `archive-plan` follow the message by construction and are not claimed.
- CodeRabbit (required) used its one included hourly review on the first commit; the fix commit `7df7d49d` was reviewed after a 90-minute wait and a
  manual `@coderabbitai review`, with no actionable comments and Merge Risk "Minimal". Sourcery approved at `7df7d49d`. Zero resolvable threads
  (`thread_id` non-empty): the six unresolved rows are all `review_body` / `issue_comment` kinds, which cannot be resolved.
- An earlier automatic-review record had been force-marked `done` before the CodeRabbit review arrived; the plan superseded it with a normal-path
  record at the same head. Recorded as a process observation, not a defect of the merged change.
- Post-merge `Performance Benchmark` run `41305177059` (PR-attached) completed with `success`. Main-branch `Maven Build` `41305174716`, `Integration Tests`
  `41305174726`, `Demo Client E2E` and `Scorecard` on `267a285d` all `success`.
- No Sonar analysis exists for this PR (docs-only footprint; `sonar-build` skipped), recorded as no count, not zero.

## Follow-ups

- None raised by the measurement itself. A re-measure on a quiet host or in CI would sharpen the answer, but the issue asked for a measurement and a
  verdict, and the verdict is recorded. The pom comment at the pin ("Re-measure this paragraph on every `${version.token-sheriff}` bump") now has
  a section to point at; whether to update it is a small choice for the next bump, not a task.
- Host load is the limit of this result. The same hazard is in `PLAN-V02-55`'s spec, which names a quiet host and `uptime` for its own builds.
