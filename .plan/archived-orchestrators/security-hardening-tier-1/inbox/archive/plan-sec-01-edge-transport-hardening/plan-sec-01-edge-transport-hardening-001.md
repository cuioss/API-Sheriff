envelope_version=1
sender_type=plan
sender_id=plan-sec-01-edge-transport-hardening
epic=security-hardening-tier-1
kind=landing
created=2026-11-08T20:40:29Z

## What landed

plan-sec-01-edge-transport-hardening shipped as #582 (merged via merge queue, c85817d93258e8b6357f79323cad47aa14306e1a).

```landing-facts
schema=landing-facts/1
plan_id=plan-sec-01-edge-transport-hardening
epic=security-hardening-tier-1
pr=#582
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=34903306
total_wall_seconds=93625.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2036
executor_version=0.1.2036
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:skipped,branch-cleanup:done,lessons-capture:skipped,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.functional-review.deliverables_met=3
step.functional-review.deliverables_met_with_residuals=3
step.functional-review.deliverables_not_met=0
step.functional-review.criteria_unevidenced=0
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.head_binding_waived=finalize-step-security-audit,finalize-step-simplify,functional-review
step.sonar-roundtrip.new_code_issue_count=0
```

## Residue

- Merge path: the pre-merge gate (plan-marshall 0.1.2036) refused on head binding. security-audit, simplify and functional-review were last validated at 2845a46; the merge head was 955601e, which only adds two behaviour-neutral Sonar style fixes. The operator had waived re-running those steps, but the 0.1.2036 gate does not read refire waivers (0.1.2080 does). On the operator's instruction the PR was enqueued directly via `ci pr merge-queue`. The findings gate (0 blocking) and reviewer participation (complete) were re-verified first. CI, CodeRabbit and Sonar all checked 955601e.
- D2 scope deviation (accepted): an HTTP/2 failed client upgrade now answers 502. HTTP/1.1 keeps Vert.x's own 400, which is pinned by test.
- Accepted residuals: D3.C4 (migrated test cases named in commit 7e410dd, not in a task note) and D4.C2 (reset test fills the default pool with a 2 s bound rather than a pool of one with about 1 s).
- Open test-coverage advisories:
  - ADR-0066 does not mention the 405 refusal.
  - The onStartup idle-timeout wiring is untested.
  - The HOURS/DAYS branches of `applyIdleTimeout` and its final throw are untested.
  - Headers and Set-Cookie on the registry-full and budget 503 responses are untested.
  - The TlsPassthroughIT deadline tests have no lower bound.
- Post-merge Performance Benchmark run 42520067944 was still in progress when the plan stopped watching; post-merge verification belongs to the orchestrator.
- Tooling: executor 0.1.2036 vs installed plugin 0.1.2080; marshal.json provisioning stale (run /marshall-steward).
