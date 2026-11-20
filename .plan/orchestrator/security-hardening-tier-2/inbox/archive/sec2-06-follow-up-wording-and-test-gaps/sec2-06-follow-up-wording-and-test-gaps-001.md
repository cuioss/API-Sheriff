envelope_version=1
sender_type=plan
sender_id=sec2-06-follow-up-wording-and-test-gaps
epic=security-hardening-tier-2
kind=landing
created=2026-11-20T14:56:35Z

## What landed

sec2-06-follow-up-wording-and-test-gaps shipped as #641 (merged).

```landing-facts
schema=landing-facts/1
plan_id=sec2-06-follow-up-wording-and-test-gaps
epic=security-hardening-tier-2
pr=#641
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=28318091
total_wall_seconds=84432.0
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
step.code-review.blocking_count=0
step.code-review.advisory_count=3
step.code-review.rules_cited=3
```

## Residue

- The idle-host repetitions of `DispatchStageTest$BodyDeadlineBehindASlowConnection` that the spec asks for were not run: the host was never idle. 160 repeats under `-XX:ActiveProcessorCount=1` on a loaded host showed no failure of the suspected read. The chunk-after-deadline-reset ordering those repeats did surface was fixed test-first in this plan. The repetitions remain owed on an idle host.
- The functional review at the final head was closed by the operator over this one open residual; its D2 items were settled by two integration-profile runs.
- The first merge-queue entry was ejected by `SniFrontListenerTest.returnsTheSlotOnTheError` (30 s timeout in the Java 25 leg, `tls` package, not touched by this plan, green on the PR-head run). One retry through the same gate merged as `d5288639`.
- `BffRefreshSingleFlightIT.concurrentRequestsShareOneRefresh` failed once in a full integration run under host load of about 13 and passed on its own re-run; the code is not touched by this plan.
