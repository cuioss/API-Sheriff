# Landing Analysis: PLAN-23 — Unit-Lane Vacuity Audit

epic: deployment-configurability
workstream: WS-07
pr: #336 (https://github.com/cuioss/API-Sheriff/pull/336), merge commit `cc10ce2a2c7840162bbc087242d42cbbc3cd14c3`

> Landing record for one shipped plan. Lives at `landings/PLAN-23.md`. Written by the
> `analyze` verb after verifying claims against ground truth (actual code, artifacts,
> PR state) — a pasted claim is a lead, never a fact. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the analysis and
> reconciliation contract.

## Deliverable Fidelity vs Spec

Corroborated against `git show --stat cc10ce2`, the PR #336 body/description (via the
CI abstraction), and the epic's own `unit-lane-vacuity-audit-002.md` inbox landing
message (`landing-facts/1`, `complete: true`).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Audit the unit lane exhaustively and record the per-site report | shipped-as-specified | `doc/development/test-corpus-integrity.adoc` +1008/-? lines; 44 flagged methods classified into 14 `strengthen` / 30 `keep`, each with `file:line`, one shape, one verdict; "not read" pool row is `0` files (exhaustive, not ranked) |
| 2. Fix the reported in-lane sites | shipped-as-specified | 11 test files touched carrying the fourteen strengthened assertions (`UpstreamAssetSourceTest`, `RetryingJwksLoaderTest`, `SealedSessionCookieCodecTest`, `ConfigModelContractTest`, `GatewayEdgePipelineTest`, `GatewayEdgeRouteBffWiringTest`, `PassthroughHostGuardStageTest`, `VerbGateStageTest`, `BffRuntimeProducerTest`, `AwaitsTest`, `MtlsServerCustomizerTest`) |
| 3. Land the `Awaits.until`-then-re-assert fitness function | shipped-as-specified | new `arch/AwaitsReassertionArchTest.java` (650 lines) with matched positive/negative control specimens `arch/specimen/AwaitsWithReassertionSpecimen.java` / `AwaitsWithoutReassertionSpecimen.java`; non-vacuity guard per spec |
| 4. Prove every strengthened test by reversion | shipped-as-specified | PR body: "every one of the fourteen strengthenings was inverted and observed RED before being reverted; the per-site attribution records observed reds, not expected ones" |
| 5. End the standing gate rewrite by fixing the recipe/formatter disagreement | shipped-modified | `doc/development/build-gate-discipline.adoc` (+79/-?) records the predicted two-file (`SniFrontListener` + `LoopbackEphemeralBindArchTest`) standing rewrite did **not reproduce** at `main` `3abc370` — settled by measurement as refuted rather than mechanically "fixed"; neither file appears in the PR diff. What the same gate run rewrote instead (four test sources this branch had just authored) was kept, not reverted, per the existing rule |
| 6. Settle the `WebSocketRelayStageTest` flakiness claim | shipped-as-specified | PR body: "no artifact in the tree, no CI signal across the 30 most recent Maven Build runs" — routed out of the known-flaky reading; corresponding spec HYPOTHESIS claim (index 2) stamped `contradicted \| rescoped: yes` via `corpus set-verdict` |

⚠ **Deliverable 5's Expected-Surface residual is a measured over-declaration, not a gap.** The spec's `## Expected Surface` named `api-sheriff/src/main/java/.../tls/SniFrontListener.java` as "deliverable 6's half of the gate churn that lives in src/main" — that file was never touched, because the churn it anticipated was refuted by measurement rather than corrected by edit. Recorded per the epic's own under/over-declaration tracking; no correction needed since the file was declared, not silently touched-and-undeclared.

⚠ **One production file was touched despite the PR body's "no production behaviour changes" claim.** `git diff --stat` shows `UpstreamAssetSource.java` (+/-81 lines) in `api-sheriff/src/main/java/.../asset/`. This reconciles with the inbox landing message's Residue note: a Sonar `java:S3398` finding (`93017f`) on `UpstreamAssetSource.defaultSslContext()` required moving the method's call site (not its behaviour) — declined in-scope as a follow-up cleanup item because this branch did not author the method. Not a spec deviation: no deliverable claimed production-behaviour immunity, only the PR body's own summary line, and the substance (call-site relocation, no behaviour change) is consistent with that line's intent.

## Metrics and Anomalies

- Tokens: 7,737,623 total (dispatched-mixed across phases); 6-finalize alone carried 3,050,422 (~39%), 5-execute 3,089,258 (~40%) — the two heaviest phases by a wide margin over 1–4 combined (~1.6M)
- Duration: 13h20m wall (5h46m worked / 4h15m idle, remainder in tool time); loop-back iteration 3 of this plan
- Anomalies: `ci-verify` finalize step ended on a stale `loop_back` record (3 `ci_timeout` findings accepted, replay scheduled) that was never overwritten with a settled outcome — corroborated as a record-keeping artifact, not an actual CI failure: `ci pr view --pr-number 336` confirms `state: merged`, `merge_commit_sha: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3`, matching `origin/main` HEAD exactly

## Routing and Merge Behavior

- Review: `automatic-review` found 1 comment (2 reviewed, 1 empty; unified triage pending per the step record). `sonar-roundtrip`: 1 new-code issue confirmed, 1 filed for triage (the `java:S3398` follow-up above)
- CI/merge: merge-queue path (`branch-cleanup: rebase deferred to queue, queue-merged, corroborated`); merged to `main` at `cc10ce2`, confirmed by both `ci pr view` and `git log origin/main`. No rebase conflicts or re-verify signals recorded — solo `running` plan, no concurrent-plan collision to check

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-23 --status shipped`
- [x] row `pr` stamped → `336`
- [x] row `landing` stamped → `landings/PLAN-23.md`
- [x] row `plan_marshall_plan_id` stamped → `unit-lane-vacuity-audit`
- [x] re-grounding verdict persisted on the spec's HYPOTHESIS claim (index 2): `contradicted | rescoped: yes` via `corpus set-verdict`
- [x] epic.md queue reconciled from status.json (START-HERE + Ordered Queue regenerated)
- [x] inbox candidate-lesson `unit-lane-vacuity-audit-001.md` dispositioned (see Follow-Ups)
- [x] inbox messages archived: `unit-lane-vacuity-audit-001.md`, `unit-lane-vacuity-audit-002.md`
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated

## Follow-Ups

- **Sonar `java:S3398` on `UpstreamAssetSource.defaultSslContext()`** — carried by the plan itself as an explicit follow-up cleanup item (declined in-scope: this branch only moved the call site, did not author the method). Recorded as an Open Defect in `epic.md` for operator disposition before `close`.
- **Candidate-lesson `unit-lane-vacuity-audit-001.md`** — "suppress Sonar test-shape rules (`java:S3577`, `java:S2699`) via in-code `@SuppressWarnings` on ArchTest positive/negative-control specimen fixtures" (recurred 3× in this PR). The message named its own sink (`architecture enrich best-practice --module cuioss_API-Sheriff`), which is what it was dispositioned through — see Step 5b disposition below (**Promote**, filed to the project's architecture best-practices, not the global lessons corpus, since the payload names that specific sink and `best-practice` is not a `manage-lessons` category).
- **The corpus is now empty of live/staged work** — PLAN-23 was the last live spec (per the pre-landing resume anchor). All 27 rows are terminal (18 shipped, 2 shipped by this landing... — actually 19 shipped total after this landing, 1 landed, 1 superseded, 6 parked/superseded). The epic is closeable; the open Sonar-S3398 defect above is the one item worth a decision (fold into a tiny follow-up plan, or resolve elsewhere) before `close`.
