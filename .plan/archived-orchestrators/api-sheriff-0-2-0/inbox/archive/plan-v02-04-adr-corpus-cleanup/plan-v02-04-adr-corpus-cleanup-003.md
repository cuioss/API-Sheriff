envelope_version=1
sender_type=plan
sender_id=plan-v02-04-adr-corpus-cleanup
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-26T09:58:39Z

## What landed

plan-v02-04-adr-corpus-cleanup shipped as #521 (merged, squash via merge queue, f5826465).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-04-adr-corpus-cleanup
epic=api-sheriff-0-2-0
pr=#521
merge_state=merged
cleanup_owed=false
deliverables_total=12
deliverables_done=12
total_tokens=85956760
total_wall_seconds=150264
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.1986
executor_version=0.1.1986
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=8
step.functional-review.deliverables_met_with_residuals=4
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=6
step.code-review.rules_cited=3
```

## Residue

- ADR corpus cleanup: 71 records judged against the new rubric (`doc/development/adr-writing.adoc`), 6 removed from the corpus (5 relocated to developer documentation, 0056 merged into 0054), 65 remain. No record body is over the 500-line ceiling; the largest is 0037 at 499. The ceiling is guarded by the new `AdrRecordLengthContractTest`, shown red on the five oversized records before it went green. 13 statuses flipped from Proposed to Accepted after checking each against the shipped code.
- Operator-approved production fix `90dff9d2` (scope change in a documentation plan): `WebSocketRelayStage.onUpstreamFailure` now ends its pre-upgrade 502/504 answer through `ResponseEnds.endResponse` instead of a bare `response.end()`. The regression test is `WebSocketRelayStageTest.PreUpgradeFailureEnd`; it timed out against the old code and passes with the fix. It is the PR's only production behaviour change.
- The branch was rebased onto `main` at `6b8d88f4` (#520) during finalize. Every figure in the PR is measured at the head `90dff9d2`.
- Shipped advisories (PR #521 "Known residuals", shipped by operator decision):
  - Code review F1: `doc/architecture.adoc:587-588` still says the end rule leaves the WebSocket path unaffected, and its list of end-rule writers omits the pre-upgrade WebSocket answer.
  - Code review F2: the `ResponseEnds` Javadoc and ADR-0066 name only the failed-dial answer as a user of the end rule; the relayed upgrade refusal also passes through that seam.
  - Code review F3: `WebSocketRelayStageTest.PreUpgradeFailureEnd` covers the failed dial only, not the relayed upgrade refusal that shares the seam.
  - Code review F4: `benchmarks/README.adoc:514-516` says Methodology lists every parity condition, but the list leaves out the shared auth realm and tokens.
  - Code review F5: `doc/development/bff-session.adoc:302` credits the native-image residual verdict only to entry 3, although its EC key-generation part is verified in entry 4.
  - Code review F6: the ADR-0060:331-334 sentence went stale through the rebase. It still says no test covers the `dispatchGrpc` watched-stream hand-off and the fixed five-minute value, but #520 added `TerminalDispatchGrpcStreamIdleTest` for both. It should name only the public one-line hand-over, as the threat model now does.
  - Functional review: term-list wording left in the rewritten records ("refuse"/"refusal" for runtime rejections, "routing" for route selection, unqualified "warning" for the boot WARN concept, "outbound" for the egress direction), and the ADR-0041 rewrite dropped its stale snapshot-repository paragraph.
- Follow-up: PLAN-V02-48 (ADR corpus cleanup, part 2) takes the 9 deferred groups (53 records, F1-F9). The stale ADR-0060:331-334 sentence (F6 above) belongs to it as well.
- Flaky test seen in this run: `EdgeAdmissionTest`'s admit/drain race failed its vacuity control on a loaded runner (0 of 100 rounds admitted, merge-group run 40292535629). It is fixed separately in PR #522 (spin-barrier release plus an adaptive round count); it is not part of #521. The project lesson is filed as message plan-v02-04-adr-corpus-cleanup-002 in this inbox.
- Open point for the operator, carried from the PR: the project's `release` skill says an ADR is immutable and never edited, which conflicts with this PR's in-place rewrites, status flips and merges. The skill was not edited.
- Post-merge checks are the orchestrator's: the PR-attached post-merge run and the main-branch Maven run for f5826465.
