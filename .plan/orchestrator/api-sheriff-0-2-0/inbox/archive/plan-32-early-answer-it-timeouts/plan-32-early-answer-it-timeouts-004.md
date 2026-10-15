envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-15T12:41:29Z

# Upstream close after an early answer: consider FIN-first with an abortive fallback

**Source:** PLAN-32 (kidicap-gateway-requirements, PR #466, merged 73233f1e) and a competitor comparison of seven gateways.

**Observation.** After an early upstream answer or a client abort, the gateway resets the upstream HTTP/1.1 request and closes the connection abortively (SO_LINGER 0), per PLAN-30/ADR-0066 A1, to avoid local port exhaustion. The gateway frees its own socket and pool slot immediately. In PLAN-32's investigation an upstream whose TCP stack did not process that RST (body data still in flight) kept its side of the connection open until its own idle timeout. This is pre-existing on main. Also: ADR-0060 A1 / architecture.adoc say the stalled dispatch's upstream connection is reused after the idle bound; that holds only when no body byte was sent — a partly sent HTTP/1.1 request closes its connection.

**How others do it.**
- Whether to close: every product closes (never reuses) an upstream connection whose request body was not fully sent — NGINX (`!u->request_body_sent`), Envoy (by design; CVE-2026-48743 shows reuse of an incompletely-sent connection causes request desync), Go Transport (Traefik, Caddy), Reactor Netty (since 1.0.37 / 1.1.12). Our "close, never reuse" matches.
- How to close: NGINX/Kong close upstreams gracefully (their SO_LINGER 0 knob `reset_timedout_connection` is client-side only); Go uses a plain `Close()`; HAProxy sets SO_LINGER 0 only on sockets flagged at linger risk and clears the flag once drained, and its `option nolinger` docs warn the reset may be dropped by middleboxes. Bounded draining exists only client-side (NGINX `lingering_close` 30 s / 5 s; Envoy `delayed_close_timeout` 1 s).
- Port-exhaustion protection is mainly keep-alive pools (NGINX 60 s idle / 1000 requests, Kong pool 512, Traefik 2 idle/host, Caddy 32 idle/host).

**Proposed.** Evaluate FIN-first: shut down the write side, drain briefly (~1 s, byte-capped), fall back to SO_LINGER 0 only if the drain does not finish — keeping never-reuse and re-running PLAN-30's port-exhaustion reset tests and upload benchmarks. Correct the ADR-0060 A1 / architecture.adoc reuse wording.
