# Landing Analysis: PLAN-04 — Protocol Processors (WebSocket + gRPC)

epic: api-sheriff-roadmap
workstream: WS-02
pr: #88 (squash-merged via merge queue → bdc8948, single parent 5265ed6 verified)

> Landing record for one shipped plan. Written by the `analyze` verb 2026-07-22 after
> verifying the operator's landing narrative against ground truth (git, origin/main
> tree, live SonarCloud API, live GitHub Actions API).

## Deliverable Fidelity vs Spec

Plan `protocol-processors-ws-grpc` (roadmap 05), 14/14 deliverables, archived to
`.plan/local/archived-plans/2026-07-21-protocol-processors-ws-grpc`. Both folded work
items from the epic ledger executed — a clean counter-example to the
"outlines drop named deliverables" watch (both were stated as their own line items at
emit, per the watch's own lesson).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1-2 doc-first reference (allowed_origins, ws idle timeout, gRPC status mapping) | shipped-as-specified (narrative) | Doc-first commits 183bbe4/693e87d were on branch pre-merge |
| 3 auditability (ADRs, plan-doc, README sweep) | shipped-as-specified | c0bef8c on branch; one ADR draft deliberately deferred (see Follow-Ups) |
| 4-6 WS config/validation + WS processor + gRPC processor | shipped-as-specified (narrative + branch commits d163efe/880243f/13c52f9) | CI green incl. native ITs at 535244a |
| 7 GatewayExceptionMapper removal (folded watch item) | shipped-as-specified | **Verified: 0 files matching GatewayExceptionMapper on origin/main** |
| 8 verify-green-while-errored probe (folded defect) | shipped-as-specified | Finding recorded (39073ea + plan doc): **build-wrapper, not pom** — the masking sat in the build-wrapper layer, not testFailureIgnore/quarkus-jacoco. Closes the epic defect with a root-cause finding |
| 9-11 gRPC echo upstream + WS/gRPC IT matrix | shipped-as-specified (narrative) | 556fd36/ebc77e6 + IT suites; CI native ITs green |
| 12 k6 ws/grpc benchmarks + APISIX comparison matrix | shipped-as-specified (narrative) | Extends the PLAN-09 harness as designed |
| 13-14 operator + developer guides | shipped-as-specified (narrative) | Three-layer docs convention held |
| ADDED in-flight (TASK-024): gRPC anchor-containment exemption | added-unplanned, justified | gRPC paths are single opaque segments → ConfigValidator exempts protocol:grpc from containment rules 3+4, auth floor + access matrix stay enforced; 5 tests + ADR-0007 note |
| ADDED in-flight (TASK-027): upstream.path materialization bug | added-unplanned, real bug fix | Route upstream.path was never applied to the forward URI; fixed with replace-semantics in RouteTableBuilder.applyRouteUpstreamPath — also fixed silently-ignored upstream.path on httpbin benchmark routes. Spawns the deferred ADR (Follow-Ups) |

## Metrics and Anomalies

- Tokens: 7.4M; worked 7h52m / wall 15h39m; finalize alone 3.2M tokens, 784 tool uses.
- Three CI loop-back rounds (TASK-024/025-026/027) — all real defects, all resolved;
  final round fully clean.
- 26 prior review comments triaged + replied; WS handshake-failure responses gained
  stage-0 security headers; chmod 0777 scoped to a dedicated log subdir.
- Tooling anomaly (lesson 2026-07-22-00-003): the 0.1.1166→0.1.1172 plugin upgrade left
  stale script copies in the long-lived worktree → two documented force-done escapes on
  the review-completeness gate. Operator routine after upgrades: preflight +
  manage-config sync-defaults (matches the known post-update routine).

## Routing and Merge Behavior

- **Second-merger obligation SATISFIED**: finalize-step-sync-baseline rebased onto
  origin/main folding in PR #89 (3 conflicts, resolved manually), whole-tree quality
  gate green at 535244a, CI green pre-merge. Verified on main: single parent 5265ed6.
- **README D7 lines survived the conflict resolution** — both k6 badge targets present
  on origin/main (verified). The badge defect stays closed.
- Sonar: PR new-code 0 confirmed; post-merge project gate verified OK; **backlog
  unchanged at 34 with identical rule counts 24h after 5265ed6** → the S5778×17/S7467×7
  are unfixed remnants, NOT propagation lag — PLAN-13's scope is now firm (~34 real).
- Source-mutating finalize watch: CLEAN this round (simplify 0 edits, security audit
  0 edits — no freshness reconciliation to re-check).
- **deploy-snapshot hung AGAIN on bdc8948** (23:00:04Z → cancelled 23:30:20Z, all other
  jobs green) — third consecutive main push. Systemic; org-admin escalation upgraded.

## Reconciliation Actions

- [x] status.json `plans[]` entry updated (PLAN-04 shipped, pr #88, landing recorded)
- [x] epic.md queue reconciled from status.json
- [x] Watch DISCHARGED: collision/second-merger obligation (rebase verified)
- [x] Defect CLOSED: verify-green-while-errored (root cause: build-wrapper, not pom)
- [x] Defect UPDATED: deploy-snapshot now 3 consecutive hangs — systemic
- [x] Defect UPDATED: Sonar backlog 34 stable → remnants, not lag; PLAN-13 unblocked
- [x] resume_anchor updated; START-HERE regenerated
- [x] PLAN-13 hand-off command emitted (both emit preconditions now met)

## Follow-Ups

- **Deferred ADR**: "Route upstream.path replaces the alias base-path in forward-URI
  reconstruction" (refines ADR-0004's concatenation wording to TASK-027's
  replace-semantics). Disposition: operator lands it as a small ad-hoc doc PR, OR it
  folds into the PLAN-05 hand-off if still open at that emit (PLAN-05 shares the
  route/upstream neighborhood). Tracked as a Watch.
- Uncommitted on main: 2 enriched.json preference-emitter hint files — ride along with
  the next PR (PLAN-13's).
- Lessons 2026-07-22-00-001..003 recorded in-repo; -002 (baseline-reconcile
  git-version) and -003 (stale worktree scripts after plugin upgrade) are
  plan-marshall-upstream-relevant — candidates for the next lessons-handling epic,
  alongside the credentials-key-prefix report already drafted.
