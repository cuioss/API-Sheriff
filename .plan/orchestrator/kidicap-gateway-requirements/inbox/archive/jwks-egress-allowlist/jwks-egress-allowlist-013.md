envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-23T05:36:49Z

# Intermittent WebSocketRelayStageTest.relaysBidirectionalTextFrames timeout on main — evidence points to a dropped first frame (possible production race), not a network stall

**Verdict of the verification re-run: POSITIVE (green).** The failure does not reproduce deterministically, so it is intermittent. The analysis below still points at a real race window and therefore warrants a follow-up plan. It is not a benign runner flake.

## Occurrence

- Workflow "Integration Tests", run 35802987168 (https://github.com/cuioss/API-Sheriff/actions/runs/35802987168), push to `main`, merge commit `c1b09c7` (#345, PLAN-17 jwks-egress-allowlist).
- **Attempt 1 — FAILED** (2026-09-23 ~00:43Z): `WebSocketRelayStageTest.relaysBidirectionalTextFrames:200`, `TimeoutException: timed out awaiting the echoed frame to return through the relay: ceiling=30000 ms, elapsed=30000408843 ns (30000 ms)`. Result: 3612 run, 1 failure; api-sheriff surefire FAILURE, so the ITs never ran.
- **Attempt 2** (failed-jobs re-run): **success**.
- **Attempt 3** (full re-run, watched with a monitor, 2026-09-23 ~05:21Z): **success**. 3612/0 unit tests and 222/0 ITs; all 13 WebSocketRelayStage tests passed in 6.7s.
- The same code passed on every PR #345 run and on local runs (3611 and 202 tests).
- #345 does not touch the WebSocket relay code, so the failure is not attributable to that plan.
- Among the last 40 Integration Tests runs, the only other red one (35721031325) was a different test (`BffBackchannelLogoutIT`).

## Evidence gathered (attempt-1 log, `Awaits` timeout diagnostics from #255)

1. **The timeout was exactly at the ceiling** (30000 ms), and total test time was 34.24s. Nothing progressed during the wait.
2. **Thread dump:** every Vert.x thread was idle in `sun.nio.ch.EPoll.wait` — the acceptor and event loops 0-3 alike. The main thread was only waiting in `Awaits.await`. No thread was blocked, deadlocked or busy.
3. **Socket snapshot** (lsof + `netstat -ant`, pid 6998):
   - Two loopback listeners, `127.0.0.1:38019` and `127.0.0.1:38947`.
   - Two ESTABLISHED connections, `45676<->38947` and `45606<->38019`: the client→front leg and the gateway→upstream leg.
   - **Recv-Q and Send-Q were 0 on every socket.**
   - All sockets were bound to loopback, so the wildcard-bind mechanism from #255 is ruled out.
4. **What 2 and 3 establish together:** both relay legs were upgraded and established. No bytes were waiting in any kernel buffer, and no thread had work queued. So the client's text frame was delivered to user space and then **consumed and discarded**. This is a **lost frame**, not a stalled connection.
5. **This is the first Linux/CI occurrence of the "30s at the ceiling, all threads idle" signature.**
   - #243 documented it as macOS-local (kqueue); that PR claimed "zero hangs across 100 CI runs".
   - #255 fixed the macOS mechanism (wildcard ephemeral bind).
   - This occurrence is on Linux epoll with loopback-bound listeners, so neither the kqueue reading nor the wildcard-bind cause applies. It is a different mechanism with the same symptom.

## Mechanism (verified in library bytecode; the timing window is inferred)

- **VERIFIED — Vert.x core 4.5.33 drops frames with no handler.** `WebSocketImplBase.receiveFrame` reads `frameHandler` and dispatches only when it is non-null. The inbound `InboundBuffer pending` starts in flowing mode. So a frame that arrives before a `frameHandler` is installed is dropped silently, with no buffering or replay. (Checked with `javap -c` on the 4.5.33 jar.)
- **VERIFIED — the handlers are installed late.**
  - In `WebSocketRelayStage` (api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/WebSocketRelayStage.java), the server-side `ServerWebSocket` gets its `frameHandler` only in `RelaySession.start()`.
  - `start()` is reached through `ctx.request().toWebSocket().onSuccess(clientWs -> establishRelay(...))`, inside `onUpstreamConnected`, the callback of `webSocketClient.connect(...)`.
  - The `toWebSocket()` promise is bound to the request's context (`context.promise()` in `Http1xServerRequest.webSocket`).
- **INFERRED — the race window.** The 101 is flushed on the server connection's event loop before the `onSuccess` listener runs:
  - The listener may be attached from the upstream-client callback, or dispatched as a separate task on the request context.
  - The client can therefore send its first frame, and the connection can read it, before `RelaySession.start()` wires `frameHandler`.
  - That frame then reaches `receiveFrame` with a null handler and is dropped.
  - This test writes its frame immediately after the upgrade future completes, which is exactly the pattern that hits the window.
  - A loaded shared runner widens the window, which fits the rare, CI-only occurrence.
- The `RelaySession` Javadoc asserts that "All callbacks run on the shared request event loop". That is not proven for the path from the upstream-client callback to `toWebSocket().onSuccess`.

## Why this may matter beyond the test

If the mechanism holds, **a production client that sends a frame right after the 101 can have that frame silently dropped by the gateway.** Chat and subscription protocols commonly send a first message immediately. Nothing in the pipeline counts or logs the drop. The relay then looks healthy and idle, so the frame is lost without trace.

## Suggested follow-up (for a plan, not done here)

1. **Make the race visible, deterministically, before fixing it.** Add a test in which the client writes its first frame in the upgrade callback on the client's own event loop, with no gap. Optionally add a test-only delay hook between `toWebSocket()` completion and `RelaySession.start()`. The test should fail reliably with the current code.
2. **Fix candidates**, to be evaluated against that test:
   - (a) Install the frame and close handlers on the `ServerWebSocket` in the same event-loop task in which the upgrade completes. This means running `toWebSocket()` and its continuation on the request's own context: capture `ContextInternal`/`Vertx.currentContext()` in `relay()` and hop back with `context.runOnContext` before `toWebSocket()`.
   - (b) `pause()` the server socket until the relay is wired, then `resume()`.
   - (c) Buffer early frames in the relay.

   (a) plus (b) is the least intrusive.
3. **Correct the `RelaySession` threading Javadoc** to state which context each callback actually runs on.
4. **Extend the `Awaits` diagnostics.** On a relay test timeout, record whether the gateway-side `frameHandler` was set when the first frame arrived. A wiring timestamp against a frame-receipt timestamp would turn a future occurrence into direct evidence.
5. **Correct the documentation.** `doc/development/build-gate-discipline.adoc` and the #243/#255 narrative say the 30-second stall is macOS-local, and #243 reported it absent from CI. This occurrence contradicts that: the symptom appears on Linux CI with a different mechanism.

## Provenance

- Found by: orchestrator session finalizing PLAN-17 (jwks-egress-allowlist); post-merge verification of `main`.
- Artifacts: attempt-1 log lines 842-1156 of job "integration-tests / test" (thread dump and socket snapshot); attempt-3 log confirms green.
- Local check: the local Maven repo holds only the `vertx-core-4.5.33` jar (no sources); bytecode inspected with `javap`.
