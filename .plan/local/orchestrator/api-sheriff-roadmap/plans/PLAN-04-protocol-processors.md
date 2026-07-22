# PLAN-04: Protocol Processors (roadmap 05)

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. Source of truth: `doc/plan/05-protocol-processors.adoc` — read in full at
> launch; this spec is a summary staged from `doc/plan/README.adoc`.

## Objective

Execute `doc/plan/05-protocol-processors.adoc`: WebSocket upgrade proxying (full pipeline on
the handshake, then bidirectional relay) and gRPC over upstream h2 with trailer relay + a
gRPC-status rejection mapping — both native to the ADR-0008 Vert.x transport. In-repo Quarkus
gRPC echo upstream for ITs. Closes the ADR-0002 protocol promise and the Variant 1 scope.

## Deliverables

Per the plan doc at launch. Additionally (operator decisions 2026-07-19): the
convention-mandated ws/grpc hot-path benchmarks ship as k6 scripts on the PLAN-09 harness
(k6 replaced wrk repo-wide), and the plan extends the APISIX comparison matrix with the
websocket and grpc aspects (activating/completing the parity routes PLAN-09 pre-provisioned,
adding both aspects to the comparison runner and side-by-side summary).

### Folded scope (operator decisions 2026-07-21)

Two watches folded in here because PLAN-04 is the next `api-sheriff/`-touching / build-changing
plan and both are its natural home. Named as their own line items on purpose — outlines in this
epic have twice abstracted a named deliverable away into prose and dropped it (PLAN-09 doc
bootstrap; PLAN-10 terminal-action wiring, recovered only as a TASK-015 infeasibility). Each of
these MUST survive outline as a discrete deliverable:

1. **Remove the orphaned JAX-RS `GatewayExceptionMapper`.** Advisory finding from PLAN-03's
   landing — an unused mapper left after the pipeline migration removed `GatewayResource`.
   Incidental cleanup; delete it (pre-1.0: remove, never deprecate). If it turns out to still
   be wired to something live, stop and report rather than deleting blind.
2. **Investigate `verify` reporting success while tests errored.** PLAN-10 lesson
   `2026-07-21-07-001`: a `verify` run reported green while **3 tests errored** — suspected
   `testFailureIgnore` / quarkus-jacoco interaction. A green build that hides errored tests is
   a gate-integrity defect for a project that gates on green, and it undermines every other
   plan's "all green" claim. Since PLAN-04 changes the build anyway, scope a focused
   investigation: determine whether the reactor config can let an errored test pass, and if so
   close it (fail-fast on test errors) with a test proving an errored test now fails the build.
   If the root cause is environmental / not reproducible, record that finding explicitly rather
   than silently dropping it.

### Auditability (standing convention, operator 2026-07-21)

Doc-first must carry the **orchestration rationale** — why each shape was chosen and what was
rejected — into the tracked `doc/plan/05-protocol-processors.adoc` + any ADRs, not only the
technical spec. The staged spec under `.plan/local/` is gitignored and not an audit artifact;
the tracked design doc + ADRs are the permanent record. PLAN-10's ADR-0013/0014 are the model.

## Expected Surface

- `api-sheriff/` protocol-processor packages, Vert.x transport upstream h2 path
- `integration-tests/` — gRPC echo upstream, WebSocket/gRPC suites; `benchmarks/` hot-path additions

## Dependencies and Sequencing

- Depends on: PLAN-03 (roadmap 04). Also builds on PLAN-10's landed `ResolvedRoute` union and
  route-table terminal-action materialization — that surface no longer races PLAN-04 (the
  earlier "verify disjointness against PLAN-10" watch is discharged; PLAN-10 shipped first).
- **Sequencing decision (operator 2026-07-21): PLAN-04 then PLAN-05, strictly sequential — NOT
  parallel.** Both touch the ADR-0008 Vert.x transport neighbourhood; running them in separate
  worktrees concurrently risks adjacent-surface merge collisions for a speed gain the epic has
  not needed. PLAN-05 emits after PLAN-04 lands. (Supersedes the earlier "parallel candidate"
  note.)
- PLAN-09-comparative-benchmark shares the integration-tests compose + benchmarks surface —
  already shipped, so PLAN-04 extends the landed harness (ws/grpc aspects + APISIX parity
  routes PLAN-09 pre-provisioned) rather than racing it.
- **Sequential AFTER PLAN-12-sonar-hardening** (operator decision 2026-07-21, reversing an
  earlier parallel call). PLAN-12 was expanded to a full 59-finding zero-Sonar sweep, which
  pulls in PLAN-04's own edge/pipeline surface (`DispatchStage`, `GatewayEdgeRoute`,
  `RouteRuntimeAssembler`, `ForwardPolicyStage`, `TcpPeerGate`) — parallel would mean repeated
  edge-package conflicts. PLAN-12 lands first; PLAN-04 branches from a green, zero-findings
  baseline and only has to keep its OWN new code clean (the sonar zero-findings clause in this
  hand-off then applies to PLAN-04's additions).

## Hand-Off Command

```text
/plan-marshall Execute doc/plan/05-protocol-processors.adoc in full (read it and its required reading first; the design docs govern). Deliver WebSocket and gRPC proxying per the plan's work breakdown — WebSocket upgrade proxying (full stages 0-5 pipeline on the handshake incl. Origin validation against an allowlist per GW-09, then opaque bidirectional relay after 101 with a bounded idle timeout) and gRPC over upstream HTTP/2 with trailer relay (grpc-status/grpc-message) and no protobuf inspection — with integration tests (incl. the in-repo gRPC echo upstream) and benchmarks in the same plan. This builds on PLAN-10's landed ResolvedRoute union and route-table terminal-action materialization (roadmap plan 10, PR #84) — the config model is final; author against it, do not reintroduce anchor type/access assumptions. BENCHMARKS (operator decisions 2026-07-19): the convention-mandated ws/grpc hot-path benchmarks ship as k6 scripts on the existing k6 harness (roadmap plan 04b replaced wrk repo-wide), and extend the APISIX comparison matrix with the websocket and grpc aspects — activate/complete the parity routes plan 04b pre-provisioned, and add both aspects to the comparison runner and side-by-side summary. TWO FOLDED DELIVERABLES (operator 2026-07-21) — each MUST be carried as its own discrete work item and survive the outline (this epic has twice had an outline abstract a named deliverable into prose and drop it, so do not fold either into a general cleanup clause): (1) REMOVE the orphaned JAX-RS GatewayExceptionMapper — an unused mapper left after the pipeline migration removed GatewayResource (PLAN-03 advisory finding); delete it per pre-1.0 rules (remove, never deprecate), but if it turns out still wired to something live, STOP and report rather than deleting blind. (2) INVESTIGATE verify reporting success while tests errored — roadmap plan 10 observed a verify run report green while 3 tests errored, suspected testFailureIgnore / quarkus-jacoco interaction; a green build that hides errored tests is a gate-integrity defect that undermines every plan's all-green claim. Since this plan changes the build anyway, scope a focused investigation: determine whether the reactor config can let an errored test pass, and if so close it (fail-fast on test errors) with a test proving an errored test now fails the build; if the cause is environmental / not reproducible, record that finding explicitly rather than silently dropping it. DOCUMENTATION (standing epic convention, THREE layers, all in the SAME PR): (a) REFERENCE — every new or changed configuration key, default, enum value, and endpoint this plan introduces MUST land in doc/configuration.adoc, and structural changes in doc/architecture.adoc; exhaustive per-key, not optional (this plan introduces at least websocket.idle_timeout_seconds and the bearer-route allowed_origins field — both authored doc-first per the design doc). (b) OPERATOR GUIDE — doc/user/, task-oriented, linking to the reference rather than duplicating its key tables. (c) DEVELOPER GUIDE — doc/development/, contributor-facing (build, test, run, extend). The doc/user/ and doc/development/ trees and the three-layer convention now EXIST (created by roadmap plan 10); follow the fifth bullet in doc/plan/README.adoc's == Conventions section. Updating plan docs, variant docs or ADRs does NOT satisfy (a), (b) or (c). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it. AUDITABILITY (standing convention, operator 2026-07-21): the doc-first work must carry the ORCHESTRATION RATIONALE — why each shape was chosen and what alternatives were rejected — into the tracked doc/plan/05-protocol-processors.adoc and any ADRs, not only the mechanical spec, because that tracked design doc is the permanent audit record (roadmap plan 10's ADR-0013/0014 are the model). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it. SONAR ZERO-FINDINGS (standing epic convention, operator 2026-07-21): the SonarCloud quality gate (project cuioss_API-Sheriff) must be GREEN before merge — a red gate is a HARD STOP, never merge over red or on a stale green while analysis is still pending; deliver ZERO new findings (new bugs = 0, new vulnerabilities = 0, new code smells = 0, security hotspots 100% reviewed, new_reliability / new_security / new_maintainability ratings all A, new coverage >= 80%); the GOAL is to FIX findings, and where a fix is genuinely not sensible an IN-CODE suppression carrying a documented rationale (e.g. // NOSONAR or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo alongside the code — what is NOT acceptable is reaching zero by silent server-side won't-fix / false-positive marking; and this plan is NOT 'done' / 'all green' until the PROJECT-level gate on the merged result is green — the completion report must reflect the actual post-merge gate, not a transient PR-scoped new-code green.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-04.md)
