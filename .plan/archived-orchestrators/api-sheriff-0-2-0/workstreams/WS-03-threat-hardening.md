# WS-03: Threat Hardening

epic: api-sheriff-0-2-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Close the reconnaissance surface: stop leaking stack identity in responses, stop leaking endpoint existence through status codes, and detect enumeration campaigns in-process.

## Surface

- **In scope**: response-header hygiene at the relay; the uniform-404 existence oracle; a per-client detection substrate (leaky bucket / sliding window) and the structured attack-signal classification built on it.
- **Out of scope**: per-request input validation (shipped); the deception layer, which consumes this substrate but ships in `api-sheriff-0-3-0`.

## Plans

- **PLAN-V02-05** — Response hygiene (`Server` / `X-Powered-By` passthrough leak).
- **PLAN-V02-06** — Enumeration hardening. *Builds the detection substrate.*
- **PLAN-V02-07** — Threat classification. *Decided to merge INTO PLAN-V02-06 — see the epic ledger; the merged spec is deliberately not yet authored.*

[IMPORTANT]
**The detection substrate ships a release ahead of its main consumer.** Honeypot/deception (`PLAN-V03-01`) is the substrate's principal consumer and lands in 0.3.0. Operator decision 2026-08-04, taken with that cost stated: design the substrate general-purpose here, because retrofitting it for a consumer in the next version is the more expensive order.

## Status — 2026-08-09

- **Shipped**: none yet
- **Remaining**: **PLAN-V02-13** (head of the chain), then **PLAN-V02-06**, then **PLAN-V02-07**; **PLAN-V02-05** independent

*Epic-wide since these charters were written*: the build now fails on any compiler warning
(`showDeprecation` + `failOnWarning`, reactor-wide), so every remaining plan in this workstream must
migrate off a warned construct rather than suppress it. See each spec's `## Re-Grounded (2)` section.
