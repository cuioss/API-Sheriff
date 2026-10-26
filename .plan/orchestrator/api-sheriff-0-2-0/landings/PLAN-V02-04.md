# Landing Analysis: PLAN-V02-04 — ADR Corpus Content Audit and Consolidation

epic: api-sheriff-0-2-0
workstream: WS-02
pr: #521 (https://github.com/cuioss/API-Sheriff/pull/521)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-26 from the inbox messages
> `plan-v02-04-adr-corpus-cleanup-002` (candidate lesson) and `-003` (the landing); the earlier `-001`
> (deferral) was analyzed on 2026-10-25 and staged as `PLAN-V02-48`. Checked against `origin/main` at
> `e087e5ce`, the merged diff `6b8d88f4..f5826465`, and the CI runs. The plan ran once under its own plan id.

## Deliverable Fidelity vs Spec

#521 changed 56 files (+4,269 / −5,458): under the 100-file review-bot cap. Three are production files, one is the
new guard test, one is a test addition; the rest are documentation, `doc/adr/` (22 files) and the compose and
integration-test files that name a relocated record.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — classify every record, then delete, relocate or keep | shipped for the whole corpus, acted on for six groups | The rubric is `doc/development/adr-writing.adoc` (385 lines, on `main`). 71 records were judged. Six left the corpus: `0002`, `0012`, `0021`, `0031`, `0065` relocated to developer docs and `0056` merged into `0054` (git: six deletions in `doc/adr/`). 65 remain. The verdicts for the nine groups left alone are in the deferral message and now in `PLAN-V02-48`. |
| D2 — merge what is said twice | shipped for four clusters, rest deferred | `0056` into `0054`. The other clusters named in the spec (config neutrality, anchors and assets, TLS edge) are in the deferred groups. |
| D3 — rewrite every survivor to one skeleton and one writing rule | **partly shipped** | 12 records were rewritten (groups C1 `0060`/`0066`, C2 `0018`/`0019`, C3 `0023`/`0024`/`0026`/`0029`, C6 `0040`/`0041`/`0045`, plus the ceiling cuts of `0037`/`0058`). The other 52 records (plus `0027`, owned by `PLAN-V02-09`) are not rewritten. The term list is recorded in `adr-writing.adoc`. Owner: `PLAN-V02-48`. |
| D4 — bring every survivor under the ceiling and make it true | shipped, residuals | `AdrRecordLengthContractTest` exists with `MAX_BODY_LINES = 500` and a fixture test that the detector flags 501 and not 500; the plan reports it red on the five oversized records before green. The largest file on `main` is `0058` at 523 lines including its meta block, so the body ceiling holds as reported (`0037` at 499). 13 statuses flipped; 27 of 65 records still read `Proposed` (was 45 of 71). The `0066` lead is closed: the record now names four triggers including the request-body idle timeout. The test gap for two of the triggers stays open by operator ruling (Watch). |

**Corpus size, measured by me with `git show` on every file:** 71 records and 17,899 lines at `6b8d88f4`; 65 records and
15,289 lines at `f5826465`. That is −2,610 lines (−14.6%); the mean per file went 252 → 235. The spec's reference
point is the early records at about 136. The first pass therefore did not reach the target mean; it covered 12 of 65
records. `PLAN-V02-48` applies the same rule to the rest, and its D11 report is where the result is judged.

One production behaviour change, operator-approved as a scope change in a documentation plan: `WebSocketRelayStage.onUpstreamFailure`
now ends its pre-upgrade 502/504 answer through `ResponseEnds.endResponse(ctx, response::end)` instead of a bare
`response.end()` (diff confirmed: 3 production files, +12 / −8, the rest Javadoc). The regression test is
`WebSocketRelayStageTest.PreUpgradeFailureEnd`; it covers the failed dial only.

## Metrics and Anomalies

- Tokens: 85,956,760. Wall time: 150,264 s (41.7 h) for a plan whose spec expected one PR. Model claude-opus-5-5,
  effort low. Plugin and executor 0.1.1986.
- Functional review: 8 deliverables met, 4 met with residuals, 0 not met. Code review: 0 blocking, 6 advisory.
- The branch was rebased onto `main` at `6b8d88f4` during finalize. #521 had been `DIRTY` against `main`; that is
  resolved (the Watch is retired).
- A merge-group run (40292535629) failed `EdgeAdmissionTest`'s vacuity control. Not part of #521; see below.
- No security note was recorded by the plan.

## Routing and Merge Behavior

- Merged through the merge queue as `f5826465` at 2026-10-26T09:22:49Z.
- Post-merge on `main` at `f5826465`, all completed with success: Maven Build (40299089880, including
  `deploy-snapshot`), Integration Tests (40299089882), Config Validation, Demo Client E2E and Scorecard. The
  PR-attached Performance Benchmark run (40299092613, head `90dff9d2`) passed.
- **Side fix #522** (`e087e5ce`, 2026-10-26T11:17:43Z, merged by the same session, test-only, 2 files): it fixes two
  flaky tests, `EdgeAdmissionTest` (both race threads released from a `CountDownLatch`, so the vacuity control failed
  on a loaded runner; now a spin barrier and an adaptive round count, mutation-checked) and
  `GatewayCookiesTest.shouldCompareConfiguredNameCaseSensitively` (the "different case" variant could equal the
  configured name; now it inverts every letter). Post-merge on `e087e5ce`: Maven Build, Config Validation, Demo Client
  E2E, Scorecard and Integration Tests (40313997209) green. Its PR benchmark
  (40313999276) passed.

## Residue and Owners

Verified on `main` at `e087e5ce`:

- **F6, stale `0060` sentence** (`doc/adr/0060-…adoc:331-334`): still says no test covers the `dispatchGrpc` hand-off
  and the five-minute value, although #520 added `TerminalDispatchGrpcStreamIdleTest`. Confirmed. Owner:
  `PLAN-V02-48` D10 (the threat model's wording is the target).
- **F1, `doc/architecture.adoc:587-588`**: still says the WebSocket upgrade is unaffected by the end rule. Confirmed.
  Owner: `PLAN-V02-48` D12.
- **F2, ADR-0066 and the `ResponseEnds` Javadoc** name the failed-dial answer; the relayed upgrade refusal also passes
  the seam. Owner: `PLAN-V02-48` D12 (the Javadoc is already corrected for the failed dial; the refusal is the gap).
- **F4, `benchmarks/README.adoc:514-516`** says Methodology lists every parity condition. Plan-reported; I read the
  sentence and did not re-count the Methodology list. Owner: `PLAN-V02-48` D12 (verify at outline).
- **F5, `doc/development/bff-session.adoc` residual-verdict sentence**: plan-reported; the text near line 302 credits
  verdicts to entries 3, 5 and 7 and I did not re-derive entry 4. Owner: `PLAN-V02-48` D12 (verify at outline).
- **F3, `PreUpgradeFailureEnd`** covers the failed dial only, not the relayed upgrade refusal. Owner:
  `PLAN-V02-49` D3 (test work in `edge/`).
- **Wording residue** ("refuse", "routing", "warning", "outbound") and the dropped stale snapshot-repository paragraph
  of `0041`: `PLAN-V02-48` D10 already holds the wording; the `0041` paragraph is intended removal, no owner needed.
- **ADR immutability line in the release skill:** unchanged, operator's call (Watch stays).
- **Test gap, `0066` triggers:** operator ruled report only (Watch stays).
- **Flaky tests of #522:** both are fixed at the root. The admit/drain vacuity failure is the cause named in the Watch
  for `EdgeAdmissionTest`; it is retired. The `GatewayCookiesTest` flake belongs to `PLAN-V02-23` (#515) test code and
  is retired the same way.
- **Candidate lesson `-002`** (a latch release is not a simultaneous start): project-local, promoted as a local lesson.
- **Plan-marshall tooling lessons:** none in this landing. The 41.7 h wall time for a 12-deliverable documentation plan
  is recorded here only.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-04 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-04 --field pr --value 521`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-04 --field landing --value landings/PLAN-V02-04.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] `PLAN-V02-48` spec brought to the merged state, D10 extended, D12 added
- [x] `PLAN-V02-49` D3 extended with the F3 test
- [x] epic.md narrative reconciled (Watches retired and added)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- `PLAN-V02-48` (`adr-corpus-cleanup-part-2`), already staged; now unblocked by the merge of #521.
- `PLAN-V02-49` D3, extended with the relayed-upgrade-refusal test.
