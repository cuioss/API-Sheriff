envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=landing
created=2026-10-12T15:32:21Z

## What landed

jwks-unknown-kid-refresh (PLAN-24: JWKS unknown-kid refresh) shipped as #430 (merged, squash via the merge queue, merge commit c013781dba85878c6a2bab93e88118e5648b3838).

```landing-facts
schema=landing-facts/1
plan_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
pr=#430
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=7763576
total_wall_seconds=24966.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.create-pr.pr_number=430
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.sonar-roundtrip.new_code_issue_count=0
step.sonar-roundtrip.count_status=confirmed
step.pre-submission-self-review.acceptance=accepted
step.record-metrics.any_phase_missing_end_time=false
```

## Residue

Items the epic should track that no step recorded as a fact.

**Upstream request owed (operator decision, 2026-10-12).** A refreshed key set is published only when it holds the key id that triggered the refresh. A caller presenting invented key ids can therefore win an issuer's window and delay pick-up of a genuine key rotation until a token signed with the new key claims a window, or at the latest until the background refresh (`jwks.refresh_interval_seconds`). It never accepts an invalid token and never drops a valid key; the worst case equals the behaviour before this plan. Closing it exactly needs token-sheriff's `JwksLoader` to expose which key ids a loaded key set holds; token-sheriff 0.9.6 has no such accessor. The operator chose to ship the documented residual (ADR-0063 Risks, threat model GW-07) and to ask token-sheriff for a key-id accessor. That request has not been filed anywhere by this plan; the epic owns it, and with it the later switch to the exact rule.

**Follow-up candidates, not filed as issues.**

- `RetryingJwksLoader.runRetry` has the weakness that was fixed in `runRefresh` during this plan: its in-flight state is not released in a `finally` block on an unanticipated throwable. Found by the security re-check, not fixed here.
- An unanticipated throwable in `runRefresh` releases the refresh but leaves no log record.
- The refresh window and the grace deadline are measured on the wall clock; a clock step shifts them by the size of the step (finding 1663c4, accepted as low risk by the triage's own judgement, not by the operator).
- The simplify re-check left three findings unactioned: a `@param` line on `buildDelegate`, the static-versus-instance shape of that helper, and one near-identical test pair.

**Verification gaps the facts above do not show.**

- No single full local run of the Docker integration suite was green end to end. One full run was 273 of 274 (the failure, `BffReservedPathPrecedenceIT`, is untouched by this branch and was green on a targeted re-run). The Integration Tests workflow in CI was green on both pushed heads, and the merge queue's own run was green.
- CodeRabbit reviewed the pull request at 10252a63 only. It counted as having taken part, but it did not review the three later commits (a814f178, 4f24a24a, f77de777). cuioss-review-bot re-reviewed the final head and filed nothing. Sourcery declined on diff size (optional reviewer).
- The pre-push gate recorded module-tests as degraded because no narrow test scope exists for Maven; the whole-tree `verify -Ppre-commit` did run all 5937 tests green at the merged head.
- Post-merge verification belongs to the orchestrator: when this plan stopped waiting, the Performance Benchmark run 38268812353 attached to #430 was still in progress (no failure seen), and the Maven Build run for merge commit c013781d on `main` (including `deploy-snapshot`) was not checked.

**Flaky test seen twice, unrelated to this branch.** `GatewayEdgeFramingCorpusTest` failed once in CI on Java 26 (`soleTransferEncodingNotExactlyChunked`, 502) and once locally (`bodylessMethodNeverCarriesABodyUpstreamOnHttp2[2]`, 30 s timeout); green on re-run both times.

**Documentation gaps reported during the run, not closed.** AsciiDoc list-format warnings on ADR-0063 and ADR-0011; the validation-rules summary in `doc/configuration.adoc` and the threat model's validation matrix were not checked for missing rows for the three new keys; a sentence in `doc/configuration.adoc` saying BFF variants do not read `token_validation` may be stale.

**Fourteen candidate-lesson messages** from this plan are in the same inbox (`jwks-unknown-kid-refresh-001` to `-014`).
