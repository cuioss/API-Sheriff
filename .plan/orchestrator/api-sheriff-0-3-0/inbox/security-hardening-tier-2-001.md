envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=api-sheriff-0-3-0
kind=finding
created=2026-11-20T06:57:32Z

# Open operator action: file the drafted upstream Quarkus request-head-timeout request

Moved here from the `kidicap-gateway-requirements` inbox (message `security-hardening-tier-2-004`) on the operator's word of 2026-11-20; originally an open item of the wound-down epic `security-hardening-tier-2`. Please absorb it as a Watch (an operator action).

**Background.** Neither the transport nor the gateway bounds the time a connection may take to send a complete request head: the idle timer restarts on every byte, and the connection cap is one global counter shared by the terminated listeners. The operator decided on 2026-11-17 to state the gap and the deployment requirement (a front proxy with its own request-head timeout) rather than build a deadline. That statement shipped with PLAN-SEC2-04 (#635, deliverable 10) in the edge-security user documentation, the security-and-edge-hardening configuration page and the threat model.

**Open action.** The upstream request below was drafted by that plan and is not filed. The operator files it with Quarkus, then notes its URL here. The draft is kept verbatim here because its only other copy is a git-ignored decision log of an archived plan (`.plan/local/archived-plans/2026-11-19-sec2-04-leftover-hardening/logs/decision.log`, entry `33ad2c`), which a cleanup can lose.

**Retire when** the operator has filed the request (note its URL) or decided not to.

---

## Draft (verbatim, line breaks restored)

**Title:** quarkus-vertx-http: a request-head timeout and a per-connection hook for the main HTTP listeners
[LF] The main HTTP/HTTPS listeners have no bound on how long a request head may take to arrive. `idle-timeout` restarts on every byte, so a client that sends one byte within each window keeps a connection open with no request on it. `quarkus.http.limits.max-connections` is one counter (the `AtomicInteger` handed to `setupTcpHttpServer` for both the HTTP and HTTPS servers), so connections that never deliver a head hold its slots until they close. Applications cannot add a bound themselves: `HttpServerOptionsCustomizer` sees options only, the `HttpServer` instances are not exposed, and Vert.x keeps a single `connectionHandler` slot, which Quarkus already uses for the cap.
[LF] Request, either of:
(a) a `quarkus.http.limits.request-head-timeout` (and its `quarkus.management.*` counterpart) that closes a connection, or answers 408, when a complete request line plus headers (HTTP/1.x) or a complete header block (HTTP/2) has not arrived within the duration, counted from accept for the first request and from the previous response for later requests on a kept-alive connection;
(b) a supported per-connection hook (a CDI-observable or SPI callback receiving each accepted `HttpConnection` before the first request, able to close it, and composed with the max-connections handler instead of replacing it), so an application can enforce such a deadline or a per-source connection share itself.
