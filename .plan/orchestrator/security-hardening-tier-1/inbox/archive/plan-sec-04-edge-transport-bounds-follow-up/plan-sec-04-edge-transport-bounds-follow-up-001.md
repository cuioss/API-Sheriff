envelope_version=1
sender_type=plan
sender_id=plan-sec-04-edge-transport-bounds-follow-up
epic=security-hardening-tier-1
kind=landing
created=2026-11-10T12:40:30Z

## What landed

plan-sec-04-edge-transport-bounds-follow-up shipped as #589 (merged, 47e73a15).

```landing-facts
schema=landing-facts/1
plan_id=plan-sec-04-edge-transport-bounds-follow-up
epic=security-hardening-tier-1
pr=#589
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=53913406
total_wall_seconds=87496.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2090
executor_version=0.1.2090
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
unresolved_review_threads=0
review_body_findings_total=4
review_body_findings_handled=4
sonar_open_new_issues=0
step.functional-review.deliverables_met=3
step.functional-review.deliverables_met_with_residuals=4
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.code-review.blocking_count=0
step.code-review.advisory_count=14
step.code-review.rules_cited=11
```

## Residue

- Functional review closed by operator override at 728062ea: the operator accepted the rewrite of WebSocketUpgradeGateTest.Accepted::acceptsTokenInsideCommaList and ::acceptsTokenOnRepeatedHeaderLine (they asserted acceptance of handshake shapes vertx-core 4.5.34 / Netty refuse; rewritten to assert the 426). The four verdict facts above are from that full round.
- Residuals graded non-consequential: D2 (the dial bound is the host-name lookup plus the 10 s TCP connect timeout; the outline's Decisions/Risks text still says 10 s alone), D3 (client-gone test exhausts the whole default HTTP/1.1 pool, no pool-size seam), D4 (tab-padded Connection token pinned at unit level only), D7 (no CI run of the final head before the queue's own run).
- 14 code-review advisories left unfixed (docs/Javadoc/test hygiene): stale "one client can hold all 1024 slots" texts (SniFrontListener Javadoc, doc/development/tls-edge.adoc, doc/user/tls-edge.adoc), review-history paragraph with "option a/b" labels in the contributor guide, runbook source_share wording, missing @since on SniFrontListener.Refusal, failed relay hand-off logged at DEBUG only, a misleading relay DEBUG line after a failed hand-off, ConnectionRetirement class Javadoc naming only the 413, docs not describing the space-only Connection list trim, a duplicated WebSocketUpgradeGateTest case, and two PassthroughRelayTest fixture socket leaks on unchecked exits.
- Mid-finalize the branch was rebased onto main (#590) by operator choice; CodeRabbit reviewed every pushed head after the fixes; the last review of 728062ea had no actionable comments.
- Squash message used the repository default (title + PR body, including the bot summary section); repository merge-message settings were left unchanged.
