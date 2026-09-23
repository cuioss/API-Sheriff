# Landing Analysis: PLAN-29 — Final Gap Closure

epic: deployment-configurability
workstream: WS-09
pr: [#348](https://github.com/cuioss/API-Sheriff/pull/348)

> Reconciled 2026-09-23 from the plan's own `kind: landing` inbox message
> (`plan-29-final-gap-closure-007.md`, `complete: true`), corroborated against ground truth:
> `ci pr view --pr-number 348` (state `merged`, squash `070eda54d465f532e77cca717d2f2bfd06172328`,
> matching the message's `footprint_base_sha`), `git show --stat` (8 files, +132/-9), and
> `ci checks status --pr-number 348` (`overall_status: success`, 34 checks). The `RefreshFlow.reportScopeDelta`
> claim underpinning D1's drop was independently verified against the resolved `token-sheriff-client:0.9.6`
> bytecode (`javap`), not taken on the plan's word alone.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|---|---|---|
| 1. `TokenRefreshCoordinator.scopeDelta` observability WARN | **dropped by operator decision** | Verified independently: `RefreshFlow.classifyScopeDelta(...)` → `reportScopeDelta(ScopeDelta, String, RefreshRedemption)` is called in the refresh flow, and `ClientLogMessages$WARN` declares `SCOPE_NARROWED`/`SCOPE_BROADENED` (`TokenSheriffClient-110`/`-111`) — confirmed via `javap` against the resolved `token-sheriff-client-0.9.6.jar`. A gateway-side WARN would have duplicated an existing engine-side signal. Correct call. |
| 2. `BuildGateCoverageContractTest` citation fix | shipped-as-specified | `:230` now reads `"...the quality gate that the 'Pre-Commit Process' section of CLAUDE.md"` — section-name citation, matching `doc/development/build-gate-discipline.adoc`'s existing style; immune to the line-drift that caused the original defect |
| 3. `doc/development/local-test-environment-caveats.adoc` | shipped-as-specified, **with a mid-flight correction** | New doc (115 lines) + `doc/development/README.adoc` index entry (+7); a review bot caught the doc overstating "green baseline proves non-causality" (it only supports the hypothesis) — fixed in the same PR (TASK-3, commit `927947c`) |

### Opportunistic fix, outside spec

⛔ **The ADR-0050 cross-epic ordinal collision this epic routed to `api-sheriff-0-2-0` on 2026-09-23
was fixed IN THIS PR, not by that epic.** `doc/adr/0050-Portal_templates...adoc` renamed to
`0053-Portal_templates_render_on_a_standalone_Qute_engine_and_escape-bypass_constructs_are_refused_at_boot.adoc`
(commit `32c20d1`), with four referencing docs updated (`doc/architecture.adoc`,
`doc/configuration.adoc`, `doc/security-threat-model.adoc`, `doc/user/portal.adoc`). Verified: `ls
doc/adr/ | grep -oE '^[0-9]{4}' | sort | uniq -d` now returns nothing — no duplicate ordinals. The
epic's earlier routed finding (`.plan/orchestrator/api-sheriff-0-2-0/inbox/deployment-configurability-001.md`)
is superseded by this fix; not this epic's to retract from the other epic's inbox, but recorded here
so the Open Defect closes with the right attribution.

### Surface fidelity

Declared 7, realized 8 (the ADR rename touched 5 files the spec never declared, since it was
opportunistic, outside-spec work — an honest over-declaration miss the spec could not have
anticipated). `TokenRefreshCoordinator.java` and `BffLogMessages.java` — declared for the now-dropped
D1 — were correctly never touched.

## Metrics and Anomalies

- Tokens: **6,581,259**; wall **5h32m**, worked 1h41m (n=4/6 phases reporting), idle 3h50m.
- Loop-back iteration **5 of 5 spent** — the run consumed its entire budget. Root cause, per the
  plan's own candidate-lesson: `re_review_on_loopback: false` (the PLAN-28-documented, deliberately
  kept setting) left the required review bot's participation `participated_stale` after every fix
  push, forcing a loop-back to re-trigger it each time. Quantified evidence the standing decision now
  carries a real cost, not fixed here (correctly — the rationale still stands) but recorded.
- `ci_timeout` recurred 5× in one run (Maven `sonar-build` ~850s, `integration-tests` ~1600s
  routinely exceed `ci_wait`'s default budget), all `accepted`/retry — 3 separate loop-back iterations
  spent on a disposition that was never anything but "retry."
- Two plan-marshall argparse/tooling gaps recurred from PLAN-28's own findings class (`qgate list`
  missing `--phase`, `review_completeness --participated-bots` given a bare bot_kind — the latter a
  direct recurrence of an item already forwarded from this epic to `process-compliance`).

## Routing and Merge Behavior

- Review: CodeRabbit found and the plan fixed the evidence-overclaim issue in the new doc (`412fa1`,
  TASK-3); "3 comment(s) found — 3 reviewed" per the plan's own report, "unified triage pending" in
  its own record — per this epic's own established rule, that prose is not the reliable signal; the
  pre-merge barrier's own clean report is, and it fired correctly on retry after the bare-bot-kind
  argparse rejection was corrected.
- CI/merge: `ci checks status --pr-number 348` → `overall_status: success`, 34 checks. Merged via
  merge queue (squash), `070eda5`.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped `348`
- [x] row `landing` stamped `landings/PLAN-29.md`
- [x] row `plan_marshall_plan_id` stamped `final-gap-closure`
- [x] epic.md queue reconciled from status.json
- [x] Open Defect resolved: `RotationResult.scopeDelta` unread — closed as "already surfaced by the
      engine" (`TokenSheriffClient-110`/`-111`), not fixed gateway-side, per verified operator decision
- [x] Open Defect resolved: ADR-0050 cross-epic collision — fixed opportunistically in this PR
      (renumbered to 0053), attribution corrected
- [x] Inbox drained: 7 messages, 7 archived, 0 invalid — 1 reconciled (this landing), 3 lessons
      promoted to the local corpus, 3 routed to plan-marshall's `lessons-routing` epic (one a
      recurrence of an already-forwarded finding)
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated

## Follow-Ups

- Epic queue is now fully terminal — no staged candidates, nothing to emit. This closes out
  `WS-09`'s plan list (PLAN-28 + PLAN-29) and, pending the operator's confirmation, the epic itself.
- The `re_review_on_loopback` cost evidence (lesson `2026-09-23-15-003`) is not actioned — the
  standing PLAN-28 decision holds — but is available if the setting is ever revisited.
