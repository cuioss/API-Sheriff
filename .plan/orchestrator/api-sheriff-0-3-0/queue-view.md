<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug api-sheriff-0-3-0`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug api-sheriff-0-3-0`, and `git add` the result. -->

# Queue view: API Sheriff 0.3.0 — New Capabilities

## START HERE

**Resume anchor**: === NOT EMITTABLE — gated on api-sheriff-0-2-0's PLAN-V02-06. Cleanup pass 2026-09-24 at 05f6ee3 complete. NEXT ACTION: commit this cleanup, then wait; re-run `next` only after PLAN-V02-06 ships. ===

STATE: 0 shipped / 0 running / 3 staged (PLAN-V03-01, -02, -04) / 1 superseded (PLAN-V03-03, folded into V03-02). Ledger migrated to the per-concern layout 2026-09-24 (queue/, resume_anchor.md, queue-view.md).

GATES: (1) api-sheriff-roadmap closed — MET per the last recorded evidence (phase:closed, 2026-08-08); that tree is no longer present in this checkout, so it was not re-observed. (2) api-sheriff-0-2-0's PLAN-V02-06 (per-client detection substrate) shipped — STILL CLOSED: V02-06 and V02-07 both `staged` (re-read 2026-09-24). When V02-06 ships, re-ground PLAN-V03-01 a SECOND time against what actually landed, not against V02-06's spec.

CLEANUP 2026-09-24: all 25 claims re-grounded at 05f6ee3 (0 blocking, 0 stale). Expected Surface fixed in V03-01/02/03 — package-relative main-source paths and continuation-line entries never reached the disjointness gate (22 -> 36 claimed, 9 -> 0 unresolved). Hand-Off Commands repointed from .plan/local/orchestrator/ to .plan/orchestrator/. V03-03 merged into V03-02 per the 2026-07-27 decision: 8 deliverables, two PRs, unsplit with the rationale recorded. The portal app catalog (69b322b5) was checked: not an inventory substrate, so no overlap.

STANDING ITEMS: PLAN-V03-04 DO-NOT-LAUNCH (plan-marshall classifier still has no helm glob). ADR allocation: doc/adr has a DUPLICATE 0053 and runs to 0054 — resolve the duplicate before any plan allocates; next free is re-derived at write time, never assumed. Tooling defect (plan-marshall): `corpus cross-check` counts the .plan/local/plans/NO_PLAN scratch bucket as an indeterminate live plan, so candidate_comparison_determinate is false and `next` would refuse EVERY candidate — fix upstream or expect that refusal.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-V03-01 (WS-01)
2. PLAN-V03-02 (WS-02)
3. PLAN-V03-04 (WS-03)
- PLAN-V03-03 (WS-02) — status: superseded

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| 1 | PLAN-V03-01 | WS-01 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/**; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/**; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/user/ |
| 2 | PLAN-V03-02 | WS-02 | staged | api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ManagementConfig.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ResolvedRoute.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/RouteTable.java; api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java; api-sheriff/src/main/resources/application.properties; api-sheriff/src/main/resources/schema/gateway.schema.json; api-sheriff/src/test/**; doc/adr/00NN-*.adoc; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/user/ |
| 3 | PLAN-V03-04 | WS-03 | staged | deployment/helm/; deployment/pom.xml; deployment/templates/**; doc/configuration.adoc; doc/development/; doc/user/; integration-tests/docker-compose.yml |
