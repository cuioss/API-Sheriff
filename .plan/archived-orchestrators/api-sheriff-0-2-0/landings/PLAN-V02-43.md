# Landing Analysis: PLAN-V02-43 — Validator Slice: ReservedPath, Header and Topology Rules

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #505 (https://github.com/cuioss/API-Sheriff/pull/505)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-22 from the inbox message
> `plan-v02-43-validator-reserved-header-topology-rules-001.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `1e53ab4f` (#505, merge queue): 21 files changed (+3,518 / −1,836) in 16 commits.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Extract `ReservedPathRules`, `HeaderRules`, `TopologyRules` | met | `ConfigValidator` is down to 289 lines with no private `validate*` rule method left; the validator split (V02-26 → V02-43) is complete. |
| Series-closing ADR | met | ADR-0071 (renumbered from 0070 after #504 took it, as the orchestrator's mailbox note asked). ADR-0070 and ADR-0071 both on `main`; no ordinal collision. |
| Concern-map contract test | met | Added with `boot-validation-rules.adoc`. |
| Retire the PR-Agent / review-bot prose | met | No `pr-agent` / `cuioss-review-bot` mention left in `doc/development/`, `CLAUDE.md` or `AGENTS.md`; `ReviewerRosterDocsContractTest` guards it. |
| Threat model, test-corpus and remaining docs | met | `doc/security-threat-model.adoc`, `doc/development/test-corpus-integrity.adoc` updated. |

7/7 deliverables done.

## Metrics and Anomalies

- Tokens: 8,126,746. Wall time: 69,791 s (19.4 h), in parallel with `PLAN-V02-45`, `-47` and `-22`. Model: claude-sonnet-5-5.
- **Review closes.** Functional review and code review were closed by the operator at three heads
  (`841609fc`, `ee067536`, `1094af28`), every time on the same tooling false positive: `weakened_test`
  counts test cases per file, so cases moved from `ConfigValidatorTest` into the per-class tests read as
  losses. Recurrence of the V02-42 finding (lessons-routing 036).
- CodeRabbit: four findings, all fixed or answered, 0 unresolved threads. Sourcery declined on size (optional).

## Routing and Merge Behavior

- Merged through the merge queue as `1e53ab4f`.
- Post-merge on `main` at `1e53ab4f`, all passed: Maven Build, Integration Tests, Demo Client E2E, Config
  Validation, Scorecard. PR-attached Run Integration Benchmarks: passed.
- `cleanup_owed=true` at landing (the main checkout was on the operator's release-fix branch). Since resolved:
  the main checkout is on `main` at `00293178`, clean, and no plan worktree remains.

## Residue and Owners

- The validator split is closed. `PLAN-V02-23` (cookie forwarding policy) is unblocked and needs a full
  re-ground; `PLAN-V02-05` and `PLAN-V02-20` are no longer held by the split.
- **Lessons.** Three plan-marshall lessons recorded locally by the plan were routed to the plan-marshall
  lessons-routing inbox as 048–050 and removed from the local store (048 recurs 036).
