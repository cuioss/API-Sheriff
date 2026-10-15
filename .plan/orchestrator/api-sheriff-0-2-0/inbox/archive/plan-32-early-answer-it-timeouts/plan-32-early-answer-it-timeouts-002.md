envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-15T12:41:26Z

# Release buffered request-body credit for HTTP/2 streams we refuse or answer 503

**Source:** PLAN-32 (kidicap-gateway-requirements, PR #466, merged 73233f1e) and a competitor comparison of seven gateways.

**Observation.** PR #466 sizes the inbound HTTP/2 connection window to max concurrent streams × initial stream window, with streams capped at 100 per connection (6,553,500 bytes per connection). Streams the gateway answers 503 at admission, or that are still queued for an upstream connection, are not reset, so DATA the client already sent stays buffered up to the stream window and its flow-control credit is not returned. The gateway-wide budget is bounded by the 2048-stream admission cap × 64 KiB (~128 MiB), but that accounting only holds if refused streams' bytes are counted and released.

**How others do it.**
- Per-connection budgets: Envoy's recommended edge config 1 MiB connection window / 64 KiB stream / 100 streams (built-in defaults are 24 MiB / 16 MiB / 1024); HAProxy 3.2 a shared per-connection rx pool (`tune.h2.fe.rxbuf`, ~1.6 MiB) with stream windows following the space left; Go net/http2 (Traefik, Caddy) 1 MiB connection and stream receive windows, 250 streams; Netty/Reactor Netty 65,535 unless raised; NGINX ~128 × 64 KiB ≈ 8 MiB effective. Nobody else sizes the connection window as streams × stream window.
- Refused/closed streams: NGINX sends RST_STREAM(REFUSED_STREAM) over its limit and skips DATA for unknown streams; Go and Netty credit back bytes for closed/reset streams immediately.
- Process-wide: only Envoy (overload manager: heap/cgroup thresholds, 503 new requests, reset the streams holding the most buffered memory) and HAProxy (`tune.buffers.limit`) bound memory globally.

**Proposed.** Send RST_STREAM for streams answered 503/refused at admission and return their credit to the connection window and the admission account. Benchmark a connection window decoupled from the stream count (~1–2 MiB) against upload-small/large before changing it. Owner suggestion: edge hardening (EdgeHardeningOptions / admission).
