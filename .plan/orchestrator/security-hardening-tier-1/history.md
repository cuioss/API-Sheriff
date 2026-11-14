# History: Security hardening - tier 1

slug: security-hardening-tier-1
closed: 2026-11-14

> Frozen final state of the epic, written by the `close` verb. `epic.md`, the plan specs, the
> landing reports, the queue rows and the logs stay beside this file as the audit record.

## Vision as Pursued

Fix the six tier-1 findings of the 2026-11-07 ground-truth check of the operator's private security
assessment (M7, M2, M4, M5, M6, C3), each with a regression test that fails before the fix, and
correct the tracked documentation that overclaimed for those surfaces. On 2026-11-10 the operator
widened the epic with the cheap tier-2 fixes (WS-04). Both goals were met: every planned finding
shipped with failing-first tests, and the documentation overclaims were corrected.

## Final State (rendered by `resume-summary` at close)

START HERE:

**Resume anchor**: PLAN-SEC-01..07 all shipped; the operator-pasted follow-up session shipped as PR #620 (a6c36b37), post-merge runs verified green. S6-A (per-source connection bound) deferred by operator, private notes only. No open plan rows, no pending watches on main runs. Next: on the operator's word, land the ledger and run close (/plan-marshall:plan-orchestrator close slug=security-hardening-tier-1).
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 13 archived
**Queue** (staged, in order):
- (empty)
- PLAN-SEC-01 (WS-01) — plan=plan-sec-01-edge-transport-hardening — PR 582 — landing=landings/PLAN-SEC-01.md — status: shipped
- PLAN-SEC-03 (WS-03) — plan=plan-sec-03-pending-authorization-store-fairness — PR 581 — landing=landings/PLAN-SEC-03.md — status: shipped
- PLAN-SEC-02 (WS-02) — plan=sec-02-upstream-attribution-forward-headers — PR 590 — landing=landings/PLAN-SEC-02.md — status: shipped
- PLAN-SEC-04 (WS-01) — plan=plan-sec-04-edge-transport-bounds-follow-up — PR 589 — landing=landings/PLAN-SEC-04.md — status: shipped
- PLAN-SEC-05 (WS-02) — plan=plan-sec-05-grpc-request-stream-attribution — PR 600 — landing=landings/PLAN-SEC-05.md — status: shipped
- PLAN-SEC-06 (WS-04) — plan=plan-sec-06-tier-2-quick-wins — PR 607 — landing=landings/PLAN-SEC-06.md — status: shipped
- PLAN-SEC-07 (WS-04) — plan=sec-07-tier-2-follow-ups — PR 619 — landing=landings/PLAN-SEC-07.md — status: shipped

Ordered Queue:

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| — | (empty) | — | — | — | — |

## Queue Outcome

**Shipped (7):**

| Plan | Workstream | Lane | PR | Merge | Landing |
|------|------------|------|----|-------|---------|
| PLAN-SEC-01 — edge transport hardening (M7, M2, C3) | WS-01 | plan-marshall | #582 | c85817d9 | `landings/PLAN-SEC-01.md` |
| PLAN-SEC-03 — pending-authorization store fairness (M4) | WS-03 | plan-marshall | #581 | 3eef748b | `landings/PLAN-SEC-03.md` |
| PLAN-SEC-02 — upstream attribution and forward headers (M5, M6) | WS-02 | plan-marshall | #590 | 81b46785 | `landings/PLAN-SEC-02.md` |
| PLAN-SEC-04 — edge transport bounds follow-up | WS-01 | plan-marshall | #589 | 47e73a15 | `landings/PLAN-SEC-04.md` |
| PLAN-SEC-05 — gRPC request-stream attribution (M6 completion) | WS-02 | plan-marshall | #600 | 50b7e6d3 | `landings/PLAN-SEC-05.md` |
| PLAN-SEC-06 — tier-2 quick wins | WS-04 | plan-marshall | #607 | 898e0100 | `landings/PLAN-SEC-06.md` |
| PLAN-SEC-07 — tier-2 follow-ups | WS-04 | runbook | #619 | e1b8a2cb | `landings/PLAN-SEC-07.md` |

**Closed unshipped:** none. **Parked:** none.

Outside the ledger, by operator decision: the remaining follow-ups (admission WARN threshold,
management doc sites, upstream pool wait bound, late-timeout answer, cheap PLAN-SEC-05 minor points)
shipped in an operator-pasted Claude Code session as PR #620 (a6c36b37); the post-merge `main` runs
and benchmark were verified green by the orchestrator. Side PRs on the operator's word: #618
(e6fda739, formatter import-order rewrite) and #621 (40b202fa, `.gitignore`).

## Decision Record

The full dated record is the Decisions section of `epic.md` and the orchestrator decision log under
`logs/`. The decisions that shaped the outcome:

- 2026-11-07/08 — Six tier-1 findings grouped into three plans by touched surface; the two
  `TerminalDispatch` findings share PLAN-SEC-01 instead of serialising on one class.
- 2026-11-09 — PLAN-SEC-02 held back at the overlap prompt until PLAN-SEC-03 merged.
- 2026-11-09/10 — Follow-up plans PLAN-SEC-04 and PLAN-SEC-05 staged from landing analyses with
  mechanism-level specs; details kept in the operator's git-ignored local notes.
- 2026-11-10 — Epic widened with WS-04 (tier-2 quick wins, PLAN-SEC-06).
- 2026-11-13 — PLAN-SEC-07 run on the runbook lane; its JWKS items left to the kidicap epic's
  PLAN-31 (cross-epic collision recorded here only, that ledger is hands-off).
- 2026-11-14 — PLAN-SEC-07's per-source connection share stopped by its verify-first clause (no
  supported per-connection hook in Quarkus 3.40.1); the operator took no route for it for now.
  Remaining follow-ups handed to one operator-pasted session outside the ledger (PR #620).
- 2026-11-14 — Epic closed on the operator's word.

## Carried-Forward Leads

Defects (tooling, no project action):
- PLAN-SEC-05 landing: `sonar_open_new_issues` unrecorded (post-merge Sonar read undecidable);
  routed as lesson `-005`.
- PLAN-SEC-06 landing: `effort` and the review-body pair unrecorded (`review-gate` parse failure);
  routed as lesson `-006`.
- PLAN-SEC-07 landing: `total_tokens`, `model`, `effort` unmeasured by the runbook lane.

Watches:
- Ground-truth check: every plan's findings held with failing-first tests. Retired at close.
- Formatter style mismatch: settled by #618; open only until a landing reports churn again.
- Gate blind spot: a mid-run scope expansion into another in-flight plan's package is invisible to
  the disjointness gate — raise such expansions to the orchestrator.
- PLAN-SEC-03 follow-ups not staged: per-source cap value has no recorded rationale; no ADR for the
  in-process peer-attribution mechanism or the per-source quota policy.

Open security items: the per-source connection bound on the Quarkus-terminated listeners and every
other unfixed item stay in the operator's private assessment, outside tracked text.

## Closing Rationale

All seven plans shipped and their landings were reconciled; no row is staged, running or parked; the
inbox is drained; the post-merge runs of the last merges are green. The one open structural item has
no supported fix route today and the operator chose to defer it outside this epic.
