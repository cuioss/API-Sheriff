# Landing Analysis: PLAN-17 — JWKS Egress Allowlist (AS-10 follow-on defect)

epic: kidicap-gateway-requirements
workstream: WS-01
pr: #345 (merged as c1b09c7014fb32911ec90eb1da54e2e31f3dde2d)

> Landing record for one shipped plan. Lives at `landings/PLAN-17.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

Source: inbox `jwks-egress-allowlist-012.md` (`kind: landing`, `landing-check complete: true`, no missing
keys) plus the operator's report. Corroborated 2026-09-23: `ci pr view 345` reports `state: merged` with
`merge_commit_sha` c1b09c7, which is `origin/main`'s head; `ci checks status` reports 33 checks, overall
`success`. The plan's own diff is 37 files. Plan archived at
`.plan/local/archived-plans/2026-09-23-jwks-egress-allowlist`.

## Deliverable Fidelity vs Spec

3 of 3 reported done. The defect this plan closed came from the downstream (inbox
`kidicap-gateway-downstream-003.md`): `allowed_egress_hosts` duplicated the JWKS URL host, a blank entry
booted and failed later, and a `host:port` entry was silently inert.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1 Derive the allowance from the `jwks.url` host on absent/empty | shipped-as-specified | `TokenValidatorProducer` derives on `isEmpty()` with the port dropped; an explicit list is authoritative and is never merged with the derived host |
| 2 Refuse the silent-failure inputs at boot | shipped-as-specified | blank entries, `host:port` entries and a hostless `jwks.url` now abort startup with `CONFIG_INVALID` |
| 3 Prove the mismatch refusal end to end | shipped-as-specified | new `JwksEgressMismatchIT` with a control run showing the refusal comes from the allowlist rather than from anything else |
| — docs / records | shipped-as-specified | ADR-0011 Amendment A1; threat model GW-05 / BFF-07 record the accepted trade-off: the `jwks.url` host is exempt from the private-address check in every deployment, certificate trust unchanged |

The one claim the 2026-09-22 re-grounding pass left `unverifiable` (whether a blank entry boots and fails
later, unsettleable without executing) is now settled by deliverable 2 shipping the refusal — the plan
answered its own open clause, which is the verify-first contract working as intended.

## Metrics and Anomalies

- Tokens: 5,521,793. Duration: 22,154 s wall (~6 h 9 m) — the cheapest and fastest plan of the five.
- Sonar new-code issues: 0. One loop-back round (1 of 5): two CodeRabbit doc-wording findings fixed.
- Local verification: native IT suite 222 tests green; pre-commit gate 202 tests green.
- An IT-fixture defect surfaced and was fixed in-run: a generated `gateway.yaml` written under the build
  daemon's umask 077 was unreadable by the distroless container's uid 1001 (`ApiSheriff-200`). Host-umask
  dependent, so it can pass on CI and fail locally.
- **Merge-lock release reported the lock "already free"** although this plan had acquired it. No effect on
  the merge, but the release path did not observe its own claim — recorded as an Open Defect.

## Routing and Merge Behavior

- Review: CodeRabbit left 5 comments — 2 real doc-wording errors fixed, 3 declined or acknowledged with
  replies; all 4 threads resolved. `cuioss-review-bot` does not re-review on push, so its review of the
  final commit was triggered explicitly per CLAUDE.md step 6, and reported no issues. Sourcery skipped on
  diff size. **This is the first of the five plans whose merge head was freshly reviewed by a required
  bot** — the explicit re-trigger is what made the difference.
- CI/merge: merge queue (squash); `cleanup_owed=false`.
- **Post-merge verification (the orchestrator's job):**
  - PR-attached **Run Integration Benchmarks** (run 35802988238) — **success** (1269 s). Corroborated.
  - main-branch **Integration Tests** for c1b09c7 (run 35802987168) — **failed once** in
    `WebSocketRelayStageTest.relaysBidirectionalTextFrames` (30 s echo timeout). Outside this plan's
    footprint, green on every PR run, so a runner timing flake is the likely cause; a job re-run was
    requested and its verdict is not yet known. **Not confirmable through the CI abstraction**, which
    reads PR-attached runs only. Carried as a Watch, now covering three merge commits.
  - main-branch **Maven Build** for c1b09c7 — likewise unobserved.
- **Surface expansion:** declared 9 entries, realized 37 files — 28 undeclared, 1 declared-but-untouched;
  `state: expansion_detected`, base `origin/main` c1b09c7, not stale. Fifth landing in a row expanding,
  here ~4x. The plan's own footprint capture recorded 45 paths because it diffed against a stale local
  `main`, pulling in 8 unrelated `.plan/orchestrator/**` files from #344; the 37-file figure above is the
  merged diff and is the one this record uses.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-17 --status shipped`
- [x] row `pr` stamped `#345` — `queue --set-row`
- [x] row `landing` stamped `landings/PLAN-17.md` — `queue --set-row`
- [x] row `plan_marshall_plan_id` already stamped `jwks-egress-allowlist` at start
- [x] epic.md reconciled: the downstream `allowed_egress_hosts` defect closed and its Open Defect retired;
      WS-01 has no further staged plan
- [x] Watch updated: unverified main-branch runs now span 3e3addc, 69b322b and c1b09c7 (the last with a
      known flake and a pending re-run)
- [x] Open Defect opened for the merge-lock release reporting "already free"
- [x] 11 candidate lessons dispositioned (2 promoted new, 2 folded as recurrences, 7 recorded here)
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated — `orchestrator compact`

## Follow-Ups

- The re-run of the flaked main-branch Integration Tests job on c1b09c7 needs a verdict; a second failure
  in `WebSocketRelayStageTest` would make it a real defect rather than a flake, and that test belongs to
  the #320 (PLAN-15) surface — the second timing-race finding in that class after PLAN-16's metering race.
- Nothing in this epic now depends on PLAN-17. The queue is PLAN-18, PLAN-19, PLAN-20, PLAN-21.
