# Landing Analysis: PLAN-13 — Loopback Bind Fix and Socket Instrumentation

epic: deployment-configurability
workstream: WS-07
pr: [#255](https://github.com/cuioss/API-Sheriff/pull/255) — merged as `3fc4c83` (squash, via merge queue)

> The epic's longest-running plan and its single biggest unblocker. Landing arrived **by paste, and
> correctly so** — see Inbox below.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| Bind every ephemeral test listener to loopback | shipped-as-specified | 22 live-socket fixtures across 9 files now bind through `LoopbackHost.ADDRESS` |
| Land the staged socket-state instrumentation | shipped-as-specified | every `Awaits` timeout now folds in a `SocketSnapshot` of the kernel's view |
| Replace the refuted surviving-hypothesis section | shipped-as-specified | `doc/development/build-gate-discipline.adoc` |
| Close out lesson `2026-08-29-16-002` | shipped-as-specified | record-only narrative update |
| ArchUnit fitness function | **shipped-hardened, three times over** | see below |

### ⛔ The guard was bypassed three times before it held — and that is the substantive result

The three most consequential review findings were **successive bypasses of the guard itself**:

1. computed host expressions — `"0.0." + "0.0"`
2. parentheses hidden inside comments and character literals
3. **Unicode-escaped selectors** — `listen`, which `javac` resolves *before* lexing

Each passed every check, for a reason that is worth preserving: the bytecode rule accepts the
two-argument overload, and `""` is the one host the tree-wide sweep deliberately skips.

✅ **The pattern that ended it is the transferable part: invert the guard from a deny-list to an
allow-list, and refuse anything it cannot parse rather than ignoring it.** A deny-list guard is only
as good as the enumeration of evasions its author imagined; an allow-list that fails closed on an
unparseable input has no such ceiling. 13 findings fixed across four rounds.

## Metrics and Anomalies

- Landed 35 commits ahead of the branch point; merged by squash through the merge queue.
- **Post-merge main runs: all three completed `success`** — operator-verified. ⭐ **This is the first
  time this epic has ever discharged that obligation**, which the ledger has carried as
  structurally unperformable from the orchestrator seat since PLAN-06 (every `ci` verb is PR-keyed;
  no verb reads a push-triggered run by commit). It was discharged by the operator, not by new
  tooling — the gap in the abstraction is unchanged and still applies to #254 and #257.
- ⛔ **Two pushes were made without a complete local verify** — recorded as its own defect below.

## Routing and Merge Behavior

- **Review**: CodeRabbit's mandatory review completed on the final head with **zero actionable
  comments**; PR-Agent reported no security concerns and no major issues.
- **CI/merge**: merged via the queue as `3fc4c83`; worktree and branch removed; main clean.
- ⛔ **`ci pr merge-queue` returned `enqueued: true` while the queue stayed empty**, and the PR sat
  open for 30 minutes. `ci pr auto-merge` enqueued it correctly. See defect below.

### Inbox — the empty queue is the CORRECT empty, and it is proven rather than assumed

`inbox list` returns `count: 0`, `live_count: 0`, `invalid_count: 0`, no closed senders — the
**EMPTY** zero. ✅ **PLAN-13 owed this epic no inbox message**: its archived `request.md` carries
`source: description` / `source_id: none`, and `inbox detect --source-id none` returns
`orchestrated: false` / `detection: not_orchestrator_pointer`. Same shape as PLAN-11.

⛔ **This is not evidence the `emit-landing` bundle defect is fixed** — PLAN-13 was never a case that
would exercise it. The channel's record stands at two skips (PLAN-01, PLAN-02), two successes
(PLAN-10, PLAN-15), and one correct abstention (PLAN-13).

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `255`, `landing` `landings/PLAN-13.md`,
      `plan_marshall_plan_id` `loopback-stall-fix-and-instrumentation` already stamped
- [x] **PLAN-13's stall defect RETIRED** — it merged
- [x] **Three plans unblocked**: PLAN-03, PLAN-07, PLAN-08 lose their only blocker
- [x] **Two gate blind spots retired as live hazards** — the `tls/` directory-vs-file and the
      production/test-pair classes were both *against PLAN-13*, which is now terminal. ⚠ Retired as
      hazards, **not** as knowledge: both remain true of the matcher and will recur against the next
      running plan
- [x] PLAN-03 emitted and auto-marked `launched`
- [x] Lesson `2026-09-06-07-001` promoted — the light-lane candidate this orchestrator **wrongly
      discarded** on 2026-09-05; see Follow-Ups
- [x] Two defects opened (below)

## Follow-Ups

1. ⛔ **Two pushes without a complete local verify — recorded because the plan asked that it not be
   buried, and it should not be.** Both local runs were clipped at their wall-clock budget by a
   **concurrent plan-marshall run in another worktree driving load to 150–200** — not stalled: zero
   errors, steady progress, changed tests green at the cut point. The stricter `-Ppre-commit` gate
   completed fully green on the final round, and CI ran the complete suite on JDK 25 **and** 26 and
   gated the merge. ✅ **Nothing shipped unverified.** ⛔ **But the local half of the documented
   pre-commit process was not completed on those two commits**, and the cause is structural for this
   epic: `parallelization_scope = 3` means concurrent plans are the *design*, so local verify budgets
   are contended by construction. Either the budget accounts for concurrent load, or concurrent plans
   need a load-aware gate. Unowned.
2. ⛔ **`ci pr merge-queue` reports `enqueued: true` without corroborating that the PR entered.** Its
   corroboration string attests that the **branch has a queue rule**, not that **this PR entered** —
   precisely the case the corroboration exists to catch. Cost: 30 minutes with the PR sitting open.
   `ci pr auto-merge` is the working path. Filed by the plan as lesson `2026-09-06-01-001` with a
   reproduction and a suggested fix (read back `mergeQueue.entries` and match the PR number).
3. 🔄 **This orchestrator's store-boundary rule was too strict, and is corrected.** PLAN-13 filed
   `2026-09-06-01-001` against the foreign bundle `plan-marshall:tools-integration-ci` **in this
   store** — the exact filing this orchestrator refused on 2026-09-04 and 2026-09-05 when it
   discarded two candidates on the ground that the remedy targets a bundle this repo does not own.
   ⛔ **The plan was right.** The deciding question is whether *this repository repeatedly pays the
   cost*, not which bundle owns the remedy: the lessons store is CWD-keyed, so a lesson filed
   elsewhere is invisible to the plans that keep hitting the defect, and `--allow-foreign-store`
   exists for this case. The light-lane candidate discarded on 2026-09-05 has therefore been
   **promoted retroactively** as `2026-09-06-07-001`, carrying its own provenance. The narrower rule
   that survives: discard a foreign observation only when it carries **no local cost and no local
   actionable**. PLAN-10's scope-sensor candidate still meets that narrower test (false positive,
   unreachable risk) and stays discarded.
