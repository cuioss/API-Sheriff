# WS-01: Architecture and Code Quality

epic: api-sheriff-0-2-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Settle the framework-agnostic architecture question and bring the code to current Java and Lombok idiom. This workstream exists because several apparently-separate findings are one architectural decision working as designed — settling the decision is the precondition for the rest.

## Surface

- **In scope**: ADR-0005 and its supersession, the arch gate that enforces it, hand-rolled components that exist only because the ADR prohibited the platform equivalent, Java-idiom and deprecation sweeps.
- **Out of scope**: documentation structure (WS-02); anything that changes runtime behaviour at the edge (WS-03).

## Plans

- **PLAN-V02-01** — ADR-0005 reversal / Quarkus adoption. *Load-bearing: run it first and alone.*
- **PLAN-V02-02** — Java idiom sweep.

## Status — 2026-08-09

- **Shipped**: **PLAN-V02-02** Java idiom sweep — #198 `e343404`; **PLAN-V02-16** image metadata fidelity — #199 `aeb80c5`
- **Remaining**: **PLAN-V02-01** (load-bearing, runs alone, `launched` and not started), **PLAN-V02-14** (offline config validation)

*Epic-wide since these charters were written*: the build now fails on any compiler warning
(`showDeprecation` + `failOnWarning`, reactor-wide), so every remaining plan in this workstream must
migrate off a warned construct rather than suppress it. See each spec's `## Re-Grounded (2)` section.
