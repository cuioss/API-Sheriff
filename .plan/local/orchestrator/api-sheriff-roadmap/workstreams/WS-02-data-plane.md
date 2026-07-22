# WS-02: Data Plane

epic: api-sheriff-roadmap

> Charter document for one workstream — a coherent slice of the epic with its own goal
> and surface. Lives at `workstreams/WS-02-data-plane.md` and is tracked in the epic
> `status.json` `workstreams[]` field. See
> `persona-marshall-orchestrator/standards/orchestration-model.md` for the tier contract.

## Charter

Build the gateway's core request path: anchor-scoped policy config (roadmap 03), the full
HTTP request pipeline (roadmap 04), WebSocket/gRPC protocol processors (roadmap 05), and the
TLS edge with SNI passthrough + mTLS (roadmap 06). Closes when the ADR-0002 initial-scope
protocol promise (Variant 1) and the TLS edge are delivered.

## Scope

- In scope: `gateway.yaml` anchors + env substitution, route table, inbound filtering, bearer
  validation, forward policy, dispatch/retry/circuit-breaker, events/metrics/logging/error edge,
  WebSocket + gRPC processors, SNI split + mTLS termination, related ITs and benchmarks.
- Out of scope: BFF/session features (WS-03), release hardening (WS-04), rate limiting
  (explicitly off the roadmap).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-02-endpoint-anchors | shipped | Roadmap plan 03 — PR #74 squash-merged 2026-07-18 (a9fd717) |
| PLAN-03-request-pipeline | shipped | Roadmap plan 04 — PR #76 squash-merged 2026-07-19 (9c47959) |
| PLAN-09-comparative-benchmark | staged | NEW (04b) — k6 replaces wrk repo-wide + APISIX reference stack + 6-aspect comparison; BEFORE PLAN-04 (harness dependency) |
| PLAN-04-protocol-processors | staged | Roadmap plan 05 — WebSocket + gRPC |
| PLAN-05-tls-edge | staged | Roadmap plan 06 — SNI passthrough + mTLS |

## Sequencing and Surface Notes

- Hard edges: 03 → 04 → 05; 06 depends on 04 but is independent of 05.
- Roadmap 05 and 06 are mutually independent — candidates for surface-disjointness parallel
  launch (05 touches protocol processors/upstream h2; 06 touches the accept path/L4 relay);
  verify disjointness at emit time.
- Roadmap 04 requires approval-gated dependencies: `cui-http:2.1.0` and
  `quarkus-smallrye-fault-tolerance` (operator approval needed inside that plan).
