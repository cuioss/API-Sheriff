# PLAN-SEC-01: Edge transport hardening — SNI ClientHello deadline, WebSocket upgrade gate, relay-failure upstream reset

epic: security-hardening-tier-1
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-01-edge-transport-hardening.md` and is queued as one row file,
> `queue/PLAN-SEC-01.json`, in the epic ledger. The orchestrator EMITS the command below; it never
> launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer
> and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

Close three availability gaps at the gateway's inbound transport edge, found in the 2026-11-07
ground-truth check of the security notes (code reading at `main` `7f5c61af`):

1. **M7 (Medium, rank 1, effort S).** The SNI passthrough front listener holds a connection that
   never completes a ClientHello indefinitely, with no cap: an unauthenticated slowloris against any
   deployment that configures `tls.passthrough_sni`.
2. **M2 (Medium; High where a `require: none` WebSocket route exists; rank 2, effort M).** A
   `protocol: websocket` route dials the upstream WebSocket BEFORE validating that the client
   request is an upgradable HTTP/1.1 request. On HTTP/2 the client upgrade always fails and the
   gateway writes no response at all, leaving the stream hanging outside the admission cap while
   the upstream absorbs a full handshake per request.
3. **C3 (Low; Medium with unbounded streaming upstreams; rank 6, effort S).** After the upstream
   response has been handed to the relay, a client that leaves never causes the upstream exchange
   to be reset: the upstream body is drained to its end, holding its pooled connection — forever
   for SSE / streaming responses.

Each fix ships with a regression test that fails against the unfixed code.

## Deliverables

1. **SNI front listener: ClientHello deadline, undecided-connection cap, incremental parse (M7).**
   - In `SniFrontListener.Incoming`, arm a timer at connection start (proposed 10 s, a constant —
     not a new config key unless outline finds a reason) that closes the socket unless a routing
     decision was made; cancel it when `route()` decides.
   - Cap the number of undecided connections (an `AtomicInteger`; close new connections beyond the
     cap at once). Derive the cap at outline (for example from the admission cap) and document it.
   - Stop re-copying and re-parsing the whole buffer on every chunk
     (`parser.parse(buffer.getBytes())`): parse only once the TLS record length is complete, or
     keep incremental parser state.
   - Set an idle timeout on both legs of `PassthroughRelay` once it relays.
   - Tests (`SniFrontListenerTest`): a silent connection and a half-sent ClientHello are both closed
     within a (test-shortened) deadline; a complete ClientHello delivered in slow chunks within the
     deadline is still routed (matched positive control); connections beyond the cap are closed.
2. **WebSocket upgrade gate before dialling the upstream (M2).**
   - In `TerminalDispatch.dispatchWebSocket`, BEFORE session tracking, `tryAcquireWebSocketRelay`
     and `relay()`, refuse any request that is not a valid HTTP/1.1 WebSocket upgrade: HTTP version
     `HTTP_1_1`; `Connection` contains the token `upgrade` (token list, case-insensitive);
     `Upgrade` contains `websocket`; `Sec-WebSocket-Version: 13`; a `Sec-WebSocket-Key` present.
     Answer `426 Upgrade Required` with `Upgrade: websocket` (or `400` for a malformed key/version
     — decide at outline) through `ResponseEnds.reject`, so the one end rule, the problem document
     and the security headers apply. Vert.x's `HttpUtils.canUpgradeToWebSocket` is internal API:
     write a small gateway-owned helper instead.
   - Tests: an HTTP/2 GET to a WebSocket route is answered 426/400 within a short deadline and the
     stub upstream records ZERO handshakes; an HTTP/1.1 GET without `Upgrade` likewise; a valid
     upgrade still relays (positive control).
3. **WebSocket client-upgrade failure branch answers the client (M2).**
   - In `WebSocketRelayStage` (`toWebSocket().onFailure`, around the `lifetime.releaseAdmission()`
     call): when `!ctx.response().ended() && !ctx.response().headWritten()`, end the response through
     `ResponseEnds.endResponse` (502) before releasing. Correct the comment "there is no HTTP response
     to render anymore" and the class Javadoc claiming the same.
   - Fix the existing test in `WebSocketRelayStageTest` that states a plain GET "ends no response":
     against Vert.x 4.5 it is false for HTTP/1.1 (Vert.x answers 400/405/426 itself) and true only
     for HTTP/2.
4. **Relay failure resets the upstream exchange (C3).**
   - When the relay reports a failure after the hand-over (client gone, write failure), reset the
     upstream request (`upstream.request().reset()` — a no-op once the response has ended). Place it
     in `TerminalDispatch.failRelay`'s relay-failure path or in `ResponseStage.pipeThenEnd`'s failure
     path (decide at outline; one site, not both). Optionally also on the client response's
     close/reset.
   - Test: an endlessly chunking upstream behind an upstream pool of 1; the client reads the head
     and closes; assert the upstream request is reset within ~1 s and a follow-up request is served
     on the freed connection.
5. **Documentation for the changed behaviour.** Correct the `TerminalDispatch` Javadoc that states
   "the upstream exchange is not reset on that path"; ADR-0066's wording that treats the WebSocket
   path as never being an HTTP/2 response end; and the threat-model rows for the WebSocket upgrade
   (GW-09) and slowloris (GW-07) so they describe the new behaviour (mechanism + test name). Add the
   SNI front deadline to the passthrough documentation if a user-facing page describes that listener.

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims; breaking behaviour changes are allowed.
- No new dependency. Logging through `CuiLogger` with `%s`; any new INFO/WARN/ERROR record is a
  `LogRecord` documented in `doc/LogMessages.adoc` with the next free identifier, derived at write
  time and never assumed. Never log request data beyond what existing records log.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every fix starts with a test that FAILS
  against the unfixed code; a fix whose test cannot be made to fail is a refuted premise and loops
  back to re-scope (record it in the plan's inbox message).
- Every gateway-written answer goes through the one end rule (`ResponseEnds`, ADR-0066).
- Do not touch `edge/upstream/**` (PLAN-SEC-02's surface).
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.

## Claim Labels

- OBSERVED: `SniFrontListener.start()` creates its server with `vertx.createNetServer()` and default options (idle timeout 0); `Incoming` arms no timer and has no undecided-connection cap — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `start` / `Incoming`
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base c85817d9^1 tls/SniFrontListener.java start() createNetServer() default options; Incoming.begin arms no timer, no cap (three-layer read, re-read)
- OBSERVED: `Incoming` re-parses the full accumulated buffer on every chunk, up to `ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES` (32 KiB) — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `Incoming`
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base tls/SniFrontListener.java Incoming.onData parser.parse(buffer.getBytes()) every chunk; MAX_CLIENT_HELLO_BYTES 32 KiB
- HYPOTHESIS: `PassthroughRelay` sets no idle timeout on either leg — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` § `PassthroughRelay` (verify-at-outline)
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base tls/PassthroughRelay.java and TlsEdgeProducer: createNetServer()/createNetClient() default options, no idle timeout; fixed via applyIdleTimeout in PR #582
- OBSERVED: the WebSocket dispatch path checks no HTTP version and no `Upgrade` header before the upstream dial; `WebSocketProtocolProcessor` gates on GET only — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` § `dispatchWebSocket` and `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/WebSocketProtocolProcessor.java`
  - verdict: contradicted | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: yes | evidence: dial half holds (base dispatchWebSocket has no version/Upgrade check) but WebSocketProtocolProcessor GET-only is not enforced at request time: allowed_methods falls back to all methods (config/PolicyBlock.java:96), so non-GET reached the dial; absorbed by PR #582's gate 405 branch, not by a spec edit
- OBSERVED: `WebSocketRelayStage` dials the upstream first and then calls `ctx.request().toWebSocket()`; its `onFailure` branch releases admission and closes the upstream with 1011 but writes no HTTP response — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStage.java` § `relay` / `toWebSocket().onFailure`
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base edge/relay/WebSocketRelayStage.java dial-then-toWebSocket; onFailure released admission and closed upstream 1011 with no HTTP response
- HYPOTHESIS: Vert.x 4.5.34 `Http2ServerRequest.toWebSocket()` returns a failed future and writes nothing, so on HTTP/2 the client stream is never answered — confirm/refute at vertx-core 4.5.34 `io/vertx/core/http/impl/Http2ServerRequest.java` § `toWebSocket` (verify-at-outline; the regression test settles it)
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: vertx-core 4.5.34 Http2ServerRequest.toWebSocket() returns context.failedFuture(...) and writes nothing (sources jar, re-read)
- HYPOTHESIS: on HTTP/1.1, Vert.x answers a GET without a valid upgrade itself (400/405/426) from `Http1xServerConnection` — confirm/refute at vertx-core 4.5.34 `io/vertx/core/http/impl/Http1xServerConnection.java` § `toWebSocket` (verify-at-outline)
  - verdict: contradicted | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: yes | evidence: vertx-core 4.5.34 Http1xServerConnection.createHandshaker checks Connection contains upgrade (400), GET (405), version (426) but never Upgrade: websocket or Sec-WebSocket-Key, and Netty assumes V00 without a version; absorbed by PR #582's gate applying to HTTP/1.1 too
- OBSERVED: after the hand-over, a relay failure goes to `failRelay` and the upstream exchange is not reset; the class Javadoc states so — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` § class Javadoc (relay paragraph) / `failRelay`
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: base edge/terminal/TerminalDispatch.java relayOnEventLoop onFailure -> failRelay only; Javadoc said upstream exchange not reset; PR #582 adds the single reset site at TerminalDispatch.java:625
- HYPOTHESIS: on a client write failure, Vert.x `PipeImpl` resumes the source and drops its handlers, so the upstream body is read to its end and discarded while the pooled connection stays leased — confirm/refute at vertx-core 4.5.34 `io/vertx/core/streams/impl/PipeImpl.java` § `to` (verify-at-outline; the regression test settles it)
  - verdict: corroborated | checked_at: c85817d9 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: vertx-core 4.5.34 streams/impl/PipeImpl.to() nulls src handlers and resumes src on WriteException, so the upstream body drains while the pooled connection stays leased; deliverable 4 stands
- Verify-first clause: before scoping deliverable 4, settle whether the reset belongs in `TerminalDispatch.failRelay` or `ResponseStage.pipeThenEnd` by reading both against the hand-over contract; a refutation of the drain hypothesis drops deliverable 4 and is reported, not silently skipped.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` — `start`, `Incoming`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` — leg idle timeout
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ClientHelloSniParser.java` — only if incremental parsing needs parser state (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` — `dispatchWebSocket`, `failRelay`, class Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStage.java` — `relay` failure branch, class Javadoc
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/relay/ResponseStage.java` — `pipeThenEnd` failure path, only if the C3 reset is placed there (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/relay/WebSocketRelayStageTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeHttp2AbuseTest.java` — HTTP/2 WebSocket-route test, if the raw-frame rig lives only there (verify-at-outline)
- OBSERVED: `doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — GW-07, GW-09 rows
- HYPOTHESIS: `doc/LogMessages.adoc` — only if a new LogRecord is added (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: PLAN-SEC-02 and PLAN-SEC-03 share only documentation — `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (two files; `next` asks the operator).
- Adjacent to: `edge/upstream/DispatchStage.java` (PLAN-SEC-02) — the C3 reset acts on the upstream request handed over by the dispatch, but must not change `DispatchStage`; `edge/end/EarlyResponseEnd.java` (HTTP/2 quiet rule, a tier-4 finding) — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-01-edge-transport-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
