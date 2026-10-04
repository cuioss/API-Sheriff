envelope_version=1
sender_type=plan
sender_id=plan-v02-13-terminal-rejection-contract
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-03T19:48:55Z

## What landed

plan-v02-13-terminal-rejection-contract shipped as #383 (merged, squash 5ddf8081 via the merge queue).

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-13-terminal-rejection-contract
epic=api-sheriff-0-2-0
pr=#383
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=7103106
total_wall_seconds=57324.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:skipped,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.create-pr.pr_number=383
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

- Issue #188 closed by #383. Issue #189 stays open by design: the demo-client panel and the remaining docs (`doc/plan/04-request-pipeline.adoc`, `doc/variants/01-base-gateway.adoc`, `demo-client/doc/integration-sample.adoc`, the PROHIBITED ASSERTION scope in `demo-client/doc/playwright-suite.adoc`) are not done; a comment on #189 lists them.
- ADR-0059 (ROUTING problem category) landed with status Proposed; it needs an acceptance decision.
- gw-02 (request framing) is recorded PARTIAL in the threat model: the HTTP/2 clauses beyond the stream-scoped gate rejection are not pinned.
- Pre-existing flaky test: `BffRuntimeProducerTest.shouldRefuseAnUnboundRefreshInClientSecretMode` failed once in CI with a `ConcurrentModificationException` from `assertNoRecordCarriesTheSecret` (the shared test log handler is iterated while the stub identity provider thread still logs). Not touched by this plan; passed on re-run. Worth a follow-up fix in the BFF test.
- Finalize loop-back count went past the configured maximum (round 6 was authorized by the operator for a CodeRabbit fix; one further CodeRabbit fix, the atomic abort claim in `DispatchStage`, was applied in that same authorization).
- Post-merge `Performance Benchmark` run on #383 was still in progress at landing time; post-merge verification (that run and the `main` Maven Build for 5ddf8081) is for the orchestrator.
