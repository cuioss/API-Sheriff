envelope_version=1
sender_type=plan
sender_id=sec2-05-early-response-quiet-exit
epic=security-hardening-tier-2
kind=landing
created=2026-11-18T05:30:50Z

## What landed

sec2-05-early-response-quiet-exit shipped as #633 (merged via merge queue, 7f09b60b).

```landing-facts
schema=landing-facts/1
plan_id=sec2-05-early-response-quiet-exit
epic=security-hardening-tier-2
pr=#633
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=25539647
total_wall_seconds=43238.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:in_progress,archive-plan:pending
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
step.functional-review.deliverables_met=2
step.functional-review.deliverables_met_with_residuals=1
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=4
step.code-review.rules_cited=3
```

## Residue

- Framing corpus with `-XX:ActiveProcessorCount=1` ran 53/53 green during execute (passed via `-DargLine`; its presence in the forked JVM was not separately observed). It was not re-run in that mode on the final code; the final whole-tree test runs (8718 tests, default CPU count) include it and are green.
- `Http2EarlyResponseResetIT` (native image) was last run green (2/2) before the later PING-bound, event-loop and moved-window changes; CI's integration-tests job is green on the merged head.
- Review fixes shipped in-run: CI-only PING-acknowledgement race (ping() now confined to the event loop), moved-and-returned window now restarts the quiet check, cross-instance PING-bound test, Sonar java:S1117. Comparing the stream's own receive window was evaluated and not shipped (padding returns stream credit on a paused stream); the docs state the compared value and its limit instead.
- Open advisories (non-blocking): no test for the acknowledgement-relay release; stopped-sender edge test and native IT cannot distinguish the PING exit from the 1 s cap; docs still claim an acknowledging client never reaches the 100-PING bound (a finished wait's PING stays counted until its ack); SVG `<desc>` still states the superseded 50 ms rule; ADR-0066 lacks an alternatives-rejected entry for the stream-window option.
- Sourcery did not review (hard weekly quota, optional bot).
