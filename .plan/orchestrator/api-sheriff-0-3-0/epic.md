# Epic: API Sheriff 0.3.0 — New Capabilities

slug: api-sheriff-0-3-0

> Ledger document for one epic under `.plan/local/orchestrator/api-sheriff-0-3-0/`. The layout and
> authority contract live in the central standard — see
> `persona-marshall-orchestrator/standards/orchestration-model.md`. `status.json` is the
> machine authority; any statement here that conflicts with it is stale prose. This document
> describes the epic's **current state** only — no history is retained.

## Vision

Add the capabilities API Sheriff does not yet have: an active **deception layer** that wastes an
attacker's time and yields high-confidence signal, a first-class **API inventory** the management
plane can serve in the formats the ecosystem consumes, and a **Helm chart** verified against a real
cluster rather than by inspection.

**Scope**: net-new capability only. Cleanup, consolidation and hardening of what already ships
belongs to `api-sheriff-0-2-0`, which this epic depends on.

## START HERE

<!-- GENERATED BLOCK — never hand-write or hand-edit this section.
     Regenerate after every queue-touching state change via:
     python3 .plan/execute-script.py plan-marshall:marshall-orchestrator:orchestrator resume-summary --slug api-sheriff-0-3-0
     Paste the returned block verbatim between the markers. -->

<!-- BEGIN GENERATED: resume-summary -->
**Resume anchor**: === NOT READY TO EMIT — DOUBLE-GATED. Full first-ever cleanup pass 2026-09-22 at af63895. NEXT ACTION: wait on the gate, do not emit. ===

STATE: 0 shipped / 0 running / 0 launched / 4 staged (PLAN-V03-01, -02, -03, -04). Migrated to the git-tracked .plan/orchestrator/api-sheriff-0-3-0/ address (was .plan/local/orchestrator/, same PR as api-sheriff-0-2-0's migration+cleanup, #340). None of these 4 specs had EVER been re-grounded before this pass — their baseline was still 3f60d49/818d964/5298237 (late July 2026).

=== THE DOUBLE-GATE, RE-VERIFIED === Gate 1 — the api-sheriff-roadmap epic reaches 'closed': OPEN. Confirmed via .plan/local/archived-orchestrators/api-sheriff-roadmap/status.json, phase:closed, updated 2026-08-08. (Note: that epic's own tree is still at the OLD .plan/local/archived-orchestrators/ address — out of scope for this migration, flagged here only because this pass's dispatch found it while checking the gate.) Gate 2 — api-sheriff-0-2-0 delivers PLAN-V02-06's per-client detection substrate: STILL CLOSED. PLAN-V02-06 is still `staged` in api-sheriff-0-2-0/status.json (confirmed again in that epic's own same-day cleanup pass). PLAN-V02-07 (the ECS/OCSF emit-formatter dependency PLAN-V03-01 also needs) is likewise still staged. NOTHING in this epic is emittable until PLAN-V02-06 ships and PLAN-V03-01 is re-grounded a SECOND time against what actually landed (not against V02-06's staged spec — that instruction, already in this epic's Cross-Epic Dependency section, still stands).

=== ALL 4 STAGED SPECS RE-GROUNDED FOR THE FIRST TIME EVER. 21 claims scanned, 0 blocking. ===

=== PLAN-V03-01 (honeypot-deception): genuinely nothing to re-scope — every checkable claim held (no deception layer exists yet, Vert.x timer primitive is available and unused). The three claims gated on PLAN-V02-06/-07 correctly stay unverifiable/open; they cannot resolve until those plans land. Expected Surface was PROSE-ONLY (the disjointness gate could not read it at all — 0 resolved paths) and has been reformatted into one-path-per-bullet, now declarative with 5 resolved entries. ===

=== PLAN-V03-02 (api-inventory-endpoint): all 4 checkable claims corroborated (management HTTPS-only+unauthenticated still holds; RouteTable/ResolvedRoute inventory substrate still ready to serialize, fields grew further; per-route metrics still cardinality-safe; bearer-validation infra still exists). Two Expected Surface additions applied: config/model/ManagementConfig.java and its schema entry (the natural home for a management-auth policy knob, currently missing from the list entirely). MAJOR STANDING ITEM (not new, but re-confirmed live): this epic's own § below already records a taken-but-unexecuted decision that PLAN-V03-03 MERGES INTO this plan, instructed "RE-GROUND FIRST, THEN MERGE" — both specs are now freshly re-grounded, so that merge is UNBLOCKED and is the epic's own next authoring action once launch time comes (not before Gate 2 opens). ===

=== PLAN-V03-03 (inventory-interop-formats): all 3 claims stay open exactly as the spec's own framing expects (external-standards research, a hypothesis gated on V03-02 shipping, a procedural verify-first directive) — none contradicted, none settleable yet since no inventory/CycloneDX/api-catalog code exists anywhere in main. Expected Surface gained doc/architecture.adoc and doc/adr/00NN-*.adoc (Deliverable 4 named both explicitly; the list had omitted them). Confirms the merge-with-V03-02 plan is still the right call. ===

=== PLAN-V03-04 (helm-chart-real-cluster): 6/7 claims corroborated, the DO-NOT-LAUNCH blocker reconfirmed still fully accurate — plan-marshall's execution-manifest classifier still has no helm/chart-shaped glob, so any Helm YAML this plan would add still classifies `unknown` and parks phase-4 at the Q-Gate. Management-HTTPS-only and the neutral-TLS-surface landings (PR #138) both hold. ONE CORRECTION APPLIED: Deliverable 1 miscited ADR-0011 (JWKS/egress-scoped) for the whole-server-TLS-neutral-naming invariant this deliverable actually needs — corrected in place to ADR-0025, the two live citations fixed. ===

=== ADR CORPUS (cross-checked against api-sheriff-0-2-0's same-day count): 49 records / 10,543 lines, contiguous 0001-0049. Next free number is 0050. Both PLAN-V03-01's config-model ADR and PLAN-V03-03's decision-record ADR will need to claim from there — re-verify against main AND every open branch at write time, per the standing rule. ===

*** THIS ANCHOR IS THE EPIC'S FIRST EVER RESUME-SUMMARY REGENERATION — the Ordered Queue section did not exist before this pass (the epic predates that marker convention) and has been created alongside this anchor. Nothing prior was discarded; this is a from-scratch synthesis of the epic's existing sections plus this pass's findings. ***
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 0 archived
**Queue** (staged, in order):
1. PLAN-V03-01 (WS-01)
2. PLAN-V03-02 (WS-02)
3. PLAN-V03-03 (WS-02)
4. PLAN-V03-04 (WS-03)
<!-- END GENERATED: resume-summary -->

## Ordered Queue

<!-- GENERATED BLOCK — never hand-write or hand-edit this section.
     Regenerate after every queue-touching state change via:
     python3 .plan/execute-script.py plan-marshall:plan-orchestrator:orchestrator resume-summary --slug api-sheriff-0-3-0
     Paste the returned ordered_queue block verbatim between the markers. -->

<!-- BEGIN GENERATED: ordered-queue -->
| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| 1 | PLAN-V03-01 | WS-01 | staged | api-sheriff/src/test/**; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/user/ |
| 2 | PLAN-V03-02 | WS-02 | staged | api-sheriff/src/test/**; doc/adr/00NN-*; doc/architecture.adoc; doc/configuration.adoc; doc/development/; doc/user/ |
| 3 | PLAN-V03-03 | WS-02 | staged | api-sheriff/src/test/**; doc/configuration.adoc; doc/development/; doc/user/ |
| 4 | PLAN-V03-04 | WS-03 | staged | deployment/helm/; deployment/pom.xml; deployment/templates/**; doc/configuration.adoc; doc/development/; doc/user/; integration-tests/docker-compose.yml |
<!-- END GENERATED: ordered-queue -->

### Queue annotations

_None yet — per-row narrative that the generator cannot derive goes here, keyed by plan id._

## Workstreams

| Workstream | Title | Plans |
|---|---|---|
| WS-01 | Deception and Detection | PLAN-V03-01 |
| WS-02 | Management-Plane Introspection | PLAN-V03-02, PLAN-V03-03 |
| WS-03 | Deployment and Distribution | PLAN-V03-04 |

## Renumbering Map — `api-sheriff-next` → this epic

The `api-sheriff-next` backlog was split by target version on **2026-08-04** and retired. Plan specs
were renumbered; **their in-body `PLAN-NN` references were deliberately NOT rewritten**, because many
point at `api-sheriff-roadmap` plans that keep their own numbers. Resolve any in-body reference
through this map first; if the number is not listed here, it belongs to `api-sheriff-roadmap` or to
`api-sheriff-0-2-0` and is unchanged.

| Was | Now | Plan |
|---|---|---|
| PLAN-20 | **PLAN-V03-01** | Honeypot / deception layer |
| PLAN-21 | **PLAN-V03-02** | API inventory endpoint |
| PLAN-22 | **PLAN-V03-03** | Inventory interop formats |
| PLAN-41 | **PLAN-V03-04** | Helm chart, real-cluster verified |

The eleven cleanup/consolidation/hardening plans moved to `api-sheriff-0-2-0` — see that epic's map.

## Cross-Epic Dependency

**`PLAN-V03-01` (honeypot) has a hard dependency on `api-sheriff-0-2-0` `PLAN-V02-06`**, which
builds the per-client detection substrate the deception layer consumes. That ordering is a recorded
operator decision, taken with its cost stated: the substrate ships a release ahead of its consumer.

Do not start `PLAN-V03-01` until 0.2.0 has shipped the substrate, and **re-ground it against what
actually landed** rather than against `PLAN-V02-06`'s staged spec — a staged spec is not evidence of
what a plan delivered.

## Decisions

- **2026-08-04 — the backlog is split by target version.** `api-sheriff-next` was a single
  undifferentiated backlog; it is replaced by `api-sheriff-0-2-0` and this epic, and removed.
- **2026-08-04 — the deception layer stays a separate plan from the substrate it consumes.** It
  carries its own legal and operational review, which a merge into the substrate plan would bury.
- **2026-08-04 — workstreams re-cut from WS-01.** The old WS-05..WS-09 split across both new epics,
  so carrying the numbers would have left gaps in each and put WS-05 in both.
