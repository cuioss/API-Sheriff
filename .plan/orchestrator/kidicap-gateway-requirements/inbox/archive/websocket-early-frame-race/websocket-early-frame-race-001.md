envelope_version=1
sender_type=plan
sender_id=websocket-early-frame-race
epic=kidicap-gateway-requirements
kind=landing
created=2026-09-24T06:41:57Z

## What landed

websocket-early-frame-race (PLAN-22) shipped as #350 (merged, merge commit 2f4254d via merge queue).

```landing-facts
schema=landing-facts/1
plan_id=websocket-early-frame-race
epic=kidicap-gateway-requirements
pr=#350
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=5784350
total_wall_seconds=61804
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.branch-cleanup.merge_mechanism=merge_queue
step.sonar-roundtrip.new_code_issue_count=0
step.create-pr.pr_number=350
```

## Residue

- d3-production-verdict: GREEN (refuted as far as measured) — early-frame IT on the UNFIXED native gateway echoed 50/50 first frames; 0/50 bounds the per-upgrade loss rate below ~6% at 95% confidence. The race is real in the relay (reproductions RED 5/5 on unfixed code); production client-leg loss was masked by Quarkus's context-preserving virtual-thread executor. Fixed structurally regardless.
- Scope widened with operator approval during review loop-backs (3 of max 5): (1) Sonar java:S107 fix (RelaySession inner class); (2) pre-existing bug on main — WebSocket pongs were relayed to the other leg as BINARY data frames — now forwarded as pongs (CodeRabbit 549c25); (3) CWE-400 — ping/pong forwarding bypassed write-queue backpressure (pre-existing ping branch + new pong branch) — now backpressured (CodeRabbit 28eea5). All mutation-verified.
- Merged under operator-accepted gaps: (a) cuioss-review-bot (PR-Agent) reviewed only at PR open; on-demand `/review` re-trigger fails org-side — GitHub App token error "repository does not exist or is not accessible to the parent installation" (token scope includes cuioss/pr-agent-settings); same failure on another PR at 19:22 on 2026-09-23. Org-level fix needed. (b) Pre-merge ADR duplicate-number gate waived: origin/main carries two ADR-0053 files (0053-A_header_matchers... from #346 route-header-matcher-defects, and 0053-Portal_templates...); #350 touches no ADR. The route-header-matcher-defects owner should renumber. The gate also returned no verdict — manage-adr scan success payload omitted duplicate_count/duplicate_numbers/duplicate_paths (plan-marshall defect).
- Follow-up candidates (recorded, not done): the pre-existing `source.pongHandler(pong -> resetIdle())` in WebSocketRelayStage.wire() is now redundant (relayFrame receives pongs and resets idle); test helpers queueFullDialer/frameThreadRecordingDialer near-duplicate (one decoratingDialer would do).
- Plan-marshall friction observed: phase-transition mailbox probe classifies this plan `not_orchestrated` (detection=not_orchestrator_pointer) while `orchestrator inbox detect` on the same source_id says orchestrated; compute-footprint / self-review surfacer diff against a stale local `main` ref (174-file footprint until main was fast-forwarded); build wrapper summary reports only the last reactor module's test count; references.json carries no plan_creation_sha so the scope-creep check never measured; the self-review surfacer has no Java/AsciiDoc detectors (every round zero-observation).
- CI occurrence cited in the spec (run 35802987168) could not be confirmed through the CI tool; D5 docs cite it as "reported by the plan spec", not as measured.
