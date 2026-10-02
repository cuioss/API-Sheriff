# PLAN-22: Close the WebSocket Relay's Early-Frame Race and Correct Its Threading Record

epic: kidicap-gateway-requirements
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-22-websocket-early-frame-race.md` and is queued in the epic `status.json` `plans[]`
> field. The orchestrator EMITS the command below; it never launches the plan inline.
> This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no brief.
> Staged 2026-09-23 from inbox message `jwks-egress-allowlist-013.md` (kind: finding).

## Objective

The gateway wires its WebSocket relay AFTER the client upgrade completes. A client that sends its first
frame immediately after the `101` can therefore have that frame arrive while no `frameHandler` is
installed — and Vert.x drops a frame with no handler rather than buffering it. If that holds in
production, a chat or subscription client's first message is silently lost: nothing counts it, nothing
logs it, and the relay then looks healthy and idle. This plan makes the window deterministically
reproducible, closes it, and corrects the two records that currently describe the symptom as a
macOS-only test artifact.

The occasion was an intermittent CI failure, but **the test is the symptom, not the subject**: the
deliverables below are a production race fix plus record corrections, and a flake-hardening framing
would mis-scope the plan.

## Source

Inbox `jwks-egress-allowlist-013.md` (finding, 2026-09-23), filed by the plan that shipped PLAN-17 while
verifying `main` after its own merge. Its material claims were re-verified by the orchestrator against
HEAD `c1b09c7` before staging — see Claim Labels.

Occurrence: workflow "Integration Tests" run 35802987168, push to `main`, merge commit `c1b09c7` (#345).
Attempt 1 failed in `WebSocketRelayStageTest.relaysBidirectionalTextFrames:200` with
`TimeoutException ... ceiling=30000 ms`; attempts 2 and 3 were green (3612/0 unit tests, 222/0 ITs, all 13
WebSocketRelayStage tests in 6.7 s). #345 touches no WebSocket code, so the failure is not attributable to
it. Among the last 40 Integration Tests runs the only other red one failed a different test
(`BffBackchannelLogoutIT`).

Evidence from the attempt-1 log (the `Awaits` diagnostics added by #255): the wait ran to the exact
ceiling; the thread dump showed every Vert.x thread idle in `EPoll.wait` with nothing blocked or
deadlocked; the socket snapshot showed BOTH relay legs ESTABLISHED on loopback with `Recv-Q` and `Send-Q`
zero on every socket. Together those say the frame reached user space and was then consumed and discarded
— a lost frame, not a stalled connection. This is the first Linux/epoll occurrence of the
"30 s at the ceiling, all threads idle" signature; #243 recorded it as macOS-local and #255 fixed the
macOS mechanism (a wildcard ephemeral bind), neither of which applies to a loopback-bound listener on
epoll.

## Deliverables

1. **A deterministic reproduction, landed BEFORE the fix.** A test in which the client writes its first
   frame inside the upgrade callback on the client's own event loop, with no gap — optionally with a
   test-only hook that delays the step between `toWebSocket()` completion and `RelaySession.start()`. It
   must fail reliably against unfixed code; a test that merely passes more often is not a reproduction.
2. **Close the window.** Evaluate the three candidates against deliverable 1 and implement the least
   intrusive combination that the reproduction proves closed:
   (a) install the frame and close handlers in the same event-loop task in which the upgrade completes —
   capture `ContextInternal` / `Vertx.currentContext()` in `relay()` and hop back with
   `context.runOnContext` before `toWebSocket()`; (b) `pause()` the server socket until the relay is
   wired, then `resume()`; (c) buffer early frames in the relay. (a) + (b) is expected to suffice; record
   why whichever is chosen was chosen.
3. **Prove the production consequence, or refute it.** Establish by test whether an early client frame is
   observable end to end through a real relay — this is what separates "a test raced" from "the gateway
   drops a first frame". If it is refuted, say so explicitly and re-scope deliverables 4–5 accordingly.
4. **Correct the threading record.** `RelaySession`'s Javadoc asserts that "All callbacks run on the
   shared request event loop"; that is not established for the path from the upstream-client callback to
   `toWebSocket().onSuccess`. State which context each callback actually runs on.
5. **Correct the investigation record.** `doc/development/build-gate-discipline.adoc` carries the heading
   "The condition is macOS-local" and the #243/#255 narrative reports the stall absent from CI. This
   occurrence contradicts that: same symptom, Linux CI, different mechanism. Amend rather than delete —
   the macOS finding was measured and stays true for its own mechanism.
6. **Extend the timeout diagnostics.** On a relay test timeout, record whether the gateway-side
   `frameHandler` was installed when the first frame arrived (a wiring timestamp against a frame-receipt
   timestamp), so a future occurrence is direct evidence rather than another inference.

Split guard: 6 deliverables — within the operator-authorized 12 per plan.

## Claim Labels

- OBSERVED: the server-side `frameHandler` is installed only in `RelaySession.start()` → `wire()`
  (`WebSocketRelayStage.java:251,263`), and `start()` is reached through
  `ctx.request().toWebSocket().onSuccess(...)` inside `onUpstreamConnected` (`:157`), itself the
  `onSuccess` of `webSocketClient.connect(...)` dispatched via `ctx.vertx().runOnContext` (`:150`).
  Re-verified by the orchestrator at `c1b09c7`.
- OBSERVED: `RelaySession`'s Javadoc claims "All callbacks run on the shared request event loop"
  (`WebSocketRelayStage.java:219`). Re-verified at `c1b09c7`.
- OBSERVED: `doc/development/build-gate-discipline.adoc:327` heads a section "The condition is
  macOS-local". Re-verified at `c1b09c7`.
- OBSERVED: `source.pause()` already exists in the relay, used for write-queue backpressure
  (`WebSocketRelayStage.java:298`), so candidate (b) reuses a mechanism the class already has.
- HYPOTHESIS: Vert.x core 4.5.33 `WebSocketImplBase.receiveFrame` dispatches only when `frameHandler` is
  non-null and the inbound `InboundBuffer` starts in flowing mode, so a frame arriving before the handler
  is installed is dropped silently with no buffering or replay. Reported as verified by `javap -c` against
  the 4.5.33 jar by the filing plan; the orchestrator did NOT re-run that inspection — confirm/refute at
  the `vertx-core-4.5.33` jar's `io.vertx.core.http.impl.WebSocketImplBase` § `receiveFrame`
  (verify-at-outline).
- HYPOTHESIS: the `101` is flushed on the server connection's event loop BEFORE the `toWebSocket()`
  `onSuccess` listener runs, which is what opens the window; the filing plan inferred this from the
  timing rather than observing it. Confirm/refute at
  `io.vertx.core.http.impl.Http1xServerRequest` § `webSocket` (the `context.promise()` binding)
  (verify-at-outline).
- HYPOTHESIS: a loaded shared runner widens the window, which is why the occurrence is CI-only and rare.
  Inferred, not measured; confirm/refute at the reproduction from deliverable 1 § its behaviour under
  induced load (verify-at-outline).

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStage.java` — the
  wiring path, `RelaySession.start()`, and the threading Javadoc
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStageTest.java` — the
  timing-out test and the new deterministic reproduction
- OBSERVED: `doc/development/build-gate-discipline.adoc` — the "macOS-local" section and the #243/#255
  narrative
- HYPOTHESIS: the `Awaits` test-support helper carrying the timeout diagnostics from #255 (deliverable 6)
  — its owning module is `api-sheriff/src/test/java/` by expectation; confirm/refute at the class the
  timeout message "timed out awaiting the echoed frame to return through the relay" originates from
  (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/WebSocketRelayIT.java`
  — an end-to-end early-frame IT, if deliverable 3 needs one beyond the unit level. Declared as this ONE
  expected file rather than the whole `integration-tests/src/test/java/` directory on purpose: the broad
  form collided with every other spec's IT surface (and with PLAN-18's live footprint, whose only
  intersection with this spec was `RoutingAndResponseHeadersIT` — a file this plan will not touch), which
  is over-declaration that costs parallel slots for no safety. Confirm/refute at the IT this plan
  actually adds (verify-at-outline)
- HYPOTHESIS: `doc/LogMessages.adoc` — only if the fix introduces a counted or logged drop
  (verify-at-outline)

## Dependencies and Sequencing

- Depends on: none. The WebSocket relay is untouched by every other staged spec in this epic.
- Overlaps with: nothing in the live queue on its main-source surface — PLAN-19, PLAN-20 and PLAN-21 all
  work in `bff/` and `auth/`. It shares `doc/development/` and `integration-tests/src/test/java/` only.
- This is the **most disjoint spec in the queue**, so it is the natural second slot whenever
  `parallelization_scope` 2 has one free — the first pairing in this epic that is genuinely parallel
  rather than nominally so.
- Not an AS item: this is a defect found in API Sheriff's own relay while verifying an epic landing. It is
  carried here because this epic found it and owns the record, not because the downstream asked for it.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/kidicap-gateway-requirements/plans/PLAN-22-websocket-early-frame-race.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/local/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
