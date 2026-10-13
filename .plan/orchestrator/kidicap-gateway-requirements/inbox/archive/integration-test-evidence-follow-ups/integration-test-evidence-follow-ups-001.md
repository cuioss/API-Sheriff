envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=finding
created=2026-10-12T17:23:01Z

# DP-2 remainder: four upstream keys still parse and act on nothing

PLAN-27 (integration-test-evidence-follow-ups) removes `upstream.circuit_breaker` and records the
fixed breaker policy in ADR-0064. It deliberately leaves the other half of code-correctness DP-2
open, because changing it is outside the plan's one sanctioned production bend.

## What is still inert

Re-grounded against `GatewayEdgeRoute` on this branch:

- `upstream.connect_timeout_ms` and `upstream.read_timeout_ms` are bound into `UpstreamConfig` and
  read by nothing. `clientFor` builds the Vert.x `HttpClient` from a bare `HttpClientOptions` (only
  the egress-TLS settings are applied): no connect timeout, no idle timeout. The guard applies a
  fixed 30 s fault-tolerance timeout to every dispatch (`guardFor`, `withTimeout().duration(30, SECONDS)`).
- `upstream.retry.max_attempts` and `upstream.retry.idempotent_only` are bound and read by nothing.
  Only `retry.enabled` acts (`RouteTableBuilder.resolveRetryEnabled`); a route that enables retry
  gets `maxRetries(2)` with a 100 ms delay, gated by `DispatchStage::allowsRetryAfter`.

## Where the documentation still claims they act

- `doc/architecture.adoc`, Error Contract, `504` row: "Upstream timeout (`connect_timeout_ms` /
  `read_timeout_ms`)".
- `doc/adr/0008-data-plane-transport.adoc`: "Timeouts are transport options on the shared Vert.x
  client (connect/read per upstream tuple)" and the attempt count assembled from `upstream.retry`.
  Its Amendment A2 (added by PLAN-27) points at ADR-0064's "What this decision does not settle".
- `doc/configuration.adoc` and `doc/user/endpoint-routes.adoc` still document the four keys.

## What a successor plan has to decide

For each key: wire it (a runtime behaviour change, with tests that go red when the key is deleted)
or remove it pre-1.0 the way ADR-0064 removed `circuit_breaker`. DP-6 (the 30 s FT timeout strands
a pooled connection) is coupled to the timeout half and should be planned with it.
