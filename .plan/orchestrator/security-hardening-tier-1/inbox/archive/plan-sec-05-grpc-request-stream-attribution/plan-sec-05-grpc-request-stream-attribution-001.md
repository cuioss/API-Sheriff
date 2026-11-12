envelope_version=1
sender_type=plan
sender_id=plan-sec-05-grpc-request-stream-attribution
epic=security-hardening-tier-1
kind=landing
created=2026-11-12T10:23:08Z

## What landed

plan-sec-05-grpc-request-stream-attribution (WS-02, spec PLAN-SEC-05) shipped as #600 (merged via merge queue, squash 50b7e6d3379c860bb2430c2133248be1a74f17cb).

```landing-facts
schema=landing-facts/1
plan_id=plan-sec-05-grpc-request-stream-attribution
epic=security-hardening-tier-1
pr=#600
merge_state=merged
cleanup_owed=false
deliverables_total=9
deliverables_done=9
total_tokens=87311960
total_wall_seconds=225870.0
harness=claude
model=claude-opus-5-5
effort=low
plugin_version=0.1.2090
executor_version=0.1.2090
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,functional-review:done,code-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:in_progress,archive-plan:pending
unresolved_review_threads=0
review_body_findings_total=4
review_body_findings_handled=4
sonar_open_new_issues=unknown
step.finalize-step-sync-baseline.action=rebased
step.finalize-step-sync-baseline.upstream_commit_count=3
step.create-pr.pr_number=600
step.sonar-roundtrip.new_code_issue_count=0
step.sonar-roundtrip.count_status=confirmed
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.branch-cleanup.head_binding_unconsulted=architecture-refresh
step.record-metrics.total_tokens=87311960
step.record-metrics.total_wall_seconds=225870.0
step.record-metrics.any_phase_missing_end_time=false
step.record-metrics.harness=claude
```

## Residue

Measurement notes:

- `unresolved_review_threads=0` and `review_body_findings_total=4` were measured on PR #600 after the merge: all 11 rows reported unresolved are `review_body` (4) or `issue_comment` (7) kinds with an empty thread id, so no resolvable thread is open. `review_body_findings_handled=4`: the plan's findings store holds no pending pr-comment finding; the outside-diff items in those review bodies were answered on the PR (one fixed in e07e7076, two declined with rationale).
- `sonar_open_new_issues=unknown`: `sonar-roundtrip` confirmed 0 new-code issues at 6b95ece8, but that count excludes hotspots, and the hotspot-inclusive post-merge read returned `count_status: undecidable` (head revision unavailable). Recorded as a gap, not as zero.

Delivered scope:

- Part A, gRPC request-stream attribution (M6 invariant): the first-message clock stops only at a complete first message; every request message is bounded until half-close; a pre-response deadline charges a held stream to the client unless the upstream had the whole request and stalled; idle-client ends are counted apart from client departures and body-stream failures; HTTP 408 maps to gRPC DEADLINE_EXCEEDED; `GrpcProxyIT` pins the invariant end to end.
- Part B, SNI front follow-ups from PLAN-SEC-04: per-target passthrough dial budget inside the front cap; exact slot accounting on every exit path; IPv6 sources grouped by /64; WebSocket upgrade-gate and HTTP/2 early-end relay probes settled against the real Vert.x stack; TLS front documentation corrected.
- Documentation corrections across ADR-0016, ADR-0060, the TLS / observability architecture docs, the user docs, LogMessages and the threat model, including the GW-07 residual wording fix in the threat model (verified against code).

Operator-accepted scope widenings:

- The ResponseStage gRPC client-departure fix (ResponseStage was originally a read-only reference in the outline).
- The late-end request-body deadline rule (an inbound end that arrives after the deadline instant but before its late timer runs is ended at the deadline) also applies to plain HTTP, not only gRPC.

Operator-declined review items:

- Two CodeRabbit late-timer suggestions (`onChunk` forwarding a chunk inside the timer's lateness; `stopMessageClock` when response headers arrive inside the timer's lateness) were declined: deadlines are enforced at timer resolution, and the code acts at an event only where cancelling the timer would drop the deadline. Rationale is posted on the PR.

Refuted probes:

- D8 probes 1 and 2 were refuted by a run; they changed no code and remain as regression guards.

Advisory residuals (non-blocking, general level):

- Documentation wording drift, including the STALLED_BY_UPSTREAM message text, the ApiSheriff-146 WARN wording, the ADR-0060 departure / IT-extent wording, and the TerminalDispatch late-callback event-loop Javadoc.
- D4: the native-image red-first run was not performed.
- D7: no concurrent never-above-cap test.
- One lost-claim guard branch is untested.
- `architecture-refresh` was not re-consulted after 9da2c232 (branch-cleanup recorded `head_binding_unconsulted=architecture-refresh`).
- Preference-emitter promoted no architecture hints (the only recurring tuple, planned red-first test failures, is attributed to the build tool rather than a project module).

Owed to the orchestrator:

- The post-merge "Run Integration Benchmarks" run (PR-attached) and the main-branch Maven Build for 50b7e6d3 were still in progress at merge time; their outcome is the orchestrator's to verify.
- Open security notes are held privately by the operator.
