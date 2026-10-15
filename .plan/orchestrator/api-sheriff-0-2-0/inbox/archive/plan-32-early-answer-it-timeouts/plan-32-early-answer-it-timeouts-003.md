envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-15T12:41:27Z

# Complement the 10 s request-body idle bound with a total-body bound, and give gRPC an idle bound

**Source:** PLAN-32 (kidicap-gateway-requirements, PR #466, merged 73233f1e) and a competitor comparison of seven gateways.

**Observation.** PR #466 adds a fixed 10 s request-body idle bound on proxied HTTP dispatch (408 REQUEST_BODY_TIMEOUT, upstream request reset, not counted against the breaker, not retried); gRPC dispatch is exempt. An idle-only bound restarts on every chunk, so a body that keeps trickling bytes below the bound is held for as long as it trickles; gRPC streams currently have neither an idle nor a total bound beyond the guard.

**How others do it.**
- Idle-between-reads model (ours): NGINX `client_body_timeout` (60 s, 408).
- Whole-request deadlines: HAProxy `timeout http-request` (408; covers the body only with `option http-buffer-request` / `wait-for-body`), Traefik `readTimeout` (60 s incl. body), Caddy `read_body` (no default), Envoy `request_timeout` (off by default because it breaks streaming).
- HAProxy's manual states explicitly that idle timers alone are not enough against slow senders.
- Nobody exempts gRPC by protocol: Envoy sets route `timeout: 0` for streams but keeps `stream_idle_timeout` (default 5 min, 408 before response headers) and `max_stream_duration`, honouring `grpc-timeout` up to a cap; HAProxy switches to `timeout tunnel`; Caddy has `stream_timeout`.

**Proposed.** Add a total-body deadline or a minimum-rate check next to the 10 s idle bound for non-streaming HTTP. Replace the blanket gRPC exemption with a long stream-idle bound (optionally honouring `grpc-timeout` up to a cap). Also closes the PLAN-32 code-review advisory gaps: a test for the gRPC exemption, a retry test that can fail for its named defect, and tests pinning that the pool wait is not counted and that the clock stops at send completion.
