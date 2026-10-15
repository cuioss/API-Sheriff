# PLAN-V02-45: Bound What a Slow or Refused Request Body Can Hold

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-15 from inbox `plan-32-early-answer-it-timeouts-002` and `-003`. The kidicap session
> routed these findings of kidicap PLAN-32 (#466, `73233f1e`) here. Each finding carries a comparison
> of seven gateways. The operator ruled that both findings are staged openly.

## Objective

#466 put two new bounds on the edge. First, the inbound HTTP/2 connection window is sized to the maximum
number of concurrent streams times the initial stream window, with streams capped at 100 per connection
(6,553,500 bytes per connection). Second, a proxied HTTP dispatch that sees no request-body progress for
10 s is answered `408`; gRPC dispatch is exempt. Each bound leaves a gap open:

- **Refused streams may keep their buffered body.** A stream the gateway answers `503` at admission, or
  one still queued for an upstream connection, may keep the DATA the client already sent, up to one
  stream window, and that credit may never return to the connection window. The gateway-wide figure
  (the 2,048-stream admission cap × 64 KiB, about 128 MiB) only holds if those bytes are counted and
  released.
- **An idle-only bound restarts on every chunk.** A body that keeps trickling bytes just inside the 10 s
  bound holds its dispatch, and its upstream connection, for as long as it trickles. HAProxy's manual
  states plainly that idle timers alone are not enough against slow senders.
- **gRPC streams have no request-body bound at all**, apart from the guard.

Other gateways do it this way:
- NGINX answers an over-limit stream with `RST_STREAM(REFUSED_STREAM)` and skips DATA for unknown
  streams. Go and Netty return credit for closed or reset streams immediately.
- Per-connection receive budgets run from about 1 to 8 MiB:
  - Envoy edge recommendation: 1 MiB connection, 64 KiB stream, 100 streams.
  - HAProxy 3.2: a shared rx pool of about 1.6 MiB.
  - Go: 1 MiB.
  - NGINX: about 8 MiB effective.
  
  Nobody else sizes the connection window as streams × stream window.
- Whole-request deadlines are common: HAProxy `timeout http-request`, Traefik `readTimeout` (60 s),
  Caddy `read_body`.
- Nobody exempts gRPC by protocol. Envoy keeps `stream_idle_timeout` (5 min) and `max_stream_duration`
  for streams and honours `grpc-timeout` up to a cap.

## Deliverables

1. **Refused and queued streams release their body.** Verify the claim first. #431 already ends
   early-answered streams with `RST_STREAM(NO_ERROR)` (`edge/EarlyResponseEnd`, `edge/InboundStreamWindow`).
   Establish whether the `503` admission refusal and the queue-for-connection path go through that path
   or not. Where they do not, reset the stream (`REFUSED_STREAM` for admission refusals), return its
   buffered bytes to the connection window, and release them from the admission account. Write a test
   that holds buffered credit on a refused stream and fails without the release.
2. **Measure the connection window before changing it.** Benchmark a connection window decoupled from
   the stream count (about 1–2 MiB) against #466's derived window. Use the k6 upload-small and upload-large
   benchmarks and `Http2EarlyResponseResetIT`, which #466's window fixed. Change the derivation only if
   the measurement supports it, and record the numbers either way.
3. **A total-body bound next to the idle bound.** For non-streaming HTTP dispatch, add a total
   request-body deadline or a minimum-rate check alongside the 10 s idle bound. It answers `408` like the
   idle bound, is not counted against the breaker and is not retried. Settle at outline which of the two
   to add, and what its default is. Coordinate with kidicap `PLAN-29`, which decides whether the per-route
   `read_timeout_ms` is wired, so the two bounds do not overlap. Whichever plan runs second reads the
   other's result.
4. **gRPC gets a bound instead of an exemption.** Replace the blanket exemption in `GrpcDispatchStage`
   with a long stream-idle bound, optionally honouring `grpc-timeout` up to a cap. Settle the default at
   outline, with Envoy's 5 min as the reference point.
5. **Close the test gaps #466's code review left.** Add tests for: the gRPC behaviour (today's exemption
   has no test); a retry test that can fail for the defect it names; the pool wait not being counted
   against the idle bound; and the clock stopping at send completion.
6. **Record it.** Amend ADR-0060 (A1 states the idle bound) with the total bound and the gRPC bound; add
   a new ADR only if the outline says the change is larger than an amendment. Update `doc/architecture.adoc`,
   `doc/LogMessages.adoc` for any new record, and the threat model, including how this interacts with
   `gw-08`.

Split guard: 6 deliverables. This is a behaviour change, so the normal limits apply: 100 files and 150,000
diff characters.

## Claim Labels

- OBSERVED: the 6,553,500-byte window, the 100-stream cap, the 10 s idle bound answered `408`, and the gRPC exemption — #466's PR body and `GrpcDispatchStage.java` (passes no idle bound), merged at `73233f1e`
- HYPOTHESIS: streams answered `503` at admission, or queued for an upstream connection, keep their buffered DATA and credit — from inbox `-002`; not verified, and #431's `EarlyResponseEnd` may already cover part of it (verify at outline)
- HYPOTHESIS: a body trickling inside the 10 s idle bound holds its dispatch indefinitely — from inbox `-003`, follows from an idle-only design (verify at outline with a test)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/` (admission, `EdgeHardeningOptions`, `DispatchStage`, `GrpcDispatchStage`, `EarlyResponseEnd`, `InboundStreamWindow`) — the sub-package names follow `PLAN-V02-25`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/`
- HYPOTHESIS: `integration-tests/` (`Http2EarlyResponseResetIT`), `benchmarks/` (upload benchmarks) (verify at outline)
- OBSERVED: `doc/adr/0060-…adoc`, `doc/architecture.adoc`, `doc/LogMessages.adoc`, `doc/security-threat-model.adoc`

## Dependencies and Sequencing

- After `PLAN-V02-25`: the edge decomposition moves every class this plan edits. Re-ground the surface
  against its sub-packages.
- Overlaps with `PLAN-V02-06` D8 (HTTP/2 stream-abuse bounds, `gw-08`): same files and threat-model row,
  so run sequentially, never together. This plan owns the refused-stream release and the request-body
  bounds. V02-06 D8 keeps the reset rate, CONTINUATION, `HTTP2-Settings` and the h2→h1 reframing.
- Coordinates with kidicap `PLAN-29` (per-route upstream timeouts) on deliverable 3. That plan is in the
  other epic; this spec only records the dependency.
- Before `PLAN-V02-46` (upstream close), which touches the same relay code.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-45-request-body-and-h2-buffer-bounds.md" plan_id=plan-v02-45-request-body-and-h2-buffer-bounds
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
