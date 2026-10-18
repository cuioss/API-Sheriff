# Landing Analysis: PLAN-V02-25 — Decompose `GatewayEdgeRoute` into Edge Sub-Packages

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #487 (https://github.com/cuioss/API-Sheriff/pull/487)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-18 from the inbox message
> `plan-v02-25-edge-route-decomposition-011.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `ef4a3345` (#487, merge queue). The PR changed 96 files (+5,373 / −2,742) with a
619,963-byte diff.

| Area | Verdict | Evidence |
|------|---------|----------|
| Decomposition | shipped | `edge/` now has nine sub-packages: `admission`, `assembly`, `end`, `error`, `relay`, `request`, `reserved`, `terminal` and `upstream`. `GatewayEdgeRoute` is down to 632 lines and is the only class left in the root package. `RouteAssembler` moved to `edge/assembly/`. |
| Guards | shipped | `arch/EdgeSubPackageArchTest` enforces acyclic sub-packages and forbids any dependency on the edge root package. Its population is derived from the `package-info` declarations (CodeRabbit fix). |
| ADR | shipped | ADR-0068 (sub-packages beneath a thin `GatewayEdgeRoute`) was written. Each extracted class now logs under its own class name; message ids and texts are unchanged. |
| Functional review | 3 met, 2 met with residual, 0 not met | Code review: 0 blocking, 7 advisory. One Q-Gate info finding (174a5a) was left open, non-blocking. |

## Metrics and Anomalies

- Tokens: 9,825,509. Wall time: 8h55m (32,107 s).
- **Size.** The PR came in at 96 files and 620 KB. That is under the 100-file cap, but above the ~350 KB split point the
  move-only rule sets. CodeRabbit still reviewed it and found three real defects, so the binding limit held. The 350 KB
  point stays as stated, and the next move-only slices are estimated well below it.
- Integration tests ran only in CI, on both pushed heads. They were not run locally.
- **Lessons.**
  - Project-local: the three CodeRabbit findings (an enum dispatch switch statement not checked for exhaustiveness, a
    hand-listed ArchUnit population, a predicate narrower than its package rule) were merged into lesson `2026-10-18-06-001`.
  - Plan-marshall: seven lessons were routed as `api-sheriff-api-sheriff-0-2-0-026` … `-032`. Four of them recur and were
    sent as findings: assessment coverage, now in a third plan; the invented verb `query`; the invented flag `--number`;
    and `execute_commits_unrecorded`.

## Routing and Merge Behavior

- Merged through the merge queue. Merge-group Maven Build and Integration Tests passed.
- After the merge, on `main` at `ef4a3345`, all of these passed: Maven Build, Integration Tests, Config Validation, Demo Client E2E and Scorecard.
  The plan's post-merge wait hit its deadline while the PR-attached Performance Benchmark (run 39078174255) was still
  running. It has since finished: success.

## Residue and Owners

- **Admit/drain window in `edge/admission/EdgeAdmission`.** This predates the plan; CodeRabbit raised it on #487 and the plan
  declined it as out of scope for a move. Verified on `main`: `tryAdmit` checks `draining` before incrementing
  `inFlight`, so a request admitted just as the drain starts can outlive the drain wait. It is a shutdown-lifecycle
  defect, not a security bypass. It becomes deliverable 4 of `PLAN-V02-46`.
- **Staged specs re-grounded to the new layout:** `PLAN-V02-05`, `-06`, `-07`, `-23`, `-45` and `-46`. Their class paths
  now name the sub-packages. The members that moved are:
  - `renderProblem` → `edge/error/ErrorResponseWriter`
  - `reportRejection` → `edge/error/RejectionReporter`
  - `toListMap` → `edge/request/EdgeRequests`
  - the `SO_LINGER 0` client → `edge/upstream/EgressClients`

  `PLAN-V02-23` is still re-grounded in full before it resumes.
