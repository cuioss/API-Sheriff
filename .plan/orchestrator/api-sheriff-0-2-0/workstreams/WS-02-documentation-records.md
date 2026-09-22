# WS-02: Documentation and Records

epic: api-sheriff-0-2-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Reduce the documentation and ADR corpus to what is true, said once, at a length matching its weight.

## Surface

- **In scope**: the `doc/` tree structure, `doc/archive` removal, splitting oversized reference documents into the pages that already own each subject, and the ADR corpus content audit.
- **Out of scope**: ADR *numbering*, filename form and metadata blocks — the operator ruled these explicitly out (2026-08-03); touch them only where a merge or deletion forces it.

## Plans

- **PLAN-V02-03** — Documentation restructure.
- **PLAN-V02-04** — ADR corpus cleanup (content audit, not a hygiene pass).

## Status — 2026-08-09

- **Shipped**: **PLAN-V02-03** documentation restructure — #197 `89a3cfe`; **PLAN-V02-17** lessons into source — #200 `95dd566`
- **Remaining**: **PLAN-V02-04** (ADR corpus audit), **PLAN-V02-15** (BFF compose sample, blocked on V02-12)

*Epic-wide since these charters were written*: the build now fails on any compiler warning
(`showDeprecation` + `failOnWarning`, reactor-wide), so every remaining plan in this workstream must
migrate off a warned construct rather than suppress it. See each spec's `## Re-Grounded (2)` section.
