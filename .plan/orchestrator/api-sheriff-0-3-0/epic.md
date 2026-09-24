# Epic: API Sheriff 0.3.0 — New Capabilities

slug: api-sheriff-0-3-0

> Ledger document for one epic under `.plan/orchestrator/api-sheriff-0-3-0/`. The layout and
> authority contract live in the central standard — see
> `persona-plan-orchestrator/standards/orchestration-model.md`. The machine authority is the ledger
> files — `status.json` (header), `queue/{PLAN-ID}.json` (rows) and `resume_anchor.md`; any statement
> here that conflicts with them is stale prose. This document is hand-written narrative only and
> describes the epic's **current state** — no history is retained.

## Vision

Add the capabilities API Sheriff does not yet have: an active **deception layer** that wastes an
attacker's time and yields high-confidence signal, a first-class **API inventory** the management
plane can serve in the formats the ecosystem consumes, and a **Helm chart** verified against a real
cluster rather than by inspection.

**Scope**: net-new capability only. Cleanup, consolidation and hardening of what already ships
belongs to `api-sheriff-0-2-0`, which this epic depends on.

## START HERE

Generated — see `queue-view.md` (rendered from the ledger by `orchestrator regenerate-view`; never
hand-edited).

## Ordered Queue

The table itself is generated into `queue-view.md`; only the hand-written annotation zone lives here.

### Queue annotations

- **PLAN-V03-01** — double-gated: do not emit until api-sheriff-0-2-0 ships PLAN-V02-06 (and V02-07),
  then re-ground a SECOND time against what actually landed (see § Cross-Epic Dependency).
- **PLAN-V03-02** — merged spec (V03-03 folded in 2026-09-24); 8 deliverables, two PRs. Launch gate is
  the epic-level double-gate below, not its own dependencies.
- **PLAN-V03-04** — DO NOT LAUNCH: plan-marshall's execution-manifest classifier still has no
  helm/Chart.yaml glob (re-verified 2026-09-24 against `_manifest_core.py` @ 9913740d9).

## Workstreams

| Workstream | Title | Plans |
|---|---|---|
| WS-01 | Deception and Detection | PLAN-V03-01 |
| WS-02 | Management-Plane Introspection | PLAN-V03-02 (PLAN-V03-03 folded in, superseded) |
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
- **2026-09-24 — PLAN-V03-03 folded INTO PLAN-V03-02** (cleanup A5), executing the 2026-07-27
  operator decision once both specs were re-grounded. Merged spec carries 8 deliverables — unsplit by
  recorded rationale (operator merge decision, two-PR delivery, 12-deliverable ceiling). V03-03's
  spec is kept as the audit record; its row is `superseded`.
