envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:53:12Z

# Candidate lesson (tooling bug): self-review surfacer and footprint capture diff against stale local `main`

Source: orchestrator observation 2 (this run). Likely a plan-marshall bundle defect.

## What happened

The pre-submission-self-review surfacer and `compute-footprint` / `capture-footprint` diff against the local `main` ref
rather than `origin/main`. Local `main` was behind upstream (#344 had landed). After `finalize-step-sync-baseline` rebased
the branch onto `origin/main`, the diff base no longer matched the branch's real base:

- 41 of 66 self-review candidates were upstream #344 files, not plan changes (noise that dilutes the review).
- 8 footprint paths were upstream `.plan/orchestrator` files; `realized_footprint` was persisted with 45 paths including those 8,
  corrupting the recorded plan footprint (and anything that reconciles declared vs realized footprint).

## Corrective rule / suggested fix

Diff-base resolution for footprint and self-review must use the same base the branch was rebased onto (`origin/{base}` or the
recorded merge-base after sync-baseline), never a local branch ref that nothing keeps fresh. Alternatively fast-forward
local `main` as part of sync-baseline. A test should pin: after sync-baseline onto a newer origin, the footprint contains
only plan-authored paths.

## Components

plan-marshall:manage-references (compute-footprint / capture-footprint); plan-marshall:phase-6-finalize (pre-submission self-review, sync-baseline).
