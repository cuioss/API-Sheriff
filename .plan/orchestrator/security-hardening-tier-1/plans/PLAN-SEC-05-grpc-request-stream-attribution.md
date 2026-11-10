# PLAN-SEC-05: gRPC request-stream attribution — first-message bound over a complete message, client-held request streams not charged to the upstream

epic: security-hardening-tier-1
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-SEC-05-grpc-request-stream-attribution.md` and is queued as one row file,
> `queue/PLAN-SEC-05.json`, in the epic ledger. The orchestrator EMITS the command below; it never
> launches the plan inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer
> and carries no brief, so every per-plan carry is authored here and nowhere else.

## Objective

Finish M6. PLAN-SEC-02 (PR #590, `main` `81b46785`) bounded the wait for a gRPC call's first
request message at 10 s and ended an idle call as the client's own abort, which the circuit breaker
skips. The landing analysis (`landings/PLAN-SEC-02.md`, L3-A) read the merged code and found the
bound incomplete: it ends at the first forwarded request byte, not at a complete gRPC message, and a
call whose request stream the client keeps open still ends at the upstream guard's 30 s timeout,
which the breaker counts as an upstream failure. The threat model already states the byte-level
residual (GW-07 stays PARTIAL). The consequence detail is in the operator's git-ignored local notes
(`.plan/local/security_notes.adoc`, entries "M6-bypass" of 2026-11-09 and its 2026-11-10 update);
read them before outline.

The goal is the M6 invariant, stated once: **a gRPC call ends as an upstream failure only when the
upstream had everything the client was going to send and still did not answer in time.** Time the
client itself holds the request open is the client's, and never charged to the breaker.

## Deliverables

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
4. **Tests.** Each fix starts with a test that fails against `main` `81b46785`:
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
5. **Documentation.** ADR-0060 amendment (the bound now ends at a complete message; the deliverable-2
   choice); `doc/architecture/tls-and-inbound-security.adoc` § gRPC first-message bound; the threat
   model's GW-07 row and its evidence (upgrade from PARTIAL only if deliverables 1 and 2 both land);
   `doc/LogMessages.adoc` if the `ApiSheriff-22` dispositions change.

## Standing Constraints

- Pre-1.0 rules: no deprecation, no compatibility switch; the 10 s constant may change or become
  derived, but no new configuration key without an outline decision recorded in the ADR amendment.
- No new dependency (no gRPC framing library: the 5-byte prefix is read by hand). Logging through
  `CuiLogger` with `%s`; any new INFO/WARN/ERROR record is a `LogRecord` documented in
  `doc/LogMessages.adoc` with the next free identifier, derived at write time.
- Tests: JUnit 5, AAA, no Mockito/PowerMock/Hamcrest. Every fix starts with a test that FAILS
  against the unfixed code; a fix whose test cannot be made to fail is a refuted premise and loops
  back to re-scope (record it in the plan's inbox message).
- `DispatchStage` is a concurrency hot spot (three race fixes in PR #532, the first-message clock in
  PR #590). Do NOT restructure the hand-over / departure / publish ordering. Keep every clock
  single-shot under the existing monitor and abort CAS. Run `InboundBodyDepartureTest`,
  `UpstreamAttemptReleaseOrderTest`, `GatewayEdgeHttp2AbuseTest`, `DispatchStageTest` and
  `GrpcDispatchStageTest` with `-DargLine=-XX:ActiveProcessorCount=1` in addition to the normal run.
- HTTP (non-gRPC) dispatch behaviour is unchanged: HTTP routes never arm these clocks.
- Do not touch `edge/terminal/TerminalDispatch.java` (PLAN-SEC-04's surface); if a constructor
  signature it calls must change, keep a constructor with the current signature instead.
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
once the fix is on the branch. The residual this plan closes is described in tracked text only at
the level the threat model already states (the bound ends at the first byte), until the fix is on
the branch.

## Claim Labels

- OBSERVED: `ByteCappedBodyStream.firstMessageBoundExpired` stops the first-message clock and sets `firstByteForwarded` at the first non-empty chunk that crosses — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` § `firstMessageBoundExpired` / `onChunk` (main `81b46785`)
- OBSERVED: `GrpcDispatchStage` builds its `DispatchStage` with `DispatchStage.NO_BODY_IDLE_TIMEOUT` and no request-body deadline; only the first-message bound (`GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS = 10_000`) is armed — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java` § constructor (main `81b46785`)
- OBSERVED: the breaker uses `skipOn(GatewayException.class)` and the guard's timeout is `UPSTREAM_TIMEOUT_SECONDS = 30`; a `TimeoutException` from the guard is counted as a failure — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` § `guardFor` (main `81b46785`)
- OBSERVED: the `GrpcDispatchStageTest` control stub answers a `CLIENT_STREAMING` call only after the request stream ends — read at `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java` § stub upstream Javadoc and `unansweredFirstMessageIsTheGuardTimeoutAndOpensTheBreaker` (main `81b46785`)
- HYPOTHESIS: the per-attempt state `DispatchStage` already keeps (`bytesSent`, `subscribed`, `inboundEnded`) is readable where the guard's failure is translated, so "request still open" can be told at timeout time without new synchronisation — but the breaker may record the guard's `TimeoutException` before that translation runs — confirm/refute at `DispatchStage.java` § `awaitDispatch` / the guard catch and SmallRye Fault Tolerance's ordering of `@Timeout` vs `@CircuitBreaker` in the programmatic guard (verify-at-outline)
- HYPOTHESIS: gRPC length-prefixed messages can be delimited from the forwarded chunks without buffering (the prefix may straddle chunks) — confirm by deliverable-4 tests with a prefix split across chunks
- Verify-first clause: before scoping deliverable 2, settle the hypothesis above. If the breaker records the guard's timeout before any translation, option (b) is not available and option (a) is the plan; record the finding either way in the ADR amendment and the plan's inbox message.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java` — `ByteCappedBodyStream` first-message clock, message-prefix tracking, request-stream idle bound
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStage.java` — bound wiring, class Javadoc
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuards.java` — only if option (b) is chosen (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/error/RejectionReporter.java` — dispositions, countable idle-client ends
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/metrics/` — only if the counter dimension lives outside `RejectionReporter` (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStageTest.java`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/GrpcDispatchStageTest.java`
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/UpstreamGuardsTest.java` — with option (b) (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/upstream/InboundBodyDepartureTest.java` — disposition assertions
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/GrpcProxyIT.java`
- OBSERVED: `doc/adr/0060-Dispatch_sends_a_bodyless_method_no_body_and_frames_every_declared-length_body_by_exactly_that_length.adoc`
- OBSERVED: `doc/architecture/tls-and-inbound-security.adoc` — § gRPC first-message bound
- OBSERVED: `doc/security-threat-model.adoc` — GW-07 row and evidence
- HYPOTHESIS: `doc/LogMessages.adoc` — only if the `ApiSheriff-22` dispositions change (verify-at-outline)

## Dependencies and Sequencing

- Depends on: PLAN-SEC-02 (shipped, PR #590) — this plan works on its merged code.
- Overlaps with: PLAN-SEC-04 (running, PR #589) shares only documentation — `doc/security-threat-model.adoc` and `doc/LogMessages.adoc` (two files; `next` asks the operator). No production file shared: PLAN-SEC-04 works in `tls/**` and `edge/terminal/**`.
- Adjacent to: `edge/terminal/TerminalDispatch.java` (PLAN-SEC-04) — it constructs `GrpcDispatchStage`; keep that call compatible. `forwarded.trust_scheme_host` and the provenance header sets — untouched.

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
