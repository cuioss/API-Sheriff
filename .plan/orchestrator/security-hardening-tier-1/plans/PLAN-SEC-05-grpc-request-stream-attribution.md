# PLAN-SEC-05: gRPC request-stream attribution and edge transport bounds follow-up — first-message bound over a complete message, client-held request streams not charged to the upstream, front dial budget and accounting

epic: security-hardening-tier-1
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-05-grpc-request-stream-attribution.md` and is queued as one row file,
> `queue/PLAN-SEC-05.json`, in the epic ledger. The orchestrator EMITS the command below; it never
> launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer
> and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

This plan has two parts, on two disjoint production surfaces, kept in one plan to keep the PR
count down (CLAUDE.md "minimise the number of PRs").

**Part A — finish M6 (`edge/upstream/**`).** PLAN-SEC-02 (PR #590, `main` `81b46785`) bounded the
wait for a gRPC call's first request message at 10 s and ended an idle call as the client's own
abort, which the circuit breaker skips. The landing analysis (`landings/PLAN-SEC-02.md`, L3-A) read
the merged code and found the bound incomplete: it ends at the first forwarded request byte, not at
a complete gRPC message, and a call whose request stream the client keeps open still ends at the
upstream guard's 30 s timeout, which the breaker counts as an upstream failure. The threat model
already states the byte-level residual (GW-07 stays PARTIAL). The consequence detail is in the
operator's git-ignored local notes (`.plan/local/security_notes.adoc`, entries "M6-bypass" of
2026-11-09 and its 2026-11-10 update); read them before outline.

The Part A goal is the M6 invariant, stated once: **a gRPC call ends as an upstream failure only
when the upstream had everything the client was going to send and still did not answer in time.**
Time the client itself holds the request open is the client's, and never charged to the breaker.

**Part B — PLAN-SEC-04 leftovers (`tls/**`, `edge/terminal/**`).** PLAN-SEC-04 (PR #589, `main`
`47e73a15`) made the TLS front's undecided-connection cap fair per source and held a passthrough
connection's slot until its backend dial settles. The landing analysis (`landings/PLAN-SEC-04.md`)
read the merged code and found that the dial window now draws on the same cap the terminated
handshakes need, several accounting edges of the new per-source share, three gate rules whose
parity with vertx-core is claimed but not shown, and stale documentation. The operator asked for
these to be folded here. The detail is in the operator's local notes (entry "security-hardening-tier-1
orchestrator landing analysis of PLAN-SEC-04", 2026-11-10); read it before outline. Do not copy it
into any tracked text until the corresponding fix is on the branch.

Each fix ships with a regression test that fails against the unfixed code.

## Deliverables

### Part A — gRPC request-stream attribution

1. **The first-message bound covers a complete first message.** The clock that
   `GrpcDispatchStage` arms stops only when a complete first length-prefixed gRPC message (5-byte
   prefix plus its declared length) has been forwarded, or when the request body ends — not at the
   first byte. Read the prefix incrementally from the chunks as they cross, with no extra buffering
   and no copy of the payload; a declared message length above the route's body limit is refused
   as today. Expiry ends the call exactly as now (`FirstMessageTimeout`, breaker-skipped, not
   retried).
2. **A request stream the client still holds open is not the upstream's failure.** For a gRPC
   attempt whose request body has not ended when the guard's timeout fires, the call must not be
   recorded as an upstream failure by the breaker. Decide at outline between (a) a per-message idle
   bound on the gRPC request stream, active until the client half-closes, ending as a client abort
   before the guard can fire; and (b) client-attributing the guard timeout itself when the body is
   still open (the refine of PLAN-SEC-02 found the breaker records the guard's timeout before any
   translation — see the verify-first clause). Record the choice and its reason in the plan's ADR
   amendment. Keep the guard's 30 s as the bound for an upstream that received a half-closed
   request and does not answer.
3. **Idle-client aborts are countable on their own.** An operator can tell idle-client ends
   (deliverables 1 and 2) apart from client departures and body-stream failures, which today share
   `sheriff_errors_total{event=INBOUND_BODY_ABORTED}` and one per-route latch. Use a disposition
   label or a distinct counter dimension; no new `EventType` unless outline proves one is needed.
4. **Part A tests.** Each fix starts with a test that fails against `main`:
   - a client that forwards part of the first message and stalls ends as the client's abort within
     the bound, and the breaker stays closed;
   - a client-streaming call whose client sends a complete message and then holds the stream open
     does not open the breaker;
   - matched control: an upstream that received a complete, half-closed request and never answers
     IS counted (`UPSTREAM_TIMEOUT`, breaker opens at its threshold);
   - a client that sends complete messages slower than the bound but steadily, and then
     half-closes, is not cut off by deliverable 2's bound if option (a) is chosen;
   - the existing `GrpcDispatchStageTest` control `unansweredFirstMessageIsTheGuardTimeoutAndOpensTheBreaker`
     is corrected to model an upstream that has the whole request (its stub answers only after
     half-close, so today it encodes a client hold as an upstream failure);
   - `GrpcProxyIT`: one partial-first-message case through the native image.
5. **Part A documentation.** ADR-0060 amendment (the bound now ends at a complete message; the
   deliverable-2 choice); `doc/architecture/tls-and-inbound-security.adoc` § gRPC first-message
   bound; the threat model's GW-07 row and its evidence (upgrade from PARTIAL only if deliverables 1
   and 2 both land); `doc/LogMessages.adoc` if the `ApiSheriff-22` dispositions change.

### Part B — edge transport bounds follow-up

6. **A passthrough dial does not consume the shared front cap without its own bound.** Since
   PLAN-SEC-04 a dialing connection keeps its undecided slot and its source-share slot until the
   dial settles (name resolution plus the 10 s connect timeout). Give the dial phase a budget of its
   own so dials to one passthrough target cannot occupy the cap that terminated handshakes need —
   for example a per-target bound on concurrent dials, a fail-fast for a target whose recent dials
   all timed out, or moving dialing connections out of the undecided cap into a separate bound.
   Decide at outline (with the operator if the choice is not clear) and record the decision in the
   `SniFrontListener` Javadoc and `doc/development/tls-edge.adoc`. A refusal for the new bound is
   counted and logged like the existing front refusals (reason label, rate-limited WARN). Tests:
   with a target whose dials never complete, terminated handshakes from another source are still
   accepted once that target has used its budget (matched control: the same load without the budget
   refuses them).
7. **Front accounting robustness.**
   - The per-source share check never transiently pushes the global undecided count over the cap,
     and a refusal is labelled with the bound that actually refused it.
   - The dial phase completes, and the slots come back, whatever the relay hand-off throws.
   - A refusal closes the connection even when recording it fails.
   - IPv6 sources are aggregated (for example by /64) so one client with many addresses counts as
     one source; decide the prefix at outline and document it. IPv4 keying is unchanged.
   - Tests: each of the above fails against `main`; the per-source map is observed to return to
     empty after every exit path (deadline, close, error, dial success, dial failure).
8. **WebSocket upgrade gate and relay end — verify-first parity.** Settle each by a test against
   the real Vert.x stack before changing code:
   - whether a refused HTTP/1.1 upgrade that declares a body of 1..8192 bytes leaves the connection
     stalled behind the unread body (if so, retire the connection after any refusal that leaves a
     declared body unread);
   - whether the gate's first-`Connection`-line and whole-value `Upgrade: websocket` rules refuse a
     handshake vertx-core 4.5.34 would accept (split `Connection` lines, `Upgrade: websocket, x`); if
     so, relax the gate to the stack's actual acceptance set;
   - whether a client reset during the ADR-0066 HTTP/2 early-end wait of a fully streamed response
     makes `TerminalDispatch.relayOnEventLoop` run `failRelay` on a successful exchange and schedule
     a second end; if so, keep exactly one reset site and one end.
   A probe whose premise is refuted is reported in the plan's inbox message and changes no code.
9. **Part B documentation.** Correct the texts that still say one client can hold all 1024 undecided
   slots (`SniFrontListener` Javadoc, `doc/development/tls-edge.adoc`, `doc/user/tls-edge.adoc`);
   bring the threat model's GW-07 SNI-front summary row in line with its section body (per-source
   share, dial window, connect timeout, refusal counter, and deliverable 6's bound); state the dial's
   name-resolution worst case precisely (resolver query timeout × queries × search domains, from the
   resolver configuration actually in effect); align the bound count between the user and developer
   docs.

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility switch; the 10 s constants may change or become
  derived, but no new configuration key without an outline decision recorded in the ADR amendment
  (a key that is added must act: a test fails when it is deleted).
- No new dependency (no gRPC framing library: the 5-byte prefix is read by hand). Logging through
  `CuiLogger` with `%s`, exception first; any new INFO/WARN/ERROR record is a `LogRecord` documented
  in `doc/LogMessages.adoc` with the next free identifier, derived at write time. Never log request
  data, ClientHello bytes or source addresses beyond what existing records log.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every fix starts with a test that FAILS
  against the unfixed code; a fix whose test cannot be made to fail is a refuted premise and loops
  back to re-scope (record it in the plan's inbox message).
- `DispatchStage` is a concurrency hot spot (three race fixes in PR #532, the first-message clock in
  PR #590). Do NOT restructure the hand-over / departure / publish ordering. Keep every clock
  single-shot under the existing monitor and abort CAS. Run `InboundBodyDepartureTest`,
  `UpstreamAttemptReleaseOrderTest`, `GatewayEdgeHttp2AbuseTest`, `DispatchStageTest` and
  `GrpcDispatchStageTest` with `-DargLine=-XX:ActiveProcessorCount=1` in addition to the normal run.
- HTTP (non-gRPC) dispatch behaviour is unchanged by Part A: HTTP routes never arm these clocks.
- `TerminalDispatch` keeps exactly one upstream reset site; every gateway-written answer goes
  through `ResponseEnds` (ADR-0066). No Vert.x internal API.
- `SniFrontListener` callbacks stay on the accepted connection's event loop; the slot release stays
  exactly-once on every exit path.
- Do not touch `bff/**`. `forwarded.trust_scheme_host` and the provenance header sets are untouched.
- Deliverable count: 9, above the presumptive split threshold of ~6. Kept as one plan by the
  operator's instruction (2026-11-10: fold PLAN-SEC-04's leftovers into this plan rather than open
  another), within the project's authorised 12-deliverable ceiling; Parts A and B touch disjoint
  production packages, so they can be executed and reviewed in sequence on one branch. Size the PR
  under CodeRabbit's 100-file cap at outline; if it would exceed it, split into two serial PRs
  (Part A, then Part B) rather than dropping scope.
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an
  open Sonar issue is fixed in a pushed commit, or dismissed with a stated reason recorded as
  `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less
  transition verb, `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key}
  --transition {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker, never a
  silent pass. The merge gate reads the open-issue count at the head — `0` confirmed, or every
  remaining issue dismissed — and an `undecidable` read blocks.

## Security Findings

Possible weaknesses this plan finds that it does not fix on its branch are NOT written into any
tracked text (PR body, commit message, documentation, inbox message). Append a dated entry to the
git-ignored `.plan/local/security_notes.adoc` instead — plan id, location, what was observed, why
it was not fixed — and say only that a local note was recorded. Fixed findings may be described
once the fix is on the branch. The residuals this plan closes are described in tracked text only at
the level the threat model already states (the first-message bound ends at the first byte; the
front's documented trade-offs), until the fix is on the branch.

## Claim Labels

- OBSERVED: `ByteCappedBodyStream.firstMessageBoundExpired` stops the first-message clock and sets `firstByteForwarded` at the first non-empty chunk that crosses — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` § `firstMessageBoundExpired` / `onChunk` (main `81b46785`)
- OBSERVED: `GrpcDispatchStage` builds its `DispatchStage` with `DispatchStage.NO_BODY_IDLE_TIMEOUT` and no request-body deadline; only the first-message bound (`GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS = 10_000`) is armed — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java` § constructor (main `81b46785`)
- OBSERVED: the breaker uses `skipOn(GatewayException.class)` and the guard's timeout is `UPSTREAM_TIMEOUT_SECONDS = 30`; a `TimeoutException` from the guard is counted as a failure — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` § `guardFor` (main `81b46785`)
- OBSERVED: the `GrpcDispatchStageTest` control stub answers a `CLIENT_STREAMING` call only after the request stream ends — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java` § stub upstream Javadoc and `unansweredFirstMessageIsTheGuardTimeoutAndOpensTheBreaker` (main `81b46785`)
- HYPOTHESIS: the per-attempt state `DispatchStage` already keeps (`bytesSent`, `subscribed`, `inboundEnded`) is readable where the guard's failure is translated, so "request still open" can be told at timeout time without new synchronisation — but the breaker may record the guard's `TimeoutException` before that translation runs — confirm/refute at `DispatchStage.java` § `awaitDispatch` / the guard catch and SmallRye Fault Tolerance's ordering of `@Timeout` vs `@CircuitBreaker` in the programmatic guard (verify-at-outline)
- HYPOTHESIS: gRPC length-prefixed messages can be delimited from the forwarded chunks without buffering (the prefix may straddle chunks) — confirm by deliverable-4 tests with a prefix split across chunks
- Verify-first clause: before scoping deliverable 2, settle the hypothesis above. If the breaker records the guard's timeout before any translation, option (b) is not available and option (a) is the plan; record the finding either way in the ADR amendment and the plan's inbox message.
- OBSERVED: a passthrough connection's undecided slot and source-share slot are released only when the dial phase settles, and the cap and share are shared with terminated handshakes — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `Incoming.onData` dial-phase completion and `releaseSlot` (main `47e73a15`)
- OBSERVED: `claimSlot` increments the global undecided counter before the per-source share check and rolls it back on a share refusal — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `claimSlot` (main `47e73a15`)
- OBSERVED: `PassthroughRelay.relay` completes the dial-phase promise after `establish(...)` with no try/finally — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` § `relay` dial success handler (main `47e73a15`)
- OBSERVED: `refuse` calls the refusal recorder before closing the paused socket — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `refuse` (main `47e73a15`)
- OBSERVED: `sourceOf` keys the share on the exact peer address string, with no IPv6 prefix aggregation — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` § `sourceOf` (main `47e73a15`)
- OBSERVED: the "a client that holds 1024 silent sockets" trade-off text survives in the `SniFrontListener` class Javadoc and `doc/development/tls-edge.adoc`, and the threat model's GW-07 SNI-front summary row names no per-source share, dial window or connect timeout — read at those files (main `47e73a15`)
- HYPOTHESIS: a refused HTTP/1.1 upgrade declaring a body of 1..8192 bytes leaves the connection open behind a paused, unread body — confirm/refute with a test against `WebSocketUpgradeGate` retirement (`judge`, `Refused.retiringConnection`) and the real Vert.x HTTP/1.1 server (verify-first, deliverable 8)
- HYPOTHESIS: the gate's first-`Connection`-line and whole-value `Upgrade` rules refuse handshakes vertx-core 4.5.34 accepts — confirm/refute against the real stack (verify-first, deliverable 8)
- HYPOTHESIS: a client reset during the HTTP/2 early-end wait of a fully streamed response runs `failRelay` on a successful exchange — confirm/refute at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` § `relayOnEventLoop` with an HTTP/2 test (verify-first, deliverable 8)

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` — `ByteCappedBodyStream` first-message clock, message-prefix tracking, request-stream idle bound
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java` — bound wiring, class Javadoc
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` — only if option (b) is chosen (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/RejectionReporter.java` — dispositions, countable idle-client ends
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/metrics/` — only if the counter dimension lives outside `RejectionReporter` (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java` — dial budget, `claimSlot` order, `refuse` order, IPv6 source aggregation, Javadoc
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/PassthroughRelay.java` — dial-phase completion on every path, dial budget hook
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducer.java` — dial-budget wiring and refusal recording (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java` — only if the dial-budget refusal needs its own record (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java` — the dial-budget refusal reason (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGate.java` — only if a deliverable-8 probe confirms its premise
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/TerminalDispatch.java` — only if the HTTP/2 early-end probe confirms its premise
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuardsTest.java` — with option (b) (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java` — disposition assertions
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/SniFrontListenerTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/PassthroughRelayTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/TlsEdgeProducerTest.java` — with the dial-budget wiring (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetricsTest.java` — with a new refusal reason (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeWebSocketUpgradeGateTest.java` — deliverable-8 probes
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/terminal/WebSocketUpgradeGateTest.java` — if a gate rule changes
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRelayFailureResetTest.java` — the HTTP/2 early-end probe
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/GrpcProxyIT.java`
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/TlsPassthroughIT.java` — if the dial budget is pinned end to end (verify-at-outline)
- OBSERVED: `doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc` — § gRPC first-message bound
- OBSERVED: `doc/security-threat-model.adoc` — GW-07 row and evidence, GW-07 SNI-front summary row
- OBSERVED: `doc/development/tls-edge.adoc` — stale trade-off text, dial budget, resolver worst case
- OBSERVED: `doc/user/tls-edge.adoc` — stale trade-off text, bound count, dial budget
- HYPOTHESIS: `doc/architecture/observability-and-errors.adoc` — with a new refusal reason (verify-at-outline)
- HYPOTHESIS: `doc/user/protocol-routes.adoc` — if a gate rule changes
- HYPOTHESIS: `doc/LogMessages.adoc` — only if the `ApiSheriff-22` dispositions change or a new record is added (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-SEC-02 (shipped, PR #590) and PLAN-SEC-04 (shipped, PR #589) — this plan works on their merged code at `main` `47e73a15` or later.
- Overlaps with: nothing in flight in this epic. Cross-epic: the `kidicap-gateway-requirements` epic has touched `doc/architecture/` and `doc/configuration/` pages and `DispatchStage` before (PLAN-29, `ab568dab`); check its running plans at launch.
- Adjacent to: `edge/relay/ResponseStage.java`, `edge/end/EarlyResponseEnd.java` — untouched unless a deliverable-8 probe proves otherwise.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/security-hardening-tier-1/plans/PLAN-SEC-05-grpc-request-stream-attribution.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
