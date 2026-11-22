envelope_version=1
sender_type=plan
sender_id=sec2-04-leftover-hardening
epic=security-hardening-tier-2
kind=landing
created=2026-11-19T17:43:30Z

## What landed
sec2-04-leftover-hardening shipped as #635 (merged by the merge queue as 00ebea64).

```landing-facts
schema=landing-facts/1
plan_id=sec2-04-leftover-hardening
epic=security-hardening-tier-2
pr=#635
merge_state=merged
cleanup_owed=false
deliverables_total=12
deliverables_done=12
total_tokens=72704887
total_wall_seconds=173580.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
```

## Residue
- **Review steps closed by operator override.** functional-review and code-review were closed by operator override at 6731f7a2. Their last rounds produced only wording findings, and those were fixed.
- **Review-thread figures.** No review-gate step is in the manifest, so the three review-thread facts are `unknown`. At the merge, `pre_merge_gate` reported `findings_gate: clean` and `participation_gate: complete`. A manual read found 0 open resolvable threads; the 6 rows still open are review_body or issue_comment rows, which cannot be resolved.
- **Post-merge runs.** The post-merge runs for 00ebea64 (the PR-attached benchmark, and the Maven Build and Integration Tests runs on main) were still in progress when the merge was recorded. Verifying them is the orchestrator's job.
- **Decisions to carry into the epic:**
  - D1 direction: the wording of the unknown-source key now agrees across ADR-0003, ADR-0076, ADR-0078, the threat model and the guides (2f9c2fee).
  - D4: changed in the documentation only.
  - D12(b): the limit is documented.
  - The ADR amendments are to ADR-0003, ADR-0060, ADR-0064, ADR-0076 and ADR-0078; no new ADR.
  - The refutations and their evidence are in the plan's decision log.
  - The list of deliverable-5 sites that cannot be reached is in decision-log entry 8fe9df.
  - The deliverable-10 upstream request draft is in decision-log entry 33ad2c, verbatim.
- **Follow-ups (wording and cleanup only):**
  - the EdgeRules Javadoc;
  - the ApiSheriff-149 template and its `LogMessages` row for the blank-header case;
  - ADR-0076: change "either" to "any of these", and fix the "forwarding header" wording;
  - the threat-model sentence on `Sec-Fetch-Site: none`;
  - over-long lines;
  - the `afterTheExpiry` default, which can never be reached;
  - the `SourceGate` "non-blank header" wording and its ambiguous antecedent;
  - the optional `RuleSupport` switch simplification that was dropped;
  - stale plan statements (e6c272b8, fa3a0e, and the outline's claim about integration tests).
- **Test flakes.** Timing-bound tests flaked under foreign host load. One flake was first attributed to de0e7d00; that attribution was wrong and is corrected in the decision log. See candidate lessons 002 and 003.
