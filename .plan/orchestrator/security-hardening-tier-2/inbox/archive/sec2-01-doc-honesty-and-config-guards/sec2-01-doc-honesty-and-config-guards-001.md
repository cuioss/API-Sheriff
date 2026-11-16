envelope_version=1
sender_type=plan
sender_id=sec2-01-doc-honesty-and-config-guards
epic=security-hardening-tier-2
kind=landing
created=2026-11-16T10:03:01Z

## What landed

sec2-01-doc-honesty-and-config-guards shipped as #623 (merged through the merge queue as 31595bca).

```landing-facts
schema=landing-facts/1
plan_id=sec2-01-doc-honesty-and-config-guards
epic=security-hardening-tier-2
pr=#623
merge_state=merged
cleanup_owed=false
deliverables_total=8
deliverables_done=8
total_tokens=48870277
total_wall_seconds=129774.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2118
executor_version=0.1.2118
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
step.branch-cleanup.merge_mechanism=merge_queue
step.code-review.blocking_count=0
step.code-review.advisory_count=7
step.code-review.rules_cited=5
```

## Residue

- The functional review was closed three times by operator override (at d6e0d7d0, 24ab9cd4 and eb1f78cd) accepting removed or dropped tests: the two HTTP/2 header-block tests in GatewayEdgeHttp2AbuseTest, the three HeaderListBound tests in EdgeHardeningOptionsTest, the credentials-provider assertions in ManagementPlainHttpAuditTest and TerminatedListenerTlsAuditTest, and HtmlErrorPageIT::unreachableUpstream. No functional verdict counts were recorded, so none are carried.
- The pre-push freshness gate refused every pass as stale with `worktree_root_unreadable` because every build row in the change ledger records a null worktree root. The push therefore used the logged `force-override` basis three times, each approved by the operator. This looks like a plan-marshall build-recording defect that was not investigated here.
- The squash-message composition step was not performed because the merge queue offers no per-enqueue message override. The landed squash message may therefore follow the repository default rather than title plus summary plus trailer.
- The post-merge CI wait was stopped at the tool limit after the merge had landed. The queue's merge-group run (43839962460) had already concluded success.
- Loop-back budget `wait-region-unified-triage` spent 2 of 5. The second unified triage round was skipped: a single reopened CodeRabbit summary finding was resolved directly as `taken_into_account`.
- The preference-emitter promoted nothing: the build and test dispositions are tagged `maven`, which is not an architecture module.
