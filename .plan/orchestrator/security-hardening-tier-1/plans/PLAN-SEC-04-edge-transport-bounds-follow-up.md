# PLAN-SEC-04: Edge transport bounds follow-up — front cap fairness and visibility, dial-phase bound, client-gone relay reset, gate parity, passthrough idle policy

epic: security-hardening-tier-1
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-04-edge-transport-bounds-follow-up.md` and is queued as one row file,
> `queue/PLAN-SEC-04.json`, in the epic ledger. The orchestrator EMITS the command below; it never
> launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer
> and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

Harden the transport bounds PLAN-SEC-01 introduced (PR #582, `main` `c85817d9`). PLAN-SEC-03
(PR #581, `main` `3eef748b`) has since changed `PassthroughRelay`, `TlsEdgeProducer` and
`SniFrontListener` too (relayed-peer attribution through the new `RelayedPeerRegistry`); read the
`tls/` package at current `main`, not at `c85817d9`. The landing
analysis of that PR (`landings/PLAN-SEC-01.md`) read the merged code and found five places where a
new bound is incomplete, too coarse or silent. This plan closes them and pays off the
test-coverage advisories the PLAN-SEC-01 lane left open.

The detailed observations are recorded in the operator's git-ignored local security notes
(entry dated 2026-11-09, "PLAN-SEC-01 landing review"). Ask the operator for them at outline if you
need more than this spec states. Do not copy them into any tracked text — PR body, commit
message, documentation or inbox message — until the corresponding fix is on the branch.

Each fix ships with a regression test that fails against the unfixed code.

## Deliverables

1. **Front undecided-connection cap: fairness and visibility.**
   - Make cap saturation observable at the default log level: a WARN `LogRecord` (rate-limited, so
     a saturated cap cannot flood the log) and a counter metric if the project's metrics surface
     offers one.
   - Add a per-source share of the undecided-connection cap, so one source cannot hold the whole
     cap. Key the source with the existing `SourceKeys` canonicalisation if it is reachable from
     the `tls` package without an edge-internal dependency; otherwise key on the peer address and
     record the decision.
   - Tests: a saturated cap emits the WARN record once per rate window; one source filling its share
     does not stop a second source from being accepted (matched positive control).
2. **Bound the dial phase of a passthrough relay.**
   - Set an explicit connect timeout on the passthrough dial client (`TlsEdgeProducer.relayClientOptions`),
     derived and documented next to the ClientHello deadline. Do not rely on the Vert.x default.
   - PR #581 (PLAN-SEC-03, `main` `3eef748b`) already notices a client that leaves during the dial
     (`PassthroughRelay.relay` records it with a temporary close handler) and closes the late backend
     leg at once. Its regression test, if missing, belongs here. What remains: the dial itself is not
     abandoned when the client leaves, so the buffered ClientHello and the dial are held until the
     connect completes or times out. Bound that with the connect timeout above, and release the
     buffer when the client is known to be gone.
   - Decide at outline whether connections in the dial phase count against a bound (the existing
     cap or a separate one), and document the decision in the SNI-front Javadoc and
     `doc/development/tls-edge.adoc`.
   - Tests: a dial to a backend that never answers is abandoned within the connect timeout; a
     client that closes during the dial leaves no backend leg open (pin the PR #581 behaviour if no
     test does yet).
3. **Reset the upstream exchange when the client goes, not only on the next write.**
   - Register a close (and reset) handler on the client response for the HTTP relay after the
     hand-over, so a client that leaves resets the upstream request even when the upstream is
     quiet. Keep exactly ONE reset path: route the close signal into the existing reset site in
     `TerminalDispatch` rather than adding a second `reset()` call.
   - Tests: an upstream that sends its head and then stays silent (no chunks), with the client
     closing after the head, is reset within a short bound, and its pooled connection serves a
     follow-up request. Use an upstream pool of 1 so the freed-connection claim is pinned by one
     request (this also settles PLAN-SEC-01's accepted residual D4.C2).
4. **Gate parity with the Vert.x upgrade preconditions.**
   - Extend `WebSocketUpgradeGate` so a request that Vert.x would refuse at `toWebSocket()` on
     HTTP/1.1 is refused BEFORE the dial: the request-body size bound and an unparseable
     Host/location. Read the exact preconditions from vertx-core 4.5.34
     `Http1xServerRequest.webSocket()` at outline and mirror them. Do not use Vert.x internal API.
   - Tests: each mirrored precondition is refused with zero upstream handshakes; a valid upgrade still
     relays (positive control).
5. **Passthrough relay idle policy.**
   - The derived relay idle timeout now applies to every passthrough backend. Decide at outline,
     with the operator if the choice is not clear, between (a) an explicit configuration key for
     the passthrough relay idle timeout with the derived value as the secure default, and
     (b) keeping the derived value and documenting it as fixed. Either way, settle how
     `quarkus.http.idle-timeout=0` feeds the derivation, and make sure it never yields a value
     shorter than an inner bound the operator configured.
   - If a key is added, it follows the "a configuration key that parses is not a configuration key
     that acts" rule: a test fails when the key is deleted.
   - Tests: the derivation for an idle timeout of `0`; the key, if added, both parses and acts.
6. **PLAN-SEC-01 test-coverage advisories.**
   - ADR-0066: name the 405 refusal next to the 426/400 refusal.
   - Test the `onStartup` wiring of the relay idle timeout (assert the timeout reaches the front
     server and the dial client), and the `applyIdleTimeout` HOURS / DAYS branches and its final
     throw.
   - Test the security headers and `Set-Cookie` lines on the registry-full and budget 503 refusals.
   - Give the `TlsPassthroughIT` deadline assertions a lower bound (closed no earlier than the
     deadline minus a small margin).
   - Test that the undecided slot is returned on the deadline path and on the close/error paths, not
     only on the route decision.

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility shims; breaking behaviour changes are allowed.
- No new dependency. Logging through `CuiLogger` with `%s`, exception first; any new INFO/WARN/ERROR
  record is a `LogRecord` documented in `doc/LogMessages.adoc` with the next free identifier,
  derived at write time and never assumed. Never log request data, ClientHello bytes or source
  addresses beyond what existing records log.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every fix starts with a test that FAILS
  against the unfixed code; a fix whose test cannot be made to fail is a refuted premise and loops
  back to re-scope (record it in the plan's inbox message).
- Every gateway-written answer goes through the one end rule (`ResponseEnds`, ADR-0066).
- No Vert.x internal API.
- Do not touch `edge/upstream/**` (PLAN-SEC-02's surface) or `bff/**` (PLAN-SEC-03's surface).
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.
- Deliverable count: 6, at the presumptive split threshold. Kept as one plan because every
  deliverable is a follow-up on the same PLAN-SEC-01 surface (`tls/` and `edge/terminal/`), and
  splitting would serialise plans on the same classes. This is within the project's authorised
  per-plan deliverable ceiling.

## Security Findings

Possible weaknesses this plan finds that it does not fix on its branch are NOT written into any
tracked text (PR body, commit message, documentation, inbox message). Append a dated entry to the
git-ignored `.plan/local/security_notes.adoc` instead — plan id, location, what was observed, why
it was not fixed — and say only that a local note was recorded. Fixed findings may be described
once the fix is on the branch.

## Claim Labels

- OBSERVED: `SniFrontListener.onConnect` refuses a connection beyond `MAX_UNDECIDED_CONNECTIONS` with a DEBUG log only, and the cap has no per-source share — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `onConnect` (main `c85817d9`)
  - verdict: corroborated | checked_at: 47e73a15 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: At 6b86cde3 (pre-merge) SniFrontListener.onConnect refused beyond the cap at DEBUG only, no per-source share; PR #589 added the share, WARN LogRecord ApiSheriff-146 and refusal counters
- OBSERVED: `Incoming.settle` returns the undecided slot at the route decision, before the passthrough dial starts — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `Incoming.settle`
  - verdict: corroborated | checked_at: 47e73a15 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: At 6b86cde3 Incoming.settle released the slot at the route decision before the dial; at 47e73a15 the slot is released only when the dial phase settles (SniFrontListener.java:584-593)
- OBSERVED: `TlsEdgeProducer.relayClientOptions` sets the idle timeout only, no connect timeout — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java` § `relayClientOptions`
  - verdict: corroborated | checked_at: c86ee5b9f | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: TlsEdgeProducer.relayClientOptions at main c86ee5b9f still sets only the idle timeout, no connect timeout
- HYPOTHESIS: a client that closes during the passthrough dial is not noticed until `RelaySession.start` registers its handlers — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` § `relay` / `RelaySession.start` (verify-at-outline)
  - verdict: contradicted | checked_at: c86ee5b9f | by: security-hardening-tier-1/analyze | rescoped: yes | evidence: PassthroughRelay.relay at main c86ee5b9f registers a temporary client closeHandler before netClient.connect and closes the late backend leg when the client left (PR #581 TASK-16); deliverable 2 re-scoped to the remaining connect-timeout and buffer-release half
- OBSERVED: the HTTP relay's single upstream reset fires only from the relay future's failure, and no close handler is registered on the client response after the hand-over — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` § `relayOnEventLoop`
  - verdict: corroborated | checked_at: c86ee5b9f | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: TerminalDispatch.java:625 at main c86ee5b9f is still the only upstream reset, in relayOnEventLoop onFailure; no closeHandler in TerminalDispatch
- HYPOTHESIS: vertx-core 4.5.34 `Http1xServerRequest.webSocket()` refuses a request whose body exceeds 8192 bytes (413) or whose location cannot be built (400) after the gateway has already dialled — confirm/refute at vertx-core 4.5.34 `io/vertx/core/http/impl/Http1xServerRequest.java` § `webSocket` (verify-at-outline)
  - verdict: unverifiable | checked_at: 47e73a15 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: vertx-core 4.5.34 source is not in the repo; PR #589 mirrors a 413 over 8192 and a 400 for an unparseable URI (WebSocketUpgradeGate.java:235-240); library parity not shown by reading
- HYPOTHESIS: with `quarkus.http.idle-timeout=0`, `TlsEdgeProducer.relayIdleTimeout` derives a finite value shorter than the operator intended — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java` § `relayIdleTimeout` and the Quarkus reading of `0` (verify-at-outline)
  - verdict: contradicted | checked_at: 47e73a15 | by: security-hardening-tier-1/analyze | rescoped: no | evidence: TlsEdgeProducer.relayIdleTimeout (280ff) takes the max of the HTTP idle timeout, the gRPC stream idle bound and the longest WebSocket idle, plus 60 s; a zero HTTP idle never wins the max, so no configured inner bound is undercut; PR #589 kept the value derived and fixed (option b)
- Verify-first clause: before scoping deliverable 3, settle whether a client-response close handler can feed the existing reset site without a second `reset()` call and without double-ending the response (`failRelay` ends only if `!response.ended()`). A refutation of the quiet-upstream hypothesis drops deliverable 3 and is reported, not silently skipped.
  - verdict: corroborated | checked_at: 47e73a15 | by: security-hardening-tier-1/analyze | rescoped: n/a | evidence: TerminalDispatch.relayOnEventLoop (653-681) feeds the client-close end handler, the already-closed check and the relay result into one Promise; the only reset() and failRelay are in its onFailure

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` — `onConnect`, `Incoming`, per-source share, WARN record
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` — dial phase, client-gone during dial
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java` — connect timeout, idle derivation, optional config key
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsLogMessages.java` — only if the `tls` package keeps its LogRecords in its own holder (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` — client close feeding the reset site
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGate.java` — mirrored Vert.x preconditions
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/PassthroughRelayTest.java` — if the dial-phase tests fit there rather than in `SniFrontListenerTest` (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRelayFailureResetTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeWebSocketUpgradeGateTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGateTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeSessionRelayTest.java` — headers/Set-Cookie on the 503 refusals, if those cases live there (verify-at-outline)
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/TlsPassthroughIT.java`
- OBSERVED: `doc/adr/0066-An_HTTP2_response_answered_while_its_request_body_is_open_is_ended_once_request_data_stops_arriving_or_a_time_cap_passes_and_its_stream_is_then_reset_with_NO_ERROR.adoc`
- OBSERVED: `doc/development/tls-edge.adoc`
- OBSERVED: `doc/user/tls-edge.adoc`
- OBSERVED: `doc/security-threat-model.adoc` — GW-07, GW-09 rows
- OBSERVED: `doc/LogMessages.adoc` — the WARN record of deliverable 1
- HYPOTHESIS: `doc/configuration/` — only if deliverable 5 adds a configuration key (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-SEC-01 (shipped, PR #582) — this plan works on its merged code.
- Overlaps with: PLAN-SEC-02 and PLAN-SEC-03 share only documentation — `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (two files; `next` asks the operator).
- Adjacent to: `edge/upstream/**` (PLAN-SEC-02) — untouched; `edge/relay/ResponseStage.java` — the reset stays in `TerminalDispatch`; `edge/end/EarlyResponseEnd.java` (tier 4) — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-04-edge-transport-bounds-follow-up.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
