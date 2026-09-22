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

**Resume anchor**: === NEXT ACTION === NEW EPIC, CREATED 2026-08-04 by splitting the retired api-sheriff-next backlog by target version. NOTHING LAUNCHED, NOTHING EMITTABLE YET. *** THIS EPIC IS DOUBLE-GATED: it waits on api-sheriff-roadmap reaching 'closed' AND on api-sheriff-0-2-0 delivering the detection substrate. Go to those epics first. *** parallelization_scope and auto_emit are UNSET - set them deliberately before the first emit rather than inheriting a default by accident.

=== STATE === Phase orchestrating. FOUR plans across THREE workstreams, all staged, none launched. WS-01 deception/detection, WS-02 management-plane introspection, WS-03 deployment/distribution.

=== WHEN THIS EPIC OPENS, RUN decompose - NOT next === Three obligations before any emit:
(1) *** RE-GROUND EVERYTHING. *** Every spec here predates the 0.1.0 release track landing PLAN-31 (whole TLS/trust/config surface), PLAN-15 (security-filter modes), PLAN-26/27 (image, compose sample) and PLAN-42/43, AND it will additionally predate everything api-sheriff-0-2-0 lands. All of those move surfaces these specs read. Re-ground the ENTIRE epic at decompose, not per-plan at emit. Prefer a structured architecture query over a remembered line number.
(2) *** ONE MERGE IS DECIDED BUT DELIBERATELY NOT AUTHORED: PLAN-V03-03 (inventory-interop-formats) merges INTO PLAN-V03-02 (api-inventory-endpoint). *** Two serializations plus a well-known URI over a read model PLAN-V03-02 already builds does not justify a second lifecycle, and PLAN-V03-02 just got SMALLER (see the carry below). It was left unauthored on purpose: the claim labels date from 2026-07-25 and several are already refuted, so authoring a merged spec against stale ground truth is exactly the failure the verify-first contract exists to prevent. RE-GROUND FIRST, THEN MERGE. Expected PR split after the merge: PR1 auth + read model + native schema, PR2 OpenAPI + CycloneDX + well-known + docs.
(3) Settle parallelization_scope and auto_emit.

=== THE CROSS-EPIC DEPENDENCY, AND IT IS THE REAL SEQUENCING RISK HERE === PLAN-V03-01 (honeypot) CONSUMES the per-client detection substrate that api-sheriff-0-2-0 PLAN-V02-06 builds. Operator decision 2026-08-04 put the substrate in 0.2.0 with this cost stated and accepted: THE SUBSTRATE SHIPS A RELEASE AHEAD OF ITS PRINCIPAL CONSUMER. *** DO NOT START PLAN-V03-01 UNTIL 0.2.0 HAS SHIPPED IT, AND RE-GROUND AGAINST WHAT ACTUALLY LANDED - NOT against PLAN-V02-06's staged spec. A staged spec is not evidence of what a plan delivered, and this project has already been bitten by a spec whose landed outline widened past it. *** If the substrate landed narrower than the honeypot needs, that is a FINDING and it gets its own plan - it is never quietly absorbed here.

=== CLAIMS ALREADY REFUTED - READ BEFORE TRUSTING ANY SPEC HERE === (1) 'the management interface is plain HTTP + unauthenticated on 9000' is FALSE - PLAN-23 activated management TLS and ManagementConfig has only ONE port, so mgmt is HTTPS-ONLY; PLAN-31 then made it a neutral gateway.yaml block. *** CONSEQUENCE, AND IT IS LOAD-BEARING FOR THIS EPIC: PLAN-V03-02's D1 SHRINKS to offline-JWT-scope auth alone. *** (2) 'neither the session/user-info endpoint nor the login-initiation path exists' is FALSE - PLAN-06 shipped both. (3) 'exactly FOUR reserved paths' is FALSE - PLAN-06 grew the list to SIX. (4) The 'Gated POST-first-landing' header carried by these specs has EXPIRED. (5) 'Depends on PLAN-05 and PLAN-06, both must land first' is SATISFIED - both shipped.

=== PER-PLAN CARRIES ===
PLAN-V03-01 (was PLAN-20) honeypot-deception - *** KEPT SEPARATE FROM THE SUBSTRATE PLAN DELIBERATELY: deception carries its own LEGAL AND OPERATIONAL REVIEW that a merge would bury. *** Its two HYPOTHESES (the substrate ban API, and the ECS/OCSF formatter) now resolve against ONE upstream plan instead of two, and that plan is in a DIFFERENT EPIC - so they are cross-epic claims and must be verified against landed code, never against a spec.
PLAN-V03-02 (was PLAN-21) api-inventory-endpoint - absorbs PLAN-V03-03. D1 SHRANK because of refuted claim (1) above: management is already HTTPS-only, so D1 is offline-JWT-scope auth alone. Re-cut from 8 deliverables to 6 on 2026-08-02, folding trailing tests into their parents and merging two duplicate documentation deliverables, so it already matches the release-track convention - do NOT re-split it on sight.
PLAN-V03-04 (was PLAN-41) helm-chart-real-cluster - THREE deliverables, from the PLAN-27 split. Operator decision, verbatim: 'Move the helm stuff to api-sheriff-next, we start with docker-compose for the alpha release.' *** IT CARRIES A HARD BLOCKER THAT IS NOT A BUNDLE VERSION BUMP: Helm chart YAML classifies as `unknown` in plan-marshall's path classifier, a phase-4 Q-Gate HARD ERROR. RE-VERIFY BY EXECUTING manage-execution-manifest._is_infrastructure_config_path OVER deployment/helm/*.yaml - NEVER by reading a version number. *** Delivered upstream as bundle round 4 item 1.

=== WATCHES ===
- *** INHERITED TOOLING AND PROJECT-CONFIG CONSTRAINTS FROM api-sheriff-roadmap - NOT FIXABLE BY ANY PLAN HERE, AND EVERY PLAN IN THIS EPIC RUNS UNDER THEM. *** Carried 2026-08-04 as ONE watch rather than five duplicated defect entries, so the copies cannot drift; the full text and evidence live in the api-sheriff-roadmap ledger under the numbers given. (31) HIGH AND THE DANGEROUS ONE: q_gate_validation 'once' routes Q-Gate findings to a review gate that plan_without_asking TRUE DISABLES, so BLOCKING findings reach task planning UNGATED and NOTHING REPORTS IT. The pairing occurs twice - phase-3-outline and phase-4-plan. On PLAN-27 it let through a hardening block that would not have started and a bucket misassignment that would have stripped the verify lane, both caught ONLY by manual operator intervention. The two settings are individually reasonable and JOINTLY UNSOUND. OWNER: a marshall-steward run - .plan/ config is outside every plan's write-boundary AND the orchestrator's. (22) HIGH: no plan runs the Docker IT suite pre-push, so IT feedback comes only from CI after push - verification_steps holds quality-gate, module-tests and coverage only, and the root resolve fails. DO NOT WORK AROUND IT VIA per_deliverable_build: the config layer ACCEPTS the entry but the standard forbids the placement and a non-zero exit is a hard STOP, so a deliverable touching only api-sheriff would HALT THE PLAN. Attempted and reverted 2026-07-30. (25) documentation.skills_by_profile.module_testing is EMPTY, so a task legitimately domained documentation under module_testing resolves the persona floor alone - no doc testing standards, no verification recipes - and NOTHING REPORTS IT. (30) the deep-lane component-assessment sink is EMPTY (a two-plan recurrence), so section 2.2 assessment-coverage is unevaluable on any deep-lane plan; an empty sink is indistinguishable from 'the pass ran and matched nothing'. (16)+(20) the main-branch deploy-snapshot check has NO SANCTIONED EXECUTION PATH - it is skipped on every PR by design, the check requires a by-merge-commit lookup, `ci checks status` accepts only --pr-number/--head and refuses main, and the carve-out forbids gh. Four-plus merge commits are unverified on that axis. *** CONSEQUENCE FOR THIS EPIC: a landing report here must record the post-merge check as OWED, never as complete, until the tooling changes. *** ALL FIVE ARE DELIVERED UPSTREAM AS BUNDLE MATERIAL. Re-check them at this epic's decompose rather than assuming a bundle bump fixed them - and VERIFY BY EXECUTING THE MECHANISM, never by reading a version number.

- STANDING CLAUSES INHERITED FROM api-sheriff-roadmap AND STILL BINDING: THREE-LAYER DOCS in the same PR; SONAR ZERO-FINDINGS with red a HARD STOP; NAMED LINE ITEMS survive outlining (this project has TWICE had named deliverables abstracted away); ACTIVATE-IT-IN-AN-IT (lesson 2026-07-25-15-001, which already recurred verbatim once); THE PLAN STOPS AT THE MERGE and the post-merge check is the ORCHESTRATOR'S; EVERY EMIT CARRIES AN EXPLICIT plan_id; MARK A CLAIM OBSERVED ONLY WITH A READ BEHIND IT; A GREEN SUITE IS NOT EVIDENCE FOR A BEHAVIOURAL CLAIM until its assertions are read.
- A CAPABILITY PRESENT IN A DEPENDENCY IS NOT A CAPABILITY OF THE PRODUCT. Earned 2026-08-04: doc/features-analysis.adoc asserted a PAR-driven sender-constrained flow because the ENGINE shipped ParClient while the gateway wired NOTHING. SETTLE EVERY COMPLETION CLAIM AT THE CALL SITE, NEVER AT THE IMPORT. Directly relevant here - the inventory and interop formats will lean on library capabilities the same way.
- IN-BODY PLAN-NN REFERENCES IN THESE SPECS WERE DELIBERATELY NOT REWRITTEN at the 2026-08-04 renumbering, because many point at api-sheriff-roadmap plans that keep their numbers. Resolve through the renumbering map in epic.md; a number absent from that map belongs to another epic and is unchanged.
- THE GATEWAY IS IMMUTABLE-AT-STARTUP BY DESIGN (ADR-0002). The inventory endpoint is a READ model - a mutating management API is out of scope and would contradict the posture the product is defined by.
- SECOND-ORDER COST OF PARALLEL SLOTS: concurrent Docker IT suites contend for local CPU in a repo with documented contention-driven IT startup flakes, and main is queue-gated - prefer a doc-only or build-light plan for a third slot.
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 0 archived
**Queue** (staged, in order):
1. PLAN-V03-01 (WS-01)
2. PLAN-V03-02 (WS-02)
3. PLAN-V03-03 (WS-02)
4. PLAN-V03-04 (WS-03)

<!-- END GENERATED: resume-summary -->

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
