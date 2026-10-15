envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-15T12:41:24Z

# Per-route upstream timeout keys parse but are not applied

**Source:** PLAN-32 (kidicap-gateway-requirements, PR #466, merged 73233f1e) — code review and a competitor comparison of seven gateways.

**Observation.** The per-route upstream keys `connect_timeout_ms` and `read_timeout_ms` are accepted by the config loader but no runtime reader applies them; every dispatch runs under the fixed 30 s upstream guard (ADR-0064 already lists this as open). A key that parses but does not act is the case CLAUDE.md's "a configuration key that parses is not a configuration key that acts" rule targets.

**How others do it.**
- Per-route connect/read overrides are mainstream: Envoy (route `timeout`, default 15 s incl. retries; `per_try_timeout`; cluster `connect_timeout` 5 s), Spring Cloud Gateway (route `metadata` `connect-timeout` / `response-timeout`), HAProxy (`http-request set-timeout server|connect`), Kong only via an Enterprise plugin. NGINX scopes per `location`, Traefik per service (`serversTransport`), Caddy per `reverse_proxy` block.
- Connect defaults range 3 s (Caddy) – 5 s (Envoy) – 30 s (Traefik, SCG) – 60 s (NGINX, Kong).
- HAProxy is the precedent for unapplied/missing timeouts: it starts but warns at boot. A Kong report shows the same silently-ignored route timeout we have.

**Proposed.** Either wire both keys into dispatch (connect = upstream connect; read = time to first response byte/headers, default = the existing 30 s guard, expiry → 504, counted like the guard today) or reject/WARN at boot until they act. Add one test per key that fails when the key is deleted. Owner suggestion: the edge/config refactor line (V02-24..26 successors) or PLAN-29 (upstream inert keys), whichever lands first.
