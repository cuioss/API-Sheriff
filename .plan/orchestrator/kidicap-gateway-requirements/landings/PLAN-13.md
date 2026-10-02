# Landing Analysis: PLAN-13 — Defect Fixes (AS-7, AS-13, AS-10)

epic: kidicap-gateway-requirements
workstream: WS-01
pr: #334 (merged as 3abc3700b62da5d554d846b4e0a4d0b47f88c642)

> Landing record for one shipped plan. Lives at `landings/PLAN-13.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `plan-13-defect-fixes-003.md` (`kind: landing`, `complete: true`) plus the operator's
finalize report. Corroborated: PR #334 reports `state: merged` with `merge_commit_sha`
`3abc3700b62da5d554d846b4e0a4d0b47f88c642`, which is `origin/main`'s head; 68 files, +5370/−534.

## Deliverable Fidelity vs Spec

10 of 10 reported done, covering the three AS items the spec carried.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1–2 AS-7 native JSON claim types + tests | shipped-as-specified | merged diff touches the BFF claim conversion and userinfo tests; `StrictQueryAcceptanceIT` and unit suites in the same commit |
| 3–5 AS-13 raw-query handoff, forward-what-was-validated, strict CR/LF, acceptance matrix | shipped-as-specified | new `StrictQueryAcceptanceIT` (237 lines), `SecurityProfileModeIT` changes; strict now refuses decoded CR/LF via the cui-http 3.1 option |
| 6–9 AS-10 per-issuer JWKS state, readiness DOWN until loaded, backoff retry, late-IdP IT | shipped-as-specified | JWKS state/readiness sources and ITs in the merged diff |
| 10 documentation and records | shipped-as-specified | docs and log-message records in the diff; ADR-0047 summary scope corrected in d190d90 after review |

Both library questions the spec carried as HYPOTHESIS were settled inside the plan: the structured claim
value (AS-7) and the per-issuer JWKS status (AS-10) — neither blocked the landing, and the cui-http 3.1
option (`allowLineBreaksInParameterValues`) carried the AS-13 CR/LF half as planned.

## Metrics and Anomalies

- Tokens: 8,267,036. Duration: 264,905 s wall (~73 h 35 m).
- Anomalies, all recorded rather than silent:
  - **Merged past a recorded review gap.** The required reviewer `cuioss-review-bot` (PR-Agent) last
    reviewed 8f8d790 and was not re-triggered for the final commits (`participated_stale`). The operator
    instructed to proceed; a merge authorization bound to exactly d190d90 and to that one gap was recorded
    and consumed, so `branch-cleanup` reports "merged under an authorization, gap recorded" rather than a
    clean pass. CodeRabbit reviewed abd0fba, its one actionable comment (ADR-0047 summary scope) was fixed
    in d190d90, and it did not re-review (hourly quota exhausted). Sourcery (optional) refused on diff size.
  - **Finalize loop-back ceiling exceeded by one round** (`max_iterations: 9` → a 10th on operator
    instruction), and `--force` was used once to replace the review step's round-10 loop-back record with
    `done`.
  - **`adr-propose` and `lessons-capture` skipped** (lane off) for the second plan running — see the
    standing Open Defect; the two candidate lessons this plan did emit came from the preference emitter,
    not from that lane.
  - Sonar new-code issues: 0 (confirmed).

## Routing and Merge Behavior

- Review: CodeRabbit 2 comments (1 fixed, 1 summary); PR-Agent stale and accepted by the operator;
  Sourcery skipped (optional, diff-size cap).
- CI/merge: green at d190d90; merged through the merge queue (~25 min). `cleanup_owed=false`; worktree
  removed, working tree clean.
- **Post-merge verification — the orchestrator's own job, and it was owed.** Both runs the plan could not
  observe are now checked and green:
  - main-branch **Maven Build** for 3abc370 — success, including `build / deploy-snapshot`, `sonar-build`,
    `build (25)`, `build (26)` and `conclusion`
    (`actions/runs/35573552326`). Integration Tests, Demo Client E2E and Scorecard for the same push:
    success.
  - PR-attached **Run Integration Benchmarks** (`benchmark.yml`, merged-PR gated) — success
    (`actions/runs/35573553373`).
- **Surface expansion:** declared 19 entries, realized 68 files — 39 undeclared, 0 declared-but-untouched
  (`state: expansion_detected`, base `3abc370~1`). Second landing in a row expanding roughly threefold;
  unlike PLAN-15 this one left nothing declared-and-unused, so the declaration was accurate as far as it
  went and simply too narrow.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-13 --status shipped`
- [x] row `pr` stamped `#334` — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-13.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` stamped `plan-13-defect-fixes` — `queue --set-row`
- [x] epic.md reconciled: AS-7, AS-13, AS-10 shipped; post-merge CI watch retired as verified green
- [x] Open Defect opened for the review-gap merge and the exceeded loop-back ceiling
- [x] Open Defect for skipped ADR/lessons lanes updated — now two consecutive plans
- [x] both candidate lessons dispositioned (see the epic Decisions section)
- [x] resume_anchor updated
