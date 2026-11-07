# WS-05: Integration and Test Fidelity

epic: api-sheriff-0-2-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Make the integration with `token-sheriff` and the test strategy honest: one trust mechanism rather than a process-global override, readiness signals that mean what they claim, and a decided home for resource-exhaustion testing.

## Surface

- **In scope**: the client engine's trust wiring through the existing logical trust-profile mechanism; the parallel token-validation surface and its health coverage; where soak-shaped resource-exhaustion detection belongs.
- **Out of scope**: the FAPI conformance work (WS-04), which changes what the client does on the wire rather than how it is wired.

## Plans

- **PLAN-V02-09** — Token-Sheriff integration fidelity.
- **PLAN-V02-10** — Per-client TLS trust (delete the process-global truststore override).
- **PLAN-V02-11** — Resource-exhaustion test home. *Answers a design question and implements the answer.*

## Status — 2026-08-09

- **Shipped**: none yet
- **Remaining**: **PLAN-V02-09** (now six deliverables), **PLAN-V02-10** (single deliverable, UNBLOCKED), **PLAN-V02-11** (owns Open Defect (14) / issue #201)

*Epic-wide since these charters were written*: the build now fails on any compiler warning
(`showDeprecation` + `failOnWarning`, reactor-wide), so every remaining plan in this workstream must
migrate off a warned construct rather than suppress it. See each spec's `## Re-Grounded (2)` section.
