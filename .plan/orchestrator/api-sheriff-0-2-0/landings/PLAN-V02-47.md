# Landing Analysis: PLAN-V02-47 — Publish the Gateway Image for linux/amd64 and linux/arm64

epic: api-sheriff-0-2-0
workstream: WS-05
pr: #504 (https://github.com/cuioss/API-Sheriff/pull/504)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-21 from the inbox message
> `plan-v02-47-multi-arch-image-004.md` (landing). Each material claim was verified against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `8e218103` (#504, merge queue): 11 files changed (+2,056 / −441) in 13 commits.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — one build-and-test leg per architecture | met | `publish-image` runs as a matrix over `${{ matrix.runner }}`. Each leg holds `contents: read` and `packages: write`, and no `id-token`. |
| D2 — join into one index, then tag | met | The `join` job is the only job with `id-token: write`. It assembles the index from the two tested digests and verifies it. Release tags are set only after both legs pass. |
| D3 — sign what a consumer pulls | met | The index and both platform digests are signed. `doc/user/container-image.adoc` shows how to verify all three signatures, and its run recipes start from the verified digest. |
| D4 — CPU baseline | met | ADR-0070 records the observed native targets: x86-64-v3 on amd64 and armv8.1-a on arm64. It declines `-march=compatibility`. |
| D5 — dry run | met | Two `workflow_dispatch` dry runs passed: `39603044532` on `bc3a6c38` and `39627645198` on `f59d87c0`. In both, both legs, the join and the cleanup passed, and `release` was skipped. Dry-run tags include the run attempt. |
| D6 — docs and decision record | met | ADR-0070 was added. `README.adoc`, the user docs, the compose sample `.env`, `release-process.adoc` and the `release` skill were updated. Every statement says that tags up to 0.2.4 are amd64-only. |

## Metrics and Anomalies

- Tokens: 7,704,153. Wall time: 38,117 s (10.6 h), in parallel with `PLAN-V02-42`, `-43` and `-45`. Model: claude-sonnet-5-5, effort medium.
- **Review gate.** The functional review and the code review were closed by operator override. The later docs-only commits skipped the internal re-runs.
  - CodeRabbit reviewed the final head `9884abf7`. Its coverage marker says `reviewed`, with merge risk "Minimal".
  - The landing message says "allowance ran out on the last head". That means no reviews were left after this one; the head was still reviewed.
  - All 3 review threads are resolved. Sourcery declined on quota (optional).
- **ADR ordinal collision.** This plan took ADR-0070 while `PLAN-V02-43`'s open PR #505 also adds a 0070. `AdrOrdinalUniquenessContractTest` would fail #505's merge group. `PLAN-V02-43` was told to renumber to 0071 through its mailbox.

## Routing and Merge Behavior

- Merged through the merge queue.
- On `main` at `8e218103`, all of these passed: Maven Build, Integration Tests, Demo Client E2E and Scorecard. The PR-attached Run Integration Benchmarks job also passed (20.5 min).

## Residue and Owners

- **No release has been cut from the new lane.** The first release cut after `8e218103` publishes the first two-platform index. That release is the first real run of the join and signing steps outside a dry run. Check its "exactly one of each" verification with the `release` skill, and confirm that the arm64 leg passed.
- **The epic Watch "THE PUBLISHED GATEWAY IMAGE IS AMD64-ONLY" is closed** by this landing.
- **Lessons (project, local store):**
  - `2026-10-21-19-001`: qualify docs for a capability that starts with the next release.
  - `2026-10-21-19-002`: scope re-runnable CI resources to `run_attempt`.
  - `2026-10-21-19-003`: a verify-then-run recipe must run the reference it verified.
