# WS-01: Deception and Detection

epic: api-sheriff-0-3-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Turn the detection substrate delivered in 0.2.0 into an active deception layer that wastes an attacker's time and yields high-confidence attack signal.

## Surface

- **In scope**: the honeypot / decoy surface and its response behaviour, built on the per-client detection substrate from `api-sheriff-0-2-0` WS-03.
- **Out of scope**: the substrate itself, which ships in 0.2.0; reimplementing external infrastructure (CrowdSec, SIEM, WAF) — the gateway emits to these and never replaces them.

## Plans

- **PLAN-V03-01** — Honeypot / deception layer.

**Hard dependency:** the substrate `PLAN-V02-06` builds. Do not start this until 0.2.0 has shipped it, and re-ground against what actually landed rather than against the substrate's staged spec.
