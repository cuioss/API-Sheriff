# Landing Analysis: PLAN-22 — WebSocket Relay Early-Frame Race

epic: kidicap-gateway-requirements
workstream: WS-02
pr: #350 (merged as 2f4254d30212f6ef71f8cc12d4a39138eca9ed00)

> Landing record for one shipped plan. Lives at `landings/PLAN-22.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `websocket-early-frame-race-001.md` (`kind: landing`, `landing-check complete: true`, no
missing keys) plus the operator's report. Corroborated 2026-09-24: `ci pr view 350` reports
`state: merged` with `merge_commit_sha` 2f4254d3, now `origin/main`'s head; `ci checks status` reports 33
checks, overall `success`, including the PR-attached post-merge benchmark (run 35965521999). This is the
**first plan of this epic that the orchestrator itself staged** — from PLAN-17's finding — rather than
one decomposed from the AS catalogue.

## Deliverable Fidelity vs Spec

6 of 6 done, and the deliverable order the spec insisted on (reproduce first, then fix) was honoured.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 Deterministic reproduction, landed BEFORE the fix | shipped-as-specified | reproduction tests fail **5 of 5** runs against unfixed code and pass after the fix |
| 2 Close the window | shipped-as-specified | each relay leg is `pause()`d as soon as it is acquired and `resume()`d only once its handlers are installed, and wiring runs on the client connection's own event loop — candidates (a) + (b) from the spec, as expected |
| 3 Prove or refute the production consequence | shipped-as-specified, verdict **REFUTED as far as measured** | 50 upgrades against the UNFIXED native gateway kept their first frame: 0/50, which bounds the per-upgrade loss rate below ~6% at 95% confidence. The likely masking cause is Quarkus's context-preserving virtual-thread executor already handing `relay()` the request's own event loop |
| 4 Correct the threading record | shipped-as-specified | `RelaySession` Javadoc now states which context each callback runs on |
| 5 Correct the investigation record | shipped-as-specified | dated amendments in `build-gate-discipline.adoc` and `test-corpus-integrity.adoc`; the macOS finding is preserved, as the spec required |
| 6 Extend the timeout diagnostics | shipped-as-specified | relay tests now report handler-installation time against first-frame arrival |

**The refutation is the most valuable result here, and it is correctly scoped.** The race was real in the
code (5/5 reproduction) but the production loss the spec worried about was not observed, and the plan
states the *bound* rather than claiming absence. The structural fix does not depend on the executor
behaviour that masked it — so the fix stands on its own, and the alarming half of the spec's premise is
retired with evidence rather than by assertion. My own staging prose ("a chat client's first message is
silently lost") was the stronger claim; the measurement narrowed it, which is the verify-first contract
working in the direction that costs the orchestrator its premise.

Three review-driven fixes were folded in with operator approval, each with a test that fails without it —
and **two were pre-existing defects on `main`, not this plan's doing**:

- Sonar `java:S107` (too many constructor parameters) → `RelaySession` became an inner class.
- **WebSocket pongs were relayed to the other leg as BINARY data frames** — now forwarded as pongs.
- **CWE-400: ping/pong forwarding bypassed the write-queue backpressure check** — now backpressured.

## Metrics and Anomalies

- Tokens: 5,784,350. Duration: 61,804 s wall (~17 h 10 m), mostly CI waiting.
- 3 review rounds of 5 allowed. Sonar new-code issues: 0. Final commit: `verify -Ppre-commit` green
  (3,682 + 91 + 202 tests), 224 native ITs green.
- Surface: declared 5 entries, realized 7 files — 4 undeclared, 2 declared-but-untouched. The narrow
  declaration I wrote when staging (one named IT rather than the IT directory) held up: this is the second
  small delta in a row after five large ones.
- `adr-propose` and `lessons-capture` do not appear in this plan's step list, so its plan-marshall
  findings arrived inside the landing residue rather than as candidate-lesson messages. The orchestrator
  filed them instead — see Reconciliation.

## Routing and Merge Behavior

- **Merged over two operator-accepted gaps**, both recorded:
  - **(a) PR-Agent reviewed only the opening commit.** Its on-demand `/review` re-trigger fails
    **org-side**: the GitHub App token cannot reach `cuioss/pr-agent-settings` ("repository does not exist
    or is not accessible to the parent installation"), observed again on another PR at 19:22 on
    2026-09-23. ⚠ This is an **org-level fix affecting every PR in the organisation**, not a per-plan
    nuisance — and it is the mechanism behind the "merge head not freshly reviewed" note on five of the
    seven plans in this epic.
  - **(b) The pre-merge ADR duplicate-number gate was waived**, because `main` already carried two
    ADR-0053 files and #350 touches no ADR. The gate ALSO returned no verdict at all: `manage-adr scan`
    omits `duplicate_count` / `duplicate_numbers` / `duplicate_paths` from its success payload, so that
    gate cannot pass on its own evidence — filed as a plan-marshall lesson.
- CI/merge: merge queue; `cleanup_owed=false`; CI green on every push.
- **Post-merge verification (the orchestrator's job):** the PR-attached benchmark is green (above). The
  main-branch **Maven Build** for 2f4254d3 is **unobserved**, as for every earlier merge commit — the CI
  abstraction reads PR-attached runs only.
- The CI occurrence this plan was staged from (run 35802987168) could not be confirmed through the CI
  tool, and the plan correctly cites it as *reported by the spec* rather than as measured. That is the
  same read-side limit recorded in the standing Watch.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-22 --status shipped`
- [x] row `pr` stamped `#350` — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-22.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` stamped `websocket-early-frame-race` — `queue --set-row`
- [x] epic.md reconciled: the relay race closed; the production-consequence premise retired as refuted
- [x] ADR-0053 duplicate folded into **PLAN-20** as an explicit deliverable (it already declares
      `doc/adr/`, so the fold adds no file surface)
- [x] Open Defect opened for the two relay follow-ups (redundant `pongHandler`, near-duplicate test
      helpers) and for the org-side PR-Agent failure
- [x] 5 plan-marshall findings filed from the landing residue: 2 new lessons, 3 folded as recurrences
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated — `orchestrator compact`

## Follow-Ups

- `source.pongHandler(pong -> resetIdle())` in `WebSocketRelayStage.wire()` is now redundant, since
  `relayFrame` receives pongs and resets the idle timer; and the `queueFullDialer` /
  `frameThreadRecordingDialer` test helpers are near-duplicates that one decorating dialer would cover.
  Both are small cleanups on a surface no staged plan touches — recorded as an Open Defect rather than
  staged, to be folded into whichever plan next enters the relay.
- The org-side PR-Agent installation fix is outside this repository and outside this epic; it is recorded
  because it explains a recurring merge-gap pattern here.
